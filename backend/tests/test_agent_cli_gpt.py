import base64
import json
import os
import pathlib
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.gpt.agent_cli.agent_cli_gpt import AgentCliGPT
from app.gpt.agent_cli.base import AgentCliError, TaskCancelled
from app.gpt.agent_cli.registry import (
    is_agent_cli_provider,
    kind_from_provider_id,
    resolve,
)
from app.gpt.agent_cli.zcode_cli import ZcodeCli
from app.gpt.gpt_factory import GPTFactory
from app.gpt.universal_gpt import UniversalGPT
from app.models.gpt_model import GPTSource
from app.models.model_config import ModelConfig
from app.models.transcriber_model import TranscriptSegment


def make_config(model_name="zcode-agent", provider="agent_cli", agent_kind="zcode"):
    return ModelConfig(
        name="ZCode CLI",
        provider=provider,
        api_key="",
        base_url="",
        model_name=model_name,
        agent_kind=agent_kind,
    )


def make_source(images=None, cancel=None, checkpoint_key="unittest_task"):
    return GPTSource(
        title="测试视频",
        segment=[
            TranscriptSegment(start=0, end=5, text="你好"),
            TranscriptSegment(start=5, end=9, text="世界"),
        ],
        tags=["t1"],
        video_img_urls=images,
        checkpoint_key=checkpoint_key,
        cancel_check=cancel,
    )


class TestRegistry(unittest.TestCase):
    def test_resolve_zcode(self):
        self.assertIsInstance(resolve("zcode"), ZcodeCli)

    def test_resolve_unknown_raises(self):
        # codex / antigravity 为预留位，未实现前调用应报错
        with self.assertRaises(AgentCliError):
            resolve("codex")
        with self.assertRaises(AgentCliError):
            resolve("not-exist")

    def test_kind_from_provider_id(self):
        self.assertEqual(kind_from_provider_id("zcode-cli"), "zcode")
        self.assertEqual(kind_from_provider_id("codex-cli"), "codex")
        self.assertEqual(kind_from_provider_id(""), "zcode")

    def test_is_agent_cli_provider(self):
        self.assertTrue(is_agent_cli_provider({"type": "agent_cli"}))
        self.assertFalse(is_agent_cli_provider({"type": "built-in"}))
        self.assertFalse(is_agent_cli_provider(None))


class TestZcodeCli(unittest.TestCase):
    def setUp(self):
        self.impl = ZcodeCli()

    def test_build_command_with_attach(self):
        cmd = self.impl.build_command(
            prompt_file=Path("X:/w/prompt.md"),
            workdir=Path("X:/w"),
            attach_files=[Path("X:/w/img_01.jpg")],
        )
        self.assertEqual(cmd[0], "zcode")
        self.assertEqual(cmd[1], "-p")
        self.assertIn("prompt.md", cmd[2])
        self.assertNotIn("--model", cmd)  # CLI 无 --model 选项
        idx = cmd.index("--json")
        self.assertEqual(cmd[idx], "--json")
        self.assertEqual(cmd[cmd.index("--cwd") + 1], str(Path("X:/w").resolve()))
        # prompt.md 与图片均随 --attach 附加
        self.assertEqual(cmd.count("--attach"), 2)
        last_attach = len(cmd) - 1 - cmd[::-1].index("--attach")
        self.assertIn("img_01.jpg", cmd[last_attach + 1])

    def test_build_command_js_entry_prepends_node(self):
        # ZCODE_CLI_PATH 指向 .cjs 入口时自动经 node 运行，并注入兼容垫片
        with mock.patch.dict(
            os.environ, {"ZCODE_CLI_PATH": "E:/tools/zcode.cjs"}
        ):
            cmd = self.impl.build_command(prompt_file=Path("p.md"), workdir=Path("."))
        self.assertEqual(cmd[0], "node")
        self.assertIn("--require", cmd)
        self.assertEqual(cmd[3], "E:/tools/zcode.cjs")

    def test_build_command_js_entry_compat_shim_disabled(self):
        # ZCODE_CLI_NODE_COMPAT 置空 → 不注入垫片
        env = {"ZCODE_CLI_PATH": "E:/tools/zcode.cjs", "ZCODE_CLI_NODE_COMPAT": ""}
        with mock.patch.dict(os.environ, env):
            cmd = self.impl.build_command(prompt_file=Path("p.md"), workdir=Path("."))
        self.assertEqual(cmd[:2], ["node", "E:/tools/zcode.cjs"])

    def test_cli_command_env_override(self):
        with mock.patch.dict(os.environ, {"ZCODE_CLI_PATH": "Z:/no/such/zcode.exe"}):
            self.assertEqual(self.impl.cli_command(), "Z:/no/such/zcode.exe")

    def test_runtime_command_js_entry(self):
        # check_available 等场景同样经 node 运行 .cjs 入口
        with mock.patch.dict(os.environ, {"ZCODE_CLI_PATH": "E:/tools/zcode.cjs"}):
            runtime = self.impl.runtime_command()
        self.assertEqual(runtime[0], "node")
        self.assertEqual(runtime[-1], "E:/tools/zcode.cjs")
        with mock.patch.dict(os.environ, {"ZCODE_CLI_PATH": "C:/bin/zcode.exe"}):
            self.assertEqual(self.impl.runtime_command(), ["C:/bin/zcode.exe"])

    def test_parse_output_json_result(self):
        stdout = json.dumps({"type": "result", "response": "# 笔记\n正文"})
        self.assertEqual(self.impl.parse_output(stdout), "# 笔记\n正文")

    def test_parse_output_json_typeless_response(self):
        # zcode 0.17.1 -p --json 实测形态：无 type 字段
        stdout = json.dumps(
            {"sessionId": "sess_x", "response": "# 笔记\n正文", "usage": {"totalTokens": 1}}
        )
        self.assertEqual(self.impl.parse_output(stdout), "# 笔记\n正文")

    def test_parse_output_json_nested_result(self):
        stdout = "noise line\n" + json.dumps(
            {"type": "result", "result": {"stdout": "answer"}}
        )
        self.assertEqual(self.impl.parse_output(stdout), "answer")

    def test_parse_output_plain_fallback(self):
        self.assertEqual(self.impl.parse_output("plain answer"), "plain answer")

    def test_parse_output_strip_fence(self):
        self.assertEqual(self.impl.parse_output("```markdown\n# x\n```"), "# x")

    def test_parse_output_empty_raises(self):
        with self.assertRaises(AgentCliError):
            self.impl.parse_output("")

    def test_check_available_missing_binary(self):
        with mock.patch.dict(os.environ, {"ZCODE_CLI_PATH": "Z:/no/such/zcode.exe"}):
            ok, detail = self.impl.check_available()
        self.assertFalse(ok)
        self.assertIn("zcode.exe", detail)


