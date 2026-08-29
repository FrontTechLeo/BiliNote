import base64
import hashlib
import io
import os
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
import ffmpeg
from PIL import Image, ImageDraw, ImageFont

from app.utils.logger import get_logger
from app.utils.path_helper import get_app_dir

logger = get_logger(__name__)


DEFAULT_MAX_IMAGE_BYTES = 8 * 1024 * 1024
PROVIDER_MAX_IMAGE_BYTES = 30 * 1024 * 1024
DEFAULT_IMAGE_COMPRESSION = "high"
IMAGE_COMPRESSION_PROFILES = {
    "high": {
        "format": "JPEG",
        "mime_type": "image/jpeg",
        "max_size": (1280, 720),
        "quality": 70,
        "max_bytes": DEFAULT_MAX_IMAGE_BYTES,
    },
    "medium": {
        "format": "JPEG",
        "mime_type": "image/jpeg",
        "max_size": (1600, 900),
        "quality": 82,
        "max_bytes": 16 * 1024 * 1024,
    },
    "original": {
        "format": "PNG",
        "mime_type": "image/png",
        "max_size": None,
        "quality": None,
        "max_bytes": None,
    },
}
IMAGE_COMPRESSION_ALIASES = {
    "highest": "original",
    "lossless": "original",
    "none": "original",
}


def normalize_image_compression(value: str | None) -> str:
    normalized = str(value or DEFAULT_IMAGE_COMPRESSION).strip().lower()
    normalized = IMAGE_COMPRESSION_ALIASES.get(normalized, normalized)
    if normalized not in IMAGE_COMPRESSION_PROFILES:
        choices = ", ".join(IMAGE_COMPRESSION_PROFILES)
        raise ValueError(f"image_compression 必须是以下值之一：{choices}")
    return normalized


