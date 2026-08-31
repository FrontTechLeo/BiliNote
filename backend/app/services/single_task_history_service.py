"""Persistent history metadata for standalone note-generation tasks."""

from __future__ import annotations

import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.db.engine import SessionLocal
from app.db.models.video_tasks import BatchTaskItem, VideoTask
from app.enmus.task_status_enums import TaskStatus
from app.utils.runtime_paths import get_note_output_dir


NOTE_OUTPUT_DIR = Path(get_note_output_dir())
_TASK_ID_RE = re.compile(r"^[A-Za-z0-9-]+$")
_SCREENSHOT_REF_RE = re.compile(r"!\[[^\]]*\]\(/static/screenshots/([^)]+)\)")
_RAW_SCREENSHOT_RE = re.compile(r"\*?Screenshot-(?:\[\d{2}:\d{2}\]|\d{2}:\d{2})")


class SingleTaskHistoryService:
    @staticmethod
    def _request_path(task_id: str) -> Path:
        return NOTE_OUTPUT_DIR / f"{task_id}_request.json"

    @staticmethod
    def _read_json(path: Path) -> dict[str, Any] | None:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
            return payload if isinstance(payload, dict) else None
        except (OSError, ValueError, TypeError):
            return None

    def persist_request(self, task_id: str, request_data: dict[str, Any]) -> None:
        NOTE_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        existing = self.get_request(task_id) or {}
        payload = {
            **request_data,
            "task_id": task_id,
            "created_at": existing.get("created_at") or datetime.now(timezone.utc).isoformat(),
        }
        # Prefetched transcripts can be very large and already have their own
        # cache file. Keep only the fact that one was supplied.
        transcript = payload.pop("prefetched_transcript", None)
        payload["has_prefetched_transcript"] = bool(transcript)
        target = self._request_path(task_id)
        temporary = target.with_suffix(".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(target)

    def get_request(self, task_id: str) -> dict[str, Any] | None:
        if not _TASK_ID_RE.fullmatch(task_id):
            return None
        return self._read_json(self._request_path(task_id))

    @staticmethod
    def _local_source_exists(source_url: str) -> bool:
        if not source_url.startswith("/uploads/"):
            return True
        return (Path.cwd() / source_url.lstrip("/")).is_file()

    def _serialize(self, task_id: str, db_task: VideoTask | None = None) -> dict[str, Any]:
        request = self.get_request(task_id) or {}
        result_path = NOTE_OUTPUT_DIR / f"{task_id}.json"
        status_path = NOTE_OUTPUT_DIR / f"{task_id}.status.json"
        result = self._read_json(result_path) or {}
        status_payload = self._read_json(status_path) or {}
        audio_meta = result.get("audio_meta") if isinstance(result.get("audio_meta"), dict) else {}
        markdown = str(result.get("markdown") or "")
        status = str(status_payload.get("status") or (TaskStatus.SUCCESS.value if markdown else TaskStatus.PENDING.value))
        source_url = str(request.get("video_url") or "")
        title = str(
            audio_meta.get("title")
            or request.get("display_title")
            or (Path(source_url).stem if source_url.startswith("/uploads/") else "")
            or (db_task.video_id if db_task else "")
            or task_id
        )
        created_at = request.get("created_at")
        if not created_at and db_task and db_task.created_at:
            created_at = db_task.created_at.isoformat()
        if not created_at:
            candidates = [path for path in (status_path, result_path) if path.exists()]
            if candidates:
                created_at = datetime.fromtimestamp(min(path.stat().st_mtime for path in candidates), timezone.utc).isoformat()

        has_images = bool(_SCREENSHOT_REF_RE.search(markdown) or _RAW_SCREENSHOT_RE.search(markdown))
        return {
            "task_id": task_id,
            "title": title,
            "platform": request.get("platform") or (db_task.platform if db_task else "unknown"),
            "source_url": source_url or None,
            "status": status,
            "message": status_payload.get("message") or "",
            "created_at": created_at,
            "has_result": bool(markdown),
            "has_images": has_images,
            "export_format": "zip" if has_images else "md",
            "can_regenerate": bool(request and source_url and self._local_source_exists(source_url)),
        }

    def list_tasks(self, page: int = 1, page_size: int = 20) -> dict[str, Any]:
        page = max(1, page)
        page_size = min(100, max(1, page_size))
        db = SessionLocal()
        try:
            db_rows = db.query(VideoTask).order_by(VideoTask.created_at.desc()).all()
            db_by_id = {row.task_id: row for row in db_rows}
            batch_ids = {
                row[0]
                for row in db.query(BatchTaskItem.task_id)
                .filter(BatchTaskItem.task_id.isnot(None))
                .all()
            }
        finally:
            db.close()

        task_ids = set(db_by_id)
        if NOTE_OUTPUT_DIR.exists():
            for path in NOTE_OUTPUT_DIR.glob("*_request.json"):
                task_ids.add(path.name.removesuffix("_request.json"))
            for path in NOTE_OUTPUT_DIR.glob("*.status.json"):
                task_ids.add(path.name.removesuffix(".status.json"))
            for path in NOTE_OUTPUT_DIR.glob("*.json"):
                stem = path.stem
                if not any(stem.endswith(suffix) for suffix in ("_audio", "_transcript", "_request", ".status")):
                    task_ids.add(stem)

        rows = [
            self._serialize(task_id, db_by_id.get(task_id))
            for task_id in task_ids - batch_ids
            if _TASK_ID_RE.fullmatch(task_id)
        ]
        rows.sort(key=lambda row: row.get("created_at") or "", reverse=True)
        total = len(rows)
        start = (page - 1) * page_size
        return {"items": rows[start:start + page_size], "total": total, "page": page, "page_size": page_size}


single_task_history_service = SingleTaskHistoryService()
