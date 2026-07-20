# app/routers/note.py
import io
import json
import os
import re
import tempfile
import uuid
import zipfile
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse, quote

from fastapi import APIRouter, HTTPException, BackgroundTasks, UploadFile, File
from pydantic import BaseModel, validator, field_validator
from dataclasses import asdict

from app.db.video_task_dao import get_task_by_video
from app.enmus.exception import NoteErrorEnum
from app.enmus.note_enums import DownloadQuality
from app.exceptions.note import NoteError
from app.services.note import NoteGenerator, logger
from app.services.task_serial_executor import task_serial_executor
from app.utils.response import ResponseWrapper as R
from app.utils.url_parser import extract_video_id
from app.validators.video_url_validator import is_supported_video_url
from fastapi import APIRouter, Request, HTTPException
from fastapi.responses import StreamingResponse, Response, FileResponse
import httpx
from app.enmus.task_status_enums import TaskStatus

# from app.services.downloader import download_raw_audio
# from app.services.whisperer import transcribe_audio

router = APIRouter()


class RecordRequest(BaseModel):
    video_id: str
    platform: str


class VideoRequest(BaseModel):
    video_url: str
    platform: str
    quality: DownloadQuality
    screenshot: Optional[bool] = False
    link: Optional[bool] = False
    model_name: str
    provider_id: str
    task_id: Optional[str] = None
    format: Optional[list] = []
    style: str = None
    extras: Optional[str]=None
    video_understanding: Optional[bool] = False
    video_interval: Optional[int] = 0
    grid_size: Optional[list] = []
    # 客户端（如浏览器插件）已经在用户浏览器里抓到字幕，直接传给后端复用，
    # 跳过 download_subtitles 和音频转写。形如：
    #   {"language": "zh", "full_text": "...", "segments": [{"start","end","text"}, ...]}
    prefetched_transcript: Optional[dict] = None

    @field_validator("video_url")
    def validate_supported_url(cls, v):
        url = str(v)
        parsed = urlparse(url)
        if parsed.scheme in ("http", "https"):
            # 是网络链接，继续用原有平台校验
            if not is_supported_video_url(url):
                raise NoteError(code=NoteErrorEnum.PLATFORM_NOT_SUPPORTED.code,
                                message=NoteErrorEnum.PLATFORM_NOT_SUPPORTED.message)

        return v


NOTE_OUTPUT_DIR = os.getenv("NOTE_OUTPUT_DIR", "note_results")
UPLOAD_DIR = "uploads"


def save_note_to_file(task_id: str, note):
    os.makedirs(NOTE_OUTPUT_DIR, exist_ok=True)
    with open(os.path.join(NOTE_OUTPUT_DIR, f"{task_id}.json"), "w", encoding="utf-8") as f:
        json.dump(asdict(note), f, ensure_ascii=False, indent=2)


