import importlib.util
import json
from pathlib import Path

import pytest


SCRIPT = Path(__file__).with_name("prepare_video_note.py")
SPEC = importlib.util.spec_from_file_location("prepare_video_note", SCRIPT)
prepare_video_note = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare_video_note)


def test_discovers_sorted_videos_recursively(tmp_path):
    (tmp_path / "z.MP4").write_bytes(b"z")
    nested = tmp_path / "nested"
    nested.mkdir()
    (nested / "a.mkv").write_bytes(b"a")
    (nested / "ignore.txt").write_text("x")

    assert prepare_video_note.discover_videos(tmp_path) == [
        tmp_path / "nested" / "a.mkv",
        tmp_path / "z.MP4",
    ]


def test_creates_isolated_workspace_and_manifest(tmp_path):
    video = tmp_path / "clip.mp4"
    video.write_bytes(b"video")
    workspace = prepare_video_note.create_workspace(tmp_path, "task-1")

    assert workspace == tmp_path / ".bilinote-video-notes" / "task-1"
    assert workspace.is_dir()
    assert not (tmp_path / "clip").exists()


def test_processes_video_with_stubbed_downloader_transcriber_and_reader(tmp_path, monkeypatch):
    video = tmp_path / "clip.mp4"
    video.write_bytes(b"video")
    workspace = tmp_path / "workspace"

    class StubDownloader:
        def convert_to_mp3(self, input_path, output_path=None):
            Path(output_path).write_bytes(b"mp3")
            return output_path

    class StubTranscriber:
        def __init__(self, model_size):
            self.model_size = model_size

        def transcript(self, audio_path):
            return {"language": "zh", "full_text": "hello", "segments": []}

    class StubReader:
        def __init__(self, video_path, **kwargs):
            self.frame_dir = kwargs["frame_dir"]
            self.grid_dir = kwargs["grid_dir"]

        def run(self):
            Path(self.frame_dir, "frame_00_00.jpg").write_bytes(b"jpg")
            Path(self.grid_dir, "grid_1.jpg").write_bytes(b"jpg")
            return []

    monkeypatch.setattr(prepare_video_note, "LocalDownloader", StubDownloader)
    monkeypatch.setattr(prepare_video_note, "WhisperTranscriber", StubTranscriber)
    monkeypatch.setattr(prepare_video_note, "VideoReader", StubReader)

    result = prepare_video_note.process_video(video, workspace, model="tiny")
    assert result["status"] == "success"
    video_dir = workspace / "clip"
    assert json.loads((video_dir / "transcript.json").read_text())[
        "full_text"
    ] == "hello"
    assert (video_dir / "transcript.txt").read_text() == "hello"
    assert (video_dir / "frames" / "frame_00_00.jpg").exists()
    assert (video_dir / "grids" / "grid_1.jpg").exists()


def test_frame_failure_is_recorded_without_hiding_transcript_failure(tmp_path, monkeypatch):
    video = tmp_path / "clip.mp4"
    video.write_bytes(b"video")
    workspace = tmp_path / "workspace"

    class Downloader:
        def convert_to_mp3(self, input_path, output_path=None):
            Path(output_path).write_bytes(b"mp3")
            return output_path

    class Transcriber:
        def __init__(self, model_size):
            pass

        def transcript(self, audio_path):
            raise RuntimeError("transcribe boom")

    class Reader:
        def __init__(self, video_path, **kwargs):
            pass

        def run(self):
            raise ValueError("frames boom")

    monkeypatch.setattr(prepare_video_note, "LocalDownloader", Downloader)
    monkeypatch.setattr(prepare_video_note, "WhisperTranscriber", Transcriber)
    monkeypatch.setattr(prepare_video_note, "VideoReader", Reader)

    result = prepare_video_note.process_video(video, workspace)
    assert result["status"] == "failed"
    assert result["transcript"]["status"] == "failed"
    assert "transcribe boom" in result["transcript"]["error"]
    assert json.loads((workspace / "clip" / "transcript.json").read_text())["status"] == "failed"
    assert (workspace / "clip" / "transcript.txt").read_text() == ""
    assert result["frames"]["status"] == "failed"
    assert "frames boom" in result["frames"]["error"]


def test_batch_keeps_success_when_one_video_fails(tmp_path, monkeypatch):
    good = tmp_path / "good.mp4"
    bad = tmp_path / "bad.mp4"
    good.write_bytes(b"good")
    bad.write_bytes(b"bad")

    def fake_process(video, workspace, **kwargs):
        return {"video": str(video), "status": "failed" if video.name == "bad.mp4" else "success"}

    monkeypatch.setattr(prepare_video_note, "process_video", fake_process)
    result = prepare_video_note.run_task(tmp_path, tmp_path / "out", "task-1")
    assert [item["status"] for item in result["videos"]] == ["failed", "success"]
    assert result["status"] == "partial_failure"
    assert (tmp_path / "out" / ".bilinote-video-notes" / "task-1" / "run.json").exists()


