from pathlib import Path

from validate_note_format import validate_note


def _write_note(tmp_path: Path, body: str) -> Path:
    (tmp_path / "frames").mkdir()
    (tmp_path / "frames" / "frame_00_00.jpg").write_bytes(b"fake image")
    note = tmp_path / "note.md"
    note.write_text(body, encoding="utf-8")
    return note


VALID_NOTE = """# 示例视频

## 目录
- [开场](#开场)
- [总结](#ai-总结)

## 开场 (00:01)*
### 第一步
![](frames/frame_00_00.jpg)*

## AI 总结
这是一个合法样本。
"""


def test_validate_note_accepts_valid_note(tmp_path: Path):
    assert validate_note(_write_note(tmp_path, VALID_NOTE)) == []


def test_validate_note_rejects_missing_native_structure(tmp_path: Path):
    note = _write_note(tmp_path, "# 标题\n\n正文\n")

    errors = validate_note(note)

    assert any("目录" in error for error in errors)
    assert any("锚点" in error for error in errors)
    assert any("主章节" in error for error in errors)
    assert any("子章节" in error for error in errors)
    assert any("AI 总结" in error for error in errors)


def test_validate_note_rejects_missing_image_and_external_image(tmp_path: Path):
    body = VALID_NOTE.replace("frames/frame_00_00.jpg", "frames/missing.jpg")
    body += "\n![](https://example.com/image.jpg)\n"
    note = _write_note(tmp_path, body)

    errors = validate_note(note)

    assert any("不存在" in error for error in errors)
    assert any("外链" in error for error in errors)


def test_validate_note_rejects_screenshot_marker(tmp_path: Path):
    note = _write_note(tmp_path, VALID_NOTE + "\nScreenshot-2026-08-17.png\n")

    errors = validate_note(note)

    assert any("Screenshot-" in error for error in errors)