def _persist_prefetched_transcript(task_id: str, transcript: dict) -> None:
    """把客户端预取的字幕写到 NoteGenerator 期望的转写缓存文件里。

    NoteGenerator.generate 会优先读 <task_id>_transcript.json，命中即跳过 download_subtitles
    与音频转写流程。要求字段：language(可空)/full_text/segments[{start,end,text}]
    """
    segments = transcript.get("segments") or []
    cleaned_segments = []
    for s in segments:
        text = (s.get("text") or "").strip()
        if not text:
            continue
        cleaned_segments.append({
            "start": float(s.get("start", 0)),
            "end": float(s.get("end", 0)),
            "text": text,
        })
    if not cleaned_segments:
        raise ValueError("prefetched_transcript 没有可用的 segments")

    full_text = transcript.get("full_text") or " ".join(s["text"] for s in cleaned_segments)
    payload = {
        "language": transcript.get("language") or "zh",
        "full_text": full_text,
        "segments": cleaned_segments,
    }

    os.makedirs(NOTE_OUTPUT_DIR, exist_ok=True)
    target = os.path.join(NOTE_OUTPUT_DIR, f"{task_id}_transcript.json")
    with open(target, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    logger.info(f"已写入客户端预取字幕缓存: {target} ({len(cleaned_segments)} 段)")


def run_note_task(task_id: str, video_url: str, platform: str, quality: DownloadQuality,
                  link: bool = False, screenshot: bool = False, model_name: str = None, provider_id: str = None,
                  _format: list = None, style: str = None, extras: str = None, video_understanding: bool = False,
                  video_interval=0, grid_size=[]
                  ):

    if not model_name or not provider_id:
        raise HTTPException(status_code=400, detail="请选择模型和提供者")

    def _execute_note_task():
        return NoteGenerator().generate(
            video_url=video_url,
            platform=platform,
            quality=quality,
            task_id=task_id,
            model_name=model_name,
            provider_id=provider_id,
            link=link,
            _format=_format,
            style=style,
            extras=extras,
            screenshot=screenshot,
            video_understanding=video_understanding,
            video_interval=video_interval,
            grid_size=grid_size,
        )

    logger.info(f"任务进入执行队列 (task_id={task_id})")
    note = task_serial_executor.run(_execute_note_task)
    logger.info(f"Note generated: {task_id}")
    if not note or not note.markdown:
        logger.warning(f"任务 {task_id} 执行失败，跳过保存")
        return
    save_note_to_file(task_id, note)

    # 自动建立向量索引（用于 AI 问答），失败不影响笔记生成
    try:
        from app.services.vector_store import VectorStoreManager
        VectorStoreManager().index_task(task_id)
    except Exception as e:
        logger.warning(f"向量索引失败（不影响笔记）: {e}")


@router.post('/delete_task')
def delete_task(data: RecordRequest):
    try:
        # TODO: 待持久化完成
        # NoteGenerator().delete_note(video_id=data.video_id, platform=data.platform)
        return R.success(msg='删除成功')
    except Exception as e:
        return R.error(msg=e)


@router.post("/upload")
async def upload(file: UploadFile = File(...)):
    os.makedirs(UPLOAD_DIR, exist_ok=True)
    file_location = os.path.join(UPLOAD_DIR, file.filename)

    with open(file_location, "wb+") as f:
        f.write(await file.read())

    # 假设你静态目录挂载了 /uploads
    return R.success({"url": f"/uploads/{file.filename}"})


@router.post("/generate_note")
def generate_note(data: VideoRequest, background_tasks: BackgroundTasks):
    try:
        # 就绪门禁：本地转写引擎（fast-whisper / mlx-whisper）必须等模型下载完才能跑视频，
        # 否则任务会卡在首次下载（慢 / OOM / 截断），用户只看到一个静默失败的任务。
        # 客户端已抓好字幕（prefetched_transcript）则不需要转写，跳过检查。
        if not data.prefetched_transcript:
            from app.services.transcriber_config_manager import TranscriberConfigManager
            readiness = TranscriberConfigManager().is_model_ready()
            if not readiness["ready"]:
                logger.warning(f"拒绝 generate_note：{readiness['reason']}")
                return R.error(
                    msg=readiness["reason"],
                    code=300102,
                    data={
                        "reason": "transcriber_model_not_ready",
                        "transcriber_type": readiness["transcriber_type"],
                        "model_size": readiness["model_size"],
                        "downloading": readiness["downloading"],
                    },
                )

        video_id = extract_video_id(data.video_url, data.platform)
        # if not video_id:
        #     raise HTTPException(status_code=400, detail="无法提取视频 ID")
        # existing = get_task_by_video(video_id, data.platform)
        # if existing:
        #     return R.error(
        #         msg='笔记已生成，请勿重复发起',
        #
        #     )
        if data.task_id:
            # 如果传了task_id，说明是重试！
            task_id = data.task_id
            logger.info(f"重试模式，复用已有 task_id={task_id}")
        else:
            # 正常新建任务
            task_id = str(uuid.uuid4())

        # 统一先写入 PENDING，表示已进入队列等待串行执行
        NoteGenerator()._update_status(task_id, TaskStatus.PENDING)

        # 客户端已经抓好字幕的话，写到转写缓存文件，NoteGenerator 的 cache-hit 逻辑会直接用上
        if data.prefetched_transcript:
            try:
                _persist_prefetched_transcript(task_id, data.prefetched_transcript)
            except Exception as e:
                logger.warning(f"写入预取字幕失败 (task_id={task_id}): {e}")

        background_tasks.add_task(run_note_task, task_id, data.video_url, data.platform, data.quality, data.link,
                                  data.screenshot, data.model_name, data.provider_id, data.format, data.style,
                                  data.extras, data.video_understanding, data.video_interval, data.grid_size)
        return R.success({"task_id": task_id})
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/task_status/{task_id}")
def get_task_status(task_id: str):
    status_path = os.path.join(NOTE_OUTPUT_DIR, f"{task_id}.status.json")
    result_path = os.path.join(NOTE_OUTPUT_DIR, f"{task_id}.json")

    # 优先读状态文件
    if os.path.exists(status_path):
        with open(status_path, "r", encoding="utf-8") as f:
            status_content = json.load(f)

        status = status_content.get("status")
        message = status_content.get("message", "")

        if status == TaskStatus.SUCCESS.value:
            # 成功状态的话，继续读取最终笔记内容
            if os.path.exists(result_path):
                with open(result_path, "r", encoding="utf-8") as rf:
                    result_content = json.load(rf)
                return R.success({
                    "status": status,
                    "result": result_content,
                    "message": message,
                    "task_id": task_id
                })
            else:
                # 理论上不会出现，保险处理
                return R.success({
                    "status": TaskStatus.PENDING.value,
                    "message": "任务完成，但结果文件未找到",
                    "task_id": task_id
                })

        if status == TaskStatus.FAILED.value:
            return R.error(message or "任务失败", code=500)

        # 处理中状态
        return R.success({
            "status": status,
            "message": message,
            "task_id": task_id
        })

    # 没有状态文件，但有结果
    if os.path.exists(result_path):
        with open(result_path, "r", encoding="utf-8") as f:
            result_content = json.load(f)
        return R.success({
            "status": TaskStatus.SUCCESS.value,
            "result": result_content,
            "task_id": task_id
        })

    # 什么都没有，默认PENDING
    return R.success({
        "status": TaskStatus.PENDING.value,
        "message": "任务排队中",
        "task_id": task_id
    })


@router.get("/image_proxy")
async def image_proxy(request: Request, url: str):
    headers = {
        "Referer": "https://www.bilibili.com/",
        "User-Agent": request.headers.get("User-Agent", ""),
    }

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers=headers)

            if resp.status_code != 200:
                raise HTTPException(status_code=resp.status_code, detail="图片获取失败")

            content_type = resp.headers.get("Content-Type", "image/jpeg")
            return StreamingResponse(
                resp.aiter_bytes(),
                media_type=content_type,
                headers={
                    "Cache-Control": "public, max-age=86400",  #  缓存一天
                    "Content-Type": content_type,
                }
            )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# ---------- 导出笔记（含截图时打包为 zip） ----------

