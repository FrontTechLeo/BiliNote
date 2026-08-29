# Android Reading UI and Markdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the fragile Markdown renderer with Markwon-backed native scrolling, redesign batch detail around progress and child tasks, and preserve all existing task actions except append-items UI.

**Architecture:** Markwon 4.6.2 owns CommonMark/GFM parsing and TextView spans. A Compose `AndroidView` hosts a native `ScrollView`/`TextView`, with a BiliNote adapter for theme, image URLs/authentication, and `Content-[mm:ss]` links. Batch detail keeps its existing view-model/data APIs but uses a single structured lazy detail surface with append controls removed.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose Material 3, Markwon 4.6.2, Coil 2.7, AndroidView, native ScrollView, JUnit, Compose UI tests, ADB.

---

## File Map

- Modify `android/gradle/libs.versions.toml`: add Markwon version and aliases for core, tables, task list, strikethrough, and Coil image modules.
- Modify `android/feature/reader/build.gradle.kts`: add the five Markwon dependencies.
- Create `android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownLinkSupport.kt`: pure BiliNote time-link normalization and backend-origin authorization predicates used by the renderer.
- Modify `android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownRenderer.kt`: replace the hand-written block/inline Compose renderer with Markwon configuration and the native reader surface; preserve public `MarkdownRenderer` and image URL helpers where tests depend on them.
- Modify `android/feature/reader/src/main/java/com/bilinote/android/reader/ReaderScreen.kt`: host the renderer in the reader layout, wire native ScrollView progress callbacks, add reader-level image retry, and improve the toolbar/progress presentation.
- Modify `android/feature/reader/src/androidTest/java/com/bilinote/android/reader/ReaderScreenTest.kt`: test real Markwon output, time links, image retry, and progress restoration.
- Create `android/feature/reader/src/test/java/com/bilinote/android/reader/MarkdownLinkSupportTest.kt`: test time-link conversion and backend-origin authorization.
- Modify `android/feature/batch/src/main/java/com/bilinote/android/feature/batch/BatchDetailPane.kt`: remove append UI and callbacks; add progress summary, structured configuration, stable child-task rows, and logs hierarchy.
- Modify `android/feature/batch/src/main/java/com/bilinote/android/feature/batch/BatchScreen.kt`: pass the reduced detail callback set and keep compact/medium/expanded layouts consistent.
- Create `android/feature/batch/src/androidTest/java/com/bilinote/android/feature/batch/BatchDetailPaneTest.kt`: assert append controls are absent and retained actions remain discoverable.
- Update existing reader/batch test fixtures only where the new renderer or callback signatures require it; do not alter backend/data behavior.

## Task 1: Add Markwon dependencies and pure link support

**Files:**
- Modify: `android/gradle/libs.versions.toml`
- Modify: `android/feature/reader/build.gradle.kts`
- Create: `android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownLinkSupport.kt`
- Create: `android/feature/reader/src/test/java/com/bilinote/android/reader/MarkdownLinkSupportTest.kt`

- [ ] **Step 1: Add the dependency catalog entries.**

Add:

```toml
markwon = "4.6.2"
markwon-core = { module = "io.noties.markwon:core", version.ref = "markwon" }
markwon-ext-tables = { module = "io.noties.markwon:ext-tables", version.ref = "markwon" }
markwon-ext-tasklist = { module = "io.noties.markwon:ext-tasklist", version.ref = "markwon" }
markwon-ext-strikethrough = { module = "io.noties.markwon:ext-strikethrough", version.ref = "markwon" }
markwon-image-coil = { module = "io.noties.markwon:image-coil", version.ref = "markwon" }
```

Add the corresponding `implementation(libs.markwon...)` entries in the reader module.

- [ ] **Step 2: Write failing pure-function tests first.**

`MarkdownLinkSupportTest.kt` must cover:

