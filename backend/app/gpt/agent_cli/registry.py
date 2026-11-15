"""agent CLI 实现注册表。

当前仅接入 zcode；codex cli / antigravity cli 为预留扩展位，
接入时实现 AgentCliBase 子类并在此登记即可。
"""
from app.gpt.agent_cli.base import AgentCliBase, AgentCliError
from app.gpt.agent_cli.zcode_cli import ZcodeCli

AGENT_CLI_REGISTRY: dict = {
    "zcode": ZcodeCli(),
    # ---- 预留扩展位（实现后放开） ----
    # "codex": CodexCli(),
    # "antigravity": AntigravityCli(),
}

_RESERVED_KINDS = ("codex", "antigravity")

# agent CLI 型 provider 的 id 约定形如 "<kind>-cli"（如 zcode-cli）
DEFAULT_AGENT_KIND = "zcode"


def resolve(kind: str) -> AgentCliBase:
    impl = AGENT_CLI_REGISTRY.get(kind)
    if impl is None:
        hint = "（已预留扩展位，暂未实现）" if kind in _RESERVED_KINDS else ""
        raise AgentCliError(f"未支持的 agent CLI 类型: {kind}{hint}")
    return impl


def kind_from_provider_id(provider_id: str) -> str:
    """从 provider id 解析具体 CLI 类型：'zcode-cli' -> 'zcode'。"""
    pid = str(provider_id or "")
    if pid.endswith("-cli") and pid[:-4]:
        return pid[:-4]
    return DEFAULT_AGENT_KIND


def is_agent_cli_provider(provider) -> bool:
    """判断 provider dict 是否为 agent CLI 型供应商。"""
    return bool(provider) and provider.get("type") == "agent_cli"
