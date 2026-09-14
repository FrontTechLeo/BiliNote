"""Persistent orchestration for course-style video note generation."""

from __future__ import annotations

import io
import hashlib
import json
import os
import re
import shutil
import threading
import time
import uuid
import zipfile
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import parse_qs, quote, urlsplit

import httpx
from fastapi import HTTPException
from fastapi.responses import Response

from app.db.engine import SessionLocal
from app.db.models.video_tasks import BatchTask, BatchTaskEvent, BatchTaskItem, VideoTask
from app.enmus.note_enums import DownloadQuality
from app.enmus.task_status_enums import TaskStatus
from app.services.cookie_manager import CookieConfigManager
from app.services.task_serial_executor import task_serial_executor
from app.utils.logger import get_logger
from app.utils.note_helper import remove_internal_local_source_link
from app.utils.runtime_paths import (
    get_note_output_dir,
    get_screenshots_dir,
    get_uploads_dir,
    is_filesystem_absolute,
    local_path_input_enabled,
)

logger = get_logger(__name__)

_IMAGE_REF = re.compile(r'!\[([^\]]*)\]\(/static/screenshots/([^)]+)\)')
_BV_RE = re.compile(r'BV[0-9A-Za-z]+')
_VIDEO_EXTENSIONS = {".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v"}
_TERMINAL = {TaskStatus.SUCCESS.value, TaskStatus.FAILED.value, TaskStatus.CANCELED.value}
_BATCH_TERMINAL = {"SUCCESS", "FAILED", "PARTIAL_FAILED", "CANCELED"}
_BATCH_APPENDABLE = {"PENDING", "RUNNING"} | _BATCH_TERMINAL
NOTE_OUTPUT_DIR = Path(get_note_output_dir())
_UPLOADS_BASE = Path(get_uploads_dir())
LOCAL_UPLOAD_ROOT = _UPLOADS_BASE / "batch_videos"
LOCAL_PREVIEW_ROOT = _UPLOADS_BASE / "batch_previews"
LOCAL_CACHE_MAX_AGE_SECONDS = 24 * 60 * 60


