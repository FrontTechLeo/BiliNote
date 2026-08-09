# BiliNote Android Native Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kotlin/Jetpack Compose Android client for BiliNote that connects to the existing backend and provides responsive phone, tablet, and large-screen workflows for single notes, note library, reading, and batch collections.

**Architecture:** Use a single adaptive Compose application with feature modules. UI state flows from ViewModels through repositories to Retrofit/OkHttp and Room; the backend remains responsible for downloading, transcription, AI generation, and batch execution. Compact windows use bottom navigation and single-column pages; expanded windows use a navigation rail and list/detail or three-column layouts.

**Tech Stack:** Kotlin, Android SDK 35, minSdk 26, Jetpack Compose, Material 3, Navigation Compose, WindowSizeClass, Hilt, Retrofit, OkHttp, Kotlin Serialization, Room, WorkManager, Coil, Markdown renderer, JUnit, MockWebServer, Compose UI tests, Macrobenchmark, Baseline Profile.

---

## Scope and file map

The repository currently has no Android application module. Create a new android/ project without modifying the existing BillNote_admin or backend behavior unless an API contract gap is found.

~~~text
android/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  gradle/libs.versions.toml
  app/
  core/network/
  core/database/
  core/designsystem/
  feature/home/
  feature/batch/
  feature/library/
  feature/reader/
  feature/settings/
~~~

The package name is com.bilinote.android. Keep DTOs inside core:network, database entities inside core:database, and feature-specific UI models inside their feature module. A feature must not call Retrofit or Room directly.

### Task 1: Bootstrap the adaptive Android project

**Files:**

- Create: android/settings.gradle.kts
- Create: android/build.gradle.kts
- Create: android/gradle.properties
- Create: android/gradle/libs.versions.toml
- Create: android/app/build.gradle.kts
- Create: android/app/src/main/AndroidManifest.xml
- Create: android/app/src/main/java/com/bilinote/android/MainActivity.kt
- Create: android/app/src/test/java/com/bilinote/android/BootstrapTest.kt

- [ ] Step 1: Define the module graph and version catalog.

Create settings.gradle.kts with app, core:network, core:database, core:designsystem, feature:home, feature:batch, feature:library, feature:reader, and feature:settings. Pin plugin, Kotlin, Compose BOM, AndroidX, Hilt, Retrofit, Room, WorkManager, Coil, JUnit, MockWebServer, and Macrobenchmark versions in gradle/libs.versions.toml.

- [ ] Step 2: Add the application shell and manifest permissions.

Configure minSdk 26, compileSdk 35, Java/Kotlin JVM target 17, and Compose. Add INTERNET only; use Storage Access Framework for local videos.

Create MainActivity.kt:

~~~kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BiliNoteApp() }
    }
}
~~~

- [ ] Step 3: Add a bootstrap unit test and run the project.

Create BootstrapTest.kt asserting the package constant is com.bilinote.android.

Run:

~~~powershell
cd android
./gradlew projects
./gradlew :app:testDebugUnitTest
~~~

Expected: all modules are listed and the unit-test task passes.

- [ ] Step 4: Commit the isolated bootstrap.

~~~powershell
git add android
git commit -m "feat(android): bootstrap adaptive client"
~~~

### Task 2: Implement service configuration and network contracts

**Files:**

- Create: android/core/network/src/main/java/com/bilinote/android/network/ApiResult.kt
- Create: android/core/network/src/main/java/com/bilinote/android/network/BiliNoteApi.kt
- Create: android/core/network/src/main/java/com/bilinote/android/network/BackendUrl.kt
- Create: android/core/network/src/main/java/com/bilinote/android/network/NetworkModule.kt
- Create: android/core/network/src/main/java/com/bilinote/android/network/dto/NoteDtos.kt
- Create: android/core/network/src/main/java/com/bilinote/android/network/dto/BatchDtos.kt
- Create: android/core/network/src/main/java/com/bilinote/android/network/dto/ConfigDtos.kt
- Create: android/core/network/src/test/java/com/bilinote/android/network/BackendUrlTest.kt
- Create: android/core/network/src/test/java/com/bilinote/android/network/BiliNoteApiTest.kt
- Create: android/feature/settings/src/main/java/com/bilinote/android/settings/SettingsRepository.kt
- Create: android/feature/settings/src/main/java/com/bilinote/android/settings/SettingsViewModel.kt
- Create: android/feature/settings/src/main/java/com/bilinote/android/settings/SettingsScreen.kt