```kotlin
@Test fun `time marker becomes a callback link`() {
    assertEquals(
        "[Content-01:23](bilinote-time://83)",
        normalizeBiliNoteTimeLinks("*Content-[01:23]*"),
    )
}

@Test fun `backend image auth is limited to same origin`() {
    assertTrue(shouldAttachImageAuthorization("http://host:8483/static/a.png", "http://host:8483"))
    assertFalse(shouldAttachImageAuthorization("http://other:8483/static/a.png", "http://host:8483"))
}
```

- [ ] **Step 3: Run the focused test and verify the expected red failure.**

Run from `android`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\yang\AppData\Local\Android\Sdk'
.\gradlew.bat :feature:reader:testDebugUnitTest --tests 'com.bilinote.android.reader.MarkdownLinkSupportTest' --console=plain
```

Expected: compilation/test failure because the helper and expected behavior do not exist yet.

- [ ] **Step 4: Implement the small pure helpers.**

`normalizeBiliNoteTimeLinks` must recognize `*Content-[mm:ss]*`, `Content-[mm:ss]`, and `Content-mm:ss` case-insensitively, preserve the visible `Content-mm:ss` label, and replace only valid minute/second ranges. `shouldAttachImageAuthorization` must compare normalized scheme, host, and effective port exactly, with no token for malformed or cross-origin URLs.

- [ ] **Step 5: Run the focused test and verify green.**

Run the command from Step 3. Expected: all helper tests pass.

## Task 2: Replace the renderer with Markwon and native scrolling

**Files:**
- Modify: `android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownRenderer.kt`
- Modify: `android/feature/reader/src/main/java/com/bilinote/android/reader/ReaderScreen.kt`

- [ ] **Step 1: Add failing reader assertions for rich Markdown.**

Extend `ReaderScreenTest` fixture Markdown with:

```markdown
# Heading

