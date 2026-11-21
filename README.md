# GatsMod Cut
Android Kotlin starter project using Media3 preview.

## Timeline editing prototype
- Multiple timeline lanes (video/audio/text/overlay lane types)
- Import video/audio into timeline lane model
- Text and overlay label clips
- Clip selection and drag-move
- Timeline zoom controls
- Split, trim start/end, duplicate, delete
- Adjacent same-lane merge (model operation)
- One-frame nudge (~33ms)
- Undo/redo history for timeline operations
- Media preview and playback speed

## Important limitations
This is an editing-model/UI stage, **not a fully rendered video editor yet**. Timeline tracks, clip movement, cuts and trims do not yet alter the source playback composition or export output. Audio is placed in the timeline model but not mixed into preview. Text/overlay clips are labels in the model, not yet rendered on video. Merge is a model operation and does not encode media. Zoom currently scales the timeline display without a full scrollable precision workspace. Export/rendering, thumbnails, persistence and device/build testing remain outstanding.

Open in Android Studio with JDK 17 and Android SDK Platform 35. Gradle wrapper is not included.


## Effects & Export controls (prototype)\n- Brightness, contrast, saturation and blur control dialogs\n- Filter preset selection UI\n- Keyframe marker UI\n- Chroma Key toggle UI\n- Export presets: 720p/1080p/4K and 24/30/60 FPS, MP4 selection UI\n\n**Rendering limitations:** These are control/settings prototypes and currently do not apply visual effects to rendered footage. Keyframes and Chroma Key are not implemented as processing features. Export encoding, progress reporting and cancellation are not implemented yet. Do not treat the app as a working export editor until those engine pieces are added and tested.

## Auto update
The app checks the latest GitHub Release on start (and via the "Check Update" button). If a newer version exists it downloads the APK and opens the installer, so no uninstall is needed.
- Every build is signed with `keystore/gatsmod-release.jks`. **Never replace or delete this file**, otherwise Android will refuse to install updates over the old app.
- To publish an update: just push to `main`. GitHub Actions builds the signed APK and creates a Release `v1.0.<run number>` automatically (the number always goes up). Pushing a `v*` tag by hand also works.
