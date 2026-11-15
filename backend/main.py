import os
import sys
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from starlette.middleware.cors import CORSMiddleware
from starlette.middleware.gzip import GZipMiddleware
from starlette.staticfiles import StaticFiles
from dotenv import load_dotenv

# stdout 设为行缓冲，避免控制台日志卡住不刷新
sys.stdout.reconfigure(line_buffering=True)

load_dotenv()

# ---- 必须在导入任何 app 模块之前完成的环境准备 ----
# 1) 桌面端内置模式（BILINOTE_DATA_DIR 注入）默认走 hf-mirror 镜像下载 whisper 模型：
#    huggingface_hub 在 import 时读取 HF_ENDPOINT，必须先设；Dockerfile 同款做法。
#    用户显式设置过 HF_ENDPOINT 时不覆盖。
if os.getenv("BILINOTE_DATA_DIR"):
    os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

# 2) CUDA 加速包目录（exe 同级 cuda\，可选）：桌面端打包版不带 torch，
#    GPU 转写靠这里的 cuBLAS/cuDNN。在 ctranslate2 被加载前注册才生效。
from app.utils.runtime_paths import get_cuda_dll_dir  # noqa: E402  (runtime_paths 无重依赖)

_cuda_dll_dir = get_cuda_dll_dir()
if _cuda_dll_dir:
    try:
        os.add_dll_directory(_cuda_dll_dir)
        os.environ["PATH"] = _cuda_dll_dir + os.pathsep + os.environ.get("PATH", "")
        print(f"[cuda] 已加载 CUDA 加速包: {_cuda_dll_dir}")
    except OSError:
        pass

from app.db.init_db import init_db
from app.db.provider_dao import ensure_agent_cli_providers, seed_default_providers
from app.exceptions.exception_handlers import register_exception_handlers
# from app.db.model_dao import init_model_table
# from app.db.provider_dao import init_provider_table
from app.utils.logger import get_logger, setup_full_logging
from app.utils.runtime_paths import (
    get_screenshots_dir,
    get_static_root,
    get_uploads_dir,
)
from app import create_app
from app.services.transcriber_config_manager import TranscriberConfigManager
from events import register_handler
from ffmpeg_helper import ensure_ffmpeg_or_raise

logger = get_logger(__name__)

# 路径解析：显式 env > BILINOTE_DATA_DIR 数据根 > 历史默认（见 runtime_paths.py）
static_path = os.getenv('STATIC', '/static')
out_dir = get_screenshots_dir()

# 自动创建本地目录（static 和 static/screenshots）
static_dir = get_static_root()
uploads_dir = get_uploads_dir()
if not os.path.exists(static_dir):
    os.makedirs(static_dir)
if not os.path.exists(uploads_dir):
    os.makedirs(uploads_dir)

if not os.path.exists(out_dir):
    os.makedirs(out_dir)

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动序列拆成 5 步、每步独立日志 + 异常时打明确的 [startup N/5 FAILED] 标记。
    # 目的：用户 docker logs 一眼能看出后端死在哪一步，避免「容器一直重启但看不出原因」。
    try:
        logger.info("[startup 1/5] register_handler() — 注册事件处理器")
        register_handler()

        logger.info("[startup 2/5] init_db() — 初始化 SQLite 数据库")
        init_db()

        logger.info("[startup 3/5] TranscriberConfigManager — 读取转写器配置")
        # 转写器不再在启动时强制初始化，而是在首次生成笔记时按需创建。
        # 如果配置了不可用的类型（如 mlx-whisper 未安装），会在使用时报错而非静默回退。
        _cfg = TranscriberConfigManager().get_config()
        logger.info(
            f"           当前转写器: type={_cfg['transcriber_type']}, "
            f"model_size={_cfg['whisper_model_size']}"
        )

        logger.info("[startup 4/5] seed_default_providers() — 初始化默认 LLM 供应商")
        seed_default_providers()
        # 存量库补种 agent CLI 型供应商（如 ZCode CLI），幂等
        ensure_agent_cli_providers()

        logger.info("[startup 5/5] 启动完成，等待请求")
        from app.services.batch_task_service import batch_task_service
        batch_task_service.recover_incomplete_batches()
    except Exception:
        logger.exception("[startup FAILED] 后端启动期异常，详见堆栈；容器会退出并由 restart 策略决定是否重试")
        raise

    yield

app = create_app(lifespan=lifespan)

# 允许的源：本地 web 端 + Tauri 桌面端 + 浏览器扩展（chrome/edge/firefox）
# 用 regex 是因为 chrome-extension://<id> 的 id 在每次开发版加载时不固定
# Tauri 2 不同平台 webview origin 不一样，必须全列：
#   - macOS:   tauri://localhost  （自定义协议）
#   - Windows: https://tauri.localhost  （Edge WebView2）
#   - Linux:   http://tauri.localhost   （WebKitGTK）
# 漏掉哪个都会导致桌面端 fetch 返回 200 但 browser 因为 CORS 拒绝读响应，
# 表现为前端「连不上后端」但后端日志一片 200 OK。
CORS_ORIGIN_REGEX = (
    r"^chrome-extension://[a-z]+$"
    r"|^moz-extension://.+$"
    r"|^http://(localhost|127\.0\.0\.1)(:\d+)?$"
    r"|^tauri://localhost$"
    r"|^https?://tauri\.localhost$"
)

app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=CORS_ORIGIN_REGEX,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.add_middleware(GZipMiddleware, minimum_size=1000)
register_exception_handlers(app)
app.mount(static_path, StaticFiles(directory=static_dir), name="static")
app.mount("/uploads", StaticFiles(directory=uploads_dir), name="uploads")









if __name__ == "__main__":
    port = int(os.getenv("BACKEND_PORT", 8483))
    host = os.getenv("BACKEND_HOST", "0.0.0.0")
    logger.info(f"Starting server on {host}:{port}")
    # log_config=None：不让 uvicorn 重建自己的 logging 配置，
    # 由 setup_full_logging 把全部日志（含 uvicorn 访问日志）统一写入 app.log，
    # 桌面端「服务日志」页依赖这一点才能看到完整服务日志。
    setup_full_logging()
    uvicorn.run(app, host=host, port=port, reload=False, log_config=None)