This is **bold**, *italic*, `inline code`, and [a link](https://example.com).

| Name | Value |
| --- | --- |
| A | 1 |

- [x] done
- [ ] todo
```

Assert the reader contains the `reader-content` surface and text for `bold`, `italic`, `inline code`, `Name`, `done`, and `todo`; assert the raw `**` delimiters are not displayed. Keep the existing time-link callback test.

- [ ] **Step 2: Run the reader Android tests and verify the new assertions fail against the old renderer.**

Run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\yang\AppData\Local\Android\Sdk'
.\gradlew.bat :feature:reader:connectedDebugAndroidTest --console=plain
```

Expected: the new assertions fail or cannot find the expected rich-rendered output because the old renderer displays delimiters and has no table/task-list support.

- [ ] **Step 3: Build a configured Markwon instance.**

Use `Markwon.builder(context)` with `ext-tables`, `ext-tasklist`, `ext-strikethrough`, and `CoilImagesPlugin`. Configure:

```kotlin
configureConfiguration { builder ->
    builder
        .linkResolver(BiliNoteLinkResolver(onOpenTimeLink))
        .imageDestinationProcessor(BiliNoteImageDestinationProcessor(backendUrl))
}
```

The custom Coil store must build `ImageRequest.Builder(context).data(destination)` and add `Authorization: Bearer $token` only when `shouldAttachImageAuthorization(destination, backendUrl)` is true. The implementation must use the project’s existing Coil 2 image loader.

- [ ] **Step 4: Configure the Markwon theme from Material 3 colors.**

Map the current `MaterialTheme.colorScheme` to integer colors for body text, links, code text/background, quotes, and table borders. Keep body text at the reader’s normal size, set a readable block margin, and avoid oversized headings. Rebuild the remembered Markwon instance only when theme colors, backend URL, token, or the retry generation changes.

- [ ] **Step 5: Replace `FlowRow` and custom parsing with a native surface.**

`MarkdownRenderer` should keep its existing public parameters and add optional `initialScrollOffset`, `retryGeneration`, and `onScrollChanged(offset, maxOffset)` parameters. It renders a `Column` containing an `AndroidView` with a `ScrollView` and padded, selectable `TextView`, plus a `markdown-image-retry` `TextButton` when the custom Coil store reports an image failure. Normalize time links before `markwon.setMarkdown`. Use a stable view factory; in `update`, reapply Markdown only when the source or retry generation changes. Configure link movement through Markwon’s movement plugin and custom resolver. Keep the existing `resolveImageUrl`/authorization behavior covered by tests.

- [ ] **Step 6: Wire ScrollView progress into `ReaderMarkdownContent`.**

Pass `initialScrollOffset = progress?.scrollOffset ?: 0` and `onScrollChanged` into `MarkdownRenderer`. Sample callback values at the existing 180 ms cadence and call `ProgressStore.saveThrottled`; save the final position with `NonCancellable` on disposal. The renderer restores the initial offset from a post-layout callback, clamped to `scrollView.getChildAt(0).measuredHeight - scrollView.height`. Its retry button increments `retryGeneration`, reapplies Markdown, and restores the last saved offset without adding a second scroll container.

- [ ] **Step 7: Run the reader tests and fix implementation issues.**

Run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\yang\AppData\Local\Android\Sdk'
.\gradlew.bat :feature:reader:testDebugUnitTest :feature:reader:connectedDebugAndroidTest --console=plain
```

Expected: unit tests and the reader Android tests pass. If device instrumentation is affected by the known personal/work-profile resolver, record that limitation separately instead of weakening renderer assertions.

## Task 3: Redesign batch detail and remove append UI

**Files:**
- Modify: `android/feature/batch/src/main/java/com/bilinote/android/feature/batch/BatchDetailPane.kt`
- Modify: `android/feature/batch/src/main/java/com/bilinote/android/feature/batch/BatchScreen.kt`
- Create: `android/feature/batch/src/androidTest/java/com/bilinote/android/feature/batch/BatchDetailPaneTest.kt`

- [ ] **Step 1: Add failing UI assertions for append removal and retained actions.**

In `BatchDetailPaneTest`, render `BatchDetailPane` with a selected task and one completed child item, then assert:

```kotlin
composeRule.onNodeWithText("追加条目").assertDoesNotExist()
composeRule.onNodeWithText("B站合集链接").assertDoesNotExist()
composeRule.onNodeWithTag("batch-edit").assertExists()
composeRule.onNodeWithTag("batch-export").assertExists()
composeRule.onNodeWithText("日志").assertExists()
composeRule.onNodeWithTag("batch-open-reader-item-1").assertExists()
```

- [ ] **Step 2: Run the focused batch test and verify it fails against the current detail pane.**

Run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\yang\AppData\Local\Android\Sdk'
.\gradlew.bat :feature:batch:connectedDebugAndroidTest --console=plain
```

Expected: the new absence assertion fails because the current pane still renders `追加条目`.

- [ ] **Step 3: Remove only append UI wiring.**

Delete the append URL state, append input, preview selection controls, and preview item rows from `BatchDetailPane`. Remove `onPreviewAppend`, `onAppend`, `onTogglePreview`, `onSelectAll`, `onClearSelection`, and `previewSelectionKey` from the composable signature and its callers. Leave `BatchViewModel` append/preview methods and `BatchCreateSheet` untouched unless the compiler identifies a UI-only reference that can be safely removed.

- [ ] **Step 4: Implement the reading-first detail hierarchy.**

Replace the raw configuration lines with:

```kotlin
Text(title, style = MaterialTheme.typography.headlineSmall)
Text(statusLabel, style = MaterialTheme.typography.bodyMedium)
LinearProgressIndicator(progress = { task.progress.toFloat().coerceIn(0f, 1f) })
Row { Metric("全部", task.totalCount); Metric("已完成", task.completedCount); Metric("失败", task.failedCount) }
```

Replace the outer `Column.verticalScroll` with a `LazyColumn`. Put the header, progress summary, configuration summary, and section labels in `item` blocks; render `uiState.items` with `items(key = { it.id ?: "item-$index" })`; render the selected-task log as a final `item` block. Keep `BatchItemRow` as the only child-task action surface, preserve its existing action enablement rules, and apply consistent spacing and status colors with Material 3. Apply the same `LazyColumn`/stable-key treatment to `BatchSubtaskPane` so the expanded layout does not eagerly compose every child row.

- [ ] **Step 5: Keep compact, medium, and expanded callers consistent.**

Update all `BatchDetailPane` call sites in `BatchScreen.kt` and `BatchDetailScreen` to the reduced signature. Expanded mode must continue to show the dedicated `BatchSubtaskPane`, while the right detail pane must show no append controls. Child task callbacks remain connected to the same view-model methods.

- [ ] **Step 6: Run focused batch tests and verify green.**

Run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\yang\AppData\Local\Android\Sdk'
.\gradlew.bat :feature:batch:testDebugUnitTest :feature:batch:connectedDebugAndroidTest --console=plain
```

Expected: batch unit tests pass and the focused UI assertions pass, subject to the known device resolver limitation.

## Task 4: Polish related reader surfaces and validate integration

**Files:**
- Modify: `android/feature/reader/src/main/java/com/bilinote/android/reader/ReaderScreen.kt`
- Modify: `android/feature/batch/src/main/java/com/bilinote/android/feature/batch/BatchDetailPane.kt`
- Modify: `android/feature/reader/src/androidTest/java/com/bilinote/android/reader/ReaderScreenTest.kt`
- Create: `android/feature/batch/src/androidTest/java/com/bilinote/android/feature/batch/BatchDetailPaneTest.kt`

- [ ] **Step 1: Add reader toolbar/progress polish.**

Keep back/source/export semantics and tags. Add a thin progress indicator below the toolbar driven by `progress.percent`. Ensure long titles ellipsize without pushing actions off-screen. The Markdown renderer owns the `markdown-image-retry` action and only shows it after its Coil store reports an image error; it uses the existing minimum touch target.

- [ ] **Step 2: Align related task rows.**

Use the same title/status typography and vertical spacing in `RelatedTasksPane` and `BatchItemRow`. Keep one tap target per row and preserve `reader-related-<taskId>` tags and accessibility descriptions.

- [ ] **Step 3: Run the complete relevant verification set.**

Run from `android` with JDK 17 and the explicit Android SDK path:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\yang\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :app:testDebugUnitTest :core:network:testDebugUnitTest :feature:batch:testDebugUnitTest :feature:reader:testDebugUnitTest :feature:settings:testDebugUnitTest :app:assembleDebug --console=plain
```

Expected: `BUILD SUCCESSFUL` with all requested unit-test tasks and `:app:assembleDebug` complete.

- [ ] **Step 4: Compile Android tests.**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin :feature:batch:compileDebugAndroidTestKotlin :feature:reader:compileDebugAndroidTestKotlin --console=plain
```

Expected: successful compilation of the new Compose/Markwon test code.

- [ ] **Step 5: Install and inspect on the connected device.**

```powershell
.\gradlew.bat :app:installDebug --console=plain
adb devices
adb -s ZY32MCTQQR shell am force-stop com.bilinote.android
adb -s ZY32MCTQQR shell monkey -p com.bilinote.android 1
adb -s ZY32MCTQQR exec-out screencap -p > C:\Users\yang\.codex\visualizations\2026\08\17\01a00e0f-89ac-7a43-b53f-c74a0a40a96f\bilinote-final.png
```

Use the UI tree to navigate to the batch detail and reader, verify append controls are absent, verify child-task actions remain, inspect rich Markdown with `**bold**`, tables, code and images, swipe through a long note, and check that the process remains responsive. Capture screenshots for both surfaces.

- [ ] **Step 6: Review the diff and report residual device-test limits.**

Run `git diff --check` and inspect every changed file. Do not include `.gradle-user/` or unrelated existing user changes. If connected tests are intercepted by the duplicate personal/work-profile app, report the exact count and affected assertions without calling the implementation fully device-verified.
