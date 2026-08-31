"""桌面端打包版转写链路的回归测试。

覆盖三块改动：
  1. env_checker 的 torch-free CUDA 分层探测（驱动可见 vs 推理就绪）
  2. WhisperModelRegistry 默认路径落到 BILINOTE_DATA_DIR 数据根的 config 目录
  3. /transcriber/import_local_model 本地模型零拷贝导入（裸目录 / HF cache 布局）
"""
import asyncio
import os

import pytest

import app.utils.env_checker as env_checker
import app.utils.runtime_paths as runtime_paths

# 顶层预导入：本文件按字母序先于 stub 型测试（如 test_video_reader_dedupe）被收集，
# 在真实模块还在 sys.modules 时把 config 路由加载好，避免后续被 stub 污染。
import app.routers.config as _config_router_module  # noqa: F401


# ---------- env_checker：CUDA 分层探测 ----------

def test_cuda_available_without_torch_via_ctranslate2(monkeypatch):
    monkeypatch.setattr(env_checker, "_torch_cuda_available", lambda: None)
    monkeypatch.setattr(env_checker, "_ctranslate2_cuda_count", lambda: 1)
    assert env_checker.is_cuda_available() is True
    assert env_checker.cuda_backend() == "ctranslate2"


def test_cuda_unavailable_when_no_driver(monkeypatch):
    # 两个探测都不可用（torch 未装、ct2 加载失败返回 None）
    monkeypatch.setattr(env_checker, "_torch_cuda_available", lambda: None)
    monkeypatch.setattr(env_checker, "_ctranslate2_cuda_count", lambda: None)
    assert env_checker.is_cuda_available() is False
    assert env_checker.cuda_backend() == ""


def test_backend_reports_ctranslate2_with_zero_devices(monkeypatch):
    # ct2 可导入但无设备：来源仍是 ctranslate2，只是设备数为 0
    monkeypatch.setattr(env_checker, "_torch_cuda_available", lambda: None)
    monkeypatch.setattr(env_checker, "_ctranslate2_cuda_count", lambda: 0)
    assert env_checker.cuda_backend() == "ctranslate2"
    assert env_checker.is_cuda_available() is False


def test_torch_view_wins_when_installed(monkeypatch):
    monkeypatch.setattr(env_checker, "_torch_cuda_available", lambda: True)
    monkeypatch.setattr(env_checker, "_ctranslate2_cuda_count", lambda: 0)
    assert env_checker.is_cuda_available() is True
    assert env_checker.cuda_backend() == "torch"


def test_inference_ready_short_circuits_with_torch(monkeypatch):
    # torch 自带并注册 cuBLAS/cuDNN，无需再做 DLL 探测
    monkeypatch.setattr(env_checker, "is_cuda_available", lambda: True)
    monkeypatch.setattr(env_checker, "is_torch_installed", lambda: True)
    assert env_checker.cuda_inference_ready() is True


def test_inference_ready_false_when_dll_missing(monkeypatch):
    import ctypes
    monkeypatch.setattr(env_checker, "is_cuda_available", lambda: True)
    monkeypatch.setattr(env_checker, "is_torch_installed", lambda: False)

    def _raise(name):
        raise OSError(f"{name} not found")

    monkeypatch.setattr(ctypes, "WinDLL", _raise)
    assert env_checker.cuda_inference_ready() is False


def test_inference_ready_false_without_cuda(monkeypatch):
    monkeypatch.setattr(env_checker, "is_cuda_available", lambda: False)
    assert env_checker.cuda_inference_ready() is False


def test_real_env_detection_smoke():
    """真实环境冒烟：函数可调用且返回类型正确（不预设机器有无 GPU）。"""
    result = env_checker.is_cuda_available()
    assert isinstance(result, bool)
    assert env_checker.cuda_backend() in ("", "torch", "ctranslate2")
    name = env_checker.gpu_name()
    assert name is None or isinstance(name, str)


# ---------- runtime_paths：CUDA 加速包目录 ----------

def test_cuda_dll_dir_from_explicit_env(tmp_path, monkeypatch):
    pack = tmp_path / "cuda"
    pack.mkdir()
    (pack / "cublas64_12.dll").write_bytes(b"x")
    monkeypatch.setenv("BILINOTE_CUDA_DIR", str(pack))
    assert runtime_paths.get_cuda_dll_dir() == str(pack)


def test_cuda_dll_dir_none_when_absent(tmp_path, monkeypatch):
    monkeypatch.delenv("BILINOTE_CUDA_DIR", raising=False)
    monkeypatch.setattr(runtime_paths, "DATA_ROOT", str(tmp_path))
    assert runtime_paths.get_cuda_dll_dir() is None


def test_cuda_dll_dir_under_data_root(tmp_path, monkeypatch):
    monkeypatch.delenv("BILINOTE_CUDA_DIR", raising=False)
    monkeypatch.setattr(runtime_paths, "DATA_ROOT", str(tmp_path))
    pack = tmp_path / "cuda"
    pack.mkdir()
    (pack / "cudnn64_9.dll").write_bytes(b"x")
    assert runtime_paths.get_cuda_dll_dir() == str(pack)


