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
