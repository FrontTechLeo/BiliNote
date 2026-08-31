import os
from datetime import datetime

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import app.services.batch_task_service as batch_module
import app.utils.runtime_paths as runtime_paths
from app.db.engine import Base
from app.db.models.video_tasks import BatchTask, BatchTaskItem
from app.services.batch_task_service import BatchTaskService
from app.enmus.task_status_enums import TaskStatus


# ---------- runtime_paths 数据根路由 ----------

def test_runtime_paths_routes_under_data_root(tmp_path, monkeypatch):
    monkeypatch.setattr(runtime_paths, "DATA_ROOT", str(tmp_path))
    monkeypatch.delenv("NOTE_OUTPUT_DIR", raising=False)
    monkeypatch.delenv("OUT_DIR", raising=False)
    monkeypatch.delenv("VECTOR_DB_DIR", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)

    assert runtime_paths.get_note_output_dir() == str(tmp_path / "note_results")
    assert runtime_paths.get_screenshots_dir().lower().endswith("static\\screenshots") \
        or runtime_paths.get_screenshots_dir().endswith("static/screenshots")
    assert str(tmp_path) in runtime_paths.get_screenshots_dir()
    assert runtime_paths.get_uploads_dir() == str(tmp_path / "uploads")
    assert runtime_paths.get_config_dir() == str(tmp_path / "config")
    assert runtime_paths.get_logs_dir() == str(tmp_path / "logs")
    assert runtime_paths.get_models_base_dir() == str(tmp_path / "models")
    assert runtime_paths.get_database_url() == "sqlite:///" + str(tmp_path / "bili_note.db")


def test_runtime_paths_relative_env_ignored_under_data_root(tmp_path, monkeypatch):
    # .env 历史遗留的相对路径在数据根模式下必须被忽略，否则数据会写回 cwd
    monkeypatch.setattr(runtime_paths, "DATA_ROOT", str(tmp_path))
    monkeypatch.setenv("OUT_DIR", "./static/screenshots")
    monkeypatch.setenv("NOTE_OUTPUT_DIR", "note_results")
    assert runtime_paths.get_screenshots_dir() == str(tmp_path / "static" / "screenshots")
    assert runtime_paths.get_note_output_dir() == str(tmp_path / "note_results")


def test_runtime_paths_absolute_env_wins_over_data_root(tmp_path, monkeypatch):
    data_root = tmp_path / "data-root"
    explicit = tmp_path / "explicit-notes"
    monkeypatch.setattr(runtime_paths, "DATA_ROOT", str(data_root))
    monkeypatch.setenv("NOTE_OUTPUT_DIR", str(explicit))
    assert runtime_paths.get_note_output_dir() == str(explicit)


def test_runtime_paths_historical_defaults_without_data_root(monkeypatch):
    monkeypatch.setattr(runtime_paths, "DATA_ROOT", None)
    monkeypatch.delenv("NOTE_OUTPUT_DIR", raising=False)
    monkeypatch.delenv("OUT_DIR", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    assert runtime_paths.get_note_output_dir() == "note_results"
    assert runtime_paths.get_uploads_dir() == "uploads"
    assert runtime_paths.get_database_url() == "sqlite:///bili_note.db"


# ---------- 绝对路径校验 ----------

def test_validate_local_source_gate(tmp_path, monkeypatch):
    video = tmp_path / "lesson.mp4"
    video.write_bytes(b"video-bytes")

    monkeypatch.setenv("ALLOW_LOCAL_PATH_INPUT", "")
    with pytest.raises(ValueError, match="未启用本地路径输入"):
        BatchTaskService._validate_local_source(str(video))

    monkeypatch.setenv("ALLOW_LOCAL_PATH_INPUT", "1")
    BatchTaskService._validate_local_source(str(video))

    with pytest.raises(ValueError, match="本地文件不存在"):
        BatchTaskService._validate_local_source(str(tmp_path / "missing.mp4"))


def test_validate_local_video_source_checks_suffix(tmp_path, monkeypatch):
    monkeypatch.setenv("ALLOW_LOCAL_PATH_INPUT", "1")
    audio = tmp_path / "song.mp3"
    audio.write_bytes(b"audio")
    with pytest.raises(ValueError, match="仅支持视频文件"):
        BatchTaskService._validate_local_video_source(str(audio))
    video = tmp_path / "clip.webm"
    video.write_bytes(b"video")
    BatchTaskService._validate_local_video_source(str(video))


def test_resolve_local_source_path_forms(tmp_path):
    assert batch_module._resolve_local_source_path(str(tmp_path / "a.mp4")) == tmp_path / "a.mp4"
    resolved = batch_module._resolve_local_source_path("/uploads/batch_videos/abc.mp4")
    assert str(resolved).replace("\\", "/").endswith("batch_videos/abc.mp4")


# ---------- 直读预览 ----------

def _setup_db(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'local-paths.db'}")
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine)
    monkeypatch.setattr(batch_module, "SessionLocal", session_factory)
    return session_factory


