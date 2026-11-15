"""基于本机 agent CLI（zcode cli 等）的 GPT 实现。

与 UniversalGPT 的差异：
- 不构造 OpenAI client：完整提示词写入工作目录 prompt.md，agent 读取后返回
  最终 markdown；视频理解截图以本地图片文件形式提供（agent 直接读图）
- 单次调用完成总结（不分区/不分块/无 checkpoint）：CLI 模式传文件路径，
  无 OpenAI 24MB 请求体限制；断点续传仅对多次调用流程有意义
- 任务取消通过看护线程终止子进程实现（见 base.run_cli）
"""
import base64
import re
import shutil
from datetime import timedelta
from pathlib import Path
from uuid import uuid4

from app.gpt.base import GPT
from app.gpt.prompt_builder import generate_base_prompt
from app.gpt.agent_cli.base import TaskCancelled, run_cli
from app.gpt.agent_cli.registry import DEFAULT_AGENT_KIND, resolve
from app.models.gpt_model import GPTSource
from app.models.transcriber_model import TranscriptSegment
from app.utils.logger import get_logger
from app.utils.runtime_paths import get_note_output_dir

logger = get_logger(__name__)

_DATA_URL_RE = re.compile(r"^data:image/(\w+);base64,")


class AgentCliGPT(GPT):
    """agent CLI 型 GPT 后端，实现 GPT ABC 契约（summarize/create_messages/list_models）。"""

    def __init__(self, config):
        self.config = config
        self.agent_kind = getattr(config, "agent_kind", "") or DEFAULT_AGENT_KIND
        self.impl = resolve(self.agent_kind)
        self.model = (config.model_name or "").strip() or self.impl.default_models[0]
        # 保留字段：CLI 模式温度由 agent 侧决定，仅占位以兼容既有调用方
        self.temperature = 0.7

    # ---- GPT 契约 ----

    def list_models(self):
        return self.impl.list_models()

    def create_messages(self, segments, **kwargs):
        """构造扁平化提示词（返回 OpenAI 消息结构仅用于日志/兼容）。"""
        content_text = generate_base_prompt(
            title=kwargs.get("title"),
            segment_text=self._build_segment_text(segments),
            tags=kwargs.get("tags"),
            _format=kwargs.get("_format"),
            style=kwargs.get("style"),
            extras=kwargs.get("extras"),
        )
        prompt = self._build_agent_prompt(content_text, kwargs.get("image_files", []))
        return [{"role": "user", "content": prompt}]

    def summarize(self, source: GPTSource) -> str:
        self._raise_if_cancelled(source.cancel_check)
        source.segment = self.ensure_segments_type(source.segment)

        workdir = self._workspace_dir(source.checkpoint_key)
        image_files = self._write_image_files(workdir, source.video_img_urls)
        messages = self.create_messages(
            source.segment,
            title=source.title,
            tags=source.tags,
            _format=source._format,
            style=source.style,
            extras=source.extras,
            image_files=image_files,
        )
        prompt_file = workdir / "prompt.md"
        prompt_file.write_text(messages[0]["content"], encoding="utf-8")
        logger.info(
            f"[agent_cli:{self.impl.name}] summarize 开始: workdir={workdir}, "
            f"图片 {len(image_files)} 张, prompt {prompt_file.stat().st_size} bytes"
        )

        try:
            stdout = run_cli(
                self.impl,
                prompt_file=prompt_file,
                workdir=workdir,
                model=self._cli_model_name(),
                cancel_check=source.cancel_check,
                attach_files=image_files,
            )
            markdown = self.impl.parse_output(stdout)
        except Exception:
            logger.error(
                f"[agent_cli:{self.impl.name}] 总结失败，工作目录保留用于排查: {workdir}"
            )
            raise
        shutil.rmtree(workdir, ignore_errors=True)
        return markdown

    def complete_text(self, prompt: str, cancel_check=None) -> str:
        """单轮文本补全（agent CLI 简化版问答等场景）。"""
        self._raise_if_cancelled(cancel_check)
        workdir = self._workspace_dir(f"chat-{uuid4().hex}")
        prompt_file = workdir / "prompt.md"
        prompt_file.write_text(prompt, encoding="utf-8")
        try:
            stdout = run_cli(
                self.impl,
                prompt_file=prompt_file,
                workdir=workdir,
                model=self._cli_model_name(),
                cancel_check=cancel_check,
            )
            result = self.impl.parse_output(stdout)
        except Exception:
            logger.error(
                f"[agent_cli:{self.impl.name}] 补全失败，工作目录保留用于排查: {workdir}"
            )
            raise
        shutil.rmtree(workdir, ignore_errors=True)
        return result

    # ---- 内部实现 ----

    def _cli_model_name(self):
        """具体模型名传给 CLI 的 --model；静态别名/空值表示用 CLI 默认模型。"""
        if self.model in self.impl.default_models:
            return None
        return self.model

    def _workspace_dir(self, task_key) -> Path:
        base = Path(get_note_output_dir()) / "agent_cli"
        safe = "".join(
            ch if ch.isalnum() or ch in ("-", "_") else "_" for ch in str(task_key or uuid4().hex)
        )
        workdir = base / safe
        workdir.mkdir(parents=True, exist_ok=True)
        return workdir

    def _write_image_files(self, workdir: Path, video_img_urls) -> list:
        """把 GPTSource.video_img_urls 的 base64 data URL 解码为本地图片文件。"""
        files = []
        for idx, url in enumerate(video_img_urls or [], 1):
            if not isinstance(url, str):
                continue
            match = _DATA_URL_RE.match(url)
            if not match:
                logger.warning(f"[agent_cli:{self.impl.name}] 跳过非 data URL 的图片输入 #{idx}")
                continue
            ext = match.group(1).lower()
            ext = {"jpg": "jpeg"}.get(ext, ext)
            payload = url.split(",", 1)[1] if "," in url else ""
            image_path = workdir / f"img_{idx:02d}.{ext}"
            image_path.write_bytes(base64.b64decode(payload))
            files.append(image_path)
        return files

    def _build_agent_prompt(self, content_text: str, image_files: list) -> str:
        sections = [content_text]
        if image_files:
            listing = "\n".join(f"{i}. {p.name}" for i, p in enumerate(image_files, 1))
            sections.append(
                "\n\n--- 视频关键帧截图（视频理解输入） ---\n"
                f"本次任务附带 {len(image_files)} 张视频关键帧网格图：每张图由多个单元"
                "拼接而成，每个单元左上角标注了对应的 mm:ss 时间戳。图片已随命令附加，"
                f"对应文件亦位于当前工作目录：\n{listing}\n"
                "请逐张查看并结合字幕内容完成视频内容理解后再作答。"
            )
        sections.append(
            "\n\n--- 输出要求 ---\n"
            "仅输出最终笔记 markdown 正文本身；不要输出任何解释性文字；"
            "不要把正文包裹在代码围栏中；不要修改、创建或删除任何文件。"
        )
        return "\n".join(sections)

    @staticmethod
    def _format_time(seconds: float) -> str:
        return str(timedelta(seconds=int(seconds)))[2:]

    @classmethod
    def _build_segment_text(cls, segments) -> str:
        return "\n".join(
            f"{cls._format_time(seg.start)} - {seg.text.strip()}" for seg in segments
        )

    @staticmethod
    def ensure_segments_type(segments) -> list:
        return [
            TranscriptSegment(**seg) if isinstance(seg, dict) else seg for seg in segments
        ]

    @staticmethod
    def _raise_if_cancelled(cancel_check) -> None:
        if cancel_check and cancel_check():
            raise TaskCancelled()
