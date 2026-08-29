# Android Markdown Image Viewer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let readers tap any Markdown image to inspect it full-screen with authenticated loading, zoom/pan, retry, and back navigation.

**Architecture:** Keep Markwon and the existing native `ScrollView`/`TextView` document renderer. Add a focused image-span touch bridge that reports an image destination to `MarkdownRenderer`; render a nullable full-screen Compose viewer above the reader using Coil and the existing URL/authentication rules.

**Tech Stack:** Kotlin, Jetpack Compose, Markwon 4.6.2, Coil, Android Compose UI tests, Gradle/JDK 17.

---

### Task 1: Add the failing image-viewer state test

**Files:**
- Create: `android/feature/reader/src/test/java/com/bilinote/android/reader/MarkdownImageViewerStateTest.kt`

- [ ] **Step 1: Write the failing test**

Add a small state transition test for the viewer contract:

```kotlin
@Test
fun `opening and closing image viewer preserves selected image state`() {
    val state = MarkdownImageViewerState()

    state.open("https://service-a/static/shot.png")
    assertEquals("https://service-a/static/shot.png", state.imageUrl)

    state.close()
    assertNull(state.imageUrl)
}
```

The production state type is intentionally referenced before it exists so the test fails for the missing feature rather than passing against an unrelated implementation.

- [ ] **Step 2: Run the focused test and verify the expected failure**

Run from `android` with JDK 17:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\yang\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :feature:reader:testDebugUnitTest --tests 'com.bilinote.android.reader.MarkdownImageViewerStateTest' --console=plain --no-daemon
```

Expected: compilation/test failure because `MarkdownImageViewerState` is not yet defined.

### Task 2: Implement the viewer state and full-screen surface

**Files:**
- Modify: `android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownRenderer.kt`
- Create: `android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownImageViewer.kt`
- Test: `android/feature/reader/src/test/java/com/bilinote/android/reader/MarkdownImageViewerStateTest.kt`

- [ ] **Step 1: Add the minimal state implementation**

Create `MarkdownImageViewerState` with nullable `imageUrl`, `open(url)`, and `close()` so the focused unit test passes without coupling state to Compose.

- [ ] **Step 2: Add the full-screen Compose viewer**

Create a `MarkdownImageViewer` composable that:

- covers the reader bounds with a dark scrim;
- loads the provided URL through Coil using the supplied access token when the image belongs to the configured backend;
- uses `ContentScale.Fit` initially;
- applies `rememberTransformableState` and `transformable` for pinch zoom and pan;
- provides a close button with test tag `markdown-image-viewer-close`;
- provides retry on load error with test tag `markdown-image-viewer-retry`;
- exposes root test tag `markdown-image-viewer`;
- calls `onClose` for both the close action and system back.

- [ ] **Step 3: Run the focused unit test**

Run the same Gradle command from Task 1. Expected: PASS.

### Task 3: Bridge Markwon image taps to the viewer

**Files:**
- Modify: `android/feature/reader/src/main/java/com/bilinote/android/reader/MarkdownRenderer.kt`
- Modify: `android/feature/reader/src/androidTest/java/com/bilinote/android/reader/ReaderScreenTest.kt`

- [ ] **Step 1: Add a failing Compose behavior test**

Add a test that renders a data-URI Markdown image, taps the image span through the native view bridge, and asserts `markdown-image-viewer` exists. Then close it and assert the viewer is gone. Keep the existing image retry and rich Markdown tests unchanged.

- [ ] **Step 2: Add image-span hit testing to the native TextView**

Register an image-tap callback in `MarkdownScrollState`. On `ACTION_UP`, map the touch point through the TextView layout to a character offset, find the Markwon image span covering that offset, resolve its destination with `resolveImageUrl`, and invoke the callback only when the touch is inside the drawable bounds. Return `false` for all other touches so normal scrolling, selection, tables, and links remain intact.

- [ ] **Step 3: Overlay the viewer from `MarkdownRenderer`**

Keep the existing Markdown surface and retry action in the normal composition. When the callback sets `fullScreenImage`, compose `MarkdownImageViewer` above it and clear the state on close. Use the same `backendUrl` and `accessToken` parameters for inline and full-screen requests.

- [ ] **Step 4: Run the focused Android test**

Run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME = 'C:\Users\yang\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
.\gradlew.bat :feature:reader:compileDebugAndroidTestKotlin --console=plain --no-daemon
```

Then run the focused connected test on the attached device. Expected: the image viewer test passes and existing reader tests remain green where the device runner supports Compose hierarchies.

### Task 4: Build and device verification

**Files:**
- No additional source files.

- [ ] **Step 1: Run the relevant unit tests, Android test compilation, and APK build**

Run Reader unit tests, Reader AndroidTest Kotlin compilation, and `:app:assembleDebug` with JDK 17 and the local Android SDK. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Install and launch the APK on `ZY32MCTQQR`**

Install `android/app/build/outputs/apk/debug/app-debug.apk`, launch `com.bilinote.android/.MainActivity`, and navigate to a task with Markdown images.

- [ ] **Step 3: Verify the image flow from the UI tree**

Use the UI tree to locate the rendered image/viewer controls, tap the image center, capture a screenshot, verify `markdown-image-viewer` is visible, then tap `markdown-image-viewer-close` and verify the normal reader returns. Capture crash log output and confirm no app crash.

