"""Prepare local videos for note generation without writing into application outputs."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[4]
BACKEND_ROOT = PROJECT_ROOT / "backend"
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

try:
    from app.downloaders.local_downloader import LocalDownloader
    from app.transcriber.whisper import WhisperTranscriber
    from app.utils.video_reader import VideoReader
except Exception:  # Optional runtime dependencies are available in production/test stubs.
    LocalDownloader = None
    WhisperTranscriber = None
    VideoReader = None


VIDEO_EXTENSIONS = {".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v"}


class InputError(ValueError):
    """Raised when the requested local video input is invalid."""


def format_note_filename(video_path: Path | str) -> str:
    """Return a filesystem-safe Markdown filename derived from a video stem."""
    stem = re.sub(r"\s+", " ", Path(video_path).stem).strip()
    stem = re.sub(r'[\x00-\x1f<>:"/\\|?*]', "_", stem).rstrip(" .")
    return f"{stem or 'video'}.md"


def discover_videos(input_path: Path | str) -> list[Path]:
    path = Path(input_path).expanduser().resolve()
    if not path.exists():
        raise InputError(f"input path does not exist: {path}")
    if path.is_file():
        if path.suffix.lower() not in VIDEO_EXTENSIONS:
            raise InputError(f"unsupported video extension: {path.suffix or '<none>'}")
        return [path]
    if path.is_dir():
        videos = sorted(
            (item for item in path.rglob("*") if item.is_file() and item.suffix.lower() in VIDEO_EXTENSIONS),
            key=lambda item: item.as_posix().lower(),
        )
        if not videos:
            raise InputError(f"no supported video files found in directory: {path}")
        return videos
    raise InputError(f"input path is neither a file nor a directory: {path}")


def create_workspace(workspace_root: Path | str, task_id: str) -> Path:
    task_id = _validate_task_id(task_id)
    workspace = Path(workspace_root).expanduser().resolve() / ".bilinote-video-notes" / task_id
    workspace.mkdir(parents=True, exist_ok=True)
    return workspace


def _json_value(value: Any) -> Any:
    if dataclasses.is_dataclass(value):
        return {key: _json_value(item) for key, item in dataclasses.asdict(value).items()}
    if isinstance(value, dict):
        return {str(key): _json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(item) for item in value]
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    return str(value)


def _safe_name(name: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._")
    return cleaned or "video"


def _validate_task_id(task_id: str) -> str:
    if not isinstance(task_id, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", task_id):
        raise InputError("invalid task-id: use only letters, digits, '.', '_' or '-'")
    if task_id in {".", ".."}:
        raise InputError("invalid task-id: '.' and '..' are not allowed")
    return task_id


def _video_dir(task_workspace: Path, video: Path) -> Path:
    base = task_workspace / _safe_name(video.stem)
    if not base.exists():
        return base
    manifest_path = base / "manifest.json"
    try:
        existing = json.loads(manifest_path.read_text(encoding="utf-8"))
        if existing.get("video") == str(video):
            return base
    except (OSError, ValueError):
        pass
    short_hash = hashlib.sha256(str(video).encode("utf-8")).hexdigest()[:10]
    candidate = task_workspace / f"{_safe_name(video.stem)}-{short_hash}"
    suffix = 1
    while candidate.exists():
        try:
            existing = json.loads((candidate / "manifest.json").read_text(encoding="utf-8"))
            if existing.get("video") == str(video):
                return candidate
        except (OSError, ValueError):
            pass
        candidate = task_workspace / f"{_safe_name(video.stem)}-{short_hash}-{suffix}"
        suffix += 1
    return candidate


def _error(error: Exception) -> dict[str, str]:
    return {"status": "failed", "error": f"{type(error).__name__}: {error}"}


def process_video(
    video_path: Path | str,
    task_workspace: Path | str,
    model: str = "base",
    frame_interval: int = 2,
    grid_size: tuple[int, int] = (3, 3),
) -> dict[str, Any]:
    video = Path(video_path).resolve()
    video_dir = _video_dir(Path(task_workspace), video)
    video_dir.mkdir(parents=True, exist_ok=True)
    frames_dir = video_dir / "frames"
    grids_dir = video_dir / "grids"
    frames_dir.mkdir(exist_ok=True)
    grids_dir.mkdir(exist_ok=True)
    result: dict[str, Any] = {
        "video": str(video),
        "output_dir": str(video_dir),
        "note_filename": format_note_filename(video),
    }

    try:
        if LocalDownloader is None or WhisperTranscriber is None:
            raise RuntimeError("backend transcription dependencies are unavailable")
        audio_path = video_dir / "audio.mp3"
        audio_path = Path(LocalDownloader().convert_to_mp3(str(video), str(audio_path)))
        transcript_result = WhisperTranscriber(model_size=model).transcript(str(audio_path))
        if transcript_result is None:
            raise RuntimeError("transcriber returned no result")
        transcript_json = _json_value(transcript_result)
        (video_dir / "transcript.json").write_text(
            json.dumps(transcript_json, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        full_text = transcript_json.get("full_text", "") if isinstance(transcript_json, dict) else str(transcript_json)
        (video_dir / "transcript.txt").write_text(str(full_text), encoding="utf-8")
        result["transcript"] = {"status": "success", "path": str(video_dir / "transcript.json")}
    except Exception as exc:
        result["transcript"] = _error(exc)
        (video_dir / "transcript.json").write_text(
            json.dumps(result["transcript"], ensure_ascii=False, indent=2), encoding="utf-8"
        )
        (video_dir / "transcript.txt").write_text("", encoding="utf-8")

    try:
        if VideoReader is None:
            raise RuntimeError("backend video reader dependencies are unavailable")
        VideoReader(
            str(video),
            frame_interval=frame_interval,
            grid_size=grid_size,
            frame_dir=str(frames_dir),
            grid_dir=str(grids_dir),
        ).run()
        result["frames"] = {"status": "success", "directory": str(frames_dir), "grids": str(grids_dir)}
    except Exception as exc:
        result["frames"] = _error(exc)

    result["status"] = "success" if result["transcript"]["status"] == "success" and result["frames"]["status"] == "success" else "failed"
    (video_dir / "manifest.json").write_text(json.dumps(_json_value(result), ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def run_task(
    input_path: Path | str,
    workspace_root: Path | str | None = None,
    task_id: str | None = None,
    **kwargs: Any,
) -> dict[str, Any]:
    input_path = Path(input_path).expanduser().resolve()
    task_id = task_id or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    task_id = _validate_task_id(task_id)
    videos = discover_videos(input_path)
    root = Path(workspace_root or PROJECT_ROOT).expanduser().resolve()
    workspace = create_workspace(root, task_id)
    results = []
    for video in videos:
        try:
            results.append(process_video(video, workspace, **kwargs))
        except Exception as exc:
            video_dir = _video_dir(workspace, video)
            result = {"video": str(video), "output_dir": str(video_dir), **_error(exc)}
            video_dir.mkdir(parents=True, exist_ok=True)
            (video_dir / "manifest.json").write_text(
                json.dumps(_json_value(result), ensure_ascii=False, indent=2), encoding="utf-8"
            )
            results.append(result)
    statuses = [item["status"] for item in results]
    status = "success" if all(item == "success" for item in statuses) else "partial_failure"
    run_result = {"task_id": task_id, "input": str(input_path), "workspace": str(workspace), "status": status, "videos": results}
    serialized = json.dumps(_json_value(run_result), ensure_ascii=False, indent=2)
    (workspace / "run.json").write_text(serialized, encoding="utf-8")
    (workspace / "manifest.json").write_text(serialized, encoding="utf-8")
    return run_result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Prepare local videos for BiliNote note generation.")
    parser.add_argument("--input", required=True, help="local video file or recursive video folder")
    parser.add_argument("--workspace", default=str(PROJECT_ROOT), help="workspace root for .bilinote-video-notes")
    parser.add_argument("--model", default="base")
    parser.add_argument("--frame-interval", type=int, default=2)
    parser.add_argument("--grid-size", default="3x3", help="grid columns x rows, e.g. 3x3")
    parser.add_argument("--task-id", default=None)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        match = re.fullmatch(r"(\d+)x(\d+)", args.grid_size.lower())
        if not match or int(match.group(1)) <= 0 or int(match.group(2)) <= 0:
            raise ValueError("--grid-size must be CxR with positive dimensions")
        if int(args.frame_interval) <= 0:
            raise ValueError("--frame-interval must be positive")
        if not args.model.strip():
            raise ValueError("--model must not be empty")
        result = run_task(
            args.input,
            args.workspace,
            args.task_id,
            model=args.model,
            frame_interval=args.frame_interval,
            grid_size=(int(match.group(1)), int(match.group(2))),
        )
        return 0 if result["status"] == "success" else 1
    except (FileNotFoundError, InputError, ValueError) as exc:
        print(f"input/parameter error: {exc}", file=sys.stderr)
        return 2
    except Exception as exc:
        print(f"processing failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
