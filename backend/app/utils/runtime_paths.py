"""运行期可写路径的统一解析。

历史上所有可写数据（数据库、笔记产物、截图、上传缓存、配置 JSON、模型、
向量库、日志）都按 cwd 相对路径落在 backend 工作目录里。PC 客户端安装后
安装目录不可写、也不该把数据散落在安装目录，因此支持通过环境变量
``BILINOTE_DATA_DIR`` 指定一个「数据根目录」，所有可写数据统一落到其下。

优先级（高 → 低）：
1. 各项自己的显式环境变量（如 NOTE_OUTPUT_DIR / OUT_DIR / VECTOR_DB_DIR /
   DATABASE_URL / BILINOTE_MODELS_DIR）——保持与既有部署完全兼容。注意：当
   设置了数据根时，只有**绝对路径**的显式值才生效；项目 .env 里历史遗留的
   相对路径（如 OUT_DIR=./static/screenshots）会被忽略，回落到数据根，
   否则数据会被写回 cwd；
2. ``BILINOTE_DATA_DIR`` 数据根目录（PC 客户端注入）；
3. 历史默认值（cwd 相对路径 / frozen 时的旧位置），web/docker 部署不受影响。
"""

import os
import sys
from typing import Optional

# 数据根目录；未设置时一切维持历史行为
DATA_ROOT = (os.getenv("BILINOTE_DATA_DIR") or "").strip() or None


def _explicit_value(explicit_env: str) -> str:
    """取显式 env 值；设置了数据根时，相对路径视为 .env 遗留值而忽略。"""
    value = (os.getenv(explicit_env) or "").strip()
    if value and DATA_ROOT and not os.path.isabs(value):
        return ""
    return value


def _resolve(explicit_env: str | None, default_rel: str) -> str:
    if explicit_env:
        value = _explicit_value(explicit_env)
        if value:
            return value
    if DATA_ROOT:
        return os.path.join(DATA_ROOT, default_rel)
    return default_rel


def get_static_root() -> str:
    """静态文件根目录（截图等对外可访问产物的父目录）。"""
    return _resolve(None, "static")


def get_screenshots_dir() -> str:
    """视频理解截图输出目录，历史上默认 ./static/screenshots。"""
    return _resolve("OUT_DIR", os.path.join("static", "screenshots"))


def get_uploads_dir() -> str:
    """HTTP 上传的本地视频暂存目录。"""
    return _resolve(None, "uploads")


def get_config_dir() -> str:
    """运行期配置 JSON（cookie / 转写 / 代理）目录。"""
    return _resolve(None, "config")


def get_note_output_dir() -> str:
    """单任务笔记产物目录（task JSON / 状态 / 转写缓存）。"""
    return _resolve("NOTE_OUTPUT_DIR", "note_results")


def get_vector_db_dir() -> str:
    """RAG 向量库目录。"""
    return _resolve("VECTOR_DB_DIR", "vector_db")


def get_logs_dir() -> str:
    """日志目录。"""
    return _resolve(None, "logs")


def get_database_url() -> str:
    """SQLAlchemy 连接串；SQLite 默认文件随数据根目录迁移。"""
    explicit = (os.getenv("DATABASE_URL") or "").strip()
    if explicit:
        if not DATA_ROOT:
            return explicit
        # 数据根模式下：非 sqlite URL 照常生效；sqlite 的相对文件路径视为 .env 遗留
        if not explicit.startswith("sqlite"):
            return explicit
        file_part = explicit[len("sqlite:///"):].split("?", 1)[0]
        if file_part and os.path.isabs(file_part.replace("/", "\\")) or file_part.startswith("/"):
            return explicit
    if DATA_ROOT:
        return "sqlite:///" + os.path.join(DATA_ROOT, "bili_note.db")
    return "sqlite:///bili_note.db"


def get_models_base_dir() -> str:
    """本地转写模型（whisper / mlx-whisper）的根目录。"""
    explicit = _explicit_value("BILINOTE_MODELS_DIR")
    if explicit:
        return explicit
    if DATA_ROOT:
        return os.path.join(DATA_ROOT, "models")
    if getattr(sys, "frozen", False):
        # PyInstaller 打包后的历史位置：APPDATA/BiliNote/models
        return os.path.join(os.getenv("APPDATA") or os.path.expanduser("~"), "BiliNote", "models")
    return os.path.abspath(os.path.join(os.path.dirname(__file__), "../../models"))


def get_media_data_dir() -> str:
    """下载器缓存（视频、分 P 临时文件）目录，历史上是 backend/data。"""
    if DATA_ROOT:
        return os.path.join(DATA_ROOT, "data")
    if getattr(sys, "frozen", False):
        return os.path.join(os.path.dirname(sys.executable), "data")
    return os.path.abspath(os.path.join(os.path.dirname(__file__), "../../data"))


def get_workspace_dir(subdir: str = "") -> str:
    """任务工作区根目录（video_tasks 等中间产物），历史上是 backend/data/<subdir>。"""
    base = get_media_data_dir()
    path = os.path.join(base, subdir) if subdir else base
    os.makedirs(path, exist_ok=True)
    return path


def get_cuda_dll_dir() -> Optional[str]:
    """CUDA 加速包目录（cuBLAS/cuDNN 等 DLL），供桌面端打包版启用 GPU 转写。

    打包版为控制体积不带 torch，GPU 转写依赖放在这个目录里的 CUDA 运行库。
    查找顺序：BILINOTE_CUDA_DIR > exe 同级 cuda\\ > 数据根 cuda\\。
    目录存在且里面有 dll 才返回，否则 None（纯 CPU 模式）。
    """
    candidates = []
    explicit = (os.getenv("BILINOTE_CUDA_DIR") or "").strip()
    if explicit:
        candidates.append(explicit)
    if getattr(sys, "frozen", False):
        candidates.append(os.path.join(os.path.dirname(sys.executable), "cuda"))
    if DATA_ROOT:
        candidates.append(os.path.join(DATA_ROOT, "cuda"))
    for candidate in candidates:
        if os.path.isdir(candidate) and any(name.endswith(".dll") for name in os.listdir(candidate)):
            return candidate
    return None


_TRUTHY = {"1", "true", "yes", "on"}


def local_path_input_enabled() -> bool:
    """PC 客户端「本地路径直读」功能的总开关（env: ALLOW_LOCAL_PATH_INPUT）。

    web/docker 公网部署保持关闭：接口不接受任意磁盘路径，维持原有的
    上传目录安全边界。PC 客户端拉起 sidecar 时注入该变量。
    """
    return (os.getenv("ALLOW_LOCAL_PATH_INPUT") or "").strip().lower() in _TRUTHY


def is_filesystem_absolute(value: str) -> bool:
    """判断字符串是否是真正的文件系统绝对路径。

    不能用 ``os.path.isabs``：Windows 的 ntpath 把 ``/uploads/x`` 这类
    根相对 URL 也算作绝对路径（当前盘符根），会把上传 URL 误判成磁盘路径。
    """
    import re

    if re.match(r"^[A-Za-z]:[\\/]", value) or value.startswith("\\\\"):
        return True
    if os.name != "nt" and value.startswith("/"):
        return True
    return False
