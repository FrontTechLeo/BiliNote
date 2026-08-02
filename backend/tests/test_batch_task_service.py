import io
import json
import sys
import zipfile
from datetime import datetime
from unittest.mock import MagicMock, patch

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import app.services.batch_task_service as batch_module
from app.db.engine import Base
from app.db.models.video_tasks import BatchTask, BatchTaskItem
from app.services.batch_task_service import BilibiliCollectionResolver, BatchTaskService
from app.enmus.task_status_enums import TaskStatus


class _Response:
    def __init__(self, data=None, url="https://www.bilibili.com/video/BV1seed/"):
        self._data = data or {"code": 0, "data": {}}
        self.url = url

    def raise_for_status(self):
        return None

    def json(self):
        return self._data


class _Client:
    def __init__(self, *args, **kwargs):
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return None

    def get(self, url, params=None):
        self.calls.append((url, params))
        if url == "https://www.bilibili.com/video/BV1seed/":
            return _Response(url=url)
        if url.endswith("/x/polymer/web-space/seasons_archives_list"):
            return _Response({"code": 0, "data": {
                "archives": [{"bvid": "BV1part"}],
                "page": {"total": 1},
                "meta": {"name": "测试合集"},
            }})
        bvid = (params or {}).get("bvid")
        if bvid == "BV1seed":
            return _Response({"code": 0, "data": {
                "bvid": "BV1seed", "title": "入口视频", "owner": {"mid": 100},
                "ugc_season": {"season_id": 200, "title": "测试合集"}, "pages": [{}],
            }})
        return _Response({"code": 0, "data": {
            "bvid": "BV1part", "title": "课程视频", "pages": [{"part": "第一节"}, {"part": "第二节"}],
        }})


class _Upload:
    def __init__(self, filename, content):
        self.filename = filename
        self.file = io.BytesIO(content)


def test_expand_multi_page_video_into_individual_tasks():
    items = BilibiliCollectionResolver._expand_video({
        "bvid": "BV1demo", "title": "课程", "pages": [{"part": "开场"}, {"part": "实战"}],
    })

    assert [item["title"] for item in items] == ["课程 - 开场", "课程 - 实战"]
    assert items[1]["source_url"].endswith("BV1demo/?p=2")


@patch("app.services.batch_task_service.CookieConfigManager.get", return_value=None)
@patch("app.services.batch_task_service.httpx.Client", _Client)
def test_resolve_ugc_collection_and_expand_parts(_cookie):
    result = BilibiliCollectionResolver().resolve("https://www.bilibili.com/video/BV1seed/")

    assert result["title"] == "测试合集"
    assert len(result["items"]) == 2
    assert result["items"][0]["source_url"].endswith("BV1part/?p=1")


