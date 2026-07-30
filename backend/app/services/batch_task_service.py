"""Persistent orchestration for course-style video note generation."""

from __future__ import annotations

import io
import json
import os
import re
import threading
import time
import uuid
import zipfile
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote

import httpx
from fastapi import HTTPException
from fastapi.responses import Response

from app.db.engine import SessionLocal
from app.db.models.video_tasks import BatchTask, BatchTaskEvent, BatchTaskItem
from app.enmus.note_enums import DownloadQuality
from app.enmus.task_status_enums import TaskStatus
from app.services.cookie_manager import CookieConfigManager
from app.services.task_serial_executor import task_serial_executor
from app.utils.logger import get_logger

logger = get_logger(__name__)

_IMAGE_REF = re.compile(r'!\[([^\]]*)\]\(/static/screenshots/([^)]+)\)')
_BV_RE = re.compile(r'BV[0-9A-Za-z]+')
_VIDEO_EXTENSIONS = {".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v"}
_TERMINAL = {TaskStatus.SUCCESS.value, TaskStatus.FAILED.value}
NOTE_OUTPUT_DIR = Path(os.getenv("NOTE_OUTPUT_DIR", "note_results"))


class BilibiliCollectionResolver:
    """Resolve public UGC collections using the same web APIs as DownKyiCore."""

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36",
        "Referer": "https://www.bilibili.com/",
    }

    def _request(self, client: httpx.Client, url: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        response = client.get(url, params=params)
        response.raise_for_status()
        body = response.json()
        if body.get("code", 0) != 0:
            raise ValueError(body.get("message") or "B站返回了无法解析的响应")
        return body.get("data") or {}

    @staticmethod
    def _expand_video(view: dict[str, Any]) -> list[dict[str, str]]:
        bvid = view.get("bvid")
        if not bvid:
            return []
        title = view.get("title") or bvid
        pages = view.get("pages") or []
        base_url = f"https://www.bilibili.com/video/{bvid}/"
        if len(pages) <= 1:
            return [{"title": title, "source_url": base_url, "platform": "bilibili"}]
        items = []
        for index, page in enumerate(pages, start=1):
            part_title = page.get("part") or f"P{index}"
            items.append({
                "title": f"{title} - {part_title}",
                "source_url": f"{base_url}?p={index}",
                "platform": "bilibili",
            })
        return items

    def resolve(self, source_url: str) -> dict[str, Any]:
        cookie = CookieConfigManager().get("bilibili")
        headers = self.headers.copy()
        if cookie:
            headers["Cookie"] = cookie

        with httpx.Client(headers=headers, follow_redirects=True, timeout=20) as client:
            resolved_url = str(client.get(source_url).url) if "b23.tv" in source_url else source_url
            match = _BV_RE.search(resolved_url)
            if not match:
                raise ValueError("未能从链接中识别 BV 号，仅支持公开 B 站视频和合集链接")
            view = self._request(client, "https://api.bilibili.com/x/web-interface/view", {"bvid": match.group(0)})
            season = view.get("ugc_season") or {}
            season_id = season.get("season_id")
            owner = view.get("owner") or {}
            mid = season.get("mid") or owner.get("mid")
            if not season_id or not mid:
                return {"title": view.get("title") or match.group(0), "items": self._expand_video(view)}

            archives: list[dict[str, Any]] = []
            page_num = 1
            while True:
                data = self._request(
                    client,
                    "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list",
                    {"mid": mid, "season_id": season_id, "page_num": page_num, "page_size": 100, "sort_reverse": "false"},
                )
                page_archives = data.get("archives") or []
                archives.extend(page_archives)
                page = data.get("page") or {}
                total = int(page.get("total") or len(archives))
                if not page_archives or len(archives) >= total:
                    break
                page_num += 1

            items: list[dict[str, str]] = []
            for archive in archives:
                archive_bvid = archive.get("bvid")
                if not archive_bvid:
                    continue
                archive_view = self._request(client, "https://api.bilibili.com/x/web-interface/view", {"bvid": archive_bvid})
                items.extend(self._expand_video(archive_view))
            if not items:
                raise ValueError("合集没有可生成的公开视频")
            meta = (data.get("meta") or {}) if archives else {}
            return {"title": meta.get("name") or season.get("title") or view.get("title") or match.group(0), "items": items}


class BatchTaskService:
    def __init__(self) -> None:
        self._running: set[str] = set()
        self._lock = threading.Lock()
        self._resolver = BilibiliCollectionResolver()

    @staticmethod
    def _serialize_task(task: BatchTask) -> dict[str, Any]:
        total = task.total_count or 0
        done = (task.completed_count or 0) + (task.failed_count or 0)
        return {
            "id": task.id,
            "title": task.title,
            "source_type": task.source_type,
            "source_value": task.source_value,
            "status": task.status,
            "total_count": total,
            "completed_count": task.completed_count or 0,
            "failed_count": task.failed_count or 0,
            "running_count": task.running_count or 0,
            "progress": round(done / total * 100, 1) if total else 0,
            "created_at": task.created_at.isoformat() if task.created_at else None,
            "started_at": task.started_at.isoformat() if task.started_at else None,
            "finished_at": task.finished_at.isoformat() if task.finished_at else None,
        }

    @staticmethod
    def _serialize_item(item: BatchTaskItem) -> dict[str, Any]:
        return {
            "id": item.id,
            "batch_id": item.batch_id,
            "task_id": item.task_id,
            "title": item.title,
            "source_url": item.source_url,
            "platform": item.platform,
            "position": item.position,
            "status": item.status,
            "stage": item.stage,
            "message": item.message,
            "attempts": item.attempts,
            "created_at": item.created_at.isoformat() if item.created_at else None,
            "updated_at": item.updated_at.isoformat() if item.updated_at else None,
        }

    def preview_bilibili(self, source_url: str) -> dict[str, Any]:
        return self._resolver.resolve(source_url)

    def store_local_preview(self, files: Iterable[Any]) -> dict[str, Any]:
        preview_id = str(uuid.uuid4())
        root = Path("uploads") / "batch_previews" / preview_id
        root.mkdir(parents=True, exist_ok=True)
        items = []
        for index, uploaded in enumerate(files, start=1):
            suffix = Path(uploaded.filename or "").suffix.lower()
            if suffix not in _VIDEO_EXTENSIONS:
                continue
            filename = Path(uploaded.filename or f"video-{index}{suffix}").name
            target = root / filename
            duplicate = 1
            while target.exists():
                target = root / f"{Path(filename).stem}-{duplicate}{suffix}"
                duplicate += 1
            with target.open("wb") as output:
                while chunk := uploaded.file.read(1024 * 1024):
                    output.write(chunk)
            items.append({
                "title": target.stem,
                "source_url": "/" + target.as_posix(),
                "platform": "local",
                "position": len(items) + 1,
            })
        if not items:
            raise ValueError("文件夹中没有可处理的视频文件")
        return {"preview_id": preview_id, "title": "本地视频文件夹", "items": items}

    @staticmethod
    def _validate_local_source(source_url: str) -> None:
        if not source_url.startswith("/uploads/batch_previews/"):
            raise ValueError("本地文件必须来自当前批量上传会话")
        base = (Path.cwd() / "uploads" / "batch_previews").resolve()
        target = (Path.cwd() / source_url.lstrip("/")).resolve()
        if base not in target.parents or not target.is_file():
            raise ValueError("本地文件不存在或不在允许的上传目录中")

    def create_task(self, title: str, source_type: str, source_value: str | None, config: dict[str, Any], items: list[dict[str, Any]]) -> dict[str, Any]:
        if source_type not in {"bilibili", "local"}:
            raise ValueError("仅支持哔哩哔哩合集和本地文件夹批量任务")
        if not items:
            raise ValueError("至少选择一个视频")
        if not config.get("model_name") or not config.get("provider_id"):
            raise ValueError("请选择模型和供应商")
        batch_id = str(uuid.uuid4())
        db = SessionLocal()
        try:
            task = BatchTask(
                id=batch_id,
                title=title.strip() or "未命名批量任务",
                source_type=source_type,
                source_value=source_value,
                config_json=json.dumps(config, ensure_ascii=False),
                status="PENDING",
                total_count=len(items),
            )
            db.add(task)
            for position, raw in enumerate(items, start=1):
                source_url = str(raw.get("source_url") or "")
                platform = str(raw.get("platform") or ("local" if source_type == "local" else "bilibili"))
                if platform == "local":
                    self._validate_local_source(source_url)
                if not source_url:
                    raise ValueError("视频链接不能为空")
                db.add(BatchTaskItem(
                    id=str(uuid.uuid4()), batch_id=batch_id, source_url=source_url, platform=platform,
                    title=str(raw.get("title") or f"视频 {position}"), position=position, status="PENDING",
                ))
            db.commit()
            result = self._serialize_task(task)
        finally:
            db.close()
        self.start(batch_id)
        return result

    def list_tasks(self, page: int, page_size: int) -> dict[str, Any]:
        db = SessionLocal()
        try:
            query = db.query(BatchTask).order_by(BatchTask.created_at.desc())
            total = query.count()
            rows = query.offset((page - 1) * page_size).limit(page_size).all()
            return {"items": [self._serialize_task(row) for row in rows], "total": total, "page": page, "page_size": page_size}
        finally:
            db.close()

    def get_task(self, batch_id: str) -> dict[str, Any] | None:
        db = SessionLocal()
        try:
            task = db.get(BatchTask, batch_id)
            if not task:
                return None
            payload = self._serialize_task(task)
            payload["config"] = json.loads(task.config_json)
            return payload
        finally:
            db.close()

    def list_items(self, batch_id: str) -> list[dict[str, Any]]:
        db = SessionLocal()
        try:
            rows = db.query(BatchTaskItem).filter_by(batch_id=batch_id).order_by(BatchTaskItem.position).all()
            return [self._serialize_item(row) for row in rows]
        finally:
            db.close()

    def list_events(self, item_id: str) -> list[dict[str, Any]]:
        db = SessionLocal()
        try:
            rows = db.query(BatchTaskEvent).filter_by(batch_item_id=item_id).order_by(BatchTaskEvent.id.desc()).limit(100).all()
            return [{"id": row.id, "status": row.status, "message": row.message, "created_at": row.created_at.isoformat() if row.created_at else None} for row in reversed(rows)]
        finally:
            db.close()

    def _refresh_counts(self, db: Any, batch_id: str) -> None:
        task = db.get(BatchTask, batch_id)
        if not task:
            return
        items = db.query(BatchTaskItem).filter_by(batch_id=batch_id).all()
        task.total_count = len(items)
        task.completed_count = sum(item.status == TaskStatus.SUCCESS.value for item in items)
        task.failed_count = sum(item.status == TaskStatus.FAILED.value for item in items)
        task.running_count = sum(item.status not in _TERMINAL | {TaskStatus.PENDING.value} for item in items)
        pending = any(item.status == TaskStatus.PENDING.value for item in items)
        if task.total_count and task.completed_count + task.failed_count == task.total_count:
            task.status = "SUCCESS" if task.failed_count == 0 else ("PARTIAL_FAILED" if task.completed_count else "FAILED")
            task.finished_at = datetime.utcnow()
        elif task.running_count or pending:
            task.status = "RUNNING" if task.running_count else "PENDING"
            if task.started_at is None:
                task.started_at = datetime.utcnow()

    def sync_child_status(self, task_id: str | None, status: str, message: str | None = None) -> None:
        if not task_id:
            return
        db = SessionLocal()
        try:
            item = db.query(BatchTaskItem).filter_by(task_id=task_id).first()
            if not item:
                return
            item.status = status
            item.stage = status
            if message:
                item.message = message
            db.add(BatchTaskEvent(batch_item_id=item.id, status=status, message=message))
            self._refresh_counts(db, item.batch_id)
            db.commit()
        finally:
            db.close()

    def _submit_item(self, item_id: str) -> Any:
        db = SessionLocal()
        try:
            item = db.get(BatchTaskItem, item_id)
            if not item or item.status != TaskStatus.PENDING.value:
                return None
            item.task_id = str(uuid.uuid4())
            item.attempts += 1
            item.message = "任务已进入执行队列"
            db.add(BatchTaskEvent(batch_item_id=item.id, status=TaskStatus.PENDING.value, message=item.message))
            task_id = item.task_id
            batch_id = item.batch_id
            db.commit()
        finally:
            db.close()
        self.sync_child_status(task_id, TaskStatus.PENDING.value, "任务已进入执行队列")
        future = task_serial_executor.submit(self._run_item, item_id, task_id)
        logger.info("批量子任务已提交: batch=%s item=%s", batch_id, item_id)
        return future

    def _run_item(self, item_id: str, task_id: str) -> None:
        db = SessionLocal()
        try:
            item = db.get(BatchTaskItem, item_id)
            task = db.get(BatchTask, item.batch_id) if item else None
            if not item or not task:
                return
            source_url, platform = item.source_url, item.platform
            config = json.loads(task.config_json)
        finally:
            db.close()
        try:
            from app.services.note import NoteGenerator

            quality = DownloadQuality(config.get("quality", DownloadQuality.medium.value))
            note = NoteGenerator().generate(
                video_url=source_url, platform=platform, quality=quality, task_id=task_id,
                model_name=config["model_name"], provider_id=config["provider_id"],
                link=bool(config.get("link", False)), screenshot=bool(config.get("screenshot", False)),
                _format=config.get("format") or [], style=config.get("style"), extras=config.get("extras"),
                video_understanding=bool(config.get("video_understanding", False)),
                video_interval=int(config.get("video_interval") or 0), grid_size=config.get("grid_size") or [],
            )
            if not note or not note.markdown:
                return
            NOTE_OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
            (NOTE_OUTPUT_DIR / f"{task_id}.json").write_text(json.dumps(asdict(note), ensure_ascii=False, indent=2), encoding="utf-8")
            try:
                from app.services.vector_store import VectorStoreManager
                VectorStoreManager().index_task(task_id)
            except Exception as exc:
                logger.warning("批量任务向量索引失败（不影响笔记）: %s", exc)
        except Exception as exc:
            logger.exception("批量子任务执行失败: %s", exc)
            self.sync_child_status(task_id, TaskStatus.FAILED.value, str(exc))

    def _run_batch(self, batch_id: str) -> None:
        futures: set[Any] = set()
        try:
            while True:
                futures = {future for future in futures if not future.done()}
                db = SessionLocal()
                try:
                    pending = [row.id for row in db.query(BatchTaskItem).filter_by(batch_id=batch_id, status=TaskStatus.PENDING.value).order_by(BatchTaskItem.position).all()]
                finally:
                    db.close()
                while pending and len(futures) < task_serial_executor.max_workers:
                    item_id = pending.pop(0)
                    future = self._submit_item(item_id)
                    if future is not None:
                        futures.add(future)
                if not pending and not futures:
                    db = SessionLocal()
                    try:
                        active = db.query(BatchTaskItem).filter(BatchTaskItem.batch_id == batch_id, BatchTaskItem.status.notin_(_TERMINAL | {TaskStatus.PENDING.value})).count()
                    finally:
                        db.close()
                    if active == 0:
                        return
                time.sleep(0.5)
        finally:
            with self._lock:
                self._running.discard(batch_id)

    def start(self, batch_id: str) -> None:
        with self._lock:
            if batch_id in self._running:
                return
            self._running.add(batch_id)
        threading.Thread(target=self._run_batch, args=(batch_id,), name=f"batch-{batch_id[:8]}", daemon=True).start()

    def retry_item(self, batch_id: str, item_id: str) -> dict[str, Any]:
        db = SessionLocal()
        try:
            item = db.get(BatchTaskItem, item_id)
            if not item or item.batch_id != batch_id:
                raise KeyError("子任务不存在")
            if item.status != TaskStatus.FAILED.value:
                raise ValueError("只有失败的子任务可以重试")
            item.task_id = None
            item.status = TaskStatus.PENDING.value
            item.stage = TaskStatus.PENDING.value
            item.message = "等待重试"
            db.add(BatchTaskEvent(batch_item_id=item.id, status=TaskStatus.PENDING.value, message="用户发起重试"))
            self._refresh_counts(db, batch_id)
            db.commit()
            result = self._serialize_item(item)
        finally:
            db.close()
        self.start(batch_id)
        return result

    def recover_incomplete_batches(self) -> None:
        db = SessionLocal()
        try:
            batches = db.query(BatchTask).filter(BatchTask.status.in_(["PENDING", "RUNNING"])).all()
            batch_ids = []
            for task in batches:
                items = db.query(BatchTaskItem).filter_by(batch_id=task.id).all()
                for item in items:
                    if item.status not in _TERMINAL:
                        item.task_id = None
                        item.status = TaskStatus.PENDING.value
                        item.stage = TaskStatus.PENDING.value
                        item.message = "服务重启后已恢复到队列"
                        db.add(BatchTaskEvent(batch_item_id=item.id, status=TaskStatus.PENDING.value, message=item.message))
                self._refresh_counts(db, task.id)
                batch_ids.append(task.id)
            db.commit()
        finally:
            db.close()
        for batch_id in batch_ids:
            self.start(batch_id)

    def _load_markdown(self, task_id: str) -> str:
        result = NOTE_OUTPUT_DIR / f"{task_id}.json"
        if result.exists():
            return json.loads(result.read_text(encoding="utf-8")).get("markdown") or ""
        fallback = NOTE_OUTPUT_DIR / f"{task_id}_markdown.md"
        return fallback.read_text(encoding="utf-8") if fallback.exists() else ""

    @staticmethod
    def _safe_name(value: str) -> str:
        return re.sub(r'[\\/:*?"<>|]+', "_", value).strip()[:100] or "note"

    def export_items(self, batch_id: str, item_ids: list[str] | None = None) -> Response:
        db = SessionLocal()
        try:
            query = db.query(BatchTaskItem).filter_by(batch_id=batch_id, status=TaskStatus.SUCCESS.value)
            if item_ids is not None:
                query = query.filter(BatchTaskItem.id.in_(item_ids))
            items = query.order_by(BatchTaskItem.position).all()
            task = db.get(BatchTask, batch_id)
            if not task:
                raise KeyError("批量任务不存在")
            task_title = task.title
        finally:
            db.close()
        if not items:
            raise ValueError("没有可导出的完成笔记")
        buffer = io.BytesIO()
        index = [f"# {task_title}", "", "## 课程目录", ""]
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            for item in items:
                markdown = self._load_markdown(item.task_id or "")
                if not markdown:
                    continue
                prefix = f"{item.position:03d}-{self._safe_name(item.title)}"
                image_dir = f"images/{prefix}"
                images = []
                def rewrite(match: re.Match[str]) -> str:
                    filename = Path(match.group(2)).name
                    images.append(filename)
                    return f"![{match.group(1)}]({image_dir}/{filename})"
                markdown = _IMAGE_REF.sub(rewrite, markdown)
                markdown_name = f"{prefix}.md"
                archive.writestr(markdown_name, markdown.encode("utf-8"))
                for filename in set(images):
                    source = Path("static") / "screenshots" / filename
                    if source.exists():
                        archive.write(source, f"{image_dir}/{filename}")
                index.append(f"{item.position}. [{item.title}]({quote(markdown_name)})")
            archive.writestr("README.md", "\n".join(index).encode("utf-8"))
        filename = f"{self._safe_name(task_title)}.zip"
        return Response(
            content=buffer.getvalue(), media_type="application/zip",
            headers={"Content-Disposition": f"attachment; filename*=UTF-8''{quote(filename)}"},
        )


batch_task_service = BatchTaskService()
