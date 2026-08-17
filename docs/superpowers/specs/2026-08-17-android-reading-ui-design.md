# Android Reading UI and Markdown Design

## Scope

Improve the Android batch detail flow and Markdown reader without changing backend APIs or removing existing task actions. The batch detail page removes the append-items UI only. Edit batch, export batch, task logs, reader, task export, retry, and delete remain available.

## Design

### Batch detail

`BatchDetailPane` becomes a reading-first detail surface:

- Header contains the batch title, status, refresh, edit, and export actions.
- A compact progress summary shows total, completed, failed/running counts, and progress.
- Configuration is shown as a quiet secondary summary rather than raw text lines.
- The append-items input, preview selection, and append action are removed from this surface.
- Child tasks are rendered as stable, compact rows with title, status, stage/message, and only the actions valid for that state.
- Logs remain available for the selected child task in a separate section.
- Compact and expanded layouts share the same hierarchy. Expanded mode may keep the dedicated child-task pane, but it must not duplicate append controls.

The existing view-model append methods and create flow remain intact unless compilation proves a UI-only callback is no longer referenced. No backend endpoint is removed.

### Markdown reader

Use Markwon `4.6.2` with the following modules:

- `io.noties.markwon:core`
- `io.noties.markwon:ext-tables`
- `io.noties.markwon:ext-tasklist`
- `io.noties.markwon:ext-strikethrough`
- `io.noties.markwon:image-coil`

Markwon is hosted in an `AndroidView` containing a native `ScrollView` and `TextView`. This avoids the current per-inline-fragment `FlowRow` tree and gives long documents one native scrolling surface.

The renderer configuration will:

- Use Markwon/CommonMark for headings, paragraphs, emphasis, strong emphasis, inline code, fenced code, lists, block quotes, tables, task lists, strikethrough, and links.
- Use a custom link resolver for BiliNote time links such as `Content-[01:23]`, forwarding them to `onOpenTimeLink`; other links continue through the platform browser resolver.
- Resolve relative image destinations against the configured backend URL, including root-relative paths outside an `/api` prefix.
- Use a custom Coil request factory that adds `Authorization: Bearer <token>` only for images matching the configured backend origin.
- Apply Material 3 colors and typography to Markwon's theme, with readable body spacing, restrained heading scale, visible quote treatment, and a distinct code-block surface.
- Keep image loading asynchronous with a bounded placeholder and themed inline error state. A reader-level retry action reapplies the current Markdown so failed image requests can run again without losing the reading position.

The existing `ReaderProgressStore` contract remains the persistence boundary. ScrollView changes are sampled and saved with the existing throttling behavior, and the saved offset is restored after the rendered content has been laid out.

### Related pages

The related-task pane and child-task rows use the same status language, spacing, and action hierarchy as the batch detail page. Related task selection remains unchanged. The reader toolbar keeps back, source, and export actions and gains a clear reading-progress indicator without adding a second navigation system.

## Data flow and failure handling

1. The reader receives Markdown and the existing backend URL/token from the view model.
2. A remembered Markwon instance is rebuilt only when theme colors, backend URL, or token change.
3. The Markdown string is processed by Markwon and applied to the TextView.
4. Scroll events are sampled into the existing progress store; layout restoration waits until the ScrollView has a non-zero content range.
5. Image failures show an inline error placeholder and do not prevent surrounding text from rendering. A reader-level retry reapplies the document and restores the saved position.
6. Malformed Markdown is left as readable raw text by Markwon's fallback behavior; it must not crash the reader.

## Testing

- Add unit coverage for the BiliNote time-link transformation/resolution and backend-origin authorization decision.
- Add reader UI coverage for bold/italic/code/table/task-list rendering, time-link callbacks, image retry, and progress restoration.
- Add batch UI coverage asserting append controls are absent while edit/export/log/read/task-export actions remain available.
- Run reader, batch, network, and app unit tests, Android test compilation, and the Debug APK build.
- Use ADB to inspect the batch detail and reader screens, exercise scrolling and links, and capture a final screenshot on the connected device.

## Non-goals

- No backend API or response schema changes.
- No removal of append functionality from batch creation or the view-model/data layer.
- No unrelated redesign of home, settings, or navigation.