def test_artifacts_never_enter_input_or_backend_output_dirs(tmp_path, monkeypatch):
    video = tmp_path / "input" / "clip.mp4"
    video.parent.mkdir()
    video.write_bytes(b"video")
    backend = tmp_path / "backend"
    (backend / "note_results").mkdir(parents=True)
    (backend / "static").mkdir()

    def fake_process(video_path, workspace, **kwargs):
        assert workspace.is_relative_to(tmp_path / "safe")
        return {"video": str(video_path), "status": "success"}

    monkeypatch.setattr(prepare_video_note, "process_video", fake_process)
    result = prepare_video_note.run_task(video, tmp_path / "safe", "task-2")
    assert result["workspace"] == str(tmp_path / "safe" / ".bilinote-video-notes" / "task-2")
    assert not list((backend / "note_results").iterdir())
    assert not list((backend / "static").iterdir())


@pytest.mark.parametrize("input_path", ["missing.mp4", "not-a-video.txt"])
def test_discover_videos_rejects_missing_or_unsupported_file(tmp_path, input_path):
    path = tmp_path / input_path
    if input_path.endswith(".txt"):
        path.write_text("not video")
    with pytest.raises(prepare_video_note.InputError, match="supported|exist"):
        prepare_video_note.discover_videos(path)


def test_discover_videos_rejects_empty_directory_and_non_video_path(tmp_path):
    with pytest.raises(prepare_video_note.InputError, match="does not exist"):
        prepare_video_note.discover_videos(tmp_path / "empty")
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(prepare_video_note.InputError, match="supported video"):
        prepare_video_note.discover_videos(empty)


def test_run_task_writes_task_manifest_with_artifact_summary(tmp_path, monkeypatch):
    video = tmp_path / "clip.mp4"
    video.write_bytes(b"video")

    monkeypatch.setattr(
        prepare_video_note,
        "process_video",
        lambda video, workspace, **kwargs: {
            "video": str(video),
            "output_dir": str(workspace / "clip"),
            "status": "success",
        },
    )
    result = prepare_video_note.run_task(video, tmp_path / "out", "task-manifest")
    task_dir = Path(result["workspace"])
    manifest = json.loads((task_dir / "manifest.json").read_text())
    assert manifest["task_id"] == "task-manifest"
    assert manifest["input"] == str(video.resolve())
    assert manifest["workspace"] == str(task_dir)
    assert manifest["videos"][0]["output_dir"].endswith("clip")
    assert (task_dir / "run.json").exists()


def test_same_stem_videos_get_distinct_safe_output_directories(tmp_path, monkeypatch):
    first = tmp_path / "one" / "clip.mp4"
    second = tmp_path / "two" / "clip.mp4"
    first.parent.mkdir()
    second.parent.mkdir()
    first.write_bytes(b"one")
    second.write_bytes(b"two")

    class Downloader:
        def convert_to_mp3(self, input_path, output_path=None):
            Path(output_path).write_bytes(b"mp3")
            return output_path

    class Transcriber:
        def __init__(self, model_size):
            pass

        def transcript(self, audio_path):
            return {"full_text": "ok", "segments": []}

    class Reader:
        def __init__(self, video_path, **kwargs):
            pass

        def run(self):
            return []

    monkeypatch.setattr(prepare_video_note, "LocalDownloader", Downloader)
    monkeypatch.setattr(prepare_video_note, "WhisperTranscriber", Transcriber)
    monkeypatch.setattr(prepare_video_note, "VideoReader", Reader)
    workspace = prepare_video_note.create_workspace(tmp_path / "out", "task-collision")
    first_result = prepare_video_note.process_video(first, workspace)
    second_result = prepare_video_note.process_video(second, workspace)
    assert first_result["output_dir"] != second_result["output_dir"]
    assert Path(first_result["output_dir"]).is_dir()
    assert Path(second_result["output_dir"]).is_dir()
    assert all("/" not in Path(item["output_dir"]).name and "\\" not in Path(item["output_dir"]).name for item in [first_result, second_result])


def test_task_id_is_optional_and_input_errors_map_to_exit_code_two(tmp_path, monkeypatch):
    video = tmp_path / "clip.mp4"
    video.write_bytes(b"video")
    monkeypatch.setattr(
        prepare_video_note,
        "process_video",
        lambda video, workspace, **kwargs: {"video": str(video), "status": "success"},
    )
    result = prepare_video_note.run_task(video, tmp_path / "out")
    assert result["task_id"]
    assert (tmp_path / "out" / ".bilinote-video-notes" / result["task_id"]).is_dir()

    monkeypatch.setattr(prepare_video_note, "run_task", lambda *args, **kwargs: (_ for _ in ()).throw(prepare_video_note.InputError("bad input")))
    assert prepare_video_note.main(["--input", str(tmp_path / "missing.mp4")]) == 2


