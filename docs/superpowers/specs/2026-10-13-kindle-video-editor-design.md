# Kindle Fire Video Editor: Design

Date: 2026-10-13
Status: Approved for planning

## 1. Goal

A native Android video editor that runs fully on-device on an Amazon Fire Max 11 (13th gen), with no server or network. It replaces the server-based `vid-edit` web app (FastAPI + Celery + FFmpeg) for personal use.

### Target device

- Amazon Fire Max 11 (13th gen), Fire OS 8 (Android 11 base, API 30)
- MediaTek Helio G99, 4 GB RAM, 2000x1200 display
- No Google Play Services: only AOSP and Jetpack/AndroidX libraries
- Distributed by sideloading an APK (`adb install`)

### v1 features

1. **Trim** a clip (adjust start and end).
2. **Merge** multiple clips into one video, in a user-chosen order.
3. **Split** a clip and **delete** sections (including removing the middle of a clip).
4. **Multi-level undo/redo** of all edits.

Inputs are arbitrary videos ("random videos"): mixed resolutions, frame rates, orientations, codecs, and some without audio.

### Out of scope for v1

Text/title overlays, audio editing or music, transitions, filters, crop/rotate/speed, cloud features.

## 2. Tech stack

- Kotlin, Jetpack Compose (Material 3), single Gradle module
- minSdk 30, targetSdk 34
- AndroidX Media3: `media3-transformer` (export), `media3-exoplayer` (preview), `media3-effect` (scaling/letterbox)
- kotlinx.serialization (project JSON)
- kotlinx.coroutines / Flow
- JUnit (JVM tests), AndroidX Test (instrumented tests)

Chosen over a bundled FFmpeg (unmaintained ffmpeg-kit, slow software encode, large APK, GPL concerns) and over a browser app in Silk (memory limits, uncertain WebCodecs support, awkward file saving).

## 3. Core model

All features reduce to one model:

```
Project  = id, name, ordered list of Segments, createdAt, updatedAt
Segment  = id, sourceUri, sourceDurationUs, startUs, endUs, hasAudio, width, height, isHdr
```

`width` / `height` are display dimensions after applying the source's rotation metadata (a portrait phone video stored as 1920x1080 with 90 degree rotation is recorded as 1080x1920).

- **Add clips (merge):** each picked video becomes a full-length segment appended to the end.
- **Trim:** change a segment's `startUs` / `endUs`.
- **Split:** replace one segment with two at a given source position.
- **Delete:** remove a segment. Deleting a middle section = split twice, delete the middle segment.
- **Reorder:** move a segment to a new index.

Edit operations are pure functions returning a new immutable `Project`.

The undo/redo history is in memory only. It starts empty each time a project is opened.

### Constraints

- Minimum segment length: 100 ms (`MIN_SEGMENT_US = 100_000`).
- Split is rejected (no-op, button disabled) if the split point is within 100 ms of either segment edge.
- Trim values clamp to `[0, sourceDurationUs]` and preserve the minimum length.

## 4. UX

Landscape-first layout for the 11" screen; portrait also works.

### Screens

1. **Home:** "New project" button and a list of saved projects (name, segment count, total duration, last edited). Long-press to rename or delete a project.
2. **Editor:**
   - Preview player on top, playing the whole project as one continuous sequence.
   - Timeline strip below: one block per segment, width proportional to duration, showing thumbnails. Each block has draggable start/end handles (trim). A playhead line shows the current position; tapping the strip seeks.
   - The timeline uses a fixed scale of 40 dp per second and scrolls horizontally, so long clips (10+ min) can still be trimmed precisely (about 25 ms per dp). No zoom in v1. Blocks have a minimum on-screen width of 24 dp so short segments stay tappable. The timeline auto-scrolls to keep the playhead visible during playback.
   - Split acts on the segment under the playhead, at the playhead position.
   - Long-press and drag a block to reorder.
   - Toolbar: Add, Split, Delete, Undo, Redo, Export.
   - Selected segment is highlighted; Delete acts on it.
3. **Export sheet:** quality choice (720p, 1080p, Match first clip), Start button, progress bar, then "Saved to Movies/VideoEditor/" with an Open action.

### Mixed input handling

- Output resolution comes from the quality choice. Frame rate is not converted: each clip keeps its own frame rate in the output (MP4 handles variable timing). 720p = 1280x720, 1080p = 1920x1080 (swapped to portrait dimensions if the first clip is portrait). "Match first clip" uses the first segment's dimensions.
- Clips with a different aspect ratio are scaled to fit and letterboxed (black bars).
- Clips without audio get silent audio so the output audio track is continuous.
- HDR inputs (common on newer phones) are tone-mapped to SDR (`Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`) so mixed HDR and SDR clips merge correctly.
- Output: H.264 video, AAC audio, MP4 container.

### Persistence

- Projects autosave (debounced ~500 ms after each edit) as JSON in `filesDir/projects/`.
- Source files are picked via the system document picker (`ACTION_OPEN_DOCUMENT`, `video/*`, multiple). The app takes persistable read permission on each URI and releases it when no project references the URI anymore. If a provider refuses a persistable grant, the clip is still added, and a toast warns it may be unavailable after the app restarts.

