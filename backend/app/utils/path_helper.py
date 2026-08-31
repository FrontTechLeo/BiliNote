import os

from app.utils.runtime_paths import get_media_data_dir, get_models_base_dir


def get_data_dir():
    """下载器缓存（视频等）目录，历史上是 backend/data；打包后是 exe 同级 data。"""
    data_path = get_media_data_dir()
    os.makedirs(data_path, exist_ok=True)
    return data_path


def get_model_dir(subdir: str = "whisper") -> str:
    """
    本地转写模型目录。优先级：
    BILINOTE_MODELS_DIR > BILINOTE_DATA_DIR/models > frozen 旧位置(APPDATA) > 开发目录
    """
    path = os.path.join(get_models_base_dir(), subdir)
    os.makedirs(path, exist_ok=True)
    return path


def get_app_dir(subdir: str = "") -> str:
    """
    返回一个稳定的可写目录：
    - 设置 BILINOTE_DATA_DIR 时：数据根/data/<subdir>
    - 开发时：使用项目 data 目录
    - 打包后：使用 exe 所在目录
    """
    full_path = get_media_data_dir()
    if subdir:
        full_path = os.path.join(full_path, subdir)
    os.makedirs(full_path, exist_ok=True)
    return full_path