# 匹配已替换的截图引用，如 ![](/static/screenshots/xxx.jpg)
_SCREENSHOT_REF_RE = re.compile(r'!\[([^\]]*)\]\(/static/screenshots/([^)]+)\)')

# 匹配原始截图标记，如 *Screenshot-[04:45] 或 Screenshot-04:45
_RAW_MARKER_RE = re.compile(r'\*?Screenshot-(?:\[(\d{2}):(\d{2})\]|(\d{2}):(\d{2}))')


def _find_video_file(video_id: str) -> Optional[Path]:
    """根据 video_id 在 data/data/ 目录下查找已下载的视频文件。"""
    data_dir = Path("data/data")
    for ext in (".mp4", ".mkv", ".flv", ".webm"):
        candidate = data_dir / f"{video_id}{ext}"
        if candidate.exists():
            return candidate
    return None


@router.get("/export_note/{task_id}")
def export_note(task_id: str):
    """
    导出笔记。如果 markdown 包含截图引用（已替换或原始标记），则打包为 zip（含 screenshots/ 子目录），
    否则直接返回单个 .md 文件。
    """
    from app.utils.screenshot_marker import extract_screenshot_timestamps
    from app.utils.video_helper import generate_screenshot

    # 安全校验：task_id 只允许字母数字和短横线
    if not task_id or not re.match(r'^[a-zA-Z0-9\-]+$', task_id):
        raise HTTPException(status_code=400, detail="无效的 task_id")

    # 优先从 JSON 结果读取（包含 post-processing 后的最终 markdown）
    result_path = Path(NOTE_OUTPUT_DIR) / f"{task_id}.json"
    markdown_path = Path(NOTE_OUTPUT_DIR) / f"{task_id}_markdown.md"
    markdown_text = ""

    if result_path.exists():
        try:
            result_data = json.loads(result_path.read_text(encoding="utf-8"))
            markdown_text = result_data.get("markdown", "")
        except Exception:
            pass

    # 回退：从 markdown 缓存文件读取（可能不含 post-processing 结果）
    if not markdown_text and markdown_path.exists():
        markdown_text = markdown_path.read_text(encoding="utf-8")

    if not markdown_text:
        raise HTTPException(status_code=404, detail="笔记不存在")

    # 读取音频元信息以获取标题和 video_id
    audio_meta_path = Path(NOTE_OUTPUT_DIR) / f"{task_id}_audio.json"
    title = "note"
    video_id = ""
    if audio_meta_path.exists():
        try:
            meta = json.loads(audio_meta_path.read_text(encoding="utf-8"))
            title = meta.get("title", title)
            video_id = meta.get("video_id", "")
        except Exception:
            pass

    safe_title = re.sub(r'[\\/:*?"<>|]', '_', title).strip() or "note"

    screenshots_dir = Path("static/screenshots")
    image_filenames = []  # 要打包的截图文件名列表
    rewritten_md = markdown_text

    # 情况1：已有 ![](/static/screenshots/...) 引用（post-processing 已执行）
    if _SCREENSHOT_REF_RE.search(markdown_text):
        rewritten_md = _SCREENSHOT_REF_RE.sub(r'![\1](screenshots/\2)', markdown_text)
        image_filenames = list(set(fn for _, fn in _SCREENSHOT_REF_RE.findall(markdown_text)))

    # 情况2：有原始 *Screenshot-[mm:ss]* 标记（post-processing 未执行，需要现场生成截图）
    elif _RAW_MARKER_RE.search(markdown_text):
        # 查找视频文件
        video_path = _find_video_file(video_id)
        if not video_path:
            raise HTTPException(status_code=404, detail=f"视频文件不存在（video_id={video_id}），无法生成截图")

        # 从标记中提取时间戳并生成截图
        timestamps = extract_screenshot_timestamps(markdown_text)
        tmp_dir = Path(tempfile.mkdtemp(prefix="bili_export_"))
        for idx, (marker, ts) in enumerate(timestamps):
            try:
                img_path = generate_screenshot(str(video_path), str(tmp_dir), ts, idx)
                filename = Path(img_path).name
                image_filenames.append(filename)
                # 替换标记为相对路径引用（只替换第一个匹配）
                rewritten_md = rewritten_md.replace(
                    marker, f"![](screenshots/{filename})", 1
                )
            except Exception as exc:
                logger.warning(f"导出截图生成失败 (ts={ts}s): {exc}")
                # 生成失败时保留原始标记
                continue

        # 更新截图目录为临时目录（生成的截图在这里）
        screenshots_dir = tmp_dir

    # 如果有截图，打包为 zip
    if image_filenames:
        zip_buffer = io.BytesIO()
        with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zf:
            zf.writestr(f"{safe_title}.md", rewritten_md.encode("utf-8"))
            for filename in image_filenames:
                img_path = screenshots_dir / filename
                if img_path.exists():
                    zf.write(img_path, f"screenshots/{filename}")

        tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".zip")
        tmp.write(zip_buffer.getvalue())
        tmp.close()
        encoded_name = quote(f"{safe_title}.zip")
        return FileResponse(
            path=tmp.name,
            media_type="application/zip",
            filename=f"{safe_title}.zip",
            headers={
                "Content-Disposition": f"attachment; filename*=UTF-8''{encoded_name}",
                "Content-Encoding": "identity",
            },
        )
    else:
        # 无截图，直接返回 .md 文件
        tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".md")
        tmp.write(rewritten_md.encode("utf-8"))
        tmp.close()
        encoded_name = quote(f"{safe_title}.md")
        return FileResponse(
            path=tmp.name,
            media_type="text/markdown; charset=utf-8",
            filename=f"{safe_title}.md",
            headers={
                "Content-Disposition": f"attachment; filename*=UTF-8''{encoded_name}",
            },
        )