@pytest.mark.parametrize("task_id", ["../escape", "foo/bar", ".", "..", "***"])
def test_rejects_unsafe_task_ids_without_creating_outside_workspace(tmp_path, task_id):
    root = tmp_path / "project"
    root.mkdir()
    with pytest.raises(prepare_video_note.InputError, match="task-id"):
        prepare_video_note.create_workspace(root, task_id)
    assert not (tmp_path / "escape").exists()
    assert not (root / ".bilinote-video-notes").exists()


def test_cli_rejects_path_traversal_task_id_with_exit_code_two(tmp_path):
    video = tmp_path / "clip.mp4"
    video.write_bytes(b"video")
    root = tmp_path / "project"
    root.mkdir()

    assert prepare_video_note.main(
        ["--input", str(video), "--workspace", str(root), "--task-id", "../escape"]
    ) == 2
    assert not (tmp_path / "escape").exists()
    assert not (root / ".bilinote-video-notes").exists()


def test_legal_task_id_remains_usable(tmp_path):
    workspace = prepare_video_note.create_workspace(tmp_path, "Task_1.2-3")
    assert workspace == tmp_path / ".bilinote-video-notes" / "Task_1.2-3"
    assert workspace.is_dir()


@pytest.mark.parametrize("task_id", ["task id", "task:1", "task?1"])
def test_rejects_task_ids_with_ambiguous_filename_characters(tmp_path, task_id):
    with pytest.raises(prepare_video_note.InputError, match="task-id"):
        prepare_video_note.create_workspace(tmp_path, task_id)


def test_grid_size_requires_positive_dimensions(tmp_path, monkeypatch):
    video = tmp_path / "clip.mp4"
    video.write_bytes(b"video")

    def fail_if_processing(*args, **kwargs):
        raise AssertionError("run_task must not be called for invalid grid size")

    monkeypatch.setattr(prepare_video_note, "run_task", fail_if_processing)
    assert prepare_video_note.main(["--input", str(video), "--grid-size", "0x3"]) == 2


def test_batch_records_unexpected_video_failure_and_continues(tmp_path, monkeypatch):
    bad = tmp_path / "bad.mp4"
    good = tmp_path / "good.mp4"
    bad.write_bytes(b"bad")
    good.write_bytes(b"good")

    def fake_process(video, workspace, **kwargs):
        if video.name == "bad.mp4":
            raise RuntimeError("unexpected boom")
        return {"video": str(video), "output_dir": str(workspace / "good"), "status": "success"}

    monkeypatch.setattr(prepare_video_note, "process_video", fake_process)
    result = prepare_video_note.run_task(tmp_path, tmp_path / "out", "task-1")

    assert [item["status"] for item in result["videos"]] == ["failed", "success"]
    assert "unexpected boom" in result["videos"][0]["error"]
    failed_dir = tmp_path / "out" / ".bilinote-video-notes" / "task-1" / "bad"
    assert json.loads((failed_dir / "manifest.json").read_text())["status"] == "failed"


def test_same_stem_hash_directory_collision_gets_another_directory(tmp_path, monkeypatch):
    first = tmp_path / "one" / "clip.mp4"
    second = tmp_path / "two" / "clip.mp4"
    first.parent.mkdir()
    second.parent.mkdir()
    first.write_bytes(b"one")
    second.write_bytes(b"two")
    workspace = prepare_video_note.create_workspace(tmp_path / "out", "task-collision")

    class Downloader:
        def convert_to_mp3(self, input_path, output_path=None):
            Path(output_path).write_bytes(b"mp3")
            return output_path

    class Transcriber:
        def __init__(self, model_size):
            pass

        def transcript(self, audio_path):
            return {"full_text": "ok", "segments": []}

    class Reader:
        def __init__(self, video_path, **kwargs):
            pass

        def run(self):
            return []

    monkeypatch.setattr(prepare_video_note, "LocalDownloader", Downloader)
    monkeypatch.setattr(prepare_video_note, "WhisperTranscriber", Transcriber)
    monkeypatch.setattr(prepare_video_note, "VideoReader", Reader)

    first_result = prepare_video_note.process_video(first, workspace)
    second_hash = prepare_video_note.hashlib.sha256(str(second.resolve()).encode("utf-8")).hexdigest()[:10]
    occupied = workspace / f"clip-{second_hash}"
    occupied.mkdir()
    (occupied / "manifest.json").write_text(json.dumps({"video": "another-video.mp4"}), encoding="utf-8")

    second_result = prepare_video_note.process_video(second, workspace)
    assert first_result["output_dir"] != second_result["output_dir"]
    assert second_result["output_dir"] != str(occupied)
    assert json.loads((occupied / "manifest.json").read_text())["video"] == "another-video.mp4"
