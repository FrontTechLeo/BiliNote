---
name: bilinote-video-notes
description: Use when a user asks to convert one or more local video files or a local video folder into BiliNote-style, image-rich Markdown notes; reject URLs and other remote inputs.
---

# BiliNote Video Notes

Use this skill when the input is a local video file or a local folder containing videos. Do not accept, fetch, or process URLs. Supported video extensions are `.mp4`, `.mkv`, `.mov`, `.avi`, `.webm`, and `.m4v`.

## Prepare the video artifacts

On Windows, run from the project root and resolve the existing conda environment named `BiliNote` with PowerShell. Run this mandatory resolver and preflight, then reuse `$python` for every Python command below:

```powershell
$envListJson = conda env list --json
if ($LASTEXITCODE -ne 0) { throw "conda env list failed: $LASTEXITCODE" }
$envList = $envListJson | ConvertFrom-Json
$envPath = @($envList.envs | Where-Object { ($_ -split '[\\/]+' | Where-Object { $_ } | Select-Object -Last 1) -ieq 'BiliNote' }) | Select-Object -First 1
if (-not $envPath) { throw "Could not resolve conda environment BiliNote" }
$python = Join-Path $envPath 'python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw "Python executable not found: $python" }
& $python -c "import sys; from pathlib import Path; sys.path.insert(0, str(Path('backend').resolve())); print(sys.executable); from app.downloaders.local_downloader import LocalDownloader; from app.transcriber.whisper import WhisperTranscriber; from app.utils.video_reader import VideoReader; print('backend imports: ok')"
if ($LASTEXITCODE -ne 0) { throw "Backend preflight failed: $LASTEXITCODE" }
```

If conda listing, environment resolution, executable existence, or backend imports fail, stop and report the exact command failure and error. Never use bare `python`, `conda run` as a fallback, another interpreter, pip-install dependencies, or create `.venv` or `venv`; do not continue with preparation or validation.

```powershell
& $python .agents/skills/bilinote-video-notes/scripts/prepare_video_note.py --input <local-video-or-folder> --workspace <project-root> --model base --frame-interval 6 --grid-size 3x3
```

`--task-id <id>` is optional. The script recursively discovers videos in a folder and writes artifacts under `<project-root>/.bilinote-video-notes/<task-id>/`. It returns success only when every video has successful transcription and frame extraction; otherwise it reports partial failure and exits non-zero.

Read the task directory's `run.json` and top-level `manifest.json`, then read each successful video's `manifest.json`, `transcript.txt` or `transcript.json`, and inspect the `frames/` and `grids/` images. Use the current agent's visual and text capabilities to understand the video. Do not configure or call an additional video model, LLM, OpenAI API, or other LLM API.

The per-video manifest contains `note_filename`, a readable Markdown filename derived from the original video stem. Use that exact filename in the video artifact directory; do not create a generic `note.md`. If an older manifest has no `note_filename`, normalize the original stem by collapsing whitespace, replacing Windows-invalid filename characters, trimming trailing spaces/dots, and appending `.md`.

## Write notes

For every video whose preparation succeeded, write the manifest's formatted Markdown filename inside that video's artifact directory. Use relative local image links such as `![](frames/frame_00_00.jpg)*`; never reference external URLs or the project's static service.

After writing each successful video's formatted Markdown, run the format validator:

```powershell
& $python .agents/skills/bilinote-video-notes/scripts/validate_note_format.py --note <path-to-note>
```

If validation fails, correct the note and rerun the validator before reporting the video as complete.

Match the old BiliNote workflow artifact format exactly:

- Start with a standalone H1 title derived from the formatted original video stem: `# 视频标题`.
- Add `## 目录` immediately after the title. List the document sections with clickable Markdown anchor links, for example `- [导入素材](#导入素材)`.
- Use timestamped main chapter headings in the form `## 章节名 (mm:ss)*`. Keep headings concise and instructional. Use `###` for subsections beneath a chapter.
- For each major visual operation, choose enough representative frames to make the steps understandable. As a rule of thumb, include one image for each major visual step, not only a small number of overview grid images. Embed local relative frame paths directly, for example `![](frames/frame_00_00.jpg)*`; do not add `Screenshot` markers. Use `grids/` only when a grid genuinely communicates an overview better than individual frames.
- End with `## AI 总结`, containing a concise Chinese synthesis grounded in the transcript and frames.
- Return plain Markdown, never a fenced Markdown code block. Do not add custom metadata tables or unrelated report sections.

The note must include, when applicable:

- a title;
- a concise summary;
- chapters or a timestamped timeline;
- key points;
- facts and terminology;
- action items or conclusions;
- relevant local images from `frames/` or `grids/`, with density matched to the major visual steps.

Only state what the transcript or images support. Mark information that cannot be confirmed from the transcript or frames as uncertain (for example, `不确定：…`). Preserve timestamps when available and distinguish direct evidence from inference.

## Batch, failure, and recovery

- Process every video listed in `run.json`; one failed video must not prevent notes for other successful videos.
- Use per-video `manifest.json` to identify whether transcription or frame extraction failed. Do not write a normal note for a failed video; report the failure and its error.
- For a retry after correcting local input or parameters, rerun the same `$python`-prefixed command, optionally reusing the same `--task-id` to inspect/update the same task directory. Use a new task ID when a clean run is preferable. Do not retry missing conda environments or dependencies with another interpreter or an installation.
- At the end, report the task directory, total/successful/failed video counts, each generated formatted Markdown filename, and any failures or uncertainty.

Common errors include a missing input path, an unsupported extension, an empty folder, invalid `--grid-size` (it must be `CxR`), a non-positive `--frame-interval`, unavailable transcription dependencies, and frame-reader failures. Correct local input or parameters first; for conda, resolver, executable, or dependency failures, stop and report the exact failure. Do not work around failures with URLs, another interpreter, package installation, `conda run`, or extra model/API calls.
