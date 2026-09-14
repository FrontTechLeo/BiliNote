from faster_whisper import WhisperModel

# huggingface_hub 随 faster-whisper 安装；防御性导入，失败时按未知类型处理
try:
    from huggingface_hub import constants as _hf_constants
    from huggingface_hub.errors import LocalEntryNotFoundError as _HFLocalEntryNotFoundError
except ImportError:
    _hf_constants = None
    _HFLocalEntryNotFoundError = ()

from app.decorators.timeit import timeit
from app.models.transcriber_model import TranscriptSegment, TranscriptResult
from app.transcriber.base import Transcriber
from app.transcriber.whisper_models import (
    resolve_whisper_model,
    is_local_target,
    is_hf_cache_complete,
    hf_cache_dirname,
)
from app.utils.env_checker import cuda_inference_ready, is_cuda_available
from app.utils.logger import get_logger
from app.utils.path_helper import get_model_dir

from events import transcription_finished
from pathlib import Path
import os
import shutil
import threading


'''
 Size of the model to use (tiny, tiny.en, base, base.en, small, small.en, distil-small.en, medium, medium.en, distil-medium.en, large-v1, large-v2, large-v3, large, distil-large-v2, distil-large-v3, large-v3-turbo, or turbo
'''
logger=get_logger(__name__)

# 本机 Whisper 转写的路数闸门（进程级，CPU/GPU 一律生效）。转写是全局最重的环节：
# 多路并发各自展开惰性解码循环时显存/算力成倍叠加，小显存卡（如 6GB）上 3 路 medium
# 直接把显存顶满，表现为转写长时间零输出、任务卡在 TRANSCRIBING。按需求固定串行：
# 同一批次内、以及不同批量任务（合集）之间都排队逐个转写。可用环境变量 WHISPER_GPU_CONCURRENCY 放大。
_TRANSCRIBE_CONCURRENCY = max(1, int(os.getenv("WHISPER_GPU_CONCURRENCY", "1")))
_transcribe_slots = threading.BoundedSemaphore(_TRANSCRIBE_CONCURRENCY)

