# Android Mobile UI Alignment Design

## Scope

Align the Android app's five user-facing areas with the mobile experience in `BillNote_admin`: home, batch collections, note library, reader, and settings. Keep existing backend APIs, repositories, Markdown capabilities, exports, and task actions. The Android app remains native Compose so it can support ADB validation, accessibility semantics, local persistence, and native file export.

## Design

### Shared visual language

- Compact phones use a three-item bottom bar: 首页, 合集, 笔记库. 设置 is opened from the home header action and uses a top back action.
- Expanded windows keep a navigation rail with the same destinations and content hierarchy.
- Shared tokens mirror the reference: page background `#F4F7F6`, white grouped surfaces, divider `#E6EBED`, primary `#0E9F86`, soft primary `#E6F7F2`, dark ink, and muted secondary text.
- Cards use an 8dp radius, thin borders, restrained elevation, and stable minimum touch targets. Lists use one consistent pattern: status icon block, title/supporting text, status label, and a bottom action row.
- Loading, empty, error, disabled, and success states use shared components and status colors.

### Home

Use the reference mobile header with an eyebrow, two-line value statement, and settings action. Below it show a primary create-note hero, active task progress, continue-reading item, and recent-note cards. Create-note remains a bottom sheet; task actions are shown only when valid for the task state.

### Batch collections

Use a title/refresh header, create-collection hero, four statistics, and collection cards with source, count, progress, and actions. Create, edit, and append remain bottom sheets. Collection detail is a full-screen detail surface with a progress overview, edit/append/export toolbar, child-task cards, and a separate expandable event-log section.

### Note library

Use a title/refresh header, three statistics, and history cards. The card action row presents reading, export, retry, and delete according to state. Selected-task actions use the same bottom action treatment as collection child tasks rather than a floating custom overlay.

### Reader

Keep the native Markwon reader, image authorization, BiliNote time links, image retry, and progress persistence. Present it as a full-screen reading surface with a compact top bar for back, title/source, and export, a light progress indicator, a white article surface, and the reference typography/spacing hierarchy.

### Settings and backend defaults

Keep the Android-only service URL and access token fields, but group them like the reference settings page: service connection, access credential, and connection status. The first-install default is exactly `http://172.21.16.115:8483/`; network normalization may remove the trailing slash only when constructing Retrofit URLs, and the API base remains derived as `/api/`.

## Data flow and failure handling

No backend endpoint or response schema changes. Existing view models remain the state boundary. Shared UI components map backend statuses to the same labels and tones across home, library, batch, and reader. Empty, loading, offline, failed, and invalid-configuration states remain actionable and must not block navigation to settings.

## Testing and verification

- Update Compose tests for compact navigation, page hierarchy, sheet/detail interactions, valid action visibility, and shared empty/loading/error states.
- Keep existing reader unit/UI coverage for Markdown, time links, images, and progress; add only coverage needed for the new toolbar/progress presentation.
- Add settings coverage proving the specified default URL is restored for an empty store and that Retrofit still uses `/api/`.
- Run relevant JVM tests, Android test compilation, and the Debug APK build.
- Install the Debug APK with ADB, inspect the UI tree, exercise the five-entry flow, and capture screenshots on the connected device. Confirm the service URL before data-dependent checks.

## Non-goals

- No WebView replacement.
- No backend API changes.
- No removal of existing task creation, append, retry, delete, read, or export behavior.
- No unrelated refactor outside Android UI, settings defaults, and focused tests.