- [ ] Step 1: Define the response wrapper and URL normalization.

Model the backend envelope:

~~~kotlin
@Serializable
data class ApiResult<T>(val code: Int, val msg: String = "", val data: T? = null)
~~~

BackendUrl.normalize must trim whitespace, require http or https, remove trailing slashes, and reject a URL without a host. Test HTTPS, localhost HTTP, whitespace, trailing slash, unsupported scheme, and missing host.

- [ ] Step 2: Add the Retrofit endpoint interface.

Map /generate_note, /upload, /task_status/{taskId}, /single/tasks, /single/tasks/{taskId}/regenerate, /export_note/{taskId}, all /batch/tasks routes, /batch/preview routes, /get_all_providers, and /model_list. Convert non-zero API codes and non-2xx HTTP responses to typed domain errors at the repository boundary.

- [ ] Step 3: Persist the backend URL in DataStore.

Store backend_url and optional future access_token. The settings ViewModel exposes StateFlow<SettingsUiState> and testConnection() calls /sys_health. Do not write tokens to logs.

- [ ] Step 4: Add MockWebServer tests.

Cover one successful task status, one API error envelope, one HTTP 500, URL replacement, and conditional Authorization header behavior.

Run:

~~~powershell
cd android
./gradlew :core:network:test
~~~

Expected: URL, serialization, error mapping, and MockWebServer tests pass.

- [ ] Step 5: Commit the network/settings slice.

~~~powershell
git add android/core/network android/feature/settings
git commit -m "feat(android): add backend configuration and API client"
~~~

### Task 3: Add Room cache and repository boundaries

**Files:**

- Create: android/core/database/src/main/java/com/bilinote/android/database/BiliNoteDatabase.kt
- Create: android/core/database/src/main/java/com/bilinote/android/database/entity/TaskEntity.kt
- Create: android/core/database/src/main/java/com/bilinote/android/database/entity/BatchEntity.kt
- Create: android/core/database/src/main/java/com/bilinote/android/database/entity/ReadingProgressEntity.kt
- Create: android/core/database/src/main/java/com/bilinote/android/database/dao/TaskDao.kt
- Create: android/core/database/src/main/java/com/bilinote/android/database/dao/BatchDao.kt
- Create: android/core/database/src/main/java/com/bilinote/android/database/dao/ReadingProgressDao.kt
- Create: android/core/database/src/test/java/com/bilinote/android/database/TaskDaoTest.kt
- Create: android/core/database/src/test/java/com/bilinote/android/database/ReadingProgressDaoTest.kt
- Create: android/feature/home/src/main/java/com/bilinote/android/home/data/TaskRepository.kt
- Create: android/feature/library/src/main/java/com/bilinote/android/library/data/LibraryRepository.kt

- [ ] Step 1: Define cache entities.

TaskEntity stores taskId, title, status, message, platform, sourceUrl, hasResult, createdAt, updatedAt, and nullable markdown. ReadingProgressEntity uses composite key taskId plus serviceBaseUrl and stores scrollOffset, percent, and updatedAt.

- [ ] Step 2: Implement DAO queries.

Provide recent tasks capped at 50, active tasks, lookup by ID, upsert, delete all rows for a service URL, and progress read/write. Use Flow for list observation and suspend functions for mutations.

- [ ] Step 3: Write Room tests before repository implementation.

Use an in-memory database. Verify upsert updates status, recent tasks are ordered by createdAt descending and capped at 50, and service A progress is never returned for service B.

