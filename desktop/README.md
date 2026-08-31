# BiliNote Desktop（PC 客户端）

BiliNote 的 Windows PC 客户端：把 **admin 管理功能 + Python backend 内置服务** 集成进一个独立的桌面应用，主打「本地视频 → AI 笔记」的一站式体验。

## 功能总览

- **单视频任务**：与 admin 一致的「新建任务 / 历史记录」双 Tab——视频来源（B 站 / YouTube / 抖音 / 快手 / **本地视频直读**）、模型与笔记配置、当前任务进度卡，历史记录支持阅读 / 导出 / 停止 / 重新生成，任务完成后自动打开阅读器。
- **批量合集任务**：与 admin 一致的列表 + 三步式新建 / 追加弹窗（分析视频列表 → 勾选 → 配置）+ 详情抽屉（子任务表、批量导出 / 删除、运行日志）。本地模式选择文件夹 / 多选文件后**直接读盘、零上传**。
- **专业阅读器**：Markdown + KaTeX 公式 + 视频截图，目录导航、复制、导出。
- **设置**：
  - AI 模型供应商与模型管理
  - 音频转写引擎与 Whisper 模型（下载 + **本地模型零拷贝导入**：选一个含 `model.bin` 的模型目录或 `models--*` HF 缓存目录即可，网页版 / Docker 下载过的模型可直接迁移）
  - 平台 Cookie / 网络代理
  - **数据目录**（笔记产物、截图、缓存、数据库、模型、日志的统一存放处，默认 `%LOCALAPPDATA%\BiliNote`，可自定义）
  - **局域网服务**：内置服务监听 `0.0.0.0`，展示局域网地址一键复制，供 Android 客户端连接阅读产物
- **部署监控**：后端 / FFmpeg / CUDA / 转写模型实时状态 + 安装路径诊断。

## 架构

```
desktop/
├── src/                 # React 19 + Vite + TS 前端（自研轻量组件，无重型 UI 库）
├── src-tauri/           # Tauri 2 壳（Rust）
│   └── src/lib.rs       # sidecar 生命周期、端口探测、局域网地址、设置持久化
└── scripts/
    ├── build_sidecar.bat    # PyInstaller onedir 打包 backend → Tauri externalBin（固定用 biliNote conda env 的 Python）
    ├── build_cuda_pack.bat  # 组装可选 CUDA 加速包（cuBLAS/cuDNN DLL）
    └── build_portable.bat   # 组装绿色便携版（默认含 CUDA 加速包）
```

- **Python backend 复用仓库根目录的 `backend/` 源码**，以 PyInstaller **onedir** sidecar 方式内嵌，随 Tauri 一起打包分发。相比 onefile 免去启动解压，冷启动秒级。
  - 打包时排除了 torch（约 3.9GB）：CUDA 检测改由 ctranslate2 完成（`get_cuda_device_count`，只依赖 NVIDIA 驱动），GPU 推理通过 **CUDA 加速包** 提供（见下节），没有加速包时自动回退 CPU（int8）转写。转写失败还会在运行期自动从 CUDA 降级 CPU 重试，任务不会直接失败。
