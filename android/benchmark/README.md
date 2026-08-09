# Task 8 AndroidX Macrobenchmark

This directory contains the `:benchmark` Android test module. It targets the
installed `com.bilinote.android` app and must run on a connected emulator or
physical device; a regular JVM unit test cannot measure startup or frame timing.

The app exposes a private benchmark intent that renders deterministic local
fixtures without creating `AppDependencies` or scheduling WorkManager. The
benchmarks therefore cover cold startup, 50-item home scrolling, long Markdown
reader scrolling, and repeated image scrolling without a backend or retained
DataStore state. `BiliNoteBaselineProfile` visits home, library, batch, and
reader through the same fixture navigation.
