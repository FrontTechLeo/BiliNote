# Android Admin-Mobile UI Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework the native Android app's five user-facing areas so their layout, styling, status presentation, and interaction hierarchy match the mobile pages in `BillNote_admin`, while preserving current backend behavior and making the specified service URL the first-install default.

**Architecture:** Keep existing feature modules, repositories, view models, Markwon reader, and navigation routes. Add shared Compose tokens and small reusable surface/action components in `core:designsystem`; refactor each feature screen to consume those primitives. Compact navigation mirrors the reference's three-item bottom bar, while settings remains reachable from the home header and expanded layouts retain a navigation rail.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Navigation Compose, Room/DataStore, Markwon/Coil, Gradle, ADB.

---

## File Map

- Shared visual system: `android/core/designsystem/src/main/java/com/bilinote/android/core/designsystem/Theme.kt`, `AdaptiveScaffold.kt`, and new shared surface/action files.
- Navigation shell: `android/app/src/main/java/com/bilinote/android/navigation/AppNavHost.kt`, `AppDestination.kt`, and navigation tests.
- Home/library presentation: `android/feature/home/.../HomeScreen.kt`, `NoteComposerSheet.kt`, and `android/feature/library/.../LibraryScreen.kt` plus their Compose tests.
- Batch presentation: `android/feature/batch/.../BatchScreen.kt`, `BatchDetailPane.kt`, `BatchCreateSheet.kt` plus batch UI tests.
- Reader presentation: `android/feature/reader/.../ReaderScreen.kt`, `MarkdownRenderer.kt`, and reader UI tests.
- Settings/default: `android/feature/settings/.../SettingsScreen.kt`, `SettingsRepository.kt`, `SettingsViewModel.kt`, and settings/network tests.
- Documentation: `android/README.md` for the ADB install/configuration path and the approved default URL.

### Task 1: Add shared mobile tokens and compact navigation

**Files:**
- Modify: `android/core/designsystem/src/main/java/com/bilinote/android/core/designsystem/Theme.kt`
- Modify: `android/core/designsystem/src/main/java/com/bilinote/android/core/designsystem/AdaptiveScaffold.kt`
- Create: `android/core/designsystem/src/main/java/com/bilinote/android/core/designsystem/MobileSurfaces.kt`
- Modify: `android/app/src/main/java/com/bilinote/android/navigation/AppNavHost.kt`
- Test: `android/app/src/androidTest/java/com/bilinote/android/navigation/AdaptiveNavigationTest.kt`

- [ ] Add named colors and dimensions for the reference palette: page background `Color(0xFFF4F7F6)`, ink `Color(0xFF182126)`, muted `Color(0xFF79858D)`, divider `Color(0xFFE6EBED)`, primary `Color(0xFF0E9F86)`, and soft primary `Color(0xFFE6F7F2)`; expose them through the existing theme rather than hard-coding per feature.
- [ ] Add `MobilePage`, `MobileHeader`, `MobileHeroCard`, `MobileStatStrip`, `MobileStatusIcon`, and `MobileActionRow` composables with stable minimum sizes, 8dp shape, thin border, and content descriptions for icon-only actions.
- [ ] Change compact `AdaptiveScaffold` to render only 首页/合集/笔记库 in the bottom bar; keep settings as a top-level route for header navigation and preserve the expanded navigation rail.
- [ ] Update navigation tests to assert the three compact destinations, route selection for reader/batch detail, and settings navigation from the home action.
- [ ] Run `.\gradlew.bat :app:testDebugUnitTest --tests '*AdaptiveNavigation*' --console=plain` and confirm PASS before dependent tasks.

### Task 2: Refactor home and note library to the reference hierarchy

**Files:**
- Modify: `android/feature/home/src/main/java/com/bilinote/android/home/HomeScreen.kt`
- Modify: `android/feature/home/src/main/java/com/bilinote/android/home/NoteComposerSheet.kt`
- Modify: `android/feature/library/src/main/java/com/bilinote/android/library/LibraryScreen.kt`
- Modify: `android/feature/home/src/androidTest/java/com/bilinote/android/home/HomeScreenTest.kt`
- Modify: `android/feature/library/src/androidTest/java/com/bilinote/android/library/LibraryScreenTest.kt`

- [ ] Add the reference-style home header, settings action, create-note hero, active progress card, continue-reading card, and recent-note status cards using the shared design-system components.
- [ ] Keep the existing task submission fields and upload behavior in the bottom sheet, but regroup them into labeled sections with a single primary bottom action and safe-area-aware padding.
- [ ] Add the library header, three-stat strip, history cards, and state-valid action rows; keep read/export/retry/delete callbacks unchanged and remove the current custom floating action treatment on compact screens.
- [ ] Add semantic test tags/content descriptions for header actions, bottom navigation, create actions, status cards, and action-row buttons; assert empty/loading/success/failure states.
- [ ] Run `.\gradlew.bat :feature:home:testDebugUnitTest :feature:library:testDebugUnitTest :feature:home:compileDebugAndroidTestKotlin :feature:library:compileDebugAndroidTestKotlin --console=plain` and confirm PASS.