# 历史遗留：之前用 modelscope 下载到自定义目录然后把路径传给 WhisperModel。
# 但 faster-whisper 1.1.1 的 download_model（utils.py:76）逻辑是：
# 只要 size_or_id 里含 "/" 就当 HF repo_id 处理，没有「本地目录直接返回」分支。
# 我们传 /app/models/whisper/whisper-tiny 进去 → 被当成不存在的 HF repo →
# 在线请求失败 → fallback local_files_only=True → HF cache 找不到（因为是
# modelscope 目录布局不是 HF）→ LocalEntryNotFoundError，误导说"离线模式"。
# 解法：彻底让 faster-whisper 自己处理下载——传 size name，配 download_root
# 作为 HF cache 根目录，HF_ENDPOINT 已经在 Dockerfile 里指到 hf-mirror.com，
# 国内能用。删掉 modelscope 那一套，避免布局不匹配。
class WhisperTranscriber(Transcriber):
    def __init__(
            self,
            model_size: str = "base",
            device: str = 'cpu',
            compute_type: str = None,
            cpu_threads: int = 1,
    ):
        if device == 'cpu' or device is None:
            self.device = 'cpu'
        else:
            self.device = "cuda" if self.is_cuda() else "cpu"
            if device == 'cuda' and self.device == 'cpu':
                print('没有 cuda 使用 cpu进行计算')

        self.compute_type = compute_type or ("float32" if self.device == "cuda" else "int8")
        self.model_size = model_size

        model_dir = get_model_dir("whisper")
        try:
            self.model = self._build_model(model_size, model_dir)
        except Exception as e:
            if self._cache_complete(model_dir, model_size):
                # 本地模型完整却加载失败：多半是运行期错误（并发 bug / CUDA / 显存），
                # 与 cache 内容无关。绝不能删——完整 cache 可能是用户手动拷贝的模型，
                # 曾经在并发竞争抛出 disabled_tqdm 异常时把用户的完整 medium 模型整目录删掉。
                logger.warning(f"加载 whisper-{model_size} 失败，但本地模型完整，保留 cache 不清理：{e}")
                raise self._load_failure(e, model_size, model_dir) from e
            # cache 不完整/不存在：清掉半成品后重试一次下载（自愈仅限这种情况）
            logger.warning(f"加载 whisper-{model_size} 失败且本地 cache 不完整：{e}；清理半成品后重新下载")
            self._purge_cache(model_dir, model_size)
            try:
                self.model = self._build_model(model_size, model_dir)
            except Exception as retry_exc:
                raise self._load_failure(retry_exc, model_size, model_dir) from retry_exc

    @staticmethod
    def _cache_complete(model_dir: str, model_size: str) -> bool:
        """本地是否有完整可用的模型（HF cache snapshot 含 model.bin，或本地路径模型可用）。"""
        try:
            target = resolve_whisper_model(model_size)
        except Exception:
            return False
        if is_local_target(target):
            return (Path(target) / "model.bin").is_file()
        return is_hf_cache_complete(model_dir, target)

    @staticmethod
    def _load_failure(exc: Exception, model_size: str, model_dir: str) -> RuntimeError:
        """把底层加载错误翻译成带行动指引的错误（保留原始异常链）。

        faster-whisper 在线下载失败时会吞掉真实网络错误、回退 local_files_only=True，
        最终抛出的 LocalEntryNotFoundError 文案（"Cannot find an appropriate cached
        snapshot folder ... outgoing traffic has been disabled"）极具误导性。
        """
        if isinstance(exc, _HFLocalEntryNotFoundError):
            endpoint = getattr(_hf_constants, "ENDPOINT", None) if _hf_constants else None
            endpoint_text = f"（下载端点: {endpoint}）" if endpoint else ""
            msg = (
                f'whisper 模型 "{model_size}" 未下载且联网下载失败。'
                f"请检查网络或代理设置后重试；或在「音频转写配置」页先下载该模型；"
                f"也可将完整模型目录（含 snapshots/blobs/refs）拷贝到 {model_dir}。{endpoint_text}"
            )
        else:
            msg = (
                f'whisper 模型 "{model_size}" 加载失败：{exc}。'
                f"若反复出现，请检查模型文件是否完整（{model_dir}），"
                f"或在「音频转写配置」页重新下载该模型。"
            )
        return RuntimeError(msg)

    def _build_model(self, model_size: str, model_dir: str) -> WhisperModel:
        # resolve 把模型名映射成可加载标识：内置 size→Systran repo_id、自定义映射、
        # 直通的 repo_id 或本地路径。faster-whisper 对本地目录走 os.path.isdir 分支，
        # 对 repo_id 走 download_model(cache_dir=download_root)，两者都吃 model_size_or_path。
        target = resolve_whisper_model(model_size)
        return WhisperModel(
            model_size_or_path=target,
            device=self.device,
            compute_type=self.compute_type,
            download_root=model_dir,
        )

    @staticmethod
    def _purge_cache(model_dir: str, model_size: str) -> None:
        """加载失败且本地 cache 确认不完整时，清掉对应 HF cache 的半成品目录，强制下次重下。

        只允许在 `_cache_complete` 判定为不完整后调用——完整的模型目录（尤其是用户
        手动拷贝的）绝不删除。关键：本地路径模型**绝不删**——那是用户自己的文件；
        只清 HF cache 布局 <model_dir>/models--{org}--{name}/（含历史 modelscope 目录）。
        """
        try:
            target = resolve_whisper_model(model_size)
        except Exception:
            target = model_size
        if is_local_target(target):
            logger.warning(
                f"模型 {model_size} 指向本地路径 {target}，加载失败不清理用户文件，请检查该目录是否完整"
            )
            return
        candidates = [
            Path(model_dir) / hf_cache_dirname(target),       # HF cache: models--org--name
            Path(model_dir) / f"whisper-{model_size}",        # 历史 modelscope 目录，顺手清掉
        ]
        for path in candidates:
            if path.exists():
                logger.info(f"清理损坏 cache: {path}")
                shutil.rmtree(path, ignore_errors=True)
    @staticmethod
    def is_torch_installed() -> bool:
        try:
            import torch
            return True
        except ImportError:
            return False

    @staticmethod
    def is_cuda() -> bool:
        # 设备选择看「推理就绪」而不是只看驱动：桌面端打包版没有 torch，
        # 但 ctranslate2 也能探测 GPU；缺 cuBLAS/cuDNN 运行库时老实用 CPU。
        try:
            if is_cuda_available() and cuda_inference_ready():
                logger.info("CUDA 可用，使用 GPU 转写")
                return True
            if is_cuda_available():
                logger.info("检测到 GPU 但缺少 CUDA 运行库（cuBLAS/cuDNN），使用 CPU 转写")
            return False
        except Exception:
            return False

    @timeit
    def transcript(self, file_path: str) -> TranscriptResult:
        try:
            return self._transcribe(file_path)
        except Exception as e:
            # GPU 推理失败（驱动/运行库/显存问题）自动降级 CPU 重建一次，
            # 不能让整条笔记任务直接死掉——CPU 慢但能出结果。
            if self.device != "cuda":
                raise
            logger.warning(f"CUDA 转写失败（{e}），降级 CPU 重试")
            self.device = "cpu"
            self.compute_type = "int8"
            self.model = self._build_model(self.model_size, get_model_dir("whisper"))
            return self._transcribe(file_path)

    def _transcribe(self, file_path: str) -> TranscriptResult:
        acquired = _transcribe_slots.acquire(blocking=False)
        if not acquired:
            logger.info(
                "转写并发已满（%s 路），排队等待空闲转写槽位", _TRANSCRIBE_CONCURRENCY
            )
            _transcribe_slots.acquire()
        logger.info("获得转写槽位，开始推理")
        try:
            segments_raw, info = self.model.transcribe(file_path)

            segments = []
            full_text = ""

            # transcribe() 返回的是惰性生成器，真正推理发生在下面的迭代中；
            # 槽位必须罩住整个迭代，否则限流形同虚设。
            for seg in segments_raw:
                text = seg.text.strip()
                full_text += text + " "
                segments.append(TranscriptSegment(
                    start=seg.start,
                    end=seg.end,
                    text=text
                ))
        finally:
            _transcribe_slots.release()

        return TranscriptResult(
            language=info.language,
            full_text=full_text.strip(),
            segments=segments,
            raw=info
        )


    def on_finish(self,video_path:str,result: TranscriptResult)->None:
        print("转写完成")
        transcription_finished.send({
            "file_path": video_path,
        })