def _resolve_local_source_path(source_url: str) -> Path:
    """把 ``/uploads/...`` 形式的 URL 解析为文件系统路径；磁盘绝对路径原样返回。"""
    if source_url.startswith("/uploads/"):
        return Path(get_uploads_dir()) / source_url[len("/uploads/"):]
    if is_filesystem_absolute(source_url):
        return Path(source_url)
    # 其他形式维持历史行为：按 cwd 相对处理
    return Path.cwd() / source_url.lstrip("/")


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
        self._cancel_events: dict[str, threading.Event] = {}
        self._cancel_events_lock = threading.Lock()
        self._resolver = BilibiliCollectionResolver()

    @staticmethod
    def _serialize_task(task: BatchTask) -> dict[str, Any]:
        total = task.total_count or 0
        done = (task.completed_count or 0) + (task.failed_count or 0) + (task.canceled_count or 0)
        return {
            "id": task.id,
            "title": task.title,
            "source_type": task.source_type,
            "source_value": task.source_value,
            "status": task.status,
            "total_count": total,
            "completed_count": task.completed_count or 0,
            "failed_count": task.failed_count or 0,
            "canceled_count": task.canceled_count or 0,
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

    def preview_append_bilibili(self, batch_id: str, source_url: str) -> dict[str, Any]:
        resolved = self._resolver.resolve(source_url)
        items, skipped_count = self._filter_new_items(batch_id, resolved.get("items") or [], "bilibili")
        return {
            "title": resolved.get("title") or "哔哩哔哩合集",
            "items": items,
            "discovered_count": len(resolved.get("items") or []),
            "skipped_count": skipped_count,
        }

    @staticmethod
    def _natural_sort_key(value: str) -> list[tuple[int, Any]]:
        """Sort human-facing filenames so 2 comes before 10."""
        return [
            (0, int(part)) if part.isdigit() else (1, part.casefold())
            for part in re.split(r"(\d+)", value)
            if part
        ]

    @staticmethod
    def _sampled_local_digest(target: Path) -> str:
        size = target.stat().st_size
        sample_size = 1024 * 1024
        offsets = {
            0,
            max(0, (size - sample_size) // 2),
            max(0, size - sample_size),
        }
        digest = hashlib.sha256(str(size).encode("ascii"))
        with target.open("rb") as source:
            for offset in sorted(offsets):
                source.seek(offset)
                digest.update(str(offset).encode("ascii"))
                digest.update(source.read(sample_size))
        return digest.hexdigest()

    def cleanup_local_upload_cache(self, max_age_seconds: int = LOCAL_CACHE_MAX_AGE_SECONDS) -> dict[str, int]:
        """Remove abandoned uploads while retaining every file referenced by a batch item."""
        db = SessionLocal()
        try:
            referenced = {
                str(row[0])
                for row in db.query(BatchTaskItem.source_url)
                .filter(BatchTaskItem.platform == "local")
                .all()
            }
        finally:
            db.close()

        cutoff = time.time() - max(0, max_age_seconds)
        removed_files = 0
        removed_dirs = 0
        cache_root = LOCAL_UPLOAD_ROOT.resolve()
        if cache_root.exists():
            for path in cache_root.iterdir():
                source_url = self._upload_url(path)
                if path.is_file() and source_url not in referenced and path.stat().st_mtime < cutoff:
                    path.unlink(missing_ok=True)
                    removed_files += 1

        preview_root = LOCAL_PREVIEW_ROOT.resolve()
        if preview_root.exists():
            for directory in preview_root.iterdir():
                if not directory.is_dir():
                    continue
                for path in directory.rglob("*"):
                    if not path.is_file():
                        continue
                    source_url = self._upload_url(path)
                    if source_url not in referenced and path.stat().st_mtime < cutoff:
                        path.unlink(missing_ok=True)
                        removed_files += 1
                if not any(path.is_file() for path in directory.rglob("*")):
                    shutil.rmtree(directory)
                    removed_dirs += 1
        if removed_files or removed_dirs:
            logger.info("已清理本地批量视频孤儿缓存: files=%s preview_dirs=%s", removed_files, removed_dirs)
        return {"removed_files": removed_files, "removed_dirs": removed_dirs}

    @staticmethod
    def _upload_url(path: Path) -> str:
        """把上传缓存目录内的文件转成 /uploads/... 形式的对外 URL。"""
        try:
            rel = Path(path).resolve().relative_to(_UPLOADS_BASE.resolve()).as_posix()
        except (ValueError, OSError):
            rel = Path(path).as_posix()
        return "/uploads/" + rel

    def store_local_paths_preview(self, paths: list[str]) -> dict[str, Any]:
        """PC 客户端直读模式：直接扫描本地磁盘上的文件/文件夹，零上传。

        返回结构与 store_local_preview 一致，但 source_url 是原始绝对路径，
        处理时由 LocalDownloader 直接读盘，不做内容拷贝。
        """
        if not local_path_input_enabled():
            raise ValueError("服务端未启用本地路径输入")
        self.cleanup_local_upload_cache()
        files = self._discover_local_video_files([str(p) for p in paths if str(p).strip()])
        if not files:
            raise ValueError("所选路径中没有可处理的视频文件")
        items: list[dict[str, Any]] = []
        discovered_count = 0
        duplicate_count = 0
        seen_digests: set[str] = set()
        for file_path in sorted(files, key=lambda p: self._natural_sort_key(p.name)):
            discovered_count += 1
            digest = self._sampled_local_digest(file_path)
            key = f"local-sampled:{digest}"
            if key in seen_digests:
                duplicate_count += 1
                continue
            seen_digests.add(key)
            items.append({
                "title": file_path.stem,
                "source_url": str(file_path),
                "platform": "local",
                "position": len(items) + 1,
            })
        return {
            "preview_id": str(uuid.uuid4()),
            "title": "本地视频",
            "items": items,
            "discovered_count": discovered_count,
            "duplicate_count": duplicate_count,
        }

    def preview_append_local_paths(self, batch_id: str, paths: list[str]) -> dict[str, Any]:
        preview = self.store_local_paths_preview(paths)
        items, skipped_count = self._filter_new_items(batch_id, preview["items"], "local")
        return {
            **preview,
            "items": items,
            "skipped_count": skipped_count + int(preview.get("duplicate_count") or 0),
        }

    @staticmethod
    def _discover_local_video_files(paths: list[str]) -> list[Path]:
        """把用户给的文件/文件夹路径展开为去重后的视频文件列表（递归 + 自然排序由调用方处理）。"""
        found: dict[str, Path] = {}
        for raw in paths:
            candidate = Path(raw).expanduser()
            try:
                if candidate.is_dir():
                    matches = [p for p in candidate.rglob("*") if p.is_file() and p.suffix.lower() in _VIDEO_EXTENSIONS]
                elif candidate.is_file() and candidate.suffix.lower() in _VIDEO_EXTENSIONS:
                    matches = [candidate]
                else:
                    continue
            except OSError:
                continue
            for match in matches:
                resolved = match.resolve()
                found[str(resolved).lower()] = resolved
        return list(found.values())

    def store_local_preview(self, files: Iterable[Any]) -> dict[str, Any]:
        self.cleanup_local_upload_cache()
        preview_id = str(uuid.uuid4())
        root = LOCAL_PREVIEW_ROOT / preview_id
        cache_root = LOCAL_UPLOAD_ROOT
        root.mkdir(parents=True, exist_ok=True)
        cache_root.mkdir(parents=True, exist_ok=True)
        items = []
        discovered_count = 0
        duplicate_count = 0
        seen_sources: set[str] = set()
        uploads = sorted(
            list(files),
            key=lambda uploaded: self._natural_sort_key(Path(uploaded.filename or "").name),
        )
        try:
            for index, uploaded in enumerate(uploads, start=1):
                suffix = Path(uploaded.filename or "").suffix.lower()
                if suffix not in _VIDEO_EXTENSIONS:
                    continue
                discovered_count += 1
                filename = Path(uploaded.filename or f"video-{index}{suffix}").name
                temporary = root / f"{index:06d}{suffix}.uploading"
                with temporary.open("wb") as output:
                    while chunk := uploaded.file.read(1024 * 1024):
                        output.write(chunk)
                fingerprint = self._sampled_local_digest(temporary)
                existing = cache_root / f"{fingerprint}{suffix}"
                target = existing
                if existing.is_file():
                    temporary.unlink(missing_ok=True)
                else:
                    temporary.replace(target)
                source_url = "/" + target.as_posix()
                if source_url in seen_sources:
                    duplicate_count += 1
                    continue
                seen_sources.add(source_url)
                items.append({
                    "title": Path(filename).stem,
                    "source_url": source_url,
                    "platform": "local",
                    "position": len(items) + 1,
                })
        finally:
            shutil.rmtree(root, ignore_errors=True)
        if not items:
            raise ValueError("文件夹中没有可处理的视频文件")
        return {
            "preview_id": preview_id,
            "title": "本地视频文件夹",
            "items": items,
            "discovered_count": discovered_count,
            "duplicate_count": duplicate_count,
        }

    def preview_append_local(self, batch_id: str, files: Iterable[Any]) -> dict[str, Any]:
        preview = self.store_local_preview(files)
        items, skipped_count = self._filter_new_items(batch_id, preview["items"], "local")
        return {
            **preview,
            "items": items,
            "skipped_count": skipped_count + int(preview.get("duplicate_count") or 0),
        }

    @staticmethod
    def _validate_local_source(source_url: str) -> None:
        if is_filesystem_absolute(source_url):
            # PC 客户端直读模式：允许绝对路径，但需要显式开关，且文件必须存在。
            # 原始文件永远不删，这里只做存在性校验。
            if not local_path_input_enabled():
                raise ValueError("服务端未启用本地路径输入")
            if not Path(source_url).is_file():
                raise ValueError("本地文件不存在")
            return
        allowed_roots = [LOCAL_UPLOAD_ROOT.resolve(), LOCAL_PREVIEW_ROOT.resolve()]
        target = _resolve_local_source_path(source_url).resolve()
        if not any(base in target.parents for base in allowed_roots) or not target.is_file():
            raise ValueError("本地文件不存在或不在允许的上传目录中")

    @staticmethod
    def _validate_local_video_source(source_url: str) -> None:
        BatchTaskService._validate_local_source(source_url)
        target = _resolve_local_source_path(source_url)
        if target.suffix.lower() not in _VIDEO_EXTENSIONS:
            raise ValueError("本地批量任务仅支持视频文件")

    @staticmethod
    def _source_key(platform: str, source_url: str) -> str:
        if platform == "local":
            BatchTaskService._validate_local_source(source_url)
            target = _resolve_local_source_path(source_url)
            if target.parent.name == LOCAL_UPLOAD_ROOT.name and re.fullmatch(r"[0-9a-f]{64}", target.stem):
                return f"local-sampled:{target.stem}"
            return f"local-sampled:{BatchTaskService._sampled_local_digest(target)}"
        if platform == "bilibili":
            match = _BV_RE.search(source_url)
            if match:
                page = (parse_qs(urlsplit(source_url).query).get("p") or ["1"])[0]
                return f"bilibili:{match.group(0)}:p{page}"
        return f"{platform}:{source_url.strip().rstrip('/')}"

    def _filter_new_items(
        self,
        batch_id: str,
        items: list[dict[str, Any]],
        expected_source_type: str | None = None,
    ) -> tuple[list[dict[str, Any]], int]:
        db = SessionLocal()
        try:
            task = db.get(BatchTask, batch_id)
            if not task:
                raise KeyError("批量任务不存在")
            existing = db.query(BatchTaskItem).filter_by(batch_id=batch_id).all()
            existing_keys = {self._source_key(row.platform, row.source_url) for row in existing}
        finally:
            db.close()

        filtered: list[dict[str, Any]] = []
        seen = set(existing_keys)
        skipped_count = 0
        for raw in items:
            source_url = str(raw.get("source_url") or "")
            platform = str(raw.get("platform") or expected_source_type or "")
            if platform not in {"bilibili", "local"}:
                raise ValueError("仅支持追加哔哩哔哩视频或本地视频")
            if expected_source_type and platform != expected_source_type:
                raise ValueError("视频来源与当前追加方式不一致")
            if not source_url:
                raise ValueError("视频链接不能为空")
            if platform == "local":
                self._validate_local_video_source(source_url)
            key = self._source_key(platform, source_url)
            if key in seen:
                skipped_count += 1
                continue
            seen.add(key)
            filtered.append({
                "title": str(raw.get("title") or f"视频 {len(filtered) + 1}"),
                "source_url": source_url,
                "platform": platform,
                "position": len(filtered) + 1,
            })
        return filtered, skipped_count

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
                title=(title or "").strip() or ("本地视频批量任务" if source_type == "local" else "哔哩哔哩批量任务"),
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
                    self._validate_local_video_source(source_url)
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

    def update_task(self, batch_id: str, title: str | None = None, config: dict[str, Any] | None = None) -> dict[str, Any]:
        db = SessionLocal()
        try:
            task = db.get(BatchTask, batch_id)
            if not task:
                raise KeyError("批量任务不存在")
            if title is not None:
                normalized_title = title.strip()
                if not normalized_title:
                    raise ValueError("任务名称不能为空")
                task.title = normalized_title
            if config is not None:
                if task.status not in _BATCH_TERMINAL:
                    raise ValueError("任务执行期间只能修改名称，生成设置请在任务结束后修改")
                if not config.get("model_name") or not config.get("provider_id"):
                    raise ValueError("请选择模型和供应商")
                current_config = json.loads(task.config_json)
                current_config.update(config)
                task.config_json = json.dumps(current_config, ensure_ascii=False)
            db.commit()
            return self.get_task(batch_id) or self._serialize_task(task)
        finally:
            db.close()

    def append_items(self, batch_id: str, items: list[dict[str, Any]]) -> dict[str, Any]:
        if not items:
            raise ValueError("至少选择一个待追加视频")
        db = SessionLocal()
        try:
            task = db.get(BatchTask, batch_id)
            if not task:
                raise KeyError("批量任务不存在")
            if task.status not in _BATCH_APPENDABLE:
                raise ValueError("当前批量任务状态不支持追加视频")
        finally:
            db.close()

        new_items, skipped_count = self._filter_new_items(batch_id, items)
        if not new_items:
            raise ValueError("所选视频均已存在于当前批量任务中")

        db = SessionLocal()
        try:
            task = db.get(BatchTask, batch_id)
            if not task:
                raise KeyError("批量任务不存在")
            if task.status not in _BATCH_APPENDABLE:
                raise ValueError("批量任务状态已变化，请刷新后重试")
            last_position = db.query(BatchTaskItem.position).filter_by(batch_id=batch_id).order_by(BatchTaskItem.position.desc()).first()
            next_position = int(last_position[0] if last_position else 0) + 1
            added: list[BatchTaskItem] = []
            for offset, raw in enumerate(new_items):
                item = BatchTaskItem(
                    id=str(uuid.uuid4()),
                    batch_id=batch_id,
                    source_url=raw["source_url"],
                    platform=raw["platform"],
                    title=raw["title"],
                    position=next_position + offset,
                    status=TaskStatus.PENDING.value,
                    stage=TaskStatus.PENDING.value,
                    message="追加成功，等待执行",
                )
                db.add(item)
                db.add(BatchTaskEvent(
                    batch_item_id=item.id,
                    status=TaskStatus.PENDING.value,
                    message="用户追加视频到批量任务",
                ))
                added.append(item)
            task.finished_at = None
            db.flush()
            self._refresh_counts(db, batch_id)
            db.commit()
            result = {
                "task": self._serialize_task(task),
                "added_items": [self._serialize_item(item) for item in added],
                "added_count": len(added),
                "skipped_count": skipped_count,
            }
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
        task.canceled_count = sum(item.status == TaskStatus.CANCELED.value for item in items)
        task.running_count = sum(item.status not in _TERMINAL | {TaskStatus.PENDING.value} for item in items)
        pending = any(item.status == TaskStatus.PENDING.value for item in items)
        if task.total_count == 0:
            task.status = "SUCCESS"
            task.finished_at = datetime.utcnow()
        elif task.completed_count + task.failed_count + task.canceled_count == task.total_count:
            if task.canceled_count and not task.completed_count and not task.failed_count:
                task.status = "CANCELED"
            elif task.failed_count or task.canceled_count:
                task.status = "PARTIAL_FAILED" if task.completed_count else "FAILED"
            else:
                task.status = "SUCCESS"
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
            if item.status in _TERMINAL and status != item.status:
                # A late status update from an in-flight request must not
                # change a terminal task, especially not resurrect a canceled one.
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

    def _register_cancel_event(self, task_id: str) -> threading.Event:
        event = threading.Event()
        with self._cancel_events_lock:
            self._cancel_events[task_id] = event
        return event

    def _remove_cancel_event(self, task_id: str) -> None:
        with self._cancel_events_lock:
            self._cancel_events.pop(task_id, None)

    def _request_cancel_event(self, task_id: str | None) -> None:
        if not task_id:
            return
        with self._cancel_events_lock:
            event = self._cancel_events.get(task_id)
            if event:
                event.set()

    def _item_is_canceled(self, item_id: str) -> bool:
        db = SessionLocal()
        try:
            item = db.get(BatchTaskItem, item_id)
            return not item or item.status == TaskStatus.CANCELED.value
        finally:
            db.close()

    def cancel_item(self, batch_id: str, item_id: str) -> dict[str, Any]:
        """Cancel one queued/running child task without deleting its artifacts."""
        db = SessionLocal()
        task_id: str | None = None
        try:
            item = db.get(BatchTaskItem, item_id)
            if not item or item.batch_id != batch_id:
                raise KeyError("子任务不存在")
            if item.status == TaskStatus.CANCELED.value:
                return self._serialize_item(item)
            if item.status in _TERMINAL:
                raise ValueError("只有排队中或运行中的子任务可以取消")

            task_id = item.task_id
            item.status = TaskStatus.CANCELED.value
            item.stage = TaskStatus.CANCELED.value
            item.message = "用户请求取消，当前请求结束后停止"
            db.add(BatchTaskEvent(
                batch_item_id=item.id,
                status=TaskStatus.CANCELED.value,
                message=item.message,
            ))
            self._refresh_counts(db, batch_id)
            db.commit()
            result = self._serialize_item(item)
        finally:
            db.close()

        self._request_cancel_event(task_id)
        return result

    def retry_items(self, batch_id: str, item_ids: list[str]) -> dict[str, Any]:
        """批量启动（重试）失败/已取消的子任务；其它状态的自动跳过。"""
        unique_ids = list(dict.fromkeys(item_ids))
        if not unique_ids:
            raise ValueError("请选择要启动的子任务")
        db = SessionLocal()
        retried = 0
        try:
            task = db.get(BatchTask, batch_id)
            if not task:
                raise KeyError("批量任务不存在")
            items = db.query(BatchTaskItem).filter(
                BatchTaskItem.batch_id == batch_id,
                BatchTaskItem.id.in_(unique_ids),
            ).all()
            if len(items) != len(unique_ids):
                raise KeyError("部分子任务不存在，请刷新后重试")
            for item in items:
                if item.status not in {TaskStatus.FAILED.value, TaskStatus.CANCELED.value}:
                    continue
                item.task_id = None
                item.status = TaskStatus.PENDING.value
                item.stage = TaskStatus.PENDING.value
                item.message = "等待重试"
                db.add(BatchTaskEvent(
                    batch_item_id=item.id,
                    status=TaskStatus.PENDING.value,
                    message="用户批量启动重试",
                ))
                retried += 1
            if not retried:
                raise ValueError("选中的子任务中没有失败或已取消的任务，无需启动")
            task.finished_at = None
            self._refresh_counts(db, batch_id)
            db.commit()
            updated_task = self._serialize_task(task)
        finally:
            db.close()
        self.start(batch_id)
        return {
            "task": updated_task,
            "retried_count": retried,
            "skipped_count": len(unique_ids) - retried,
        }

    def cancel_items(self, batch_id: str, item_ids: list[str]) -> dict[str, Any]:
        """批量停止排队中/运行中的子任务；已结束的自动跳过。"""
        unique_ids = list(dict.fromkeys(item_ids))
        if not unique_ids:
            raise ValueError("请选择要停止的子任务")
        db = SessionLocal()
        canceled = 0
        cancel_task_ids: list[str | None] = []
        try:
            task = db.get(BatchTask, batch_id)
            if not task:
                raise KeyError("批量任务不存在")
            items = db.query(BatchTaskItem).filter(
                BatchTaskItem.batch_id == batch_id,
                BatchTaskItem.id.in_(unique_ids),
            ).all()
            if len(items) != len(unique_ids):
                raise KeyError("部分子任务不存在，请刷新后重试")
            for item in items:
                if item.status in _TERMINAL:
                    continue
                cancel_task_ids.append(item.task_id)
                item.status = TaskStatus.CANCELED.value
                item.stage = TaskStatus.CANCELED.value
                item.message = "用户请求取消，当前请求结束后停止"
                db.add(BatchTaskEvent(
                    batch_item_id=item.id,
                    status=TaskStatus.CANCELED.value,
                    message=item.message,
                ))
                canceled += 1
            if not canceled:
                raise ValueError("选中的子任务都已结束，无需停止")
            self._refresh_counts(db, batch_id)
            db.commit()
            updated_task = self._serialize_task(task)
        finally:
            db.close()
        for task_id in cancel_task_ids:
            self._request_cancel_event(task_id)
        return {
            "task": updated_task,
            "canceled_count": canceled,
            "skipped_count": len(unique_ids) - canceled,
        }

    def _submit_item(self, item_id: str) -> Any:
        task_id = str(uuid.uuid4())
        db = SessionLocal()
        try:
            claimed = (
                db.query(BatchTaskItem)
                .filter(
                    BatchTaskItem.id == item_id,
                    BatchTaskItem.status == TaskStatus.PENDING.value,
                )
                .update(
                    {
                        BatchTaskItem.task_id: task_id,
                        BatchTaskItem.status: TaskStatus.PARSING.value,
                        BatchTaskItem.stage: TaskStatus.PARSING.value,
                        BatchTaskItem.attempts: BatchTaskItem.attempts + 1,
                        BatchTaskItem.message: "任务已进入执行队列",
                    },
                    synchronize_session=False,
                )
            )
            if claimed != 1:
                db.rollback()
                return None

            item = db.get(BatchTaskItem, item_id)
            db.add(BatchTaskEvent(
                batch_item_id=item.id,
                status=TaskStatus.PARSING.value,
                message=item.message,
            ))
            batch_id = item.batch_id
            self._refresh_counts(db, batch_id)
            db.commit()
        finally:
            db.close()

        self._register_cancel_event(task_id)
        try:
            future = task_serial_executor.submit(self._run_item, item_id, task_id)
        except Exception as exc:
            self._remove_cancel_event(task_id)
            logger.exception("批量子任务提交失败: batch=%s item=%s", batch_id, item_id)
            self.sync_child_status(task_id, TaskStatus.FAILED.value, f"任务提交失败：{exc}")
            return None

        logger.info("批量子任务已提交: batch=%s item=%s", batch_id, item_id)
        return future

    def _run_item(self, item_id: str, task_id: str) -> None:
        with self._cancel_events_lock:
            cancel_event = self._cancel_events.get(task_id)

        def cancel_check() -> bool:
            return bool(cancel_event and cancel_event.is_set()) or self._item_is_canceled(item_id)

        try:
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

            if cancel_check():
                self.sync_child_status(task_id, TaskStatus.CANCELED.value, "用户取消任务")
                return

            from app.services.note import NoteGenerator

            quality = DownloadQuality(config.get("quality", DownloadQuality.medium.value))
            note = NoteGenerator().generate(
                video_url=source_url, platform=platform, quality=quality, task_id=task_id,
                model_name=config["model_name"], provider_id=config["provider_id"],
                link=bool(config.get("link", False)), screenshot=bool(config.get("screenshot", False)),
                _format=config.get("format") or [], style=config.get("style"), extras=config.get("extras"),
                video_understanding=bool(config.get("video_understanding", False)),
                video_interval=int(config.get("video_interval") or 0), grid_size=config.get("grid_size") or [],
                image_compression=config.get("image_compression", "high"),
                display_title=item.title,
                source_link="" if platform == "local" else source_url,
                cancel_check=cancel_check,
            )
            if cancel_check():
                self.sync_child_status(task_id, TaskStatus.CANCELED.value, "用户取消任务")
                return
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
        finally:
            self._remove_cancel_event(task_id)

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
            if item.status not in {TaskStatus.FAILED.value, TaskStatus.CANCELED.value}:
                raise ValueError("只有失败或已取消的子任务可以重试")
            item.task_id = None
            item.status = TaskStatus.PENDING.value
            item.stage = TaskStatus.PENDING.value
            item.message = "等待重试"
            task = db.get(BatchTask, batch_id)
            if task:
                task.finished_at = None
            db.add(BatchTaskEvent(batch_item_id=item.id, status=TaskStatus.PENDING.value, message="用户发起重试"))
            self._refresh_counts(db, batch_id)
            db.commit()
            result = self._serialize_item(item)
        finally:
            db.close()
        self.start(batch_id)
        return result

    @staticmethod
    def _task_output_paths(task_id: str) -> list[Path]:
        return [
            NOTE_OUTPUT_DIR / f"{task_id}.json",
            NOTE_OUTPUT_DIR / f"{task_id}.status.json",
            NOTE_OUTPUT_DIR / f"{task_id}_audio.json",
            NOTE_OUTPUT_DIR / f"{task_id}_transcript.json",
            NOTE_OUTPUT_DIR / f"{task_id}_markdown.md",
            NOTE_OUTPUT_DIR / f"{task_id}_request.json",
            NOTE_OUTPUT_DIR / f"{task_id}.status.tmp",
            NOTE_OUTPUT_DIR / f"{task_id}.gpt.checkpoint.json",
            NOTE_OUTPUT_DIR / f"{task_id}.gpt.checkpoint.tmp",
            NOTE_OUTPUT_DIR / f"{task_id}_markdown.gpt.checkpoint.json",
            NOTE_OUTPUT_DIR / f"{task_id}_markdown.gpt.checkpoint.tmp",
            NOTE_OUTPUT_DIR / f"{task_id}.tmp",
        ]

    def _cleanup_deleted_item_resources(self, task_ids: list[str], local_sources: list[str]) -> dict[str, int]:
        image_names: set[str] = set()
        for task_id in task_ids:
            result_path = NOTE_OUTPUT_DIR / f"{task_id}.json"
            if result_path.exists():
                try:
                    markdown = json.loads(result_path.read_text(encoding="utf-8")).get("markdown") or ""
                    image_names.update(Path(match.group(2)).name for match in _IMAGE_REF.finditer(markdown))
                except (OSError, ValueError, TypeError):
                    pass

        removed_files = 0
        for task_id in task_ids:
            for path in self._task_output_paths(task_id):
                if path.is_file():
                    path.unlink(missing_ok=True)
                    removed_files += 1
            try:
                from app.services.vector_store import VectorStoreManager
                VectorStoreManager().delete_index(task_id)
            except Exception as exc:
                logger.debug("清理批量子任务向量索引失败（忽略）: %s", exc)

        remaining_images: set[str] = set()
        if image_names and NOTE_OUTPUT_DIR.exists():
            for result_path in NOTE_OUTPUT_DIR.glob("*.json"):
                if result_path.stem.endswith(("_audio", "_transcript", "_request")):
                    continue
                try:
                    markdown = json.loads(result_path.read_text(encoding="utf-8")).get("markdown") or ""
                    remaining_images.update(Path(match.group(2)).name for match in _IMAGE_REF.finditer(markdown))
                except (OSError, ValueError, TypeError):
                    continue
        screenshot_root = Path(get_screenshots_dir()).resolve()
        for image_name in image_names - remaining_images:
            target = (screenshot_root / image_name).resolve()
            if screenshot_root in target.parents and target.is_file():
                target.unlink(missing_ok=True)
                removed_files += 1

        db = SessionLocal()
        try:
            referenced_sources = {
                source
                for source in local_sources
                if db.query(BatchTaskItem).filter_by(platform="local", source_url=source).count() > 0
            }
        finally:
            db.close()
        request_references: set[str] = set()
        if NOTE_OUTPUT_DIR.exists():
            for request_path in NOTE_OUTPUT_DIR.glob("*_request.json"):
                try:
                    source = json.loads(request_path.read_text(encoding="utf-8")).get("video_url")
                    if source:
                        request_references.add(str(source))
                except (OSError, ValueError, TypeError):
                    continue
        allowed_roots = [LOCAL_UPLOAD_ROOT.resolve(), LOCAL_PREVIEW_ROOT.resolve()]
        for source_url in set(local_sources) - referenced_sources - request_references:
            target = _resolve_local_source_path(source_url).resolve()
            if any(root in target.parents for root in allowed_roots) and target.is_file():
                target.unlink(missing_ok=True)
                removed_files += 1
                parent = target.parent
                preview_root = allowed_roots[1]
                if preview_root in parent.parents and parent.exists() and not any(parent.iterdir()):
                    parent.rmdir()
        return {"removed_files": removed_files}

    def delete_items(self, batch_id: str, item_ids: list[str]) -> dict[str, Any]:
        unique_ids = list(dict.fromkeys(item_ids))
        if not unique_ids:
            raise ValueError("请选择要删除的子任务")
        db = SessionLocal()
        try:
            task = db.get(BatchTask, batch_id)
            if not task:
                raise KeyError("批量任务不存在")
            items = db.query(BatchTaskItem).filter(
                BatchTaskItem.batch_id == batch_id,
                BatchTaskItem.id.in_(unique_ids),
            ).all()
            if len(items) != len(unique_ids):
                raise KeyError("部分子任务不存在，请刷新后重试")
            active = [item.title for item in items if item.status not in _TERMINAL]
            if active:
                raise ValueError(f"运行中或排队中的子任务不能删除：{active[0]}")
            task_ids = [item.task_id for item in items if item.task_id]
            local_sources = [item.source_url for item in items if item.platform == "local"]
            db.query(BatchTaskEvent).filter(BatchTaskEvent.batch_item_id.in_(unique_ids)).delete(synchronize_session=False)
            if task_ids:
                db.query(VideoTask).filter(VideoTask.task_id.in_(task_ids)).delete(synchronize_session=False)
            for item in items:
                db.delete(item)
            db.flush()
            remaining = db.query(BatchTaskItem).filter_by(batch_id=batch_id).order_by(BatchTaskItem.position).all()
            for position, item in enumerate(remaining, start=1):
                item.position = position
            self._refresh_counts(db, batch_id)
            db.commit()
            updated_task = self._serialize_task(task)
        finally:
            db.close()

        cleanup = self._cleanup_deleted_item_resources(task_ids, local_sources)
        return {
            "task": updated_task,
            "deleted_count": len(unique_ids),
            "cleanup": cleanup,
        }

    def recover_incomplete_batches(self) -> None:
        self.cleanup_local_upload_cache()
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
        # 截图目录必须走 runtime_paths 解析（数据根/OUT_DIR）：
        # 相对 cwd 的 Path("static/screenshots") 在打包环境找错位置，导出会静默丢掉全部图片
        screenshots_dir = Path(get_screenshots_dir())
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            for item in items:
                markdown = self._load_markdown(item.task_id or "")
                if not markdown:
                    continue
                if item.platform == "local":
                    markdown = remove_internal_local_source_link(markdown) or ""
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
                    source = screenshots_dir / filename
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