class TestAgentCliGpt(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        patcher = mock.patch(
            "app.gpt.agent_cli.agent_cli_gpt.get_note_output_dir",
            return_value=self._tmp.name,
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        self.gpt = AgentCliGPT(make_config())

    def _workspace(self, key="unittest_task") -> Path:
        return Path(self._tmp.name) / "agent_cli" / key

    def test_list_models(self):
        self.assertEqual(self.gpt.list_models(), ["zcode-agent", "GLM-5.3-Flash"])

    def test_cli_model_name_alias_maps_to_none(self):
        self.assertIsNone(self.gpt._cli_model_name())
        custom = AgentCliGPT(make_config(model_name="glm-5.3"))
        self.assertEqual(custom._cli_model_name(), "glm-5.3")

    def test_summarize_writes_prompt_and_images(self):
        png = base64.b64encode(b"fake-image-bytes").decode()
        source = make_source(images=[f"data:image/png;base64,{png}"])
        captured = {}

        def fake_run_cli(impl, prompt_file, workdir, model=None, cancel_check=None, attach_files=None):
            captured["prompt"] = prompt_file.read_text(encoding="utf-8")
            captured["files"] = sorted(p.name for p in workdir.iterdir())
            captured["model"] = model
            captured["attach_files"] = [p.name for p in (attach_files or [])]
            return json.dumps({"type": "result", "response": "```markdown\n# 标题\n```"})

        with mock.patch(
            "app.gpt.agent_cli.agent_cli_gpt.run_cli", side_effect=fake_run_cli
        ):
            markdown = self.gpt.summarize(source)

        self.assertEqual(markdown, "# 标题")
        self.assertIn("img_01.png", captured["files"])
        self.assertIn("prompt.md", captured["files"])
        self.assertIn("img_01.png", captured["prompt"])  # 图片文件路径进入 prompt
        self.assertEqual(captured["attach_files"], ["img_01.png"])  # 图片随 --attach 附加
        self.assertIn("你好", captured["prompt"])  # 字幕进入 prompt
        self.assertIsNone(captured["model"])  # 默认别名 → 不传 --model
        self.assertFalse(self._workspace().exists())  # 成功后清理工作目录

    def test_summarize_cancelled_before_run(self):
        source = make_source(cancel=lambda: True)
        with mock.patch("app.gpt.agent_cli.agent_cli_gpt.run_cli") as fake:
            with self.assertRaises(TaskCancelled):
                self.gpt.summarize(source)
        fake.assert_not_called()

    def test_summarize_failure_keeps_workdir(self):
        source = make_source()
        with mock.patch(
            "app.gpt.agent_cli.agent_cli_gpt.run_cli",
            side_effect=AgentCliError("boom"),
        ):
            with self.assertRaises(AgentCliError):
                self.gpt.summarize(source)
        # 失败时保留工作目录用于排查
        self.assertTrue(self._workspace().exists())
        self.assertTrue((self._workspace() / "prompt.md").exists())

    def test_complete_text(self):
        captured = {}

        def fake_run_cli(impl, prompt_file, workdir, model=None, cancel_check=None):
            # 在清理前捕获 prompt 内容
            captured["prompt"] = prompt_file.read_text(encoding="utf-8")
            return json.dumps({"type": "result", "response": "回答内容"})

        with mock.patch(
            "app.gpt.agent_cli.agent_cli_gpt.run_cli", side_effect=fake_run_cli
        ):
            answer = self.gpt.complete_text("问题 prompt")
        self.assertEqual(answer, "回答内容")
        self.assertIn("问题 prompt", captured["prompt"])


class TestFactoryRouting(unittest.TestCase):
    def test_agent_cli_config_routes_to_agent_cli_gpt(self):
        gpt = GPTFactory.from_config(make_config())
        self.assertIsInstance(gpt, AgentCliGPT)

    def test_openai_compatible_path_unchanged(self):
        # 回归：非 agent_cli 类型仍走 UniversalGPT（OpenAI 兼容 API）
        cfg = ModelConfig(
            name="t",
            provider="built-in",
            api_key="sk-test-123456",
            base_url="https://example.com/v1",
            model_name="gpt-x",
        )
        gpt = GPTFactory.from_config(cfg)
        self.assertIsInstance(gpt, UniversalGPT)


if __name__ == "__main__":
    unittest.main()
