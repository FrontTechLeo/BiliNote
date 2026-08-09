---
name: bilinote-video-notes
description: Use when a user asks to convert one or more local video files or a local video folder into BiliNote-style, image-rich Markdown notes; reject URLs and other remote inputs.
---

# BiliNote Video Notes

Use this skill when the input is a local video file or a local folder containing videos. Do not accept, fetch, or process URLs. Supported video extensions are `.mp4`, `.mkv`, `.mov`, `.avi`, `.webm`, and `.m4v`.

## Prepare the video artifacts

Run from the project root:

```bash
python .agents/skills/bilinote-video-notes/scripts/prepare_video_note.py --input <local-video-or-folder> --workspace <project-root> --model base --frame-interval 6 --grid-size 3x3
```

`--task-id <id>` is optional. The script recursively discovers videos in a folder and writes artifacts under `<project-root>/.bilinote-video-notes/<task-id>/`. It returns success only when every video has successful transcription and frame extraction; otherwise it reports partial failure and exits non-zero.

Read the task directory's `run.json` and top-level `manifest.json`, then read each successful video's `manifest.json`, `transcript.txt` or `transcript.json`, and inspect the `frames/` and `grids/` images. Use the current agent's visual and text capabilities to understand the video. Do not configure or call an additional video model, LLM, OpenAI API, or other LLM API.

## Write notes

For every video whose preparation succeeded, write `note.md` inside that video's artifact directory. Use relative local image links such as `![关键画面](grids/grid_1.jpg)`; never reference external URLs or the project's static service.

The note must include, when applicable:

- a title;
- a concise summary;
- chapters or a timestamped timeline;
- key points;
- facts and terminology;
- action items or conclusions;
- relevant images from `frames/` or `grids/`.

Only state what the transcript or images support. Mark information that cannot be confirmed from the transcript or frames as uncertain (for example, `不确定：…`). Preserve timestamps when available and distinguish direct evidence from inference.

## Batch, failure, and recovery

- Process every video listed in `run.json`; one failed video must not prevent notes for other successful videos.
- Use per-video `manifest.json` to identify whether transcription or frame extraction failed. Do not write a normal note for a failed video; report the failure and its error.
- For a retry, rerun the same command, optionally reusing the same `--task-id` to inspect/update the same task directory. Use a new task ID when a clean run is preferable.
- At the end, report the task directory, total/successful/failed video counts, each generated `note.md`, and any failures or uncertainty.

Common errors include a missing input path, an unsupported extension, an empty folder, invalid `--grid-size` (it must be `CxR`), a non-positive `--frame-interval`, unavailable transcription dependencies, and frame-reader failures. Correct local input or parameters first; do not work around failures with URLs or extra model/API calls.
