import io
import json
import zipfile
from unittest.mock import patch

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