def test_cuda_dll_dir_ignores_empty_dir(tmp_path, monkeypatch):
    monkeypatch.delenv("BILINOTE_CUDA_DIR", raising=False)
    monkeypatch.setattr(runtime_paths, "DATA_ROOT", str(tmp_path))
    (tmp_path / "cuda").mkdir()
    assert runtime_paths.get_cuda_dll_dir() is None


# ---------- WhisperModelRegistry：默认路径随数据根 ----------

def test_registry_default_path_under_data_root(tmp_path, monkeypatch):
    from app.transcriber.whisper_models import WhisperModelRegistry

    monkeypatch.setattr(runtime_paths, "DATA_ROOT", str(tmp_path))
    registry = WhisperModelRegistry()
    assert str(registry.path) == os.path.join(str(tmp_path), "config", "whisper_models.json")
    registry.add_custom_model("本地模型", str(tmp_path / "m"))
    assert registry.resolve("本地模型") == str(tmp_path / "m")


def test_registry_explicit_path_still_supported(tmp_path):
    from app.transcriber.whisper_models import WhisperModelRegistry

    registry = WhisperModelRegistry(str(tmp_path / "custom.json"))
    assert str(registry.path) == str(tmp_path / "custom.json")


# ---------- 导入本地模型接口 ----------

@pytest.fixture()
def config_router(monkeypatch, tmp_path):
    """隔离的 config 路由模块：注册表指到临时文件，避免污染真实配置。"""
    import app.routers.config as config_router

    from app.transcriber.whisper_models import WhisperModelRegistry
    monkeypatch.setattr(
        "app.transcriber.whisper_models._registry",
        WhisperModelRegistry(str(tmp_path / "registry.json")),
    )
    monkeypatch.setattr(runtime_paths, "DATA_ROOT", str(tmp_path / "data"))
    return config_router


def _body(result):
    import json
    return json.loads(result.body.decode("utf-8"))


def test_import_local_model_bare_dir(config_router, tmp_path):
    from app.routers.config import LocalModelImportRequest

    model_dir = tmp_path / "my-whisper"
    model_dir.mkdir()
    (model_dir / "model.bin").write_bytes(b"weights")

    result = _body(config_router.import_local_whisper_model(
        LocalModelImportRequest(path=str(model_dir))
    ))
    assert result["code"] == 0
    assert result["data"]["name"] == "my-whisper"
    assert result["data"]["target"] == str(model_dir)
    assert "my-whisper" in result["data"]["custom"]


def test_import_local_model_hf_cache_layout(config_router, tmp_path):
    from app.routers.config import LocalModelImportRequest

    cache = tmp_path / "models--Systran--faster-whisper-tiny"
    snapshot = cache / "snapshots" / "abc123"
    snapshot.mkdir(parents=True)
    (snapshot / "model.bin").write_bytes(b"weights")

    result = _body(config_router.import_local_whisper_model(
        LocalModelImportRequest(path=str(cache))
    ))
    assert result["code"] == 0
    # 目录名按 HF 约定转成 Org/Name
    assert result["data"]["name"] == "Systran/faster-whisper-tiny"
    assert result["data"]["target"] == str(snapshot)


def test_import_local_model_rejects_incomplete_dir(config_router, tmp_path):
    from app.routers.config import LocalModelImportRequest

    empty = tmp_path / "broken"
    empty.mkdir()
    result = _body(config_router.import_local_whisper_model(
        LocalModelImportRequest(path=str(empty))
    ))
    assert result["code"] != 0
    assert "model.bin" in result["msg"]


def test_import_local_model_rejects_relative_path(config_router):
    from app.routers.config import LocalModelImportRequest

    result = _body(config_router.import_local_whisper_model(
        LocalModelImportRequest(path="uploads/some-model")
    ))
    assert result["code"] != 0
    assert "绝对路径" in result["msg"]


def test_import_local_model_rejects_missing_dir(config_router, tmp_path):
    from app.routers.config import LocalModelImportRequest

    result = _body(config_router.import_local_whisper_model(
        LocalModelImportRequest(path=str(tmp_path / "no-such-dir"))
    ))
    assert result["code"] != 0


def test_import_local_model_duplicate_name_rejected(config_router, tmp_path):
    from app.routers.config import LocalModelImportRequest

    model_dir = tmp_path / "dup"
    model_dir.mkdir()
    (model_dir / "model.bin").write_bytes(b"w")
    first = _body(config_router.import_local_whisper_model(
        LocalModelImportRequest(path=str(model_dir), name="tiny")
    ))
    # 与内置档位重名必须被拒绝，避免遮蔽内置模型
    assert first["code"] != 0


# ---------- deploy_status：整体可调用且字段齐全 ----------

def test_deploy_status_reports_layered_cuda(config_router):
    status = _body(asyncio.run(config_router.deploy_status()))
    data = status["data"]
    assert set(data["cuda"]) >= {"available", "inference_ready", "torch_installed"}
    assert isinstance(data["cuda"]["available"], bool)
    assert "ffmpeg" in data and "whisper" in data
