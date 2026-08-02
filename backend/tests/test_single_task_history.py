import io
import json
import logging
import sys
import zipfile
from types import ModuleType

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import app.services.single_task_history_service as history_module
from app.db.engine import Base
from app.services.single_task_history_service import SingleTaskHistoryService


def test_single_task_history_persists_request_and_detects_zip_export(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'history.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    output_dir = tmp_path / "notes"
    monkeypatch.setattr(history_module, "SessionLocal", session_factory)
    monkeypatch.setattr(history_module, "NOTE_OUTPUT_DIR", output_dir)

    service = SingleTaskHistoryService()
    service.persist_request("task-1", {
        "video_url": "https://www.bilibili.com/video/BV1demo/",
        "platform": "bilibili",
        "quality": "medium",
        "model_name": "demo-model",
        "provider_id": "provider-1",
        "prefetched_transcript": {"segments": [{"text": "large payload"}]},
    })
    saved_request = json.loads((output_dir / "task-1_request.json").read_text(encoding="utf-8"))
    assert "prefetched_transcript" not in saved_request
    assert saved_request["has_prefetched_transcript"] is True

    (output_dir / "task-1.status.json").write_text(json.dumps({"status": "SUCCESS"}), encoding="utf-8")
    (output_dir / "task-1.json").write_text(json.dumps({
        "markdown": "# 演示笔记\n\n![](/static/screenshots/demo.jpg)",
        "audio_meta": {"title": "演示笔记"},
    }, ensure_ascii=False), encoding="utf-8")

    history = service.list_tasks()
    assert history["total"] == 1
    row = history["items"][0]
    assert row["title"] == "演示笔记"
    assert row["status"] == "SUCCESS"
    assert row["has_images"] is True
    assert row["export_format"] == "zip"
    assert row["can_regenerate"] is True


def test_export_note_packages_markdown_and_referenced_images(tmp_path, monkeypatch):
    fake_note = ModuleType("app.services.note")
    fake_note.NoteGenerator = type("NoteGenerator", (), {})
    fake_note.logger = logging.getLogger("test-note-export")
    monkeypatch.setitem(sys.modules, "app.services.note", fake_note)
    monkeypatch.delitem(sys.modules, "app.routers.note", raising=False)
    import app.routers.note as note_router

    monkeypatch.chdir(tmp_path)
    output_dir = tmp_path / "notes"
    screenshot_dir = tmp_path / "static" / "screenshots"
    output_dir.mkdir()
    screenshot_dir.mkdir(parents=True)
    monkeypatch.setattr(note_router, "NOTE_OUTPUT_DIR", str(output_dir))

    (output_dir / "task-zip.json").write_text(json.dumps({
        "markdown": "# 图文笔记\n\n![示例](/static/screenshots/example.jpg)",
    }, ensure_ascii=False), encoding="utf-8")
    (output_dir / "task-zip_audio.json").write_text(json.dumps({"title": "图文笔记"}, ensure_ascii=False), encoding="utf-8")
    (screenshot_dir / "example.jpg").write_bytes(b"image-bytes")

    response = note_router.export_note("task-zip")

    assert response.media_type == "application/zip"
    with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
        assert "图文笔记.md" in archive.namelist()
        assert "screenshots/example.jpg" in archive.namelist()
        markdown = archive.read("图文笔记.md").decode("utf-8")
        assert "![示例](screenshots/example.jpg)" in markdown
