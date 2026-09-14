"""后端服务运行日志的查看接口。

桌面端「服务日志」页面依赖：
- GET /api/logs/tail  读取 app.log：不带 after_bytes 时回填最近 N 行（首次进入）；
  带 after_bytes 时只返回该字节偏移之后的新增完整行（客户端每秒轮询增量）。

日志统一写入 logs/app.log（见 app.utils.logger），因此这里能看到完整服务日志，
包括 uvicorn 访问日志与第三方库告警，便于排查任务卡住时的真实阻塞点。
"""

from pathlib import Path
from typing import Optional

from fastapi import APIRouter

from app.utils.logger import get_logger, log_file_path
from app.utils.response import ResponseWrapper as R

router = APIRouter()
logger = get_logger(__name__)

# 回填/增量单次最多读多少字节，避免一次接口调用把超大日志全读进内存
_MAX_READ_BYTES = 2 * 1024 * 1024
_MAX_LINES = 5000


def _read_log(path: Path, start: int, end: int) -> str:
    """读取 [start, end) 字节区间；start 落在行中间时丢弃开头的半行。"""
    with path.open("rb") as handle:
        handle.seek(start)
        chunk = handle.read(max(0, end - start))
    text = chunk.decode("utf-8", errors="replace")
    if start > 0:
        text = text.split("\n", 1)[-1] if "\n" in text else ""
    return text


@router.get("/logs/tail")
def tail_logs(lines: int = 800, after_bytes: Optional[int] = None):
    path = log_file_path()
    cap = min(max(lines, 1), _MAX_LINES)
    try:
        size = path.stat().st_size
    except OSError:
        return R.success({"path": str(path), "size": 0, "lines": [], "mode": "backfill"})

    if after_bytes is None:
        # 首次进入：从文件末尾回填最近 cap 行
        start = max(0, size - _MAX_READ_BYTES)
        text = _read_log(path, start, size)
        return R.success({
            "path": str(path),
            "size": size,
            "lines": text.splitlines()[-cap:],
            "mode": "backfill",
        })

    offset = max(0, int(after_bytes))
    if size < offset:
        # 日志被清空或轮转：回到回填模式，客户端整体替换视图
        start = max(0, size - _MAX_READ_BYTES)
        text = _read_log(path, start, size)
        return R.success({
            "path": str(path),
            "size": size,
            "lines": text.splitlines()[-cap:],
            "mode": "backfill",
        })

    if size == offset:
        return R.success({"path": str(path), "size": size, "lines": [], "mode": "increment"})

    # 增量模式：只取新增内容；单次增量过大时退化为回填最近 cap 行，避免响应无限膨胀
    if size - offset > _MAX_READ_BYTES:
        start = max(0, size - _MAX_READ_BYTES)
        text = _read_log(path, start, size)
        return R.success({
            "path": str(path),
            "size": size,
            "lines": text.splitlines()[-cap:],
            "mode": "backfill",
        })

    text = _read_log(path, offset, size)
    return R.success({
        "path": str(path),
        "size": size,
        "lines": text.splitlines()[-cap:],
        "mode": "increment",
    })
