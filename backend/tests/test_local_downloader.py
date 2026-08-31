from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Lock
from unittest.mock import patch

import pytest

from app.downloaders.local_downloader import LocalDownloader


def test_mp3_input_is_reused_without_in_place_conversion(tmp_path):
    source = tmp_path / "lesson.mp3"
    source.write_bytes(b"audio")

    with patch("app.downloaders.local_downloader.subprocess.run") as run:
        result = LocalDownloader().download(str(source))

    assert result.file_path == str(source)
    assert result.cover_url is None
    run.assert_not_called()


def test_audio_file_cannot_be_used_for_video_processing(tmp_path):
    source = tmp_path / "lesson.mp3"
    source.write_bytes(b"audio")

    with pytest.raises(ValueError, match="不是视频"):
        LocalDownloader().download_video(str(source))


def test_same_video_conversion_is_serialized_and_reuses_output(tmp_path, monkeypatch):
    # 默认输出进数据目录的 audio 缓存（避免写用户的源文件夹），且同源路径稳定复用
    cache_root = tmp_path / "app-data"
    monkeypatch.setattr(
        "app.downloaders.local_downloader.get_app_dir",
        lambda subdir="": str(cache_root / subdir) if subdir else str(cache_root),
    )

    source = tmp_path / "lesson.mp4"
    source.write_bytes(b"video")
    calls = 0
    calls_lock = Lock()

    def fake_run(command, **_kwargs):
        nonlocal calls
        with calls_lock:
            calls += 1
        Path(command[-1]).write_bytes(b"mp3")

    with patch("app.downloaders.local_downloader.subprocess.run", side_effect=fake_run):
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: LocalDownloader().convert_to_mp3(str(source)), range(2)))

    assert results[0] == results[1]
    assert "audio" in results[0].replace("\\", "/")
    assert results[0].endswith("lesson.mp3")
    assert calls == 1
