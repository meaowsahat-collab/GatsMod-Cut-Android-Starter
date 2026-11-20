# GatsMod Cut
Android Kotlin starter project with Media3 preview.

## Timeline prototype
- Visual timeline and draggable playhead (preview seek)
- Clip selection
- Split at playhead (timeline model)
- Trim start/end controls for selected timeline segment (model)
- Zoom controls
- Video import and playback speed

**Limitations:** split/trim modify the timeline model/UI only; the source preview and exported render are not yet edited. Export, multi-source playback, thumbnails, undo/redo, additional tracks and project persistence remain unimplemented.

Open in Android Studio with JDK 17 and Android SDK Platform 35. Gradle wrapper not included.
