"""agent CLI 底层调用支撑。

CLI 型后端与 OpenAI 兼容 API 后端的核心差异：
- 无 api_key / base_url，直接调用本机已安装的 agent 命令行（zcode cli 等）
- 完整提示词通过临时文件传递：Windows 命令行有 32k 长度上限，且视频理解的
  网格截图以本地图片文件形式提供，由 agent 自行读取
- 单次长时运行子进程，支持超时 / 重试 / 任务取消（终止子进程）

subprocess 调用模式参照 ffmpeg_helper.py / video_helper.py 的既有实践。
"""
import os
import subprocess
import threading
import time
from abc import ABC, abstractmethod
from pathlib import Path

from app.utils.logger import get_logger

logger = get_logger(__name__)

try:
    from app.exceptions.task import TaskCancelled
except ModuleNotFoundError:  # 兼容独立加载模块的测试/工具脚本（同 universal_gpt.py）
    class TaskCancelled(Exception):
        pass


class AgentCliError(Exception):
    """agent CLI 调用失败。"""


# 后端可能由 GUI（桌面端 sidecar）拉起，Windows 下避免弹出控制台窗口；
# 兼容无 windowsHide/CREATE_NO_WINDOW 的精简构建，POSIX 下为空。
_SUBPROCESS_NO_WINDOW_KWARGS = (
    {"creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)} if os.name == "nt" else {}
)


def get_cli_timeout_seconds() -> float:
    """单次 CLI 调用超时（agent 长任务耗时波动大，默认 30 分钟）。"""
    return float(os.getenv("AGENT_CLI_TIMEOUT_SECONDS", str(30 * 60)))


def get_cli_retry_attempts() -> int:
    return max(1, int(os.getenv("AGENT_CLI_RETRY_ATTEMPTS", "2")))


def get_cli_retry_backoff_seconds() -> float:
    return float(os.getenv("AGENT_CLI_RETRY_BACKOFF_SECONDS", "1.5"))


def _raise_if_cancelled(cancel_check) -> None:
    if cancel_check and cancel_check():
        raise TaskCancelled()


class AgentCliBase(ABC):
    """单个 agent CLI 的调用契约与默认实现。"""

    name: str = ""                 # 注册表键，如 "zcode"
    display_name: str = ""         # 展示名，如 "ZCode CLI"
    default_command: str = ""      # 默认可执行文件名（PATH 查找）
    cli_path_env: str = ""         # 可执行文件路径覆盖的环境变量名
    default_models: list = []      # 静态模型列表（CLI 型供应商不走 models API）

    def cli_command(self) -> str:
        """可执行文件：环境变量覆盖优先，否则按默认名走 PATH 查找。"""
        override = os.getenv(self.cli_path_env, "").strip() if self.cli_path_env else ""
        return override or self.default_command

    def runtime_command(self) -> list:
        """argv 前缀：可执行文件的启动形态（脚本型入口需由子类覆盖，如经 node 运行）。"""
        return [self.cli_command()]

    @abstractmethod
    def build_command(
        self,
        prompt_file: Path,
        workdir: Path,
        model: str | None = None,
        attach_files: list | None = None,
    ) -> list:
        """构造子进程 argv。prompt 写入 prompt_file 并随 --attach 附加。"""
        raise NotImplementedError

    @abstractmethod
    def parse_output(self, stdout: str) -> str:
        """从子进程 stdout 提取最终文本答案。"""
        raise NotImplementedError

    def list_models(self) -> list:
        return list(self.default_models)

    def check_available(self, timeout: float = 15) -> tuple:
        """检测 CLI 是否可用（--version）。返回 (ok, 详情)。"""
        command = self.runtime_command() + ["--version"]
        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout,
                **_SUBPROCESS_NO_WINDOW_KWARGS,
            )
        except FileNotFoundError:
            return False, f"未找到可执行文件 {self.cli_command()}"
        except subprocess.TimeoutExpired:
            return False, f"--version 执行超时（>{int(timeout)}s）"
        if completed.returncode != 0:
            detail = (completed.stderr or completed.stdout or "").strip()
            return False, f"--version 退出码 {completed.returncode}：{detail[:500]}"
        return True, (completed.stdout or "").strip()[:200] or "ok"


def run_cli(
    impl: AgentCliBase,
    prompt_file: Path,
    workdir: Path,
    model: str | None = None,
    cancel_check=None,
    attach_files: list | None = None,
) -> str:
    """执行一次 agent CLI 调用，返回原始 stdout。

    - attach_files：随命令附加的本地文件（视频理解截图等）
    - 超时（AGENT_CLI_TIMEOUT_SECONDS）与失败按 AGENT_CLI_RETRY_ATTEMPTS 重试
    - cancel_check 由看护线程轮询，命中后终止子进程并抛 TaskCancelled
    """
    _raise_if_cancelled(cancel_check)
    command = impl.build_command(
        prompt_file=prompt_file,
        workdir=workdir,
        model=model,
        attach_files=attach_files,
    )
    timeout = get_cli_timeout_seconds()
    attempts = get_cli_retry_attempts()
    backoff = get_cli_retry_backoff_seconds()

    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            return _run_once(impl, command, workdir, timeout, cancel_check)
        except TaskCancelled:
            raise
        except Exception as exc:
            last_error = exc
            logger.warning(
                f"[agent_cli:{impl.name}] 第 {attempt}/{attempts} 次调用失败: {exc}"
            )
            if attempt < attempts:
                time.sleep(backoff * attempt)
    raise AgentCliError(f"{impl.display_name} 调用失败（共尝试 {attempts} 次）: {last_error}")


def _run_once(
    impl: AgentCliBase,
    command: list,
    workdir: Path,
    timeout: float,
    cancel_check,
) -> str:
    logger.info(f"[agent_cli:{impl.name}] 调用: {command[0]} ... workdir={workdir}")
    try:
        process = subprocess.Popen(
            command,
            cwd=str(workdir),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            **_SUBPROCESS_NO_WINDOW_KWARGS,
        )
    except FileNotFoundError as exc:
        raise AgentCliError(
            f"找不到 {impl.display_name} 可执行文件（{command[0]}），"
            f"请确认已安装并加入 PATH，或通过环境变量 {impl.cli_path_env} 指定完整路径"
        ) from exc

    cancel_hit = {"value": False}

    def _watch():
        while process.poll() is None:
            if cancel_check and cancel_check():
                cancel_hit["value"] = True
                try:
                    process.terminate()
                except OSError:
                    pass
                return
            time.sleep(1.0)

    watcher = threading.Thread(target=_watch, daemon=True)
    watcher.start()
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()
        raise AgentCliError(f"{impl.display_name} 调用超时（>{int(timeout)}s）")
    watcher.join(timeout=2)

    if cancel_hit["value"]:
        raise TaskCancelled()
    if process.returncode != 0:
        detail = (stderr or "").strip() or (stdout or "").strip()
        raise AgentCliError(
            f"{impl.display_name} 退出码 {process.returncode}：{detail[:2000]}"
        )
    return stdout or ""
