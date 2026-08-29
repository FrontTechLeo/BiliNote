"""Copy selected video frames into the legacy BiliNote image layout."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import uuid
from pathlib import Path, PureWindowsPath
from urllib.parse import urlparse


IMAGE_DIRS = {"frames", "grids"}
IMAGE_SUFFIXES = {".jpg", ".png"}
WINDOWS_INVALID = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
WINDOWS_RESERVED = {
    "CON",
    "PRN",
    "AUX",
    "NUL",
    *(f"COM{i}" for i in range(1, 10)),
    *(f"LPT{i}" for i in range(1, 10)),
}


def _safe_stem(value: str) -> str:
    """Make a readable stem safe as a Windows directory name."""
    value = WINDOWS_INVALID.sub("_", value)
    value = re.sub(r"\s+", " ", value).strip(" .")
    if not value:
        return "video"
    if value.split(".", 1)[0].upper() in WINDOWS_RESERVED:
        value = f"_{value}"
    return value


def _note_stem(video_dir: Path, stem: str | None) -> str:
    if stem is None:
        manifest_path = video_dir / "manifest.json"
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise ValueError("manifest.json is missing; pass --stem") from exc
        except json.JSONDecodeError as exc:
            raise ValueError("manifest.json is not valid JSON") from exc
        note_filename = manifest.get("note_filename")
        if not isinstance(note_filename, str) or not note_filename.strip():
            raise ValueError("manifest.json has no usable note_filename; pass --stem")
        stem = note_filename.strip()
    if stem.lower().endswith(".md"):
        stem = stem[:-3]
    return _safe_stem(stem)


def _source_path(video_dir: Path, source: object) -> tuple[str, Path]:
    if not isinstance(source, str) or not source:
        raise ValueError("selection source must be a non-empty relative path")
    parsed = urlparse(source)
    windows_path = PureWindowsPath(source)
    if (
        parsed.scheme
        or parsed.netloc
        or source.startswith(("/", "\\"))
        or Path(source).is_absolute()
        or windows_path.is_absolute()
        or windows_path.drive
        or "\\" in source
    ):
        raise ValueError(f"selection source must be a local relative path: {source!r}")
    relative = Path(source)
    if ".." in relative.parts or not relative.parts or relative.parts[0] not in IMAGE_DIRS:
        raise ValueError(f"selection source must be inside frames/ or grids/: {source!r}")
    if relative.suffix.lower() not in IMAGE_SUFFIXES:
        raise ValueError(f"selection source must be a JPG or PNG: {source!r}")

    candidate = (video_dir / relative).resolve()
    allowed_root = (video_dir / relative.parts[0]).resolve()
    try:
        candidate.relative_to(allowed_root)
    except ValueError as exc:
        raise ValueError(f"selection source escapes its image directory: {source!r}") from exc
    if not candidate.is_file():
        raise ValueError(f"selection source does not exist: {source!r}")
    return source.replace("\\", "/"), candidate


def _read_selection(selection_path: Path) -> list[dict[str, object]]:
    try:
        selection = json.loads(selection_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ValueError(f"selection file does not exist: {selection_path}") from exc
    except json.JSONDecodeError as exc:
        raise ValueError("selection file is not valid JSON") from exc
    if isinstance(selection, dict):
        selection = selection.get("images")
    if not isinstance(selection, list):
        raise ValueError("selection must be an array or an object with an images array")
    result: list[dict[str, object]] = []
    seen: set[str] = set()
    for item in selection:
        if not isinstance(item, dict):
            raise ValueError("each selection item must be an object")
        source = item.get("source")
        if not isinstance(source, str):
            raise ValueError("selection source must be a non-empty relative path")
        key = source.replace("\\", "/") if isinstance(source, str) else source
        if key in seen:
            continue
        seen.add(key)
        result.append(item)
    return result


def organize_images(video_dir: str | Path, selection_path: str | Path, stem: str | None = None) -> dict[str, object]:
    """Copy selected assets and return/write the deterministic image mapping."""
    video_dir = Path(video_dir).resolve()
    if not video_dir.is_dir():
        raise ValueError(f"video artifact directory does not exist: {video_dir}")
    selection_path = Path(selection_path)
    items = _read_selection(selection_path)
    output_stem = _note_stem(video_dir, stem)
    image_dir = video_dir / "images" / output_stem
    image_dir.mkdir(parents=True, exist_ok=True)

    images: list[dict[str, object]] = []
    for index, item in enumerate(items):
        source, source_path = _source_path(video_dir, item.get("source"))
        filename = f"screenshot_{index:03d}_{uuid.uuid4()}.jpg"
        target = image_dir / filename
        shutil.copyfile(source_path, target)
        target_relative = f"images/{output_stem}/{filename}"
        images.append(
            {
                "source": source,
                "target": target_relative,
                "markdown": f"![]({target_relative})*",
                "caption": item.get("caption", ""),
            }
        )

    mapping = {"images": images}
    (video_dir / "image_map.json").write_text(
        json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return mapping


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video-dir", required=True, type=Path)
    parser.add_argument("--selection", required=True, type=Path)
    parser.add_argument("--stem")
    args = parser.parse_args(argv)
    try:
        organize_images(args.video_dir, args.selection, args.stem)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