def test_batch_export_places_same_named_images_in_separate_directories(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'batch.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    monkeypatch.setattr(batch_module, "SessionLocal", session_factory)
    monkeypatch.setattr(batch_module, "NOTE_OUTPUT_DIR", tmp_path / "notes")
    monkeypatch.chdir(tmp_path)

    db = session_factory()
    db.add(BatchTask(id="batch-1", title="课程", source_type="bilibili", config_json="{}", status="SUCCESS", total_count=2))
    db.add_all([
        BatchTaskItem(id="item-1", batch_id="batch-1", task_id="task-1", source_url="url-1", platform="bilibili", title="第一课", position=1, status=TaskStatus.SUCCESS.value),
        BatchTaskItem(id="item-2", batch_id="batch-1", task_id="task-2", source_url="url-2", platform="bilibili", title="第二课", position=2, status=TaskStatus.SUCCESS.value),
    ])
    db.commit()
    db.close()

    note_dir = tmp_path / "notes"
    note_dir.mkdir()
    markdown = "# 笔记\n\n![](/static/screenshots/shared.jpg)"
    for task_id in ("task-1", "task-2"):
        (note_dir / f"{task_id}.json").write_text(json.dumps({"markdown": markdown}), encoding="utf-8")
    screenshot_dir = tmp_path / "static" / "screenshots"
    screenshot_dir.mkdir(parents=True)
    (screenshot_dir / "shared.jpg").write_bytes(b"image")

    response = BatchTaskService().export_items("batch-1")
    with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
        names = archive.namelist()
        assert "images/001-第一课/shared.jpg" in names
        assert "images/002-第二课/shared.jpg" in names
        assert "images/001-第一课/shared.jpg" in archive.read("001-第一课.md").decode("utf-8")


def test_append_bilibili_items_filters_duplicates_and_reopens_completed_batch(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'append-remote.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    monkeypatch.setattr(batch_module, "SessionLocal", session_factory)

    db = session_factory()
    db.add(BatchTask(
        id="batch-remote",
        title="远程合集",
        source_type="bilibili",
        source_value="https://www.bilibili.com/video/BV1seed/",
        config_json='{"model_name":"demo","provider_id":"provider"}',
        status="SUCCESS",
        total_count=1,
        completed_count=1,
        finished_at=datetime.utcnow(),
    ))
    db.add(BatchTaskItem(
        id="remote-old",
        batch_id="batch-remote",
        task_id="task-old",
        source_url="https://www.bilibili.com/video/BV1same/?p=1",
        platform="bilibili",
        title="已存在",
        position=1,
        status=TaskStatus.SUCCESS.value,
    ))
    db.commit()
    db.close()

    service = BatchTaskService()
    service._resolver.resolve = lambda _url: {
        "title": "远程合集",
        "items": [
            {"title": "重复视频", "source_url": "https://www.bilibili.com/video/BV1same?p=1", "platform": "bilibili"},
            {"title": "新增视频", "source_url": "https://www.bilibili.com/video/BV1new/?p=2", "platform": "bilibili"},
        ],
    }

    preview = service.preview_append_bilibili("batch-remote", "https://www.bilibili.com/video/BV1seed/")
    assert preview["discovered_count"] == 2
    assert preview["skipped_count"] == 1
    assert [item["title"] for item in preview["items"]] == ["新增视频"]

    with pytest.raises(ValueError, match="均已存在"):
        service.append_items("batch-remote", [service._resolver.resolve("")["items"][0]])

    with patch.object(service, "start") as start:
        result = service.append_items("batch-remote", service._resolver.resolve("")["items"])

    assert result["added_count"] == 1
    assert result["skipped_count"] == 1
    start.assert_called_once_with("batch-remote")

    db = session_factory()
    task = db.get(BatchTask, "batch-remote")
    rows = db.query(BatchTaskItem).filter_by(batch_id="batch-remote").order_by(BatchTaskItem.position).all()
    assert task.status == "PENDING"
    assert task.total_count == 2
    assert task.completed_count == 1
    assert task.finished_at is None
    assert [row.title for row in rows] == ["已存在", "新增视频"]
    assert rows[1].position == 2
    db.close()

    with pytest.raises(ValueError, match="只有已结束"):
        service.append_items("batch-remote", [{
            "title": "执行中继续追加",
            "source_url": "https://www.bilibili.com/video/BV1later/",
            "platform": "bilibili",
        }])


def test_append_local_items_deduplicates_by_file_content(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'append-local.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    monkeypatch.setattr(batch_module, "SessionLocal", session_factory)
    monkeypatch.chdir(tmp_path)

    old_dir = tmp_path / "uploads" / "batch_previews" / "old"
    old_dir.mkdir(parents=True)
    (old_dir / "lesson.mp4").write_bytes(b"same-video-content")

    db = session_factory()
    db.add(BatchTask(
        id="batch-local",
        title="本地课程",
        source_type="local",
        config_json='{"model_name":"demo","provider_id":"provider"}',
        status="SUCCESS",
        total_count=1,
        completed_count=1,
        finished_at=datetime.utcnow(),
    ))
    db.add(BatchTaskItem(
        id="local-old",
        batch_id="batch-local",
        task_id="local-task-old",
        source_url="/uploads/batch_previews/old/lesson.mp4",
        platform="local",
        title="旧课程",
        position=1,
        status=TaskStatus.SUCCESS.value,
    ))
    db.commit()
    db.close()

    service = BatchTaskService()
    preview = service.preview_append_local("batch-local", [
        _Upload("lesson-copy.mp4", b"same-video-content"),
        _Upload("new-lesson.mp4", b"new-video-content"),
    ])
    assert preview["discovered_count"] == 2
    assert preview["skipped_count"] == 1
    assert [item["title"] for item in preview["items"]] == ["new-lesson"]

    with patch.object(service, "start") as start:
        result = service.append_items("batch-local", preview["items"])

    assert result["added_count"] == 1
    assert result["skipped_count"] == 0
    start.assert_called_once_with("batch-local")

    db = session_factory()
    task = db.get(BatchTask, "batch-local")
    rows = db.query(BatchTaskItem).filter_by(batch_id="batch-local").order_by(BatchTaskItem.position).all()
    assert task.status == "PENDING"
    assert task.total_count == 2
    assert [row.title for row in rows] == ["旧课程", "new-lesson"]
    rows[1].status = TaskStatus.SUCCESS.value
    task.status = "SUCCESS"
    task.completed_count = 2
    task.finished_at = datetime.utcnow()
    db.commit()
    db.close()

    with patch.object(service, "start") as start:
        mixed_result = service.append_items("batch-local", [{
            "title": "远程补充课程",
            "source_url": "https://www.bilibili.com/video/BV1mixed/",
            "platform": "bilibili",
        }])
    assert mixed_result["added_count"] == 1
    assert mixed_result["added_items"][0]["platform"] == "bilibili"
    start.assert_called_once_with("batch-local")


def test_local_preview_naturally_sorts_and_reuses_content_cache(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'local-cache.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    monkeypatch.setattr(batch_module, "SessionLocal", session_factory)
    monkeypatch.chdir(tmp_path)

    preview = BatchTaskService().store_local_preview([
        _Upload("10-结课.mp4", b"video-ten"),
        _Upload("2-基础.mp4", b"video-two"),
        _Upload("1-介绍.mp4", b"video-one"),
        _Upload("2-基础副本.mp4", b"video-two"),
    ])

    assert [item["title"] for item in preview["items"]] == ["1-介绍", "2-基础", "10-结课"]
    assert preview["discovered_count"] == 4
    assert preview["duplicate_count"] == 1
    assert len(list((tmp_path / "uploads" / "batch_videos").iterdir())) == 3
    assert not list((tmp_path / "uploads" / "batch_previews").iterdir())


def test_local_cache_cleanup_keeps_referenced_files(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'local-cleanup.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    monkeypatch.setattr(batch_module, "SessionLocal", session_factory)
    monkeypatch.chdir(tmp_path)

    cache = tmp_path / "uploads" / "batch_videos"
    cache.mkdir(parents=True)
    referenced = cache / f"{'a' * 64}.mp4"
    orphaned = cache / f"{'b' * 64}.mp4"
    referenced.write_bytes(b"keep")
    orphaned.write_bytes(b"remove")
    abandoned_preview = tmp_path / "uploads" / "batch_previews" / "abandoned"
    abandoned_preview.mkdir(parents=True)
    (abandoned_preview / "unused.mp4").write_bytes(b"remove-preview")
    shared_preview = tmp_path / "uploads" / "batch_previews" / "partly-used"
    shared_preview.mkdir(parents=True)
    referenced_preview = shared_preview / "keep.mp4"
    unused_sibling = shared_preview / "unused.mp4"
    referenced_preview.write_bytes(b"keep-preview")
    unused_sibling.write_bytes(b"remove-preview-sibling")

    db = session_factory()
    db.add(BatchTask(
        id="cache-task",
        title="缓存测试",
        source_type="local",
        config_json="{}",
        status="SUCCESS",
    ))
    db.add(BatchTaskItem(
        id="cache-item",
        batch_id="cache-task",
        source_url=f"/uploads/batch_videos/{referenced.name}",
        platform="local",
        title="保留",
        position=1,
        status=TaskStatus.SUCCESS.value,
    ))
    db.add(BatchTaskItem(
        id="legacy-cache-item",
        batch_id="cache-task",
        source_url="/uploads/batch_previews/partly-used/keep.mp4",
        platform="local",
        title="保留旧文件",
        position=2,
        status=TaskStatus.SUCCESS.value,
    ))
    db.commit()
    db.close()

    result = BatchTaskService().cleanup_local_upload_cache(max_age_seconds=0)

    assert result == {"removed_files": 3, "removed_dirs": 1}
    assert referenced.exists()
    assert not orphaned.exists()
    assert not abandoned_preview.exists()
    assert referenced_preview.exists()
    assert not unused_sibling.exists()


def test_local_batch_generation_uses_original_title_and_hides_cache_url(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'local-display-title.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    monkeypatch.setattr(batch_module, "SessionLocal", session_factory)

    source_url = f"/uploads/batch_videos/{'c' * 64}.mp4"
    db = session_factory()
    db.add(BatchTask(
        id="display-batch",
        title="本地课程",
        source_type="local",
        config_json='{"model_name":"demo","provider_id":"provider"}',
        status="RUNNING",
    ))
    db.add(BatchTaskItem(
        id="display-item",
        batch_id="display-batch",
        task_id="display-task",
        source_url=source_url,
        platform="local",
        title="2-01-AI通识与基础-认识人工智能",
        position=1,
        status=TaskStatus.PENDING.value,
    ))
    db.commit()
    db.close()

    generator = MagicMock()
    generator.return_value.generate.return_value = None
    fake_note_module = type(sys)("app.services.note")
    fake_note_module.NoteGenerator = generator
    with patch.dict(sys.modules, {"app.services.note": fake_note_module}):
        BatchTaskService()._run_item("display-item", "display-task")

    kwargs = generator.return_value.generate.call_args.kwargs
    assert kwargs["display_title"] == "2-01-AI通识与基础-认识人工智能"
    assert kwargs["source_link"] == ""
    assert kwargs["video_url"] == source_url


def test_submit_item_claims_pending_row_before_executor_submission(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'claim.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    monkeypatch.setattr(batch_module, "SessionLocal", session_factory)

    db = session_factory()
    db.add(BatchTask(
        id="claim-batch",
        title="并发测试",
        source_type="local",
        config_json="{}",
        status="PENDING",
        total_count=1,
    ))
    db.add(BatchTaskItem(
        id="claim-item",
        batch_id="claim-batch",
        source_url="/uploads/batch_videos/video.mp4",
        platform="local",
        title="测试视频",
        position=1,
        status=TaskStatus.PENDING.value,
    ))
    db.commit()
    db.close()

    submitted_future = MagicMock()
    with patch.object(batch_module.task_serial_executor, "submit", return_value=submitted_future) as submit:
        service = BatchTaskService()
        first = service._submit_item("claim-item")
        second = service._submit_item("claim-item")

    assert first is submitted_future
    assert second is None
    submit.assert_called_once()

    db = session_factory()
    item = db.get(BatchTaskItem, "claim-item")
    assert item.status == TaskStatus.PARSING.value
    assert item.stage == TaskStatus.PARSING.value
    assert item.attempts == 1
    assert item.task_id
    db.close()
