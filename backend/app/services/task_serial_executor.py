import os
from concurrent.futures import ThreadPoolExecutor, Future
from typing import Any, Callable, Optional


class ConcurrentTaskExecutor:
    """使用线程池并发执行任务，替代原来的串行锁。"""

    def __init__(self, max_workers: Optional[int] = None):
        self._max_workers = max_workers or int(os.getenv("TASK_MAX_WORKERS", "3"))
        self._pool = ThreadPoolExecutor(max_workers=self._max_workers)

    def run(self, fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
        return self.submit(fn, *args, **kwargs).result()

    def submit(self, fn: Callable[..., Any], *args: Any, **kwargs: Any) -> Future:
        """Submit work without blocking the caller.

        Batch tasks use this to share the same worker limit as ordinary video
        generation jobs.  Keeping a single executor prevents a large course
        from monopolising CPU, Whisper, or the configured LLM provider.
        """
        return self._pool.submit(fn, *args, **kwargs)

    @property
    def max_workers(self) -> int:
        return self._max_workers

    def shutdown(self, wait: bool = True):
        self._pool.shutdown(wait=wait)


# 保持向后兼容的导出名
SerialTaskExecutor = ConcurrentTaskExecutor
task_serial_executor = ConcurrentTaskExecutor()