### Task 3: Refactor batch list, create sheets, and detail flow

**Files:**
- Modify: `android/feature/batch/src/main/java/com/bilinote/android/feature/batch/BatchScreen.kt`
- Modify: `android/feature/batch/src/main/java/com/bilinote/android/feature/batch/BatchDetailPane.kt`
- Modify: `android/feature/batch/src/main/java/com/bilinote/android/feature/batch/BatchCreateSheet.kt`
- Modify: `android/app/src/androidTest/java/com/bilinote/android/navigation/AdaptiveNavigationTest.kt`
- Modify/Create: `android/feature/batch/src/androidTest/java/com/bilinote/android/feature/batch/*Test.kt`

- [ ] Match the reference collection header, create hero, four-stat strip, and task cards; preserve refresh, create, edit, append, detail, export, retry, delete, read, and log callbacks.
- [ ] Make create/edit/append sheets bottom-anchored with the reference section spacing, source segmented control, model/style selectors, content toggles, preview selection, and one primary footer action.
- [ ] Make `BatchDetailPane` use the overview card, compact toolbar, child-task cards, and separate event-log section; show only state-valid actions and retain all existing view-model methods.
- [ ] Ensure batch details use a full-screen compact surface with a clear back action and no duplicate navigation controls.
- [ ] Add UI assertions for absent/present action buttons by state, progress statistics, sheet transitions, and log visibility.
- [ ] Run `.\gradlew.bat :feature:batch:testDebugUnitTest :feature:batch:compileDebugAndroidTestKotlin --console=plain` and confirm PASS.

### Task 4: Align reader and settings, and enforce the default service URL

**Files:**
- Modify: `android/feature/reader/src/main/java/com/bilinote/android/reader/ReaderScreen.kt`
- Modify: `android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownRenderer.kt`
- Modify: `android/feature/reader/src/androidTest/java/com/bilinote/android/reader/ReaderScreenTest.kt`
- Modify: `android/feature/settings/src/main/java/com/bilinote/android/feature/settings/SettingsScreen.kt`
- Modify: `android/feature/settings/src/main/java/com/bilinote/android/feature/settings/SettingsRepository.kt`
- Modify: `android/feature/settings/src/test/java/com/bilinote/android/feature/settings/SettingsViewModelTest.kt`
- Modify: `android/core/network/src/test/java/com/bilinote/android/network/BackendUrlTest.kt`
- Modify: `android/README.md`

- [ ] Restyle the reader as a full-screen white article surface with compact top bar, source/export actions, progress indicator, reference typography, and preserved Markwon image/time-link behavior.
- [ ] Refactor settings into service, credential, and connection-status groups while keeping test-connection/save behavior; show the exact first-install URL `http://172.21.16.115:8483/` in the draft.
- [ ] Set `SettingsRepository.DEFAULT_BACKEND_URL` to the exact trailing-slash value, keep `BackendUrl.normalize` for Retrofit construction, and assert the resulting base URL remains `/api/`.
- [ ] Document the ADB install and settings check in `android/README.md` without embedding tokens or changing production endpoints.
- [ ] Run `.\gradlew.bat :feature:reader:testDebugUnitTest :feature:settings:testDebugUnitTest :core:network:testDebugUnitTest :feature:reader:compileDebugAndroidTestKotlin :feature:settings:compileDebugAndroidTestKotlin --console=plain` and confirm PASS.

### Task 5: Integrated build, installation, and ADB verification

**Files:**
- Modify only if verification exposes a scoped issue in the files above.
- Test artifacts: `android/app/build/outputs/apk/debug/app-debug.apk` and ADB screenshots outside the repository.

- [ ] Run the complete JVM suite: `.\gradlew.bat testDebugUnitTest --console=plain`.
- [ ] Build the APK: `.\gradlew.bat :app:assembleDebug --console=plain`.
- [ ] List devices with `adb devices`, resolve the package/activity, install the generated APK, and launch it.
- [ ] Before data-dependent checks, open settings through the UI tree and verify the service URL field contains `http://172.21.16.115:8483/`; save/test the connection if the device can reach it.
- [ ] Use UI tree-derived coordinates to exercise 首页 → 新建笔记 → 笔记库 → 阅读器 → 合集 → 合集详情 → 设置, capturing screenshots for the home, batch detail, reader, and settings states.
- [ ] Capture `adb logcat -b crash` after the flow and confirm no app crash; report any backend reachability limitation separately from UI verification.

## Self-review

- Spec coverage: shared visual language is Task 1; home/library are Task 2; batch is Task 3; reader/settings/default URL are Task 4; build and ADB requirements are Task 5.
- Placeholder scan: no `TBD`, `TODO`, or unresolved design decisions are required by the task steps.
- Type consistency: existing view-model callbacks remain the public contracts; shared composables are presentation-only and do not introduce new repository types.
