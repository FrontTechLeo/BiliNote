from openai import OpenAI

from app.gpt.base import GPT
from app.gpt.agent_cli.agent_cli_gpt import AgentCliGPT
from app.gpt.provider.OpenAI_compatible_provider import OpenAICompatibleProvider
from app.gpt.universal_gpt import UniversalGPT
from app.models.model_config import ModelConfig


class GPTFactory:
    @staticmethod
    def from_config(config: ModelConfig) -> GPT:
        # agent CLI 型供应商（zcode cli 等）：直接调用本机 agent 命令行
        if config.provider == "agent_cli":
            return AgentCliGPT(config)
        client = OpenAICompatibleProvider(api_key=config.api_key, base_url=config.base_url).get_client
        return UniversalGPT(client=client, model=config.model_name)