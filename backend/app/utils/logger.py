import logging
import sys
from pathlib import Path

from app.utils.runtime_paths import get_logs_dir

# 日志目录
LOG_DIR = Path(get_logs_dir())
LOG_DIR.mkdir(parents=True, exist_ok=True)

# 日志格式
formatter = logging.Formatter(
    fmt="%(asctime)s [%(levelname)s] %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)


class _FlushHandler(logging.StreamHandler):
    """写入后立即 flush stdout，防止日志积压不显示。"""

    def emit(self, record: logging.LogRecord) -> None:
        super().emit(record)
        self.flush()


# 控制台输出（每条日志后自动 flush）
console_handler = _FlushHandler(sys.stdout)
console_handler.setFormatter(formatter)

# 文件输出
file_handler = logging.FileHandler(LOG_DIR / "app.log", encoding="utf-8")
file_handler.setFormatter(formatter)


def get_logger(name: str) -> logging.Logger:
    logger = logging.getLogger(name)
    if not logger.handlers:
        logger.setLevel(logging.INFO)
        logger.addHandler(console_handler)
        logger.addHandler(file_handler)
        logger.propagate = False
    return logger


def log_file_path() -> Path:
    return LOG_DIR / "app.log"


def setup_full_logging() -> None:
    """让 root logger 接管全部日志（含 uvicorn 访问日志与第三方库告警）。

    必须在 uvicorn.run(log_config=None) 之前调用：uvicorn 默认的 dictConfig 会把
    uvicorn.* 的 handler 重置为独立的 stdout/stderr；这里通过禁用该配置，让
    uvicorn.* 的记录自然 propagate 到挂好 console+file handler 的 root logger，
    从而 app.log 里能看到完整服务日志，供桌面端「服务日志」页实时查看。
    """
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    for handler in (console_handler, file_handler):
        if handler not in root.handlers:
            root.addHandler(handler)
