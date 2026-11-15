// zcode CLI 契约模拟器（测试 fixture）。
// 模拟 ZCode CLI 0.17.1 非交互契约，用于在无法直接运行真实 CLI 的环境下
// 验证 BiliNote agent_cli 集成链路：
//   zcode -p "<instruction>" --json --cwd <dir> --attach <file> [--attach ...]
//   zcode --version
// 输出：{"type":"result","response":"<markdown>"} 单行 JSON（与真实 --json 形态一致）
// 环境变量 STUB_FAIL=1 时以非零码退出，用于验证失败/重试路径。

const fs = require("node:fs");
const path = require("node:path");

function parseArgs(argv) {
  const parsed = { attach: [], json: false, prompt: "", cwd: process.cwd(), version: false };
  const args = argv.slice(2);
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === "--version" || a === "-v") parsed.version = true;
    else if (a === "-p" || a === "--prompt") parsed.prompt = args[++i] ?? "";
    else if (a === "--json") parsed.json = true;
    else if (a === "--cwd") parsed.cwd = args[++i] ?? process.cwd();
    else if (a === "--attach") parsed.attach.push(args[++i] ?? "");
  }
  return parsed;
}

function extractTitle(promptText) {
  const m = promptText.match(/视频标题[：:]\s*(.+)/) || promptText.match(/title[：:]\s*(.+)/i);
  return (m ? m[1] : "未知视频").trim().slice(0, 60);
}

function buildNote(promptText, attachments) {
  const images = attachments.filter((f) => /\.(jpg|jpeg|png)$/i.test(f));
  const lines = [
    `# ${extractTitle(promptText)}`,
    "",
    "> 本笔记由 zcode CLI 契约模拟器（测试 fixture）生成，用于验证 agent_cli 集成链路。",
    "",
    "## 内容概要",
    "",
    `- prompt.md 大小：${Buffer.byteLength(promptText, "utf8")} 字节`,
    `- 附加文件：${attachments.length ? attachments.map((f) => path.basename(f)).join("、") : "无"}`,
    `- 附加截图：${images.length} 张`,
    "",
    "## 章节一：视频开篇",
    "",
    "视频开篇进行基础概念介绍，讲解整体框架与使用场景。",
  ];
  if (images.length) {
    lines.push("", "关键画面如截图所示。 *Screenshot-[00:05]*");
  }
  lines.push(
    "",
    "## 章节二：核心内容",
    "",
    "主体部分围绕核心功能展开，演示具体操作步骤与注意事项。",
    "",
    "## AI 总结",
    "",
    "本视频系统性地介绍了基础入门知识，适合初学者建立整体认知。"
  );
  return lines.join("\n");
}

function main() {
  const args = parseArgs(process.argv);
  if (args.version) {
    console.log("zcode-stub 0.17.1 (contract simulator)");
    process.exit(0);
  }
  if (process.env.STUB_FAIL === "1") {
    process.stderr.write("stub: forced failure for testing\n");
    process.exit(1);
  }
  const promptAttachment = args.attach.find((f) => f.toLowerCase().endsWith(".md"));
  if (!promptAttachment || !fs.existsSync(promptAttachment)) {
    process.stderr.write("stub: prompt.md attachment not found\n");
    process.exit(1);
  }
  const promptText = fs.readFileSync(promptAttachment, "utf8");
  const missingImages = args.attach
    .filter((f) => /\.(jpg|jpeg|png)$/i.test(f))
    .filter((f) => !fs.existsSync(f));
  if (missingImages.length) {
    process.stderr.write(`stub: missing attached images: ${missingImages.join(", ")}\n`);
    process.exit(1);
  }
  const note = buildNote(promptText, args.attach);
  process.stdout.write(JSON.stringify({ type: "result", response: note }) + "\n");
}

main();