class VideoReader:
    def __init__(self,
                 video_path: str,
                 grid_size=(3, 3),
                 frame_interval=2,
                 dedupe_enabled=True,
                 unit_width=960,
                 unit_height=540,
                 save_quality=90,
                 image_compression=DEFAULT_IMAGE_COMPRESSION,
                 font_path="fonts/arial.ttf",
                 frame_dir=None,
                 grid_dir=None):
        self.video_path = video_path
        self.grid_size = grid_size
        self.frame_interval = frame_interval
        self.dedupe_enabled = dedupe_enabled
        self.unit_width = unit_width
        self.unit_height = unit_height
        self.save_quality = save_quality
        self.image_compression = normalize_image_compression(image_compression)
        self.frame_dir = frame_dir or get_app_dir("output_frames")
        self.grid_dir = grid_dir or get_app_dir("grid_output")
        print(f"视频路径：{video_path}",self.frame_dir,self.grid_dir)
        self.font_path = font_path

    def _max_image_bytes(self) -> int:
        default_max_bytes = IMAGE_COMPRESSION_PROFILES[self.image_compression]["max_bytes"]
        configured = os.getenv("OPENAI_MAX_IMAGE_BYTES", str(default_max_bytes))
        try:
            max_bytes = int(configured)
        except ValueError as exc:
            raise ValueError("OPENAI_MAX_IMAGE_BYTES 必须是正整数") from exc
        if max_bytes <= 0:
            raise ValueError("OPENAI_MAX_IMAGE_BYTES 必须是正整数")
        return max_bytes

    @staticmethod
    def _encode_jpeg(image: Image.Image, quality: int) -> bytes:
        buffer = io.BytesIO()
        image.save(
            buffer,
            format="JPEG",
            quality=quality,
            optimize=True,
            progressive=True,
        )
        return buffer.getvalue()

    def _encode_image_with_limit(self, path: str, max_bytes: int) -> bytes:
        original_size = os.path.getsize(path)
        if original_size <= max_bytes:
            with open(path, "rb") as img_file:
                return img_file.read()

        with Image.open(path) as source:
            image = source.convert("RGB")

        # 先降低 JPEG 质量，仍然超限时再逐步缩小网格，确保不会把
        # 单张图片原样交给供应商。8MB 的默认上限远低于 OpenRouter 的
        # 30MB 单图限制，也为 Base64 和请求 JSON 留出余量。
        try:
            while True:
                for quality in (85, 75, 65, 55, 45, 35):
                    encoded = self._encode_jpeg(image, quality)
                    if len(encoded) <= max_bytes:
                        logger.info(
                            "图片 %s 从 %.2fMB 压缩至 %.2fMB",
                            path,
                            original_size / (1024 * 1024),
                            len(encoded) / (1024 * 1024),
                        )
                        return encoded

                width, height = image.size
                if max(width, height) <= 320:
                    raise ValueError(
                        f"图片 {path} 在最小尺寸下仍超过 {max_bytes} 字节"
                    )
                resized = image.copy()
                resized.thumbnail(
                    (max(320, int(width * 0.8)), max(320, int(height * 0.8))),
                    Image.Resampling.LANCZOS,
                )
                image.close()
                image = resized
        finally:
            image.close()

    def _encode_original_png(self, path: str) -> bytes:
        if os.path.getsize(path) <= PROVIDER_MAX_IMAGE_BYTES and path.lower().endswith(".png"):
            with open(path, "rb") as img_file:
                image_bytes = img_file.read()
        else:
            with Image.open(path) as source:
                image = source.convert("RGB")
            try:
                image_bytes = io.BytesIO()
                image.save(image_bytes, format="PNG", optimize=True)
                image_bytes = image_bytes.getvalue()
            finally:
                image.close()

        if len(image_bytes) > PROVIDER_MAX_IMAGE_BYTES:
            raise ValueError(
                f"原图 {path} 的 PNG 大小为 {len(image_bytes) / (1024 * 1024):.2f}MB，"
                "超过供应商单图 30MB 限制，请选择 high 或 medium"
            )
        return image_bytes

    @staticmethod
    def _calculate_file_md5(file_path: str) -> str:
        hasher = hashlib.md5()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(8192), b""):
                hasher.update(chunk)
        return hasher.hexdigest()

    def format_time(self, seconds: float) -> str:
        mm = int(seconds // 60)
        ss = int(seconds % 60)
        return f"{mm:02d}_{ss:02d}"

    def extract_time_from_filename(self, filename: str) -> float:
        match = re.search(r"frame_(\d{2})_(\d{2})\.jpg", filename)
        if match:
            mm, ss = map(int, match.groups())
            return mm * 60 + ss
        return float('inf')

    def _extract_single_frame(self, ts: int) -> str | None:
        """提取单帧，返回输出路径或 None（失败时）。"""
        time_label = self.format_time(ts)
        output_path = os.path.join(self.frame_dir, f"frame_{time_label}.jpg")
        cmd = ["ffmpeg", "-ss", str(ts), "-i", self.video_path, "-frames:v", "1", "-q:v", "2", "-y", output_path,
               "-hide_banner", "-loglevel", "error"]
        try:
            subprocess.run(cmd, check=True)
            return output_path
        except subprocess.CalledProcessError:
            return None

    def extract_frames(self, max_frames=1000) -> list[str]:

        try:
            os.makedirs(self.frame_dir, exist_ok=True)
            duration = float(ffmpeg.probe(self.video_path)["format"]["duration"])
            timestamps = [i for i in range(0, int(duration), self.frame_interval)][:max_frames]

            # 并行提取帧
            max_workers = min(os.cpu_count() or 4, 8, len(timestamps))
            frame_results: dict[int, str | None] = {}
            with ThreadPoolExecutor(max_workers=max_workers) as pool:
                futures = {pool.submit(self._extract_single_frame, ts): ts for ts in timestamps}
                for future in as_completed(futures):
                    ts = futures[future]
                    frame_results[ts] = future.result()

            # 按时间戳顺序整理结果，并进行去重
            image_paths = []
            last_hash = None
            for ts in timestamps:
                output_path = frame_results.get(ts)
                if not output_path or not os.path.exists(output_path):
                    continue

                if self.dedupe_enabled:
                    frame_hash = self._calculate_file_md5(output_path)
                    if frame_hash == last_hash:
                        os.remove(output_path)
                        continue
                    last_hash = frame_hash

                image_paths.append(output_path)
            return image_paths
        except Exception as e:
            logger.error(f"分割帧发生错误：{str(e)}")
            raise ValueError("视频处理失败")

    def group_images(self) -> list[list[str]]:
        image_files = [os.path.join(self.frame_dir, f) for f in os.listdir(self.frame_dir) if
                       f.startswith("frame_") and f.endswith(".jpg")]
        image_files.sort(key=lambda f: self.extract_time_from_filename(os.path.basename(f)))
        group_size = self.grid_size[0] * self.grid_size[1]
        return [image_files[i:i + group_size] for i in range(0, len(image_files), group_size)]

    def concat_images(self, image_paths: list[str], name: str) -> str:
        os.makedirs(self.grid_dir, exist_ok=True)
        font = ImageFont.truetype(self.font_path, 48) if os.path.exists(self.font_path) else ImageFont.load_default()
        images = []

        for path in image_paths:
            img = Image.open(path).convert("RGB").resize((self.unit_width, self.unit_height), Image.Resampling.LANCZOS)
            timestamp = re.search(r"frame_(\d{2})_(\d{2})\.jpg", os.path.basename(path))
            time_text = f"{timestamp.group(1)}:{timestamp.group(2)}" if timestamp else ""
            draw = ImageDraw.Draw(img)
            draw.text((10, 10), time_text, fill="yellow", font=font, stroke_width=1, stroke_fill="black")
            images.append(img)

        cols, rows = self.grid_size
        grid_img = Image.new("RGB", (self.unit_width * cols, self.unit_height * rows), (255, 255, 255))

        for i, img in enumerate(images):
            x = (i % cols) * self.unit_width
            y = (i // cols) * self.unit_height
            grid_img.paste(img, (x, y))

        profile = IMAGE_COMPRESSION_PROFILES[self.image_compression]
        if profile["max_size"]:
            grid_img.thumbnail(profile["max_size"], Image.Resampling.LANCZOS)

        extension = ".png" if profile["format"] == "PNG" else ".jpg"
        save_path = os.path.join(self.grid_dir, f"{name}{extension}")
        if profile["format"] == "PNG":
            grid_img.save(save_path, format="PNG", optimize=True)
        else:
            grid_img.save(
                save_path,
                format="JPEG",
                quality=min(self.save_quality, profile["quality"]),
                optimize=True,
                progressive=True,
            )
        return save_path

    def encode_images_to_base64(self, image_paths: list[str]) -> list[str]:
        profile = IMAGE_COMPRESSION_PROFILES[self.image_compression]
        max_bytes = self._max_image_bytes() if profile["format"] == "JPEG" else None
        base64_images = []
        for path in image_paths:
            image_bytes = (
                self._encode_image_with_limit(path, max_bytes)
                if max_bytes is not None
                else self._encode_original_png(path)
            )
            encoded_string = base64.b64encode(image_bytes).decode("utf-8")
            base64_images.append(f"data:{profile['mime_type']};base64,{encoded_string}")
        return base64_images

    def run(self)->list[str]:
        logger.info("开始提取视频帧...")
        try:
            # 确保目录存在
            print(self.frame_dir,self.grid_dir)
            os.makedirs(self.frame_dir, exist_ok=True)
            os.makedirs(self.grid_dir, exist_ok=True)
            #清空帧文件夹
            for file in os.listdir(self.frame_dir):
                if file.startswith("frame_"):
                    os.remove(os.path.join(self.frame_dir, file))
            print(self.frame_dir,self.grid_dir)
            #清空网格文件夹
            for file in os.listdir(self.grid_dir):
                if file.startswith("grid_"):
                    os.remove(os.path.join(self.grid_dir, file))
            print(self.frame_dir,self.grid_dir)
            self.extract_frames()
            print("2#3",self.frame_dir,self.grid_dir)
            logger.info("开始拼接网格图...")
            image_paths = []
            groups = self.group_images()
            for idx, group in enumerate(groups, start=1):
                if len(group) < self.grid_size[0] * self.grid_size[1]:
                    logger.warning(f"⚠️ 跳过第 {idx} 组，图片不足 {self.grid_size[0] * self.grid_size[1]} 张")
                    continue
                out_path = self.concat_images(group, f"grid_{idx}")
                image_paths.append(out_path)

            logger.info("📤 开始编码图像...")
            urls = self.encode_images_to_base64(image_paths)
            return urls
        except Exception as e:
            logger.error(f"发生错误：{str(e)}")
            if isinstance(e, ValueError):
                raise
            raise ValueError("视频处理失败")


