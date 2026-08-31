import logging

from app.gpt.base import GPT
from app.gpt.prompt_builder import generate_base_prompt
from app.models.gpt_model import GPTSource
from app.utils.runtime_paths import get_note_output_dir
import os
import hashlib
import json
import time
from datetime import datetime, timezone

logger = logging.getLogger(__name__)
from pathlib import Path

from app.gpt.prompt import BASE_PROMPT, AI_SUM, SCREENSHOT, LINK, MERGE_PROMPT
from app.gpt.utils import fix_markdown
from app.gpt.request_chunker import RequestChunker
from app.models.transcriber_model import TranscriptSegment
try:
    from app.exceptions.task import TaskCancelled
except ModuleNotFoundError:  # 兼容独立加载模块的测试/工具脚本
    class TaskCancelled(Exception):
        pass
from datetime import timedelta
from typing import List


class UniversalGPT(GPT):
    def __init__(self, client, model: str, temperature: float = 0.7):
        self.client = client
        self.model = model
        self.temperature = temperature
        self.screenshot = False
        self.link = False
        # 留出图片 Base64 编码和供应商单图限制的余量，避免本地分块器
        # 允许一个请求通过，但 OpenRouter 在下载图片时返回 413。
        self.max_request_bytes = int(os.getenv("OPENAI_MAX_REQUEST_BYTES", str(24 * 1024 * 1024)))
        self.checkpoint_dir = Path(get_note_output_dir())
        self.checkpoint_dir.mkdir(parents=True, exist_ok=True)
        # 初始化时缓存重试配置，避免每次请求重复读取环境变量
        self._max_retry_attempts = max(1, int(os.getenv("OPENAI_RETRY_ATTEMPTS", "3")))
        self._retry_base_backoff = float(os.getenv("OPENAI_RETRY_BACKOFF_SECONDS", "1.5"))

    def _format_time(self, seconds: float) -> str:
        return str(timedelta(seconds=int(seconds)))[2:]

    def _build_segment_text(self, segments: List[TranscriptSegment]) -> str:
        return "\n".join(
            f"{self._format_time(seg.start)} - {seg.text.strip()}"
            for seg in segments
        )

    def ensure_segments_type(self, segments) -> List[TranscriptSegment]:
        return [TranscriptSegment(**seg) if isinstance(seg, dict) else seg for seg in segments]

    def create_messages(self, segments: List[TranscriptSegment], **kwargs):

        content_text = generate_base_prompt(
            title=kwargs.get('title'),
            segment_text=self._build_segment_text(segments),
            tags=kwargs.get('tags'),
            _format=kwargs.get('_format'),
            style=kwargs.get('style'),
            extras=kwargs.get('extras'),
        )

        video_img_urls = kwargs.get('video_img_urls', [])

        content: list[dict] | str
        if video_img_urls:
            # 有截图时走 OpenAI 多模态 content 数组（text + image_url）
            content = [{"type": "text", "text": content_text}]
            for url in video_img_urls:
                content.append({
                    "type": "image_url",
                    "image_url": {
                        "url": url,
                        "detail": "auto"
                    }
                })
        else:
            # 纯文本场景退回 string content：DeepSeek deepseek-chat 等非多模态模型
            # 不识别 [{"type":"text",...}] 数组形态，会返回 invalid_request_error
            # （issue #282）。OpenAI 规范本身也允许 content 为 string。
            content = content_text

        messages = [{
            "role": "user",
            "content": content
        }]

        return messages

    def list_models(self):
        return self.client.models.list()

    def _estimate_messages_bytes(self, messages: list) -> int:
        import json
        return len(json.dumps(messages, ensure_ascii=False).encode("utf-8"))

    def _build_merge_messages(self, partials: list) -> list:
        merge_text = MERGE_PROMPT + "\n\n" + "\n\n---\n\n".join(partials)
        # 合并阶段没有图片，直接用 string content 兼容非多模态模型（issue #282）
        return [{
            "role": "user",
            "content": merge_text
        }]

    def _checkpoint_path(self, checkpoint_key: str) -> Path:
        safe_key = "".join(ch if ch.isalnum() or ch in ("-", "_") else "_" for ch in checkpoint_key)
        return self.checkpoint_dir / f"{safe_key}.gpt.checkpoint.json"

    def _build_source_signature(self, source: GPTSource) -> str:
        payload = {
            "model": self.model,
            "temperature": self.temperature,
            "max_request_bytes": self.max_request_bytes,
            "title": source.title,
            "tags": source.tags,
            "format": source._format,
            "style": source.style,
            "extras": source.extras,
            "video_img_urls": source.video_img_urls or [],
            "segments": [
                {
                    "start": getattr(seg, "start", None),
                    "end": getattr(seg, "end", None),
                    "text": getattr(seg, "text", "")
                }
                for seg in source.segment
            ],
        }
        raw = json.dumps(payload, ensure_ascii=False, sort_keys=True)
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _load_checkpoint(self, checkpoint_key: str, source_signature: str) -> dict | None:
        path = self._checkpoint_path(checkpoint_key)
        if not path.exists():
            return None
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("source_signature") != source_signature:
                path.unlink(missing_ok=True)
                return None
            return data
        except Exception:
            path.unlink(missing_ok=True)
            return None

    def _save_checkpoint(self, checkpoint_key: str, source_signature: str, partials: list, phase: str) -> None:
        path = self._checkpoint_path(checkpoint_key)
        data = {
            "version": 1,
            "source_signature": source_signature,
            "phase": phase,
            "partials": partials,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        tmp_path = path.with_suffix(".tmp")
        tmp_path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp_path.replace(path)

    def _clear_checkpoint(self, checkpoint_key: str) -> None:
        self._checkpoint_path(checkpoint_key).unlink(missing_ok=True)

    @staticmethod
    def _is_insufficient_quota_error(exc: Exception) -> bool:
        raw = str(exc)
        return (
            "insufficient_user_quota" in raw
            or "预扣费额度失败" in raw
            or "insufficient quota" in raw.lower()
        )

    @staticmethod
    def _is_retryable_error(exc: Exception) -> bool:
        if isinstance(exc, json.JSONDecodeError):
            return True

        raw = str(exc).lower()
        retryable_tokens = (
            "error code: 524",
            "bad_response_status_code",
            "timed out",
            "timeout",
            "rate limit",
            "error code: 429",
            "error code: 500",
            "error code: 502",
            "error code: 503",
            "error code: 504",
            "apiconnectionerror",
            "connection error",
            "service unavailable",
        )
        if any(token in raw for token in retryable_tokens):
            return True

        status = getattr(exc, "status_code", None) or getattr(exc, "status", None)
        return status in {408, 409, 429, 500, 502, 503, 504, 524}

    @staticmethod
    def _is_temperature_unsupported_error(exc: Exception) -> bool:
        """OpenAI o1/o3/gpt-5 系列等新模型不接受自定义 temperature，
        只允许默认值 1，传 0.7 会报 `'temperature' does not support 0.7 ...`。"""
        raw = str(exc).lower()
        return "temperature" in raw and (
            "does not support" in raw
            or "unsupported_value" in raw
            or "only the default" in raw
        )

    @staticmethod
    def _is_image_input_unsupported_error(exc: Exception) -> bool:
        """模型不支持图片输入时返回 True（404 且包含 image 相关关键词）。"""
        status = getattr(exc, "status_code", None) or getattr(exc, "status", None)
        if status != 404:
            return False
        raw = str(exc).lower()
        return "image" in raw

    @staticmethod
    def _strip_images_from_messages(messages: list) -> list:
        """把多模态 content 数组退化为纯文本 string，兼容不支持图片的模型。"""
        stripped = []
        for msg in messages:
            content = msg.get("content")
            if isinstance(content, list):
                # 只保留 type=text 的部分，拼成纯字符串
                text_parts = [
                    part["text"] for part in content
                    if isinstance(part, dict) and part.get("type") == "text"
                ]
                new_msg = {**msg, "content": "\n".join(text_parts)}
                stripped.append(new_msg)
            else:
                stripped.append(msg)
        return stripped

    def _do_create(self, messages: list):
        """单次调用。如果模型拒绝自定义 temperature，就地去掉该参数再试一次
        如果模型不支持图片输入，去掉图片内容再试一次
        （不消耗外层的重试次数预算），仍失败则把异常抛给外层重试逻辑。"""
        try:
            return self.client.chat.completions.create(
                model=self.model,
                messages=messages,
                temperature=self.temperature,
            )
        except Exception as exc:
            if self._is_temperature_unsupported_error(exc):
                print(f"[universal_gpt] 模型 {self.model} 不支持自定义 temperature，改用默认值重试")
                return self.client.chat.completions.create(
                    model=self.model,
                    messages=messages,
                )
            if self._is_image_input_unsupported_error(exc):
                print(f"[universal_gpt] 模型 {self.model} 不支持图片输入，去掉图片后重试")
                stripped_messages = self._strip_images_from_messages(messages)
                return self.client.chat.completions.create(
                    model=self.model,
                    messages=stripped_messages,
                    temperature=self.temperature,
                )
            raise

    def _chat_completion_create(self, messages: list):
        cancel_check = getattr(self, "cancel_check", None)
        last_exc = None
        for attempt in range(self._max_retry_attempts):
            self._raise_if_cancelled(cancel_check)
            try:
                response = self._do_create(messages)
                self._raise_if_cancelled(cancel_check)
                return response
            except Exception as exc:
                last_exc = exc
                retryable = self._is_retryable_error(exc)
                if isinstance(exc, json.JSONDecodeError):
                    will_retry = attempt < self._max_retry_attempts - 1
                    logger.warning(
                        "LLM provider response parsing failed (attempt %d/%d; retrying=%s): %s",
                        attempt + 1,
                        self._max_retry_attempts,
                        will_retry,
                        exc,
                    )
                if not retryable or attempt == self._max_retry_attempts - 1:
                    raise
                sleep_seconds = self._retry_base_backoff * (2 ** attempt)
                if cancel_check:
                    deadline = time.monotonic() + sleep_seconds
                    while time.monotonic() < deadline:
                        self._raise_if_cancelled(cancel_check)
                        time.sleep(min(0.25, deadline - time.monotonic()))
                else:
                    time.sleep(sleep_seconds)

        if last_exc is not None:
            raise last_exc
        raise RuntimeError("chat completion failed without exception")

    @staticmethod
    def _raise_if_cancelled(cancel_check) -> None:
        if cancel_check and cancel_check():
            raise TaskCancelled("用户已取消任务")

    def _merge_partials(self, partials: list, checkpoint_key: str | None, source_signature: str | None) -> str:
        def build_messages(texts, *_args, **_kwargs):
            return self._build_merge_messages(texts)

        merge_chunker = RequestChunker(
            lambda *_args, **_kwargs: [],
            self.max_request_bytes,
            self._estimate_messages_bytes
        )

        current_partials = list(partials)
        while len(current_partials) > 1:
            self._raise_if_cancelled(getattr(self, "cancel_check", None))
            groups = merge_chunker.group_texts_by_budget(current_partials, build_messages)
            new_partials = []
            for group_idx, group in enumerate(groups):
                self._raise_if_cancelled(getattr(self, "cancel_check", None))
                messages = build_messages(group)
                try:
                    response = self._chat_completion_create(messages)
                except Exception as exc:
                    if checkpoint_key and source_signature:
                        self._save_checkpoint(checkpoint_key, source_signature, current_partials, "merge")
                    raise

                if not response.choices or response.choices[0].message.content is None:
                    logger.error("合并阶段 LLM 返回无效响应: %s", response)
                    raise ValueError("LLM 合并阶段返回无效响应")
                new_partials.append(response.choices[0].message.content.strip())

                if checkpoint_key and source_signature:
                    remaining_partials = []
                    for remaining_group in groups[group_idx + 1:]:
                        remaining_partials.extend(remaining_group)
                    resumable_partials = new_partials + remaining_partials
                    self._save_checkpoint(checkpoint_key, source_signature, resumable_partials, "merge")

                self._raise_if_cancelled(getattr(self, "cancel_check", None))

            current_partials = new_partials

        return current_partials[0]

    def summarize(self, source: GPTSource) -> str:
        self.screenshot = source.screenshot
        self.link = source.link
        source.segment = self.ensure_segments_type(source.segment)
        checkpoint_key = source.checkpoint_key
        source_signature = self._build_source_signature(source) if checkpoint_key else None
        self.cancel_check = source.cancel_check
        self._raise_if_cancelled(self.cancel_check)

        def message_builder(segments, image_urls, **kwargs):
            return self.create_messages(segments, video_img_urls=image_urls, **kwargs)

        chunker = RequestChunker(message_builder, self.max_request_bytes, self._estimate_messages_bytes)

        try:
            chunks = chunker.chunk(
                source.segment,
                source.video_img_urls or [],
                title=source.title,
                tags=source.tags,
                _format=source._format,
                style=source.style,
                extras=source.extras
            )
        except ValueError:
            chunks = chunker.chunk(
                source.segment,
                [],
                title=source.title,
                tags=source.tags,
                _format=source._format,
                style=source.style,
                extras=source.extras
            )

        partials = []
        if checkpoint_key and source_signature:
            checkpoint = self._load_checkpoint(checkpoint_key, source_signature)
            if checkpoint and isinstance(checkpoint.get("partials"), list):
                partials = checkpoint["partials"]

        if len(partials) > len(chunks):
            partials = []

        for chunk in chunks[len(partials):]:
            self._raise_if_cancelled(self.cancel_check)
            messages = self.create_messages(
                chunk.segments,
                title=source.title,
                tags=source.tags,
                video_img_urls=chunk.image_urls,
                _format=source._format,
                style=source.style,
                extras=source.extras
            )
            try:
                response = self._chat_completion_create(messages)
            except Exception as exc:
                if checkpoint_key and source_signature:
                    self._save_checkpoint(checkpoint_key, source_signature, partials, "summarize")
                raise

            if not response.choices:
                logger.error(
                    "LLM 返回空 choices，模型=%s，响应=%s",
                    self.model,
                    response.model_dump_json() if hasattr(response, 'model_dump_json') else str(response),
                )
                raise ValueError(f"LLM 返回空 choices（模型 {self.model}），可能是 API Key 或无对应模型权限")
            content = response.choices[0].message.content
            if content is None:
                finish_reason = response.choices[0].finish_reason
                logger.error(
                    "LLM 返回 content=None，模型=%s，finish_reason=%s，响应=%s",
                    self.model, finish_reason,
                    response.model_dump_json() if hasattr(response, 'model_dump_json') else str(response),
                )
                raise ValueError(f"LLM 返回 content=None（模型 {self.model}，finish_reason={finish_reason}）")
            partials.append(content.strip())
            if checkpoint_key and source_signature:
                self._save_checkpoint(checkpoint_key, source_signature, partials, "summarize")

            self._raise_if_cancelled(self.cancel_check)

        if len(partials) == 1:
            if checkpoint_key:
                self._clear_checkpoint(checkpoint_key)
            return partials[0]
        merged = self._merge_partials(partials, checkpoint_key, source_signature)
        if checkpoint_key:
            self._clear_checkpoint(checkpoint_key)
        return merged
