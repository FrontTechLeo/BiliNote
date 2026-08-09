# Baseline Profile

`:baselineprofile` is an AndroidX Baseline Profile test module for the
`com.bilinote.android` app. It exercises the deterministic no-backend journey:
launch the app, then navigate through the home list, library list, batch list,
and reader.

Profile collection requires a connected supported emulator or physical device.
It is not a JVM test. Run the profile task with the Android Gradle Plugin and
Gradle versions declared by this project, then build the release variant with
the generated profile included.
