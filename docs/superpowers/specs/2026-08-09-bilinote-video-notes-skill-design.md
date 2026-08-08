# BiliNote 本地视频转图文笔记 Skill 设计规格

## 目标

新增项目内 skill `.agents/skills/bilinote-video-notes/`，让 Codex 能将本地视频文件或视频文件夹转换为接近现有 BiliNote 风格的图文 Markdown 笔记。

约束：

- 只接受本地视频文件和本地视频文件夹，不处理 URL、下载器或平台字幕。
- 语音转写复用项目现有 `WhisperTranscriber` / faster-whisper 本地模型配置。
- 视频理解、内容组织和 Markdown 写作由当前 agent 完成，不增加额外的大模型 API 配置或调用链。
- 所有中间过程和测试产物放入专用目录，并同步加入 `.gitignore`。
- 保留可复用的转写、抽帧和产物清单，便于失败后重试和自动化测试。

## 方案

采用“SKILL.md 工作流 + 本地确定性准备脚本”的薄适配方案：

```text
本地视频 / 视频文件夹
        |
        v
prepare_video_note.py
  - 校验并枚举视频
  - 创建任务工作区
  - 调用 BiliNote WhisperTranscriber
  - 调用 BiliNote VideoReader / ffmpeg 抽帧
  - 写入 manifest、transcript、frames
        |
        v
Codex 当前 agent
  - 读取转写文本和帧图
  - 结合视频帧进行内容理解
  - 生成图文 Markdown
        |
        v
任务目录中的 note.md 与资源文件
```

不复制项目内部的 Whisper 或视频处理实现，避免模型配置、缓存目录和抽帧行为发生分叉。

## skill 目录结构

```text
.agents/skills/bilinote-video-notes/
├── SKILL.md
├── agents/openai.yaml
└── scripts/
    └── prepare_video_note.py
```

`SKILL.md` 只保留触发条件、执行顺序、输入约定、agent 视频理解提示和 Markdown 输出要求；确定性和易出错的文件处理放在脚本中。

## 输入与工作区

脚本接受一个本地文件或文件夹路径，并可通过参数覆盖工作区、模型、抽帧间隔和网格尺寸。默认工作区为项目根目录下的 `.bilinote-video-notes/`，每次任务使用独立子目录：

```text
.bilinote-video-notes/
└── <task-id>/
    ├── manifest.json
    ├── videos/<相对输入文件名>/
    │   ├── transcript.json
    │   ├── transcript.txt
    │   ├── frames/frame_*.jpg
    │   ├── grids/grid_*.jpg
    │   └── note.md
    └── run.json
```

支持的输入扩展名至少包括 `.mp4`、`.mkv`、`.mov`、`.avi`、`.webm`、`.m4v`；文件夹递归扫描，按稳定排序处理。路径不存在、没有视频或输入不是文件/文件夹时应给出明确错误并返回非零退出码。

## 处理流程

1. 规范化输入路径和输出目录，拒绝把输出目录当作输入递归扫描。
2. 为每个视频建立稳定、文件系统安全的任务名和独立目录。
3. 将视频转为临时音频，或直接交给项目现有转写路径；不得把生成的音频写回原视频目录。
4. 复用项目的 `WhisperTranscriber` 和当前 Whisper 配置；脚本允许显式传入模型名，但不新增 API key 或远程模型配置。
5. 复用 `VideoReader` 生成时间有序的帧图和可供 agent 快速浏览的网格图。抽帧失败时保留转写结果并在 manifest 中记录警告。
6. 写入 JSON/纯文本转写、帧文件相对路径、视频元数据和运行状态。
7. skill 指导 agent 读取这些产物，结合转写和图片完成视频理解，直接写出 `note.md`。
8. 批量输入时每个视频独立产出；单个视频失败不应覆盖其它视频结果，并在顶层 `run.json` 汇总成功、失败和错误原因。

## Markdown 产物

每个 `note.md` 至少包含：标题、摘要、章节或时间线、关键观点、事实/术语、行动项或结论（适用时）以及与内容相关的图片。图片优先引用任务目录内的相对路径，例如 `![关键画面](frames/frame_00_30.jpg)` 或网格图；不生成指向项目静态服务或外部 URL 的链接。

agent 应避免臆造帧中不可见的内容；无法从转写或画面确认的信息要标注不确定性。图片只用于补充理解，不应把每一帧都塞进 Markdown。

## 错误处理与可重复运行

- 脚本应使用明确的退出码，并把每个视频的错误写入 manifest/run 汇总。
- 已完成且校验通过的 `transcript.json` 可复用；模型或参数改变时应通过新的 task-id 或显式覆盖重新运行。
- 中间目录不删除用户输入视频，不修改原视频所在目录。
- 进程中断后允许保留任务目录，便于诊断；skill 应支持从现有任务目录继续生成 Markdown。

## 测试策略

使用项目现有测试工具和本地可用文件，默认不加载真实 Whisper 权重、不调用网络大模型：

- 单文件输入能生成预期目录和 manifest。
- 文件夹输入能递归发现并稳定排序多个视频。
- 非法路径、空文件夹和不支持扩展名返回清晰错误。
- 通过 monkeypatch/stub 验证脚本调用项目 WhisperTranscriber 和 VideoReader，而不是复制实现。
- 模拟转写失败、抽帧失败和批量部分失败，确认状态汇总和其它任务产物仍保留。
- 运行 skill 结构校验，并验证 `.gitignore` 会忽略 `.bilinote-video-notes/`。

测试生成的媒体、JSON、Markdown 和缓存全部位于 `.bilinote-video-notes/`，不得污染 `backend/note_results`、`backend/static` 或原视频目录。

## 非目标

- 不新增 Web API、前端页面、数据库表或平台下载能力。
- 不在 skill 中配置 OpenAI、兼容 OpenAI 或其它视频大模型 API。
- 不替换项目现有的在线平台转写、GPT provider 或正式笔记生成链路。
- 不把大体积视频、音频、图片或测试笔记提交到 git。
