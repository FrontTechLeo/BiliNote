"""Validate the required structure and local assets of a BiliNote Markdown note."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path, PureWindowsPath
from urllib.parse import urlsplit


IMAGE_RE = re.compile(r"!\[[^\]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)")
TOC_LINK_RE = re.compile(r"^- \[[^\]]+\]\(#[^)]+\)\s*$")
MAIN_CHAPTER_RE = re.compile(r"^## .+ \(\d{2}:\d{2}\)\*$")
REQUIRED_H1_RE = re.compile(r"^#(?!#)\s+\S.*$")


def _read_note(path: Path) -> tuple[str | None, list[str]]:
    if not path.exists():
        return None, [f"文件不存在: {path}"]
    if not path.is_file():
        return None, [f"路径不是文件: {path}"]
    try:
        return path.read_text(encoding="utf-8"), []
    except UnicodeDecodeError:
        return None, ["文件不是有效的 UTF-8 编码"]
    except OSError as exc:
        return None, [f"文件无法读取: {exc}"]


def _is_external(target: str) -> bool:
    parsed = urlsplit(target)
    return parsed.scheme.lower() in {"http", "https", "file", "data"} or target.startswith("//")


def _is_absolute(target: str) -> bool:
    return Path(target).is_absolute() or PureWindowsPath(target).is_absolute()


def validate_note(path: str | Path) -> list[str]:
    """Return a list of format errors; an empty list means the note is valid."""
    note_path = Path(path).expanduser()
    content, errors = _read_note(note_path)
    if content is None:
        return errors

    lines = content.splitlines()
    non_empty_lines = [line for line in lines if line.strip()]
    if not non_empty_lines or not REQUIRED_H1_RE.fullmatch(non_empty_lines[0]):
        errors.append("首个非空行必须是单独的 H1 标题")
    if "## 目录" not in lines:
        errors.append("缺少 ## 目录")
    if not any(TOC_LINK_RE.fullmatch(line) for line in lines):
        errors.append("目录中至少需要一个本地 Markdown 锚点链接")
    if not any(MAIN_CHAPTER_RE.fullmatch(line) for line in lines):
        errors.append("至少需要一个带时间戳的主章节")
    if not any(line.startswith("### ") for line in lines):
        errors.append("至少需要一个 ### 子章节")
    if "## AI 总结" not in lines:
        errors.append("缺少 ## AI 总结")
    if "Screenshot-" in content:
        errors.append("不得包含 Screenshot- marker")

    image_targets = IMAGE_RE.findall(content)
    if not any(re.fullmatch(r"(?:frames|grids)/[^)\s]+\.jpg", target, re.IGNORECASE) for target in image_targets):
        errors.append("至少需要一张 frames/ 或 grids/ 下的本地 JPG 图片")
    for target in image_targets:
        if _is_external(target):
            errors.append(f"图片禁止使用外链: {target}")
            continue
        if _is_absolute(target):
            errors.append(f"图片路径必须是相对路径: {target}")
            continue
        image_path = note_path.parent / Path(target)
        if not image_path.is_file():
            errors.append(f"图片文件不存在: {target}")

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate a BiliNote Markdown note.")
    parser.add_argument("--note", required=True, help="Markdown note path")
    args = parser.parse_args(argv)
    errors = validate_note(args.note)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print("note format is valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