- **CUDA 加速包（可选，启用 GPU 转写）**：把 cuBLAS/cuDNN 运行库放到程序目录的 `cuda\` 子目录（便携版构建脚本默认自动组装）：
  ```powershell
  # 从本机 torch 安装目录抽取所需 DLL（cublas*/cudnn*/nvrtc* 等，约 1.6GB）
  desktop\scripts\build_cuda_pack.bat [torch_lib_dir] [目标目录]
  ```
  后端启动时会自动探测 `BILINOTE_CUDA_DIR` > `<exe目录>\cuda` > `<数据根>\cuda` 并注册 DLL 搜索路径；部署监控页会显示「GPU 转写就绪 / 缺少 CUDA 运行库」的分层状态。便携版 zip 因包含加速包约 1.3GB，不需要 GPU 可设 `set BILINOTE_SKIP_CUDA=1` 后再跑 `build_portable.bat`。
- 桌面端内置模式下 whisper 模型下载默认走 hf-mirror 镜像（`HF_ENDPOINT`，与 Dockerfile 一致），已下载 / 已导入的模型完全离线可用。
- Tauri 启动 sidecar 时注入关键环境变量：
  - `BILINOTE_DATA_DIR`：数据根目录，backend 的数据库/笔记产物/截图/上传缓存/配置/模型/日志全部落到其下（backend 通过 `app/utils/runtime_paths.py` 统一解析，未设置时保持原有行为，web/docker 部署不受影响）
  - `ALLOW_LOCAL_PATH_INPUT=1`：启用「本地路径直读」（`/api/local/inspect`、`/api/batch/preview/local_paths`、绝对路径的 `generate_note`）
  - `BACKEND_PORT`：默认 8483，被占用时自动顺延
- 应用退出时强制杀掉 sidecar，不留孤儿进程、不占端口。
- Android 客户端（仓库 `android/`）在设置里填入 PC 的局域网地址即可浏览/导出笔记，依赖的接口与现有 backend 完全一致（`/api/sys_health`、`/api/single/tasks`、`/api/task_status`、`/api/export_note`、`/api/batch/*`）。

## 开发

前提：Node 20+ / pnpm、Rust（tauri 依赖）、conda 环境 `biliNote`（backend 依赖）。

```powershell
cd desktop
pnpm install

# 方式 A：纯前端开发（浏览器打开，连 localhost:8483 的 backend）
pnpm dev

# 方式 B：完整桌面开发（Tauri 壳 + 自动拉起本机 Python backend）
$env:BILINOTE_DEV_PYTHON = "E:\ProgramData\.conda\envs\biliNote\python.exe"   # 可选，默认 python
pnpm tauri dev
```

开发模式下 sidecar 逻辑会直接用 `BILINOTE_DEV_PYTHON`（默认 `python`）在 `backend/` 目录用 `python main.py` 拉起服务，无需打包。

## 构建 & 发布

```powershell
# 1. 打包 backend sidecar（需要先激活 biliNote conda 环境）
conda activate biliNote
desktop\scripts\build_sidecar.bat

# 2. 打包桌面应用（产出 NSIS 安装包）
cd desktop
pnpm tauri build
# 产物：desktop/src-tauri/target/release/bundle/nsis/*.exe
```

## 绿色便携版（免安装）

不想安装也可以用便携版：数据全部跟随程序目录，**删除目录即卸载**，不写注册表、不写用户目录。

```powershell
# 前提：sidecar 已打包、release 已编译
desktop\scripts\build_portable.bat
# 产物：
#   desktop/dist-portable/BiliNote-Portable-x64/          （直接运行 BiliNote.exe）
#   desktop/dist-portable/BiliNote-Desktop-Portable-x64.zip （分发给他人）
```

便携模式原理：程序目录里的 `portable.flag` 标记文件启用便携模式，此时数据根目录变为
`<程序目录>\data`（笔记产物、数据库、模型、设置 JSON 全部在其中）。给他人分发就发 zip，
解压到任意目录即可运行。注意两点：

- 解压路径建议保持纯英文（PyInstaller sidecar 对含非 ASCII/空格的路径敏感，命中时应用内会显示诊断横幅）；
- 便携版不内置 WebView2 运行时安装逻辑（NSIS 安装包会自动装），Win10 21H2 / Win11 之前的
  系统如缺 WebView2 需先手动安装 Evergreen Runtime。

## 与现有部署的关系

- `backend/` 的所有改动向后兼容：不设置 `BILINOTE_DATA_DIR` / `ALLOW_LOCAL_PATH_INPUT` 时行为与之前完全一致，web / docker / 扩展部署不受影响。
- `BillNote_frontend`（Tauri 旧壳）与 `BillNote_admin`（web 管理台）保持可用；desktop 是独立的体验优先实现。
