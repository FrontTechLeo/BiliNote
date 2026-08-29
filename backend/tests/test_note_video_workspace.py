import sys
from pathlib import Path
from types import ModuleType
from unittest.mock import patch


def _stub_optional_modules(monkeypatch) -> None:
    if "faster_whisper" not in sys.modules:
        module = ModuleType("faster_whisper")
        module.WhisperModel = object
        monkeypatch.setitem(sys.modules, "faster_whisper", module)

    downloader_modules = {
        "app.downloaders.bilibili_downloader": "BilibiliDownloader",
        "app.downloaders.douyin_downloader": "DouyinDownloader",
        "app.downloaders.local_downloader": "LocalDownloader",
        "app.downloaders.youtube_downloader": "YoutubeDownloader",
    }
    for module_name, class_name in downloader_modules.items():
        module = ModuleType(module_name)
        setattr(module, class_name, type(class_name, (), {}))
        monkeypatch.setitem(sys.modules, module_name, module)

    gpt_base = ModuleType("app.gpt.base")
    gpt_base.GPT = type("GPT", (), {})
    monkeypatch.setitem(sys.modules, "app.gpt.base", gpt_base)
    gpt_factory = ModuleType("app.gpt.gpt_factory")
    gpt_factory.GPTFactory = type("GPTFactory", (), {})
    monkeypatch.setitem(sys.modules, "app.gpt.gpt_factory", gpt_factory)
    provider = ModuleType("app.services.provider")
    provider.ProviderService = type("ProviderService", (), {})
    monkeypatch.setitem(sys.modules, "app.services.provider", provider)
    transcriber_base = ModuleType("app.transcriber.base")
    transcriber_base.Transcriber = type("Transcriber", (), {})
    monkeypatch.setitem(sys.modules, "app.transcriber.base", transcriber_base)
    transcriber_provider = ModuleType("app.transcriber.transcriber_provider")
    transcriber_provider.get_transcriber = lambda **_kwargs: None
    transcriber_provider._transcribers = {}
    monkeypatch.setitem(sys.modules, "app.transcriber.transcriber_provider", transcriber_provider)
    video_reader = ModuleType("app.utils.video_reader")
    video_reader.VideoReader = type("VideoReader", (), {})
    monkeypatch.setitem(sys.modules, "app.utils.video_reader", video_reader)


def test_video_understanding_uses_isolated_workspace_and_cleans_it(tmp_path, monkeypatch):
    _stub_optional_modules(monkeypatch)
    import app.services.note as note_module

    captured = {}

    class _Reader:
        def __init__(self, **kwargs):
            captured.update(kwargs)
            Path(kwargs["frame_dir"]).joinpath("frame_00_00.jpg").write_bytes(b"frame")
            Path(kwargs["grid_dir"]).joinpath("grid_1.jpg").write_bytes(b"grid")

        def run(self):
            return ["data:image/jpeg;base64,dGVzdA=="]

    monkeypatch.setattr(note_module, "get_app_dir", lambda _name: str(tmp_path / "video_tasks"))
    with patch.object(note_module, "VideoReader", _Reader):
        result = note_module.NoteGenerator._read_video_frames(
            task_id="task-a",
            video_path=tmp_path / "video.mp4",
            grid_size=[2, 2],
            frame_interval=6,
            image_compression="medium",
        )

    assert result == ["data:image/jpeg;base64,dGVzdA=="]
    assert Path(captured["frame_dir"]) == tmp_path / "video_tasks" / "task-a" / "frames"
    assert Path(captured["grid_dir"]) == tmp_path / "video_tasks" / "task-a" / "grids"
    assert captured["image_compression"] == "medium"
    assert not (tmp_path / "video_tasks" / "task-a").exists()
