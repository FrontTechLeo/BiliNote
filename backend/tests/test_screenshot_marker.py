import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "app" / "utils" / "screenshot_marker.py"
spec = importlib.util.spec_from_file_location("screenshot_marker", MODULE_PATH)
if spec is None or spec.loader is None:
    raise ImportError("screenshot_marker module spec not found")
screenshot_marker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(screenshot_marker)
extract_screenshot_timestamps = screenshot_marker.extract_screenshot_timestamps


class TestScreenshotMarker(unittest.TestCase):
    def test_extract_accepts_star_bracket_format(self):
        markdown = "A\n*Screenshot-[01:02]\nB"
        matches = extract_screenshot_timestamps(markdown)
        self.assertEqual(matches, [("*Screenshot-[01:02]", 62)])

    def test_extract_accepts_legacy_formats(self):
        markdown = "*Screenshot-03:04 and Screenshot-[05:06]"
        matches = extract_screenshot_timestamps(markdown)
        self.assertEqual(
            matches,
            [
                ("*Screenshot-03:04", 184),
                ("Screenshot-[05:06]", 306),
            ],
        )

    def test_extract_wrapped_image_format(self):
        """LLM sometimes wraps the marker inside image syntax: ![alt](*Screenshot-[mm:ss]*)"""
        markdown = "![AI 生成内容示例](*Screenshot-[00:06]*)"
        matches = extract_screenshot_timestamps(markdown)
        self.assertEqual(matches, [("![AI 生成内容示例](*Screenshot-[00:06]*)", 6)])

    def test_extract_mixed_wrapped_and_standalone(self):
        """Wrapped and standalone markers should coexist, returned in document order."""
        markdown = (
            "![alt1](*Screenshot-[00:06]*)\n"
            "*Screenshot-[01:00]\n"
            "![alt2](*Screenshot-[03:00]*)"
        )
        matches = extract_screenshot_timestamps(markdown)
        self.assertEqual(
            matches,
            [
                ("![alt1](*Screenshot-[00:06]*)", 6),
                ("*Screenshot-[01:00]", 60),
                ("![alt2](*Screenshot-[03:00]*)", 180),
            ],
        )


if __name__ == "__main__":
    unittest.main()