- [ ] Step 4: Implement cache-first repositories.

A refresh updates Room only after a successful API response. On network failure return cached data plus Offline state; never clear rows.

- [ ] Step 5: Run database and repository tests.

~~~powershell
cd android
./gradlew :core:database:test :feature:home:test :feature:library:test
~~~

Expected: DAO ordering, service isolation, cache-first reads, and failure preservation pass.

- [ ] Step 6: Commit the persistence slice.

~~~powershell
git add android/core/database android/feature/home android/feature/library
git commit -m "feat(android): add cached task repositories"
~~~

### Task 4: Build the adaptive navigation shell and design system

**Files:**

- Create: android/core/designsystem/src/main/java/com/bilinote/android/designsystem/Theme.kt
- Create: android/core/designsystem/src/main/java/com/bilinote/android/designsystem/AdaptiveScaffold.kt
- Create: android/app/src/main/java/com/bilinote/android/App.kt
- Create: android/app/src/main/java/com/bilinote/android/navigation/AppDestination.kt
- Create: android/app/src/main/java/com/bilinote/android/navigation/AppNavHost.kt
- Create: android/app/src/test/java/com/bilinote/android/navigation/AppDestinationTest.kt
- Create: android/app/src/androidTest/java/com/bilinote/android/navigation/AdaptiveNavigationTest.kt

- [ ] Step 1: Define visual tokens.

Create BiliNote colors, typography, spacing, card radius, and success/progress/error states. Every interactive control has a minimum 48dp target.

- [ ] Step 2: Implement window-size navigation.

Use calculateWindowSizeClass(activity):

~~~kotlin
when (windowSizeClass.widthSizeClass) {
    WindowWidthSizeClass.Compact -> BottomNavigation()
    WindowWidthSizeClass.Medium, WindowWidthSizeClass.Expanded -> NavigationRail()
}
~~~

Use destinations Home, Batch, Library, and Settings. Reader/detail routes carry only IDs; content comes from repositories.

- [ ] Step 3: Add responsive content containers.

Implement AdaptiveListDetailPane: compact shows list then detail; medium shows rail plus one content pane; expanded shows rail, list, and detail. Keep reader content at maximum width 720dp.

- [ ] Step 4: Add Compose tests for breakpoints.

Set content widths to 390dp, 800dp, and 1280dp. Assert bottom navigation appears only at compact width, rail at medium/expanded width, and expanded reader renders list and detail together.

- [ ] Step 5: Run UI tests.

~~~powershell
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
~~~

Expected: navigation and adaptive layout tests pass.

- [ ] Step 6: Commit the adaptive shell.

~~~powershell
git add android/app android/core/designsystem
git commit -m "feat(android): add adaptive navigation shell"
~~~

### Task 5: Implement the single-video home workflow

**Files:**

- Create: android/feature/home/src/main/java/com/bilinote/android/home/HomeViewModel.kt
- Create: android/feature/home/src/main/java/com/bilinote/android/home/HomeScreen.kt
- Create: android/feature/home/src/main/java/com/bilinote/android/home/NoteComposerSheet.kt
- Create: android/feature/home/src/main/java/com/bilinote/android/home/HomeUiState.kt
- Create: android/feature/home/src/test/java/com/bilinote/android/home/HomeViewModelTest.kt
- Create: android/feature/home/src/androidTest/java/com/bilinote/android/home/HomeScreenTest.kt

- [ ] Step 1: Model the create-note request.

Match video_url, platform, provider_id, model_name, quality, style, format, link, screenshot, extras, video_understanding, video_interval, and grid_size. Derive screenshot from selected formats so the request cannot be inconsistent.

- [ ] Step 2: Implement local-video selection and upload state.

