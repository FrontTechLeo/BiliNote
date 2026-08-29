import json
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
from organize_note_assets import organize_images


SCRIPT = Path(__file__).with_name("organize_note_assets.py")


def make_video_dir(tmp_path: Path) -> Path:
    video_dir = tmp_path / "artifact"
    (video_dir / "frames").mkdir(parents=True)
    (video_dir / "grids").mkdir()
    (video_dir / "frames" / "frame_00_00.jpg").write_bytes(b"jpg")
    (video_dir / "grids" / "grid_00.png").write_bytes(b"png")
    (video_dir / "manifest.json").write_text(
        json.dumps({"note_filename": "课程  01：滤镜.md"}), encoding="utf-8"
    )
    return video_dir


def write_selection(tmp_path: Path, value: object) -> Path:
    path = tmp_path / "selection.json"
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    return path


def test_copies_and_writes_mapping_from_object_selection(tmp_path: Path):
    video_dir = make_video_dir(tmp_path)
    mapping = organize_images(
        video_dir,
        write_selection(tmp_path, {"images": [{"source": "frames/frame_00_00.jpg", "caption": "步骤"}]}),
    )
    image = mapping["images"][0]
    assert image["source"] == "frames/frame_00_00.jpg"
    assert image["caption"] == "步骤"
    assert image["markdown"].startswith("![](images/")
    assert ":" not in image["target"]
    target = video_dir / image["target"]
    assert target.suffix == ".jpg"
    assert target.read_bytes() == b"jpg"
    assert json.loads((video_dir / "image_map.json").read_text(encoding="utf-8")) == mapping


def test_accepts_array_and_deduplicates_in_first_seen_order(tmp_path: Path):
    video_dir = make_video_dir(tmp_path)
    mapping = organize_images(
        video_dir,
        write_selection(
            tmp_path,
            [
                {"source": "grids/grid_00.png"},
                {"source": "frames/frame_00_00.jpg"},
                {"source": "grids/grid_00.png", "caption": "ignored"},
            ],
        ),
    )
    assert [item["source"] for item in mapping["images"]] == [
        "grids/grid_00.png",
        "frames/frame_00_00.jpg",
    ]
    assert (video_dir / mapping["images"][0]["target"]).read_bytes() == b"png"


@pytest.mark.parametrize(
    "source",
    [
        "../outside.jpg",
        "frames/../../outside.jpg",
        "/tmp/outside.jpg",
        r"C:\outside.jpg",
        "https://example.com/frame.jpg",
        "notes/note.jpg",
        "frames/missing.jpg",
        "frames/frame.txt",
    ],
)
def test_rejects_unsafe_or_missing_sources(tmp_path: Path, source: str):
    video_dir = make_video_dir(tmp_path)
    with pytest.raises(ValueError):
        organize_images(video_dir, write_selection(tmp_path, [{"source": source}]))
    assert not (video_dir / "image_map.json").exists()


def test_stem_fallback_is_windows_safe_and_only_writes_video_dir(tmp_path: Path):
    video_dir = make_video_dir(tmp_path)
    (video_dir / "manifest.json").unlink()
    input_video_dir = tmp_path / "input-video"
    backend_dir = tmp_path / "backend"
    input_video_dir.mkdir()
    backend_dir.mkdir()
    (input_video_dir / "keep.txt").write_text("keep", encoding="utf-8")
    (backend_dir / "keep.txt").write_text("keep", encoding="utf-8")
    mapping = organize_images(
        video_dir,
        write_selection(tmp_path, [{"source": "frames/frame_00_00.jpg"}]),
        stem="  bad:name.  ",
    )
    assert mapping["images"][0]["target"].startswith("images/bad_name/")
    assert list(input_video_dir.iterdir()) == [input_video_dir / "keep.txt"]
    assert list(backend_dir.iterdir()) == [backend_dir / "keep.txt"]


def test_cli_returns_error_code_for_invalid_selection(tmp_path: Path):
    video_dir = make_video_dir(tmp_path)
    selection = write_selection(tmp_path, [{"source": "../escape.jpg"}])
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--video-dir", str(video_dir), "--selection", str(selection)],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 2
    assert "error:" in result.stderr