## 5. Architecture

| Package | Responsibility | Depends on |
|---|---|---|
| `model` | `Segment`, `Project` data classes; pure ops `append`, `trim`, `split`, `delete`, `move`; global-time helpers (total duration, map global position to segment + offset) | nothing |
| `history` | `UndoStack<T>`: `push`, `undo`, `redo`, `canUndo`, `canRedo`; capped at 50 entries; push clears redo | nothing |
| `storage` | `ProjectRepository`: list, load, save, delete project JSON; take/release persistable URI permissions | model |
| `media` | `SourceProbe` (duration, dimensions, rotation, has-audio via `MediaMetadataRetriever`), `ThumbnailLoader` (cached frame bitmaps), `CompositionBuilder` (`Project` + quality to Media3 `Composition`), `Exporter` (runs `Transformer` to a temp file in `cacheDir`, emits progress, then copies the result into MediaStore and deletes the temp file) | model, Media3 |
| `player` | Converts `Project` to an ExoPlayer playlist of `MediaItem`s with `ClippingConfiguration`; maps player position (window index + offset) to global time and back | model, ExoPlayer |
| `export` | `ExportController`: process-wide singleton holding export state as a `StateFlow` (Idle, Running(progress), Done(uri), Failed(message)), observed by both the UI and the service. `ExportService`: foreground service (`foregroundServiceType="dataSync"`, with `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` permissions) that runs `Exporter` with a progress notification so export survives backgrounding and screen-off. One export at a time. | media |
| `ui` | `HomeScreen`, `EditorScreen`, `TimelineStrip`, `ExportSheet`, `HomeViewModel`, `EditorViewModel` | all above |

### Data flow

```
user gesture
  -> EditorViewModel method
  -> pure model op (new Project)
  -> UndoStack.push
  -> StateFlow<Project> emits
       -> UI recomposes
       -> player playlist rebuilt (position preserved)
       -> debounced autosave via ProjectRepository

Export: current Project -> CompositionBuilder -> ExportService -> Exporter -> temp MP4 in cacheDir -> copied to MediaStore (Movies/VideoEditor/)
        ExportController.state <- progress updates -> ExportSheet + notification
```

During a trim-handle drag, the ViewModel updates the displayed project live but pushes a single undo entry when the drag ends.

### CompositionBuilder details

- One `EditedMediaItemSequence` containing one `EditedMediaItem` per segment, in order.
- Each `MediaItem` uses `ClippingConfiguration(startUs, endUs)`.
- Video effects: `Presentation.createForWidthAndHeight(w, h, LAYOUT_SCALE_TO_FIT)` for consistent output size.
- Silence for segments with `hasAudio = false`: `Composition.Builder.experimentalSetForceAudioTrack(true)` so the output always has an audio track and silence fills gaps.
- Output MIME types: `video/avc`, `audio/mp4a-latm`.
- HDR mode: tone-map to SDR (see section 4).

## 6. Error handling

| Case | Detection | Behavior |
|---|---|---|
| Source missing or permission revoked | `SourceProbe` / URI open fails on project load | Segment marked missing (red on timeline); can be deleted; Export disabled while any missing segment remains, with an explanation |
| Unsupported or unreadable file | Probe fails when adding | Toast "Can't use <file name>"; segment not added |
| Export failure (codec limit, out of space, etc.) | `Transformer` error callback | Error message in export sheet, Retry button; temp file in `cacheDir` deleted, nothing written to MediaStore |
| App backgrounded during export | n/a | Foreground service keeps running; notification shows progress and completion |
| Invalid edit (too-short segment, split at edge) | Model op preconditions | Op is a no-op; related UI control disabled |

## 7. Testing

### JVM unit tests (written test-first)

- `model`: append, trim (clamping, minimum length), split (middle, near edges rejected), delete, move (all index edge cases), total duration, global position mapping.
- `history`: push/undo/redo ordering, redo cleared on push, 50-entry cap.
- `storage`: project JSON round-trip (serialization only, no Android APIs).
- `CompositionBuilder` input mapping: segment list to clipping ranges and output dimensions (pure helper extracted so it is JVM-testable).

### Instrumented tests

- One export test: merge two short bundled test clips of different resolutions (one without audio) at 720p; assert the output exists, duration equals the sum of segments within ±100 ms, and the resolution is 1280x720.

### Manual device checklist (Fire Max 11)

- Phone footage (portrait and landscape mixed)
- A screen recording
- A long clip (10+ minutes)
- Clip with no audio
- Trim, split, delete middle, reorder, undo/redo across all of the above
- Export at each quality; verify playback in the Fire's gallery app
- Close app mid-edit and reopen: project restored
- Background app during export: export completes

## 8. Success criteria

- APK installs and runs on the Fire Max 11 without Google Play Services.
- A user can create a project from mixed random videos, trim, split, delete sections, reorder, undo/redo, and export a playable MP4 to `Movies/VideoEditor/`.
- All JVM unit tests and the instrumented export test pass.
- Manual device checklist passes.
