"""ZCode CLI 调用实现。

本机 zcode agent 以非交互 print 模式执行（契约经本机 zcode 0.17.1 实测核对）：
    zcode -p "<短指令>" --json --cwd <工作目录> --attach prompt.md [--attach img_01.jpg ...]

- -p：单次非交互执行；--mode 默认即为 yolo（无需显式传）
- --json：机器可读输出 {"type":"result", ...}；parse_output 防御性解析并带纯文本回退
- --attach：官方多模态附件入口（可重复）——prompt.md 与视频理解网格截图均由此附加
- 完整提示词（字幕 + 截图清单）写入工作目录 prompt.md；Windows 32k 命令行长度
  上限因此不影响超长字幕的传递
- CLI 无 --model 选项（模型由登录账号配置决定），model_name 仅作展示元数据
- node:sqlite 迁移兼容垫片：Node 23.x 无 DatabaseSync.isTransaction，zcode 迁移
  检查会必败（详见 backend/tools/zcode_cli_compat.cjs 头注释）；node 运行 .cjs
  入口时自动 --require 注入，ZCODE_CLI_NODE_COMPAT 可覆盖路径（置空禁用）
- ZCODE_CLI_PATH 支持指向 zcode 可执行文件或 zcode.cjs 入口（后者自动经 node 运行，
  node 路径可用 ZCODE_CLI_NODE 覆盖，默认取 PATH 上的 node）
"""
import json
import os
from pathlib import Path

from app.gpt.agent_cli.base import AgentCliBase, AgentCliError
from app.utils.logger import get_logger

logger = get_logger(__name__)

# 静态模型列表（CLI 型供应商不走 models API）：
# - zcode-agent：使用本机 zcode CLI 登录账号的默认模型（别名）
# - GLM-5.3-Flash：StartPlan 账号当前实际使用的模型 id（自会话库 runtime/model_selection 实证）
# 用户也可在前端手动添加其它模型名（仅作配置元数据，见 build_command 说明）。
ZCODE_DEFAULT_MODEL_ALIAS = "zcode-agent"
ZCODE_STARTPLAN_FLASH_MODEL = "GLM-5.3-Flash"

_JS_SUFFIXES = (".cjs", ".js", ".mjs")

# node:sqlite isTransaction 兼容垫片（见 backend/tools/zcode_cli_compat.cjs 头注释）。
# 仓库默认路径；打包/移动场景可用 ZCODE_CLI_NODE_COMPAT 覆盖，置空字符串可禁用。
_COMPAT_SHIM_ENV = "ZCODE_CLI_NODE_COMPAT"
_DEFAULT_COMPAT_SHIM = Path(__file__).resolve().parents[3] / "tools" / "zcode_cli_compat.cjs"

_INSTRUCTION = (
    "请完成附件 prompt.md 中记录的任务。任务全部输入（视频字幕、截图图片文件等）"
    "均已附加或位于当前工作目录中。仅输出最终答案 markdown 正文，不要输出任何解释性"
    "文字，不要修改、创建或删除任何文件。"
)


def strip_code_fence(text: str) -> str:
    """整段被 markdown 代码围栏包裹时剥离围栏（仅处理首尾，不动内部围栏）。"""
    text = text.strip()
    if not text.startswith("```") or "\n" not in text:
        return text
    lines = text.splitlines()
    if lines[-1].strip() != "```":
        return text
    return "\n".join(lines[1:-1]).strip()


class ZcodeCli(AgentCliBase):
    name = "zcode"
    display_name = "ZCode CLI"
    default_command = "zcode"
    cli_path_env = "ZCODE_CLI_PATH"
    cli_node_env = "ZCODE_CLI_NODE"
    default_models = [ZCODE_DEFAULT_MODEL_ALIAS, ZCODE_STARTPLAN_FLASH_MODEL]

    def _node_args(self) -> list:
        """node 运行 .cjs 入口时的前置参数：注入 node:sqlite 兼容垫片（如存在）。"""
        override = os.getenv(_COMPAT_SHIM_ENV)
        if override is not None:
            if not override.strip():
                return []
            shim = Path(override.strip())
        else:
            shim = _DEFAULT_COMPAT_SHIM
        if shim.is_file():
            return ["--require", str(shim)]
        return []

    def _resolve_runtime(self) -> list:
        """可执行文件解析：.cjs/.js 入口需经 node 运行。"""
        cmd = self.cli_command()
        if cmd.lower().endswith(_JS_SUFFIXES):
            node = os.getenv(self.cli_node_env, "").strip() or "node"
            return [node] + self._node_args() + [cmd]
        return [cmd]

    def runtime_command(self) -> list:
        return self._resolve_runtime()

    def build_command(
        self,
        prompt_file: Path,
        workdir: Path,
        model: str | None = None,
        attach_files: list | None = None,
    ) -> list:
        # 注意：zcode CLI 0.17.1 未提供 --model 选项（实测报 Unknown option），
        # 执行模型由登录账号决定，本机 StartPlan 账号默认即 GLM-5.3-Flash。
        # model 参数保留在签名中以备后续 CLI 版本接入；当前版本作为调用元数据记录。
        if model:
            logger.info(
                f"[agent_cli:{self.name}] 指定模型 {model}（当前 CLI 版本无 --model 选项，"
                "按登录账号默认模型执行）"
            )
        command = self._resolve_runtime() + [
            "-p",
            _INSTRUCTION,
            "--json",
            "--cwd",
            str(workdir.resolve()),
            "--attach",
            str(prompt_file.resolve()),
        ]
        for extra in attach_files or []:
            command += ["--attach", str(Path(extra).resolve())]
        return command

    def parse_output(self, stdout: str) -> str:
        text = (stdout or "").strip()
        if not text:
            raise AgentCliError(f"{self.display_name} 未返回任何输出")

        # 1) 整段 stdout 是单个 JSON 对象（-p + --json 的常规形态）
        payload = self._try_parse_json(text)
        # 2) 或结果夹在多行输出里：从后往前找 JSON 行
        if payload is None:
            for line in reversed(text.splitlines()):
                payload = self._try_parse_json(line.strip())
                if payload is not None:
                    break
        if payload is not None:
            answer = self._extract_text(payload)
            if answer:
                return strip_code_fence(answer)
            raise AgentCliError(
                f"{self.display_name} 返回的 JSON 缺少文本字段: {list(payload.keys())}"
            )

        # 3) 回退：把整个 stdout 当作纯文本答案
        logger.warning(f"[agent_cli:{self.name}] stdout 未能解析出 JSON，按纯文本回退")
        return strip_code_fence(text)

    @staticmethod
    def _try_parse_json(fragment: str):
        """解析候选 JSON 片段。

        兼容两种实测形态：
        - {"type":"result", ...}（部分版本/协议形态）
        - 无 type 字段的 {"sessionId", "response", "usage", ...}（zcode 0.17.1 -p --json 实测）
        """
        if not (fragment.startswith("{") and fragment.endswith("}")):
            return None
        try:
            payload = json.loads(fragment)
        except json.JSONDecodeError:
            return None
        if not isinstance(payload, dict):
            return None
        if payload.get("type") == "result" or ZcodeCli._extract_text(payload):
            return payload
        return None

    @staticmethod
    def _extract_text(payload: dict) -> str:
        for key in ("response", "result", "content", "text"):
            value = payload.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
            # 兼容 result 为嵌套对象（含 stdout/stderr）的返回结构
            if isinstance(value, dict) and isinstance(value.get("stdout"), str):
                inner = value["stdout"].strip()
                if inner:
                    return inner
        return ""
