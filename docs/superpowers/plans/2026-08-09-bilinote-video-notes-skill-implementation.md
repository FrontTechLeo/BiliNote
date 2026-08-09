# BiliNote Video Notes Skill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a project-local Codex skill that prepares local videos with BiliNote's existing Whisper and frame extraction logic, then guides the current agent to produce image-rich Markdown notes without another LLM API.

**Architecture:** Keep deterministic media preparation in `prepare_video_note.py`; keep agent reasoning and Markdown composition in `SKILL.md`. The script imports BiliNote backend modules only at runtime, writes all task state under `.bilinote-video-notes/`, and isolates each input video so batch failures do not erase successful results.

**Tech Stack:** Python 3.10+, argparse, pathlib, JSON, pytest, BiliNote `WhisperTranscriber`, BiliNote `VideoReader`, ffmpeg through existing project helpers, Codex skill metadata.

---

### Task 1: Define the preparation-script contract with tests

**Files:**
- Create: `.agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py`
- Create: `.agents/skills/bilinote-video-notes/scripts/prepare_video_note.py`

- [ ] **Step 1: Write failing tests for input discovery and safe task layout**

Test these public behaviors before implementation:

```python
def test_discover_videos_accepts_one_file_and_returns_absolute_path(tmp_path):
    video = tmp_path / "lesson.MP4"
    video.write_bytes(b"video")
    assert discover_videos(video) == [video.resolve()]


def test_discover_videos_recurses_and_sorts_supported_extensions(tmp_path):
    (tmp_path / "z" ).mkdir()
    (tmp_path / "a.mp4").write_bytes(b"a")
    (tmp_path / "z" / "b.MKV").write_bytes(b"b")
    (tmp_path / "ignore.txt").write_text("ignore")
    assert discover_videos(tmp_path) == [
        (tmp_path / "a.mp4").resolve(),
        (tmp_path / "z" / "b.MKV").resolve(),
    ]


def test_discover_videos_rejects_missing_and_empty_inputs(tmp_path):
    with pytest.raises(InputError, match="不存在"):
        discover_videos(tmp_path / "missing.mp4")
    with pytest.raises(InputError, match="没有找到支持的视频文件"):
        discover_videos(tmp_path / "empty")


def test_task_directory_contains_manifest_and_per_video_workspace(tmp_path):
    video = tmp_path / "demo.mp4"
    video.write_bytes(b"video")
    task = create_task_workspace(tmp_path / "workspace", [video])
    assert (task / "manifest.json").exists()
    assert (task / "videos" / "demo" / "frames").is_dir()
    assert (task / "videos" / "demo" / "grids").is_dir()
```

Keep imports lightweight so these tests do not load faster-whisper or ffmpeg at collection time.

- [ ] **Step 2: Run the focused tests and verify the expected RED state**

Run:

```powershell
python -m pytest .agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py -q
```

Expected: collection or test failures because the skill script and its public helpers do not exist yet.

- [ ] **Step 3: Implement only the input/workspace helpers needed by the tests**

Define:

```python
SUPPORTED_EXTENSIONS = frozenset({".mp4", ".mkv", ".mov", ".avi", ".webm", ".m4v"})


class InputError(ValueError):
    pass


def discover_videos(input_path: Path) -> list[Path]: ...


def create_task_workspace(workspace_root: Path, videos: list[Path], task_id: str | None = None) -> Path: ...
```

Normalize all paths, reject a non-file/non-directory, recurse only through directories, sort by normalized case-insensitive path, and use sanitized unique names for video directories. Never delete an existing workspace automatically; create a new task id when the requested id already exists.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the same pytest command. Expected: all discovery and workspace tests pass.

- [ ] **Step 5: Commit the tested contract**

```powershell
git add .agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py .agents/skills/bilinote-video-notes/scripts/prepare_video_note.py
git commit -m "test: define local video note preparation contract"
```

### Task 2: Add mocked BiliNote preparation and batch status handling

**Files:**
- Modify: `.agents/skills/bilinote-video-notes/scripts/prepare_video_note.py`
- Modify: `.agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py`

- [ ] **Step 1: Add failing tests for runtime integration and partial failure**

Use monkeypatch fakes for `WhisperTranscriber`, `LocalDownloader.convert_to_mp3`, and `VideoReader`. Assert that the script imports and calls the existing BiliNote classes rather than implementing a second transcription path:

```python
def test_prepare_video_calls_existing_transcriber_and_writes_transcript(tmp_path, monkeypatch): ...


def test_prepare_videos_keeps_success_when_second_video_fails(tmp_path, monkeypatch): ...


def test_prepare_video_records_frame_warning_without_discarding_transcript(tmp_path, monkeypatch): ...
```

The assertions must check `transcript.json`, `transcript.txt`, `manifest.json`, per-video status, and top-level `run.json` counts/errors.

- [ ] **Step 2: Run the integration tests and verify RED**

```powershell
python -m pytest .agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py -q
```

Expected: failures for the missing preparation orchestration and status files.

- [ ] **Step 3: Implement the minimal preparation pipeline**

Implement these functions:

```python
def prepare_video(video_path: Path, video_dir: Path, model_size: str, frame_interval: int, grid_size: tuple[int, int]) -> dict: ...


def prepare_videos(input_path: Path, workspace_root: Path, model_size: str, frame_interval: int, grid_size: tuple[int, int], task_id: str | None = None) -> Path: ...
```

Runtime imports must resolve the repository root/backend path without mutating the user's environment. Reuse `LocalDownloader.convert_to_mp3` for temporary audio under the per-video workspace, instantiate `WhisperTranscriber(model_size=model_size)`, call `.transcript(file_path=...)`, serialize dataclass-like transcript objects to JSON, and use `VideoReader` to write frames/grids. If frame extraction fails, preserve transcript files and add a warning. If transcription fails, mark that video failed and continue batch processing. Do not call `GPTFactory`, `OpenAI`, or any other LLM provider.

Ensure the CLI returns `0` only when every video succeeds, `1` for partial or total processing failure, and `2` for invalid command-line input. Add `--input`, `--workspace`, `--model`, `--frame-interval`, `--grid-size`, and optional `--task-id` arguments with useful `--help` text.

- [ ] **Step 4: Run focused tests and a CLI help smoke test**

```powershell
python -m pytest .agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py -q
python .agents/skills/bilinote-video-notes/scripts/prepare_video_note.py --help
```

Expected: all focused tests pass, help exits with code 0 and documents local-file-only behavior.

- [ ] **Step 5: Commit the preparation pipeline**

```powershell
git add .agents/skills/bilinote-video-notes/scripts
git commit -m "feat: prepare local videos for agent notes"
```

### Task 3: Write the skill instructions and UI metadata

**Files:**
- Create: `.agents/skills/bilinote-video-notes/SKILL.md`
- Create: `.agents/skills/bilinote-video-notes/agents/openai.yaml`

- [ ] **Step 1: Write the skill workflow**

The frontmatter description must clearly trigger for requests to turn local video files or folders into BiliNote-style image-rich Markdown notes. The body must instruct the agent to:

1. Reject URLs and ask for a local file/folder path.
2. Run `scripts/prepare_video_note.py` from the repository root.
3. Read `run.json`, each `manifest.json`, transcript text/JSON, and selected frame/grid images.
4. Use current agent vision and reasoning for video understanding; never configure or call an extra video/LLM API.
5. Write one `note.md` per successful video with relative local image links and an honest uncertainty label when evidence is incomplete.
6. Report successful and failed inputs and the exact output directories.

Document default command, batch behavior, resume behavior, output structure, common failures, and the required Markdown sections without duplicating the full Python implementation.

- [ ] **Step 2: Generate valid `agents/openai.yaml` metadata**

Use quoted values and include only the interface fields required for this skill:

```yaml
interface:
  display_name: "BiliNote Video Notes"
  short_description: "Turn local videos into image-rich Markdown notes"
  default_prompt: "Use $bilinote-video-notes to turn local video files into BiliNote-style Markdown notes."
policy:
  allow_implicit_invocation: true
```

- [ ] **Step 3: Run skill validation**

Run the repository skill validator if available; otherwise use the bundled validator:

```powershell
python C:\Users\yang\.codex\skills\.system\skill-creator\scripts\quick_validate.py .agents\skills\bilinote-video-notes
```

Expected: valid frontmatter, description, directory structure, and metadata.

- [ ] **Step 4: Commit the skill documentation**

```powershell
git add .agents/skills/bilinote-video-notes/SKILL.md .agents/skills/bilinote-video-notes/agents/openai.yaml
git commit -m "feat: add BiliNote video notes skill instructions"
```

### Task 4: Isolate generated artifacts and verify the complete feature

**Files:**
- Modify: `.gitignore`
- Modify: `.agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py`

- [ ] **Step 1: Add failing ignore-rule and artifact-isolation assertions**

Add a test that reads the root `.gitignore` and asserts `.bilinote-video-notes/` is covered, plus a test that runs the mocked pipeline with a workspace inside the project and asserts no files are written to `backend/note_results`, `backend/static`, or the input directory.

- [ ] **Step 2: Run the new tests and verify RED**

```powershell
python -m pytest .agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py -q
```

Expected: the ignore assertion fails before the rule is added.

- [ ] **Step 3: Add the narrow ignore rule**

Append this root-level rule near other generated/runtime directories:

```gitignore
/.bilinote-video-notes/
```

- [ ] **Step 4: Run the full relevant verification suite**

```powershell
python -m pytest backend/tests .agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py -q
python C:\Users\yang\.codex\skills\.system\skill-creator\scripts\quick_validate.py .agents\skills\bilinote-video-notes
git diff --check
git status --short --ignored .bilinote-video-notes
```

Expected: all tests pass, skill validation succeeds, `diff --check` is clean, and the generated workspace is shown as ignored.

- [ ] **Step 5: Commit the ignore rule and test coverage**

```powershell
git add .gitignore .agents/skills/bilinote-video-notes/scripts/test_prepare_video_note.py
git commit -m "test: isolate BiliNote video note artifacts"
```