def test_store_local_paths_preview_discovers_sorts_and_dedupes(tmp_path, monkeypatch):
    monkeypatch.setenv("ALLOW_LOCAL_PATH_INPUT", "1")
    monkeypatch.chdir(tmp_path)
    _setup_db(tmp_path, monkeypatch)

    videos = tmp_path / "课程"
    videos.mkdir()
    (videos / "01 intro.mp4").write_bytes(b"intro-content")
    (videos / "02 body.mp4").write_bytes(b"body-content")
    (videos / "notes.txt").write_text("不是视频")
    nested = videos / "ch2"
    nested.mkdir()
    (nested / "03 outro.mkv").write_bytes(b"outro-content")
    (videos / "dup_of_intro.mp4").write_bytes(b"intro-content")  # 内容重复

    preview = BatchTaskService().store_local_paths_preview([str(videos)])
    titles = [item["title"] for item in preview["items"]]

    assert preview["discovered_count"] == 4
    assert preview["duplicate_count"] == 1
    assert titles == ["01 intro", "02 body", "03 outro"]
    for item in preview["items"]:
        assert os.path.isabs(item["source_url"])
        assert item["platform"] == "local"


def test_preview_local_paths_appends_and_filters_existing(tmp_path, monkeypatch):
    monkeypatch.setenv("ALLOW_LOCAL_PATH_INPUT", "1")
    monkeypatch.chdir(tmp_path)
    session_factory = _setup_db(tmp_path, monkeypatch)

    videos = tmp_path / "lib"
    videos.mkdir()
    existing_file = videos / "old.mp4"
    existing_file.write_bytes(b"old-content")
    new_file = videos / "new.mp4"
    new_file.write_bytes(b"new-content")

    db = session_factory()
    db.add(BatchTask(
        id="batch-paths",
        title="本地直读合集",
        source_type="local",
        config_json='{"model_name":"demo","provider_id":"provider"}',
        status="SUCCESS",
        total_count=1,
        completed_count=1,
        finished_at=datetime.utcnow(),
    ))
    db.add(BatchTaskItem(
        id="item-old",
        batch_id="batch-paths",
        task_id="task-old",
        source_url=str(existing_file),
        platform="local",
        title="old",
        position=1,
        status=TaskStatus.SUCCESS.value,
    ))
    db.commit()
    db.close()

    preview = BatchTaskService().preview_append_local_paths("batch-paths", [str(videos)])
    assert preview["discovered_count"] == 2
    assert [item["title"] for item in preview["items"]] == ["new"]
    assert preview["skipped_count"] == 1


def test_preview_local_paths_disabled_raises(tmp_path, monkeypatch):
    monkeypatch.setenv("ALLOW_LOCAL_PATH_INPUT", "")
    monkeypatch.chdir(tmp_path)
    _setup_db(tmp_path, monkeypatch)
    with pytest.raises(ValueError, match="未启用本地路径输入"):
        BatchTaskService().store_local_paths_preview([str(tmp_path)])


def test_preview_local_paths_empty_selection_raises(tmp_path, monkeypatch):
    monkeypatch.setenv("ALLOW_LOCAL_PATH_INPUT", "1")
    monkeypatch.chdir(tmp_path)
    _setup_db(tmp_path, monkeypatch)
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(ValueError, match="没有可处理的视频文件"):
        BatchTaskService().store_local_paths_preview([str(empty)])


# ---------- 删除子任务永远不碰用户原始文件 ----------

def test_cleanup_never_deletes_absolute_user_sources(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    user_video = tmp_path / "user-lib" / "keep-me.mp4"
    user_video.parent.mkdir(parents=True)
    user_video.write_bytes(b"user-original")

    note_dir = tmp_path / "note_results"
    note_dir.mkdir()
    monkeypatch.setattr(batch_module, "NOTE_OUTPUT_DIR", note_dir)

    result = BatchTaskService()._cleanup_deleted_item_resources([], [str(user_video)])
    assert result["removed_files"] == 0
    assert user_video.is_file()
