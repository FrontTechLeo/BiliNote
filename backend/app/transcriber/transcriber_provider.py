import os
import platform
import threading
import time
from enum import Enum

from app.transcriber.groq import GroqTranscriber
from app.transcriber.whisper import WhisperTranscriber
from app.transcriber.bcut import BcutTranscriber
from app.transcriber.kuaishou import KuaishouTranscriber
from app.utils.logger import get_logger

logger = get_logger(__name__)

class TranscriberType(str, Enum):
    FAST_WHISPER = "fast-whisper"
    MLX_WHISPER = "mlx-whisper"
    BCUT = "bcut"
    KUAISHOU = "kuaishou"
    GROQ = "groq"

# 在 Apple 平台尝试导入 MLX Whisper（不再依赖环境变量，支持前端动态切换）
MLX_WHISPER_AVAILABLE = False
if platform.system() == "Darwin":
    try:
        from app.transcriber.mlx_whisper_transcriber import MLXWhisperTranscriber
        MLX_WHISPER_AVAILABLE = True
        logger.info("MLX Whisper 可用，已导入")
    except ImportError:
        logger.warning("MLX Whisper 导入失败，可能未安装 mlx_whisper")

logger.info('初始化转录服务提供器')

# 转录器单例缓存
_transcribers = {
    TranscriberType.FAST_WHISPER: None,
    TranscriberType.MLX_WHISPER: None,
    TranscriberType.BCUT: None,
    TranscriberType.KUAISHOU: None,
    TranscriberType.GROQ: None,
}

# 公共实例初始化函数
# 批量任务多个 worker 并发首次取转写器时，check-then-set 若不加锁会各自 new 一份
# Whisper 模型（显存成倍占用，小显存卡上直接把转写挤死），必须持锁完成整个检查+创建。
_transcriber_init_lock = threading.Lock()

# 创建失败负缓存：key -> (失败时间, 异常)。批量任务 N 个 worker 并发取转写器时，
# 第一个创建失败（模型未下载、网络不通等）后，其余 worker 在窗口内直接复用同一
# 错误快速失败，避免逐个 new + 重试循环放大（曾在 17 秒内连续十几轮尝试）。
# 窗口过期后允许再次尝试——网络可能已恢复。
_INIT_FAILURE_TTL_SECONDS = 30
_init_failures: dict = {}


def _init_transcriber(key: TranscriberType, cls, *args, **kwargs):
    with _transcriber_init_lock:
        if _transcribers[key] is None:
            failure = _init_failures.get(key)
            if failure is not None:
                if time.monotonic() - failure[0] < _INIT_FAILURE_TTL_SECONDS:
                    raise failure[1]
                _init_failures.pop(key, None)
            logger.info(f'创建 {cls.__name__} 实例: {key}')
            try:
                _transcribers[key] = cls(*args, **kwargs)
                logger.info(f'{cls.__name__} 创建成功')
                _init_failures.pop(key, None)
            except Exception as e:
                logger.error(f"{cls.__name__} 创建失败: {e}")
                _init_failures[key] = (time.monotonic(), e)
                raise
        return _transcribers[key]


def reset_transcriber(key: TranscriberType) -> None:
    """配置变更后失效单例与失败标记，下一次任务按新配置重建。"""
    with _transcriber_init_lock:
        _transcribers[key] = None
        _init_failures.pop(key, None)

# 各类型获取方法
def get_groq_transcriber():
    return _init_transcriber(TranscriberType.GROQ, GroqTranscriber)

def get_whisper_transcriber(model_size="base", device="cuda"):
    return _init_transcriber(TranscriberType.FAST_WHISPER, WhisperTranscriber, model_size=model_size, device=device)

def get_bcut_transcriber():
    return _init_transcriber(TranscriberType.BCUT, BcutTranscriber)

def get_kuaishou_transcriber():
    return _init_transcriber(TranscriberType.KUAISHOU, KuaishouTranscriber)

def get_mlx_whisper_transcriber(model_size="base"):
    if not MLX_WHISPER_AVAILABLE:
        logger.warning("MLX Whisper 不可用，请确保在 Apple 平台且已安装 mlx_whisper")
        raise ImportError("MLX Whisper 不可用")
    return _init_transcriber(TranscriberType.MLX_WHISPER, MLXWhisperTranscriber, model_size=model_size)

# 通用入口
def get_transcriber(transcriber_type="fast-whisper", model_size=None, device="cuda"):
    """
    获取指定类型的转录器实例

    参数:
        transcriber_type: 支持 "fast-whisper", "mlx-whisper", "bcut", "kuaishou", "groq"
        model_size: 模型大小，适用于 whisper 类
        device: 设备类型（如 cuda / cpu），仅 whisper 使用

    返回:
        对应类型的转录器实例
    """
    logger.info(f'请求转录器类型: {transcriber_type}')

    try:
        transcriber_enum = TranscriberType(transcriber_type)
    except ValueError:
        logger.warning(f'未知转录器类型 "{transcriber_type}"，默认使用 fast-whisper')
        transcriber_enum = TranscriberType.FAST_WHISPER

    # 优先级：调用方显式值（来自配置文件）> WHISPER_MODEL_SIZE env（docker/.env 默认）> tiny
    # 此前 env 永远压过配置文件，导致前端切了模型仍用 .env 里的旧档位。
    whisper_model_size = model_size or os.environ.get("WHISPER_MODEL_SIZE", "tiny")

    if transcriber_enum == TranscriberType.FAST_WHISPER:
        return get_whisper_transcriber(whisper_model_size, device=device)

    elif transcriber_enum == TranscriberType.MLX_WHISPER:
        if not MLX_WHISPER_AVAILABLE:
            raise RuntimeError(
                "MLX Whisper 不可用：需要 macOS 平台并安装 mlx_whisper 包 (pip install mlx_whisper)。"
                "请在「音频转写配置」页面切换到其他转写引擎。"
            )
        return get_mlx_whisper_transcriber(whisper_model_size)

    elif transcriber_enum == TranscriberType.BCUT:
        return get_bcut_transcriber()

    elif transcriber_enum == TranscriberType.KUAISHOU:
        return get_kuaishou_transcriber()

    elif transcriber_enum == TranscriberType.GROQ:
        return get_groq_transcriber()

    # fallback
    logger.warning(f'未识别转录器类型 "{transcriber_type}"，使用 fast-whisper 作为默认')
    return get_whisper_transcriber(whisper_model_size, device=device)