Use ActivityResultContracts.OpenDocument with video/*. Expose Idle, Uploading(percent), Submitting, Polling, Success, and Failure(message).

- [ ] Step 3: Implement lifecycle-aware polling.

Poll every 2.5 seconds while the screen is started and the status is non-terminal. Stop on ON_STOP and refresh on ON_START. Open the reader route once after success.

- [ ] Step 4: Implement responsive home UI.

Compact layout shows create hero, active task, continue-reading card, and recent notes. Expanded layout keeps create/active task and recent notes visible together. The creation sheet includes platform, link/file, provider, model, style, video understanding, formats, and extras.

- [ ] Step 5: Test request mapping and transitions.

Test screenshot/video-understanding dependency, local upload before generate_note, failed polling retry, and one-time success navigation. Compose-test the sheet and active task card at compact and expanded widths.

- [ ] Step 6: Run and commit.

~~~powershell
cd android
./gradlew :feature:home:test
./gradlew :feature:home:connectedDebugAndroidTest
git add android/feature/home
git commit -m "feat(android): add single-note home workflow"
~~~

### Task 6: Implement note library, Markdown reader, export, and progress

**Files:**

- Create: android/feature/library/src/main/java/com/bilinote/android/library/LibraryViewModel.kt
- Create: android/feature/library/src/main/java/com/bilinote/android/library/LibraryScreen.kt
- Create: android/feature/reader/src/main/java/com/bilinote/android/reader/ReaderViewModel.kt
- Create: android/feature/reader/src/main/java/com/bilinote/android/reader/ReaderScreen.kt
- Create: android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownRenderer.kt
- Create: android/feature/reader/src/main/java/com/bilinote/android/reader/ReaderProgressStore.kt
- Create: android/feature/reader/src/androidTest/java/com/bilinote/android/reader/ReaderScreenTest.kt

- [ ] Step 1: Implement library refresh.

Load /single/tasks?page=1&page_size=50, write successful responses to Room, and display status-specific actions. Do not show reading/export actions for failed or processing tasks.

- [ ] Step 2: Implement Markdown rendering.

Render headings, paragraphs, lists, fenced code, block quotes, links, images, and screenshot references. Resolve relative /static and /uploads paths against the configured backend URL. Use Coil and provide an image retry state.

- [ ] Step 3: Implement compact and expanded readers.

Compact opens a full-screen reader. Expanded keeps the list or collection pane visible and places the reader to the right. Add back, source link, export, and optional heading outline without duplicating reader state.

- [ ] Step 4: Implement throttled progress persistence.

Save scroll position at most every 180ms while scrolling and once when leaving. Restore the offset only after content measurement completes.

- [ ] Step 5: Test reader and export behavior.

Compose-test heading rendering, image error state, back navigation, compact/expanded layout, and restored progress. Mock export and assert .md for Markdown and .zip for screenshot bundles.

- [ ] Step 6: Run and commit.

~~~powershell
cd android
./gradlew :feature:library:test :feature:reader:test
./gradlew :feature:reader:connectedDebugAndroidTest
git add android/feature/library android/feature/reader
git commit -m "feat(android): add library and markdown reader"
~~~

### Task 7: Implement batch collections and three-column tablet detail

**Files:**

- Create: android/feature/batch/src/main/java/com/bilinote/android/batch/BatchViewModel.kt
- Create: android/feature/batch/src/main/java/com/bilinote/android/batch/BatchScreen.kt
- Create: android/feature/batch/src/main/java/com/bilinote/android/batch/BatchCreateSheet.kt
- Create: android/feature/batch/src/main/java/com/bilinote/android/batch/BatchDetailPane.kt
- Create: android/feature/batch/src/main/java/com/bilinote/android/batch/BatchRepository.kt
- Create: android/feature/batch/src/test/java/com/bilinote/android/batch/BatchViewModelTest.kt
- Create: android/feature/batch/src/androidTest/java/com/bilinote/android/batch/BatchAdaptiveLayoutTest.kt

- [ ] Step 1: Implement preview and selection.

Support /batch/preview/bilibili and /batch/preview/local. Sort local files naturally, show discovered items, support select all/clear all, and prevent creation with zero selected items.

- [ ] Step 2: Map batch configuration.

Reuse the single-note configuration model and derive link and screenshot from format. Preserve backend defaults: quality medium, style tutorial, video interval 6, grid size [2, 2].

- [ ] Step 3: Implement collection lifecycle actions.

Add list, detail, update, append, item retry, item delete, item events, item export, and collection export. Refresh active detail every 3.5 seconds and stop at SUCCESS, FAILED, or PARTIAL_FAILED.

- [ ] Step 4: Implement responsive batch UI.

Compact collection cards open a full-screen detail page. Expanded renders navigation rail, collection/video list, and item detail/reader panes together. Failed children expose only logs, retry, and delete.

- [ ] Step 5: Test batch edge cases.

Test duplicate filtering, zero-selection prevention, terminal polling stop, retry mapping, and failure action availability. Compose-test the expanded three-column layout at 1280dp and the fallback at 800dp.

- [ ] Step 6: Run and commit.

~~~powershell
cd android
./gradlew :feature:batch:test
./gradlew :feature:batch:connectedDebugAndroidTest
git add android/feature/batch
git commit -m "feat(android): add batch collection workflow"
~~~

### Task 8: Add recovery, accessibility, performance, and release checks

**Files:**

- Create: android/app/src/main/java/com/bilinote/android/sync/SyncWorker.kt
- Create: android/app/src/main/java/com/bilinote/android/sync/WorkScheduler.kt
- Create: android/app/src/androidTest/java/com/bilinote/android/sync/SyncWorkerTest.kt
- Create: android/benchmark/src/main/java/com/bilinote/android/benchmark/StartupBenchmark.kt
- Create: android/benchmark/src/main/java/com/bilinote/android/benchmark/ReaderScrollBenchmark.kt
- Create: android/baselineprofile/src/main/java/com/bilinote/android/baselineprofile/BiliNoteBaselineProfile.kt
- Create: android/app/src/androidTest/java/com/bilinote/android/a11y/AccessibilityTest.kt
- Create: android/README.md

- [ ] Step 1: Implement recoverable synchronization.

Schedule WorkManager only for refreshing active task snapshots and retrying uploads under network constraints. The worker must be idempotent, update Room after successful responses, and never delete cached notes on failure.

- [ ] Step 2: Add accessibility coverage.

Give icon-only actions content descriptions, give each status a text label in addition to color, and keep all controls at least 48dp. Test increased font scale and TalkBack-compatible semantics.

- [ ] Step 3: Add performance benchmarks.

Measure cold startup, home with 50 tasks, reader launch with long Markdown, and image scrolling. Add a Baseline Profile covering app launch, home, library, batch, and reader routes.

- [ ] Step 4: Run the complete verification matrix.

~~~powershell
cd android
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew :benchmark:connectedCheck
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
~~~

Manually verify 390x844 phone portrait, 800x1280 tablet portrait, 1280x800 tablet landscape, network loss during polling, service URL change, local upload, Markdown images, and ZIP export.

- [ ] Step 5: Document configuration and backend prerequisites.

android/README.md must describe backend URL configuration, local debug build, HTTPS self-hosting, required routes, and the requirement for authentication and HTTPS in public deployment.

- [ ] Step 6: Commit release hardening.

~~~powershell
git add android
git commit -m "test(android): add recovery accessibility and performance checks"
~~~

## Plan self-review

- The plan covers the confirmed client-only architecture, configurable backend URL, phone layout, adaptive tablet layout, expanded three-column layout, single-note flow, library, reader, batch workflow, caching, error handling, performance, and release verification.
- No task requires local Whisper, video downloading, or AI execution on the Android device.
- All feature modules depend on repositories rather than coupling UI directly to Retrofit or Room.
- Terminal task handling is consistent across single tasks and batch child tasks.
- The only expected repository-side follow-up is security and API contract confirmation; it does not block scaffolding or read-only API client work.
