"""agent CLI 后端包：通过本机 AI 代理命令行（zcode cli 等）实现 AI 能力。

与 OpenAI 兼容 API 后端（universal_gpt.py）并列的一种 GPT 后端类别，
由 GPTFactory 按 ModelConfig.provider == "agent_cli" 分发。
"""
from app.gpt.agent_cli.base import AgentCliBase, AgentCliError, run_cli
from app.gpt.agent_cli.registry import (
    AGENT_CLI_REGISTRY,
    is_agent_cli_provider,
    resolve,
)
from app.gpt.agent_cli.agent_cli_gpt import AgentCliGPT

__all__ = [
    "AgentCliBase",
    "AgentCliError",
    "AgentCliGPT",
    "AGENT_CLI_REGISTRY",
    "run_cli",
    "is_agent_cli_provider",
    "resolve",
]
