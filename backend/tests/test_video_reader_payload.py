import base64
import io
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image

from app.utils.video_reader import VideoReader


IMAGE_LIMIT_BYTES = 8 * 1024 * 1024


def _write_jpeg(path: pathlib.Path, size: tuple[int, int], quality: int) -> int:
    image = Image.effect_noise(size, 100).convert("RGB")
    image.save(path, format="JPEG", quality=quality)
    return path.stat().st_size


def _decode_data_url(data_url: str) -> bytes:
    header, encoded = data_url.split(",", 1)
    if header != "data:image/jpeg;base64":
        raise AssertionError(f"unexpected image data URL header: {header}")
    return base64.b64decode(encoded)


class TestVideoReaderPayload(unittest.TestCase):
    def _make_frame_paths(self, tmp_dir: str) -> list[str]:
        paths = []
        for index in range(4):
            path = pathlib.Path(tmp_dir) / f"frame_00_{index:02d}.jpg"
            Image.new("RGB", (960, 540), (index * 40, 80, 120)).save(path, format="JPEG")
            paths.append(str(path))
        return paths

    def test_high_profile_outputs_720p_jpeg(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            reader = VideoReader(
                video_path="unused.mp4",
                grid_size=(2, 2),
                image_compression="high",
                frame_dir=str(pathlib.Path(tmp_dir) / "frames"),
                grid_dir=str(pathlib.Path(tmp_dir) / "grids"),
            )
            output = reader.concat_images(self._make_frame_paths(tmp_dir), "grid_1")

            with Image.open(output) as image:
                self.assertEqual(image.format, "JPEG")
                self.assertEqual(image.size, (1280, 720))
            self.assertTrue(reader.encode_images_to_base64([output])[0].startswith("data:image/jpeg;base64,"))

    def test_original_profile_preserves_grid_resolution_as_png(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            reader = VideoReader(
                video_path="unused.mp4",
                grid_size=(2, 2),
                image_compression="original",
                frame_dir=str(pathlib.Path(tmp_dir) / "frames"),
                grid_dir=str(pathlib.Path(tmp_dir) / "grids"),
            )
            output = reader.concat_images(self._make_frame_paths(tmp_dir), "grid_1")

            with Image.open(output) as image:
                self.assertEqual(image.format, "PNG")
                self.assertEqual(image.size, (1920, 1080))
            self.assertTrue(reader.encode_images_to_base64([output])[0].startswith("data:image/png;base64,"))

    def test_medium_profile_outputs_intermediate_jpeg_size(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            reader = VideoReader(
                video_path="unused.mp4",
                grid_size=(2, 2),
                image_compression="medium",
                frame_dir=str(pathlib.Path(tmp_dir) / "frames"),
                grid_dir=str(pathlib.Path(tmp_dir) / "grids"),
            )
            output = reader.concat_images(self._make_frame_paths(tmp_dir), "grid_1")

            with Image.open(output) as image:
                self.assertEqual(image.format, "JPEG")
                self.assertEqual(image.size, (1600, 900))

    def test_oversized_jpeg_is_compressed_below_single_image_limit(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            image_path = pathlib.Path(tmp_dir) / "large.jpg"
            source_size = _write_jpeg(image_path, (3000, 2000), quality=100)
            self.assertGreater(source_size, IMAGE_LIMIT_BYTES)

            reader = VideoReader(
                video_path="unused.mp4",
                frame_dir=str(pathlib.Path(tmp_dir) / "frames"),
                grid_dir=str(pathlib.Path(tmp_dir) / "grids"),
            )
            with patch.dict(os.environ, {"OPENAI_MAX_IMAGE_BYTES": str(IMAGE_LIMIT_BYTES)}):
                encoded = reader.encode_images_to_base64([str(image_path)])

            payload = _decode_data_url(encoded[0])
            self.assertLess(len(payload), IMAGE_LIMIT_BYTES)
            with Image.open(io.BytesIO(payload)) as compressed:
                self.assertEqual(compressed.format, "JPEG")

    def test_small_jpeg_is_encoded_as_valid_data_url(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            image_path = pathlib.Path(tmp_dir) / "small.jpg"
            source_size = _write_jpeg(image_path, (64, 64), quality=80)
            self.assertLess(source_size, IMAGE_LIMIT_BYTES)

            reader = VideoReader(
                video_path="unused.mp4",
                frame_dir=str(pathlib.Path(tmp_dir) / "frames"),
                grid_dir=str(pathlib.Path(tmp_dir) / "grids"),
            )
            with patch.dict(os.environ, {"OPENAI_MAX_IMAGE_BYTES": str(IMAGE_LIMIT_BYTES)}):
                encoded = reader.encode_images_to_base64([str(image_path)])

            payload = _decode_data_url(encoded[0])
            self.assertEqual(payload, image_path.read_bytes())


if __name__ == "__main__":
    unittest.main()
