"""In-process cancellation signals for video-generation workers."""

from __future__ import annotations

import threading


class TaskCancellationRegistry:
    """Keep cancellation requests safe across queued and running tasks."""

    def __init__(self) -> None:
        self._events: dict[str, threading.Event] = {}
        self._requested: set[str] = set()
        self._lock = threading.Lock()

    def register(self, task_id: str) -> threading.Event:
        event = threading.Event()
        with self._lock:
            self._events[task_id] = event
            if task_id in self._requested:
                event.set()
        return event

    def request(self, task_id: str) -> None:
        with self._lock:
            self._requested.add(task_id)
            event = self._events.get(task_id)
            if event:
                event.set()

    def release(self, task_id: str) -> None:
        with self._lock:
            self._events.pop(task_id, None)
            self._requested.discard(task_id)


task_cancellation_registry = TaskCancellationRegistry()
