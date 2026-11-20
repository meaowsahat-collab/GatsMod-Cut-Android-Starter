# GatsMod Cut

Android video-editor starter project built with Kotlin, Android Views and AndroidX Media3.

## Current working features
- Android app shell and GatsMod Cut branding
- Import a video through Android's document picker
- Preview/playback using Media3 ExoPlayer
- Preview playback-speed control (1.0x to 2.0x)
- No storage permission required; uses the system document picker

## Not implemented yet
Trim, split, multi-track timeline, text overlays, music mixing, filters, transitions and export are roadmap items. The visible tool buttons explain their current status; they do not claim to perform those edits.

## Build
1. Open this folder in Android Studio.
2. Use JDK 17.
3. Install Android SDK Platform 35 and accept licenses.
4. Sync Gradle.
5. Run the `app` configuration on an Android 8.0+ device or emulator.

## Technical notes
- `minSdk 26` (Android 8.0)
- `compileSdk 35`
- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- AndroidX Media3 1.5.1

This is a starter foundation, not a finished CapCut replacement. Build and device testing are still required.
