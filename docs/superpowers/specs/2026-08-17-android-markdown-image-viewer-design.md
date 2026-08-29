# Android Markdown Image Viewer Design

## Goal

Make images in the Android Markdown reader tappable so readers can inspect them at full-screen size on a phone, while preserving the existing Markwon rendering, image authentication, retry behavior, text selection, and BiliNote time links.

## Design

The existing reader remains the source of truth for Markdown rendering: Markwon parses the document, Coil loads images, and a native `ScrollView` containing a `TextView` provides smooth document scrolling. A small touch bridge on the `TextView` identifies taps inside Markwon's image spans and emits the resolved image URL plus its authenticated request configuration. Non-image touches continue through the current movement method.

The emitted image opens a full-screen Compose overlay owned by `MarkdownRenderer`. The overlay uses Coil with the same backend URL and access token rules as the inline image, displays the image with `ContentScale.Fit`, and supports pinch zoom and panning through Compose's transform gesture APIs. A close action and the system back action remove the overlay without changing the document scroll position. A failed full-screen request exposes retry and close actions locally to the overlay.

## State and Error Handling

- `fullScreenImage` is nullable; null means the reader is in normal document mode.
- Opening an image does not mutate Markdown or reading progress.
- Inline and full-screen image failures are independent; retrying the full-screen request does not rerender the document.
- Image URLs are resolved once through the existing `resolveImageUrl` path before opening the viewer.

## Testing

- Add a focused image-viewer state test proving open and close transitions.
- Add an Android Compose test proving the viewer is shown after an image click and disappears after close.
- Preserve the existing image retry, rich Markdown, link, and scroll restoration tests.
- Verify on the connected Android device that tapping a rendered image opens the full-screen layer, pinch/drag controls do not crash, and back returns to the reader.
