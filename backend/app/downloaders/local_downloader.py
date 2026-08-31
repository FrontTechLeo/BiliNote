import hashlib
import os
import subprocess
import threading
from collections import defaultdict
from abc import ABC
from typing import Optional

from app.downloaders.base import Downloader
from app.enmus.note_enums import DownloadQuality
from app.models.audio_model import AudioDownloadResult

from app.utils.path_helper import get_app_dir
from app.utils.runtime_paths import get_uploads_dir
from app.utils.video_helper import save_cover_to_static


_AUDIO_EXTENSIONS = {".aac", ".flac", ".m4a", ".mp3", ".ogg", ".opus", ".wav", ".wma"}
_OUTPUT_LOCKS: defaultdict[str, threading.Lock] = defaultdict(threading.Lock)


def _path_key(path: str) -> str:
    return os.path.normcase(os.path.abspath(path))


class LocalDownloader(Downloader, ABC):
    def __init__(self):

        super().__init__()

    @staticmethod
    def _resolve_local_path(video_url: str) -> str:
        if video_url.startswith('/uploads/'):
            video_url = os.path.join(get_uploads_dir(), video_url[len('/uploads/'):])
        return os.path.normpath(video_url)

    @staticmethod
    def _is_audio_path(input_path: str) -> bool:
        return os.path.splitext(input_path)[1].lower() in _AUDIO_EXTENSIONS

    @staticmethod
    def _has_output(path: str) -> bool:
        try:
            return os.path.isfile(path) and os.path.getsize(path) > 0
        except OSError:
            return False


    def extract_cover(self, input_path: str, output_dir: Optional[str] = None) -> str:
        """
        从本地视频文件中提取一张封面图（默认取第一帧）
        :param input_path: 输入视频路径
        :param output_dir: 输出目录，默认和视频同目录
        :return: 提取出的封面图片路径
        """
        if not os.path.exists(input_path):
            raise FileNotFoundError(f"输入文件不存在: {input_path}")

        if output_dir is None:
            output_dir = os.path.dirname(input_path)

        base_name = os.path.splitext(os.path.basename(input_path))[0]
        output_path = os.path.join(output_dir, f"{base_name}_cover.jpg")

        with _OUTPUT_LOCKS[_path_key(output_path)]:
            if self._has_output(output_path):
                return output_path
            try:
                command = [
                    'ffmpeg',
                    '-i', input_path,
                    '-ss', '00:00:01',  # 跳到视频第1秒，防止黑屏
                    '-vframes', '1',  # 只截取一帧
                    '-q:v', '2',  # 输出质量高一点（qscale，2是很高）
                    '-y',  # 覆盖
                    output_path
                ]
                subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)

                if not self._has_output(output_path):
                    raise RuntimeError(f"封面图片生成失败: {output_path}")

                return output_path
            except subprocess.CalledProcessError as e:
                raise RuntimeError(f"提取封面失败: {output_path}") from e

    def convert_to_mp3(self, input_path: str, output_path: str = None) -> str:
        """
        将本地视频文件转为 MP3 音频文件
        :param input_path: 输入文件路径（如 .mp4）
        :param output_path: 输出文件路径（可选，默认放到数据目录的 audio 缓存，避免写用户的源文件夹）
        :return: 生成的 mp3 文件路径
        """
        if not os.path.exists(input_path):
            raise FileNotFoundError(f"输入文件不存在: {input_path}")

        if output_path is None:
            digest = hashlib.md5(_path_key(input_path).encode("utf-8")).hexdigest()[:8]
            cache_dir = get_app_dir(os.path.join("audio", digest))
            output_path = os.path.join(cache_dir, os.path.splitext(os.path.basename(input_path))[0] + ".mp3")

        if _path_key(input_path) == _path_key(output_path):
            if os.path.splitext(input_path)[1].lower() == ".mp3":
                return input_path
            raise ValueError(f"音频转换的输入和输出不能相同: {input_path}")

        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
        try:
            with _OUTPUT_LOCKS[_path_key(output_path)]:
                if self._has_output(output_path):
                    return output_path

                command = [
                    'ffmpeg',
                    '-i', input_path,
                    '-vn',  # 不要视频流
                    '-acodec', 'libmp3lame',  # 使用mp3编码
                    '-y',  # 覆盖输出文件
                    output_path
                ]

                subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)

                if not self._has_output(output_path):
                    raise RuntimeError(f"mp3 文件生成失败: {output_path}")

                return output_path
        except subprocess.CalledProcessError as e:
            detail = e.stderr.decode(errors="replace").strip() if isinstance(e.stderr, bytes) else str(e.stderr or "")
            suffix = f": {detail}" if detail else ""
            raise RuntimeError(f"mp3 文件生成失败: {output_path}{suffix}") from e

    def download_video(self, video_url: str, output_dir: str = None) -> str:
        """
        处理本地文件路径，返回视频文件路径
        """
        video_url = self._resolve_local_path(video_url)

        if not os.path.exists(video_url):
            raise FileNotFoundError()
        if self._is_audio_path(video_url):
            raise ValueError(f"本地文件不是视频，无法进行截图或视频理解: {video_url}")
        return video_url

    def download(
            self,
            video_url: str,
            output_dir: str = None,
            quality: DownloadQuality = "fast",
            need_video: Optional[bool] = False,
            skip_download: bool = False,
    ) -> AudioDownloadResult:
        """
        处理本地文件路径，返回音频元信息
        """
        video_url = self._resolve_local_path(video_url)

        if not os.path.exists(video_url):
            raise FileNotFoundError(f"本地文件不存在: {video_url}")

        file_name = os.path.basename(video_url)
        title, _ = os.path.splitext(file_name)
        print(title, file_name,video_url)
        if self._is_audio_path(video_url):
            if need_video:
                raise ValueError(f"本地文件不是视频，无法进行截图或视频理解: {video_url}")
            file_path = video_url if os.path.splitext(video_url)[1].lower() == ".mp3" else self.convert_to_mp3(video_url)
            cover_url = None
        else:
            file_path = self.convert_to_mp3(video_url)
            # 封面提到数据目录的 cover 缓存里，避免往用户的源视频文件夹写文件；
            # 按源路径哈希分目录，防止同名视频封面互相覆盖。
            digest = hashlib.md5(_path_key(video_url).encode("utf-8")).hexdigest()[:8]
            cover_path = self.extract_cover(video_url, output_dir=get_app_dir(os.path.join("covers", digest)))
            cover_url = save_cover_to_static(cover_path)

        print('file——path',file_path)
        return AudioDownloadResult(
            file_path=file_path,
            title=title,
            duration=0,  # 可选：后续加上读取时长
            cover_url=cover_url,  # 暂无封面
            platform="local",
            video_id=title,
            raw_info={
                'path':  file_path
            },
            video_path=None
        )
