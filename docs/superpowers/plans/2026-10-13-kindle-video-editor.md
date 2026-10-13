# Kindle Fire Video Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloadable native Android app that trims, splits, deletes sections of, reorders, and merges arbitrary videos fully on-device on an Amazon Fire Max 11, with multi-level undo and export to `Movies/VideoEditor/`.

**Architecture:** Single-module Kotlin + Jetpack Compose app. All editing logic is pure Kotlin (`model`, `history`, plan/geometry helpers) and unit-tested on the JVM. Media3 ExoPlayer previews the project as a clipped playlist; Media3 Transformer exports it from a foreground service. Projects persist as JSON files in app storage.

**Tech Stack:** Kotlin 2.0.20, AGP 8.5.2, Gradle 8.7, Compose BOM 2024.09.00 (Material 3), AndroidX Media3 1.4.1 (transformer, effect, exoplayer, ui), kotlinx.serialization 1.7.1, kotlinx.coroutines 1.8.1, Navigation Compose 2.8.0, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-13-kindle-video-editor-design.md`

## Global Constraints

- Package and applicationId: `com.jjveleber.videoeditor`. Source root: `app/src/main/java/com/jjveleber/videoeditor/`.
- `minSdk = 30`, `targetSdk = 34`, `compileSdk = 34` (Fire OS 8 is API 30; SDK platform 34 is what is installed).
- No Google Play Services or any `com.google.android.gms` / Firebase dependency.
- `MIN_SEGMENT_US = 100_000` (100 ms). Split rejected within 100 ms of a segment edge.
- Undo history capped at 50 entries, in memory only.
- Autosave debounce: 500 ms.
- Output: H.264 video, AAC audio, MP4, saved to `Movies/VideoEditor/` via MediaStore.
- Quality: 720p = 1280x720, 1080p = 1920x1080, swapped for a portrait first clip. "Match first clip" uses the first segment's display size, capped to a 1920 long side, rounded to even numbers.
- HDR inputs tone-mapped to SDR (`Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`).
- Timeline: fixed scale 20 dp per second, minimum block width 24 dp, horizontal scroll, no zoom.
- Every `./gradlew` command in this plan runs with `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` set in the shell (the default `java` is 25, which Gradle 8.7 cannot run on).
- `ANDROID_HOME` is already set (`/home/linuxbrew/.linuxbrew/share/android-commandlinetools`).
- Never use the em-dash character anywhere: code, UI copy, comments, commit messages. Use a colon, comma, parentheses, or period.
- Every commit message ends with a blank line then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Portrait phone video stored landscape with rotation metadata:** must be probed as portrait (width < height) so "Match first clip" and orientation choice are right. Test: Task 6 `probesRotatedClipAsPortrait`.
2. **4K first clip exported with "Match first clip":** the G99 H.264 encoder cannot be assumed to encode 4K; output must cap at 1920 long side. Test: Task 7 `matchFirstCapsFourK`.
3. **Edits made less than 500 ms before the app is backgrounded or killed:** must not be lost. Test: Task 10 `flushSavesImmediately` (and Task 13 wires `flush()` to `ON_STOP`).
4. **Undo after deleting a clip:** the source file must still be readable, so persistable URI grants are never released mid-session (only when a project is deleted or the editor closes). Test: Task 10 `deletingSegmentDoesNotReleaseGrant`.
5. **Corrupt or half-written project file:** must be skipped; the home list still shows every other project. Test: Task 5 `corruptFileIsSkipped`.

---

## File Map

```
settings.gradle.kts, build.gradle.kts, gradle.properties, .gitignore, gradlew, gradle/wrapper/*
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/jjveleber/videoeditor/
  MainActivity.kt                 navigation host (placeholder until Task 13)
  VideoEditorApp.kt               Application, owns AppContainer
  AppContainer.kt                 manual DI: repository, grants, prober, thumbnails
  model/Segment.kt                Segment, Project, MIN_SEGMENT_US
  model/Timing.kt                 totalDurationUs, startOfSegmentUs, locate
  model/Edits.kt                  append, trimStart, trimEnd, canSplitAt, splitAt, delete, move
  history/UndoStack.kt            generic capped undo/redo stack
  storage/ProjectRepository.kt    JSON files in filesDir/projects
  storage/UriGrants.kt            UriGrants interface, AndroidUriGrants, releaseUnreferencedGrants
  media/SourceProbe.kt            ProbeResult, SourceProber, AndroidSourceProber, displaySize, toSegment
  media/ThumbnailLoader.kt        cached frame bitmaps
  media/CompositionPlan.kt        Quality, ClipPlan, CompositionPlan, planComposition, outputSize (pure)
  media/CompositionBuilder.kt     CompositionPlan to Media3 Composition
  media/Exporter.kt               Transformer to temp file, copy into MediaStore
  export/ExportController.kt      ExportState + process-wide StateFlow
  export/ExportService.kt         foreground service running Exporter
  player/ProjectPlayer.kt         ExoPlayer playlist + global time mapping
  ui/Format.kt                    formatDuration
  ui/home/HomeViewModel.kt, ui/home/HomeScreen.kt
  ui/editor/EditorViewModel.kt, ui/editor/EditorScreen.kt, ui/editor/ExportSheet.kt
  ui/timeline/TimelineGeometry.kt, ui/timeline/TimelineStrip.kt
app/src/test/java/com/jjveleber/videoeditor/...      JVM tests (+ testing/FakeGrants.kt, testing/FakeProber.kt)
app/src/androidTest/java/com/jjveleber/videoeditor/  instrumented tests (+ TestAssets.kt)
app/src/androidTest/assets/                          generated test clips
```

---

### Task 1: Project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `local.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/jjveleber/videoeditor/MainActivity.kt`
- Create (generated): `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Consumes: nothing
- Produces: a building app module; `MainActivity` (placeholder, replaced in Task 13)

- [ ] **Step 1: Generate the Gradle 8.7 wrapper using the cached Gradle 8.2**

The cached 8.2 cannot configure AGP 8.5, so generate the wrapper in an empty temp dir and copy it in.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd /home/jjveleber/projects/android/video-editor
W=$(mktemp -d) && touch "$W/settings.gradle.kts"
(cd "$W" && ~/.gradle/wrapper/dists/gradle-8.2-bin/*/gradle-8.2/bin/gradle wrapper --gradle-version 8.7 --distribution-type bin -q)
cp -r "$W/gradlew" "$W/gradlew.bat" "$W/gradle" .
cat gradle/wrapper/gradle-wrapper.properties
```

Expected: properties file contains `gradle-8.7-bin.zip`.

- [ ] **Step 2: Write root build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "VideoEditor"
include(":app")
```

`build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`.gitignore`:

```
.gradle/
.kotlin/
build/
local.properties
.idea/
*.iml
captures/
```

`local.properties` (not committed):

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

- [ ] **Step 3: Write `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.jjveleber.videoeditor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jjveleber.videoeditor"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
```

- [ ] **Step 4: Write manifest and placeholder activity**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:label="Video Editor"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`configChanges` keeps the activity (and its player) alive across rotation.

`app/src/main/java/com/jjveleber/videoeditor/MainActivity.kt`:

```kotlin
package com.jjveleber.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Text("Video Editor")
                }
            }
        }
    }
}
```

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`, and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties .gitignore gradlew gradlew.bat gradle app
git commit -m "build: scaffold Compose app with Media3 deps

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Model types and timing

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/model/Segment.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/model/Timing.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/model/TimingTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `const val MIN_SEGMENT_US: Long = 100_000L`
  - `@Serializable data class Segment(id: String, sourceUri: String, sourceDurationUs: Long, startUs: Long, endUs: Long, hasAudio: Boolean, width: Int, height: Int)` with `val durationUs: Long`
  - `@Serializable data class Project(id: String, name: String, segments: List<Segment> = emptyList(), createdAt: Long, updatedAt: Long)`
  - `data class SegmentPosition(index: Int, offsetUs: Long)`
  - `val Project.totalDurationUs: Long`, `fun Project.startOfSegmentUs(index: Int): Long`, `fun Project.locate(globalUs: Long): SegmentPosition?`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/jjveleber/videoeditor/model/TimingTest.kt`:

```kotlin
package com.jjveleber.videoeditor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimingTest {
    private fun seg(id: String, durationUs: Long) =
        Segment(id, "file:///$id.mp4", 10_000_000L, 0L, durationUs, true, 1920, 1080)

    private val project = Project("p", "P", listOf(seg("a", 1_000_000L), seg("b", 2_000_000L)), 0L, 0L)

    @Test
    fun segmentDurationUsesTrimmedRange() {
        val s = seg("x", 0L).copy(startUs = 1_000_000L, endUs = 3_000_000L)
        assertEquals(2_000_000L, s.durationUs)
    }

    @Test
    fun totalDurationSumsSegments() {
        assertEquals(3_000_000L, project.totalDurationUs)
    }

    @Test
    fun startOfSegment() {
        assertEquals(0L, project.startOfSegmentUs(0))
        assertEquals(1_000_000L, project.startOfSegmentUs(1))
    }

    @Test
    fun locateEmptyIsNull() {
        assertNull(project.copy(segments = emptyList()).locate(0L))
    }

    @Test
    fun locateNegativeClampsToStart() {
        assertEquals(SegmentPosition(0, 0L), project.locate(-5L))
    }

    @Test
    fun locateInsideSecond() {
        assertEquals(SegmentPosition(1, 500_000L), project.locate(1_500_000L))
    }

    @Test
    fun locateBoundaryBelongsToNext() {
        assertEquals(SegmentPosition(1, 0L), project.locate(1_000_000L))
    }

    @Test
    fun locateEndAndBeyondClampToLastEnd() {
        assertEquals(SegmentPosition(1, 2_000_000L), project.locate(3_000_000L))
        assertEquals(SegmentPosition(1, 2_000_000L), project.locate(9_000_000L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.model.TimingTest'`
Expected: FAIL, compilation errors (`Unresolved reference: Segment`).

- [ ] **Step 3: Implement**

`app/src/main/java/com/jjveleber/videoeditor/model/Segment.kt`:

```kotlin
package com.jjveleber.videoeditor.model

import kotlinx.serialization.Serializable

const val MIN_SEGMENT_US: Long = 100_000L

/** A kept range [startUs, endUs) of one source video. width/height are display size (rotation applied). */
@Serializable
data class Segment(
    val id: String,
    val sourceUri: String,
    val sourceDurationUs: Long,
    val startUs: Long,
    val endUs: Long,
    val hasAudio: Boolean,
    val width: Int,
    val height: Int,
) {
    val durationUs: Long get() = endUs - startUs
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val segments: List<Segment> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)
```

`app/src/main/java/com/jjveleber/videoeditor/model/Timing.kt`:

```kotlin
package com.jjveleber.videoeditor.model

/** A position inside the project: segment index plus offset from that segment's start. */
data class SegmentPosition(val index: Int, val offsetUs: Long)

val Project.totalDurationUs: Long get() = segments.sumOf { it.durationUs }

fun Project.startOfSegmentUs(index: Int): Long = segments.take(index).sumOf { it.durationUs }

/** Maps a project-global time to a segment. A boundary belongs to the next segment; out-of-range clamps. */
fun Project.locate(globalUs: Long): SegmentPosition? {
    if (segments.isEmpty()) return null
    var start = 0L
    segments.forEachIndexed { i, s ->
        val end = start + s.durationUs
        if (globalUs < end) return SegmentPosition(i, (globalUs - start).coerceAtLeast(0L))
        start = end
    }
    return SegmentPosition(segments.lastIndex, segments.last().durationUs)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.model.TimingTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/model app/src/test/java/com/jjveleber/videoeditor/model
git commit -m "feat(model): add Segment, Project, and timing helpers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Edit operations

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/model/Edits.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/model/EditsTest.kt`

**Interfaces:**
- Consumes: `Segment`, `Project`, `MIN_SEGMENT_US`, `locate` (Task 2)
- Produces (all return a new `Project`; invalid input returns an equal project):
  - `fun Project.append(newSegments: List<Segment>): Project`
  - `fun Project.trimStart(segmentId: String, startUs: Long): Project`
  - `fun Project.trimEnd(segmentId: String, endUs: Long): Project`
  - `fun Project.canSplitAt(globalUs: Long): Boolean`
  - `fun Project.splitAt(globalUs: Long, newId: () -> String = { UUID.randomUUID().toString() }): Project`
  - `fun Project.delete(segmentId: String): Project`
  - `fun Project.move(from: Int, to: Int): Project`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/jjveleber/videoeditor/model/EditsTest.kt`:

```kotlin
package com.jjveleber.videoeditor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditsTest {
    private fun seg(id: String, startUs: Long = 0L, endUs: Long = 4_000_000L, sourceUs: Long = 10_000_000L) =
        Segment(id, "file:///$id.mp4", sourceUs, startUs, endUs, true, 1920, 1080)

    private fun project(vararg s: Segment) = Project("p", "P", s.toList(), 0L, 0L)

    private fun ids(p: Project) = p.segments.map { it.id }

    @Test
    fun appendAddsToEnd() {
        val p = project(seg("a")).append(listOf(seg("b"), seg("c")))
        assertEquals(listOf("a", "b", "c"), ids(p))
    }

    @Test
    fun trimStartMovesStart() {
        assertEquals(1_000_000L, project(seg("a")).trimStart("a", 1_000_000L).segments[0].startUs)
    }

    @Test
    fun trimStartClampsAtZero() {
        assertEquals(0L, project(seg("a", startUs = 1_000_000L)).trimStart("a", -500L).segments[0].startUs)
    }

    @Test
    fun trimStartKeepsMinimumLength() {
        assertEquals(3_900_000L, project(seg("a")).trimStart("a", 3_990_000L).segments[0].startUs)
    }

    @Test
    fun trimEndClampsToSource() {
        assertEquals(10_000_000L, project(seg("a")).trimEnd("a", 20_000_000L).segments[0].endUs)
    }

    @Test
    fun trimEndKeepsMinimumLength() {
        val p = project(seg("a", startUs = 1_000_000L)).trimEnd("a", 1_050_000L)
        assertEquals(1_100_000L, p.segments[0].endUs)
    }

    @Test
    fun trimUnknownIdIsNoOp() {
        val p = project(seg("a"))
        assertEquals(p, p.trimStart("zz", 5L))
        assertEquals(p, p.trimEnd("zz", 5L))
    }

    @Test
    fun splitInMiddleOfSecondSegment() {
        val p = project(seg("a"), seg("b")).splitAt(5_000_000L) { "n" }
        assertEquals(listOf("a", "b", "n"), ids(p))
        assertEquals(0L to 1_000_000L, p.segments[1].startUs to p.segments[1].endUs)
        assertEquals(1_000_000L to 4_000_000L, p.segments[2].startUs to p.segments[2].endUs)
    }

    @Test
    fun splitUsesSourceTimeForTrimmedSegment() {
        val p = project(seg("a", startUs = 2_000_000L, endUs = 6_000_000L)).splitAt(1_000_000L) { "n" }
        assertEquals(2_000_000L to 3_000_000L, p.segments[0].startUs to p.segments[0].endUs)
        assertEquals(3_000_000L to 6_000_000L, p.segments[1].startUs to p.segments[1].endUs)
    }

    @Test
    fun splitNearEdgeIsRejected() {
        val p = project(seg("a"))
        assertFalse(p.canSplitAt(50_000L))
        assertFalse(p.canSplitAt(3_950_000L))
        assertTrue(p.canSplitAt(100_000L))
        assertEquals(p, p.splitAt(50_000L))
        assertEquals(p, p.splitAt(3_950_000L))
    }

    @Test
    fun splitEmptyProjectIsNoOp() {
        val p = project()
        assertFalse(p.canSplitAt(0L))
        assertEquals(p, p.splitAt(0L))
    }

    @Test
    fun deleteRemovesSegment() {
        assertEquals(listOf("b"), ids(project(seg("a"), seg("b")).delete("a")))
    }

    @Test
    fun moveForwardAndBackward() {
        val p = project(seg("a"), seg("b"), seg("c"))
        assertEquals(listOf("b", "c", "a"), ids(p.move(0, 2)))
        assertEquals(listOf("c", "a", "b"), ids(p.move(2, 0)))
    }

    @Test
    fun moveInvalidIndexIsNoOp() {
        val p = project(seg("a"), seg("b"), seg("c"))
        assertEquals(p, p.move(0, 3))
        assertEquals(p, p.move(-1, 0))
        assertEquals(p, p.move(1, 1))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.model.EditsTest'`
Expected: FAIL, `Unresolved reference: append` (and others).

- [ ] **Step 3: Implement**

`app/src/main/java/com/jjveleber/videoeditor/model/Edits.kt`:

```kotlin
package com.jjveleber.videoeditor.model

import java.util.UUID

fun Project.append(newSegments: List<Segment>): Project = copy(segments = segments + newSegments)

fun Project.trimStart(segmentId: String, startUs: Long): Project = mapSegment(segmentId) { s ->
    s.copy(startUs = startUs.coerceIn(0L, s.endUs - MIN_SEGMENT_US))
}

fun Project.trimEnd(segmentId: String, endUs: Long): Project = mapSegment(segmentId) { s ->
    s.copy(endUs = endUs.coerceIn(s.startUs + MIN_SEGMENT_US, s.sourceDurationUs))
}

fun Project.canSplitAt(globalUs: Long): Boolean {
    val pos = locate(globalUs) ?: return false
    val seg = segments[pos.index]
    return pos.offsetUs >= MIN_SEGMENT_US && seg.durationUs - pos.offsetUs >= MIN_SEGMENT_US
}

/** Splits the segment under [globalUs] into two. The right half gets a new id. */
fun Project.splitAt(globalUs: Long, newId: () -> String = { UUID.randomUUID().toString() }): Project {
    if (!canSplitAt(globalUs)) return this
    val pos = locate(globalUs)!!
    val seg = segments[pos.index]
    val cutUs = seg.startUs + pos.offsetUs
    val list = segments.toMutableList()
    list[pos.index] = seg.copy(endUs = cutUs)
    list.add(pos.index + 1, seg.copy(id = newId(), startUs = cutUs))
    return copy(segments = list)
}

fun Project.delete(segmentId: String): Project = copy(segments = segments.filterNot { it.id == segmentId })

/** Moves the segment at [from] so it ends up at index [to]. */
fun Project.move(from: Int, to: Int): Project {
    if (from !in segments.indices || to !in segments.indices || from == to) return this
    val list = segments.toMutableList()
    list.add(to, list.removeAt(from))
    return copy(segments = list)
}

private inline fun Project.mapSegment(id: String, transform: (Segment) -> Segment): Project =
    copy(segments = segments.map { if (it.id == id) transform(it) else it })
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.model.EditsTest'`
Expected: PASS (14 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/model/Edits.kt app/src/test/java/com/jjveleber/videoeditor/model/EditsTest.kt
git commit -m "feat(model): add trim, split, delete, move, append ops

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Undo stack

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/history/UndoStack.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/history/UndoStackTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `class UndoStack<T>(initial: T, capacity: Int = 50)` with `val current: T`, `val canUndo: Boolean`, `val canRedo: Boolean`, `fun push(value: T)`, `fun undo(): T?`, `fun redo(): T?` (undo/redo return the new current, or null when nothing to do)

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/jjveleber/videoeditor/history/UndoStackTest.kt`:

```kotlin
package com.jjveleber.videoeditor.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {
    @Test
    fun startsWithInitialAndNothingToUndo() {
        val s = UndoStack(0)
        assertEquals(0, s.current)
        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
        assertNull(s.undo())
        assertNull(s.redo())
    }

    @Test
    fun pushThenUndoRedo() {
        val s = UndoStack(0)
        s.push(1)
        s.push(2)
        assertEquals(1, s.undo())
        assertEquals(0, s.undo())
        assertNull(s.undo())
        assertEquals(1, s.redo())
        assertEquals(2, s.redo())
        assertNull(s.redo())
        assertEquals(2, s.current)
    }

    @Test
    fun pushClearsRedo() {
        val s = UndoStack(0)
        s.push(1)
        s.undo()
        s.push(5)
        assertFalse(s.canRedo)
        assertTrue(s.canUndo)
        assertEquals(5, s.current)
    }

    @Test
    fun capacityDropsOldest() {
        val s = UndoStack(0, capacity = 3)
        (1..5).forEach { s.push(it) }
        var steps = 0
        while (s.undo() != null) steps++
        assertEquals(3, steps)
        assertEquals(2, s.current)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.history.UndoStackTest'`
Expected: FAIL, `Unresolved reference: UndoStack`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/jjveleber/videoeditor/history/UndoStack.kt`:

```kotlin
package com.jjveleber.videoeditor.history

class UndoStack<T>(initial: T, private val capacity: Int = 50) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    var current: T = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(value: T) {
        past.addLast(current)
        if (past.size > capacity) past.removeFirst()
        current = value
        future.clear()
    }

    fun undo(): T? {
        if (past.isEmpty()) return null
        future.addLast(current)
        current = past.removeLast()
        return current
    }

    fun redo(): T? {
        if (future.isEmpty()) return null
        past.addLast(current)
        current = future.removeLast()
        return current
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.history.UndoStackTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/history app/src/test/java/com/jjveleber/videoeditor/history
git commit -m "feat(history): add capped undo/redo stack

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Project storage and URI grants

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/storage/ProjectRepository.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/storage/UriGrants.kt`
- Create: `app/src/test/java/com/jjveleber/videoeditor/testing/FakeGrants.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/storage/ProjectRepositoryTest.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/storage/UriGrantsTest.kt`

**Interfaces:**
- Consumes: `Project`, `Segment` (Task 2)
- Produces:
  - `class ProjectRepository(dir: File, clock: () -> Long = System::currentTimeMillis)` with `fun create(name: String): Project`, `fun list(): List<Project>` (newest `updatedAt` first, unreadable files skipped), `fun load(id: String): Project?`, `fun save(project: Project): Project` (sets `updatedAt`), `fun rename(id: String, name: String): Project?`, `fun delete(id: String)`. Blocking I/O: call from `Dispatchers.IO`.
  - `interface UriGrants { fun take(uri: String): Boolean; fun release(uri: String); fun persisted(): Set<String> }`
  - `class AndroidUriGrants(resolver: ContentResolver) : UriGrants`
  - `fun releaseUnreferencedGrants(projects: List<Project>, grants: UriGrants)`
  - Test helper `class FakeGrants(var allowTake: Boolean = true) : UriGrants` with `val taken: MutableSet<String>`, `val released: MutableList<String>`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/jjveleber/videoeditor/testing/FakeGrants.kt`:

```kotlin
package com.jjveleber.videoeditor.testing

import com.jjveleber.videoeditor.storage.UriGrants

class FakeGrants(var allowTake: Boolean = true) : UriGrants {
    val taken = mutableSetOf<String>()
    val released = mutableListOf<String>()

    override fun take(uri: String): Boolean {
        if (allowTake) taken += uri
        return allowTake
    }

    override fun release(uri: String) {
        released += uri
        taken -= uri
    }

    override fun persisted(): Set<String> = taken.toSet()
}
```

`app/src/test/java/com/jjveleber/videoeditor/storage/ProjectRepositoryTest.kt`:

```kotlin
package com.jjveleber.videoeditor.storage

import com.jjveleber.videoeditor.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1L
    private lateinit var dir: File
    private lateinit var repo: ProjectRepository

    private val segment = Segment("s1", "content://a.mp4", 5_000_000L, 0L, 5_000_000L, true, 1920, 1080)

    @Before
    fun setUp() {
        dir = tmp.newFolder("projects")
        repo = ProjectRepository(dir) { now }
    }

    @Test
    fun createThenLoadRoundTrips() {
        val p = repo.create("A")
        repo.save(p.copy(segments = listOf(segment)))
        val loaded = repo.load(p.id)!!
        assertEquals("A", loaded.name)
        assertEquals(listOf(segment), loaded.segments)
    }

    @Test
    fun saveUpdatesTimestamp() {
        val p = repo.create("A")
        now = 5L
        repo.save(p)
        assertEquals(5L, repo.load(p.id)!!.updatedAt)
        assertEquals(1L, repo.load(p.id)!!.createdAt)
    }

    @Test
    fun listSortsByMostRecent() {
        val a = repo.create("A")
        now = 2L
        repo.create("B")
        now = 3L
        repo.save(a)
        assertEquals(listOf("A", "B"), repo.list().map { it.name })
    }

    @Test
    fun renameChangesName() {
        val p = repo.create("A")
        repo.rename(p.id, "Trip")
        assertEquals("Trip", repo.load(p.id)!!.name)
    }

    @Test
    fun deleteRemovesProject() {
        val p = repo.create("A")
        repo.delete(p.id)
        assertNull(repo.load(p.id))
        assertEquals(0, repo.list().size)
    }

    @Test
    fun loadMissingReturnsNull() {
        assertNull(repo.load("nope"))
    }

    @Test
    fun corruptFileIsSkipped() {
        repo.create("Good")
        File(dir, "broken.json").writeText("{not json")
        File(dir, "half.json.tmp").writeText("{\"id\":")
        assertEquals(listOf("Good"), repo.list().map { it.name })
        assertNull(repo.load("broken"))
    }
}
```

`app/src/test/java/com/jjveleber/videoeditor/storage/UriGrantsTest.kt`:

```kotlin
package com.jjveleber.videoeditor.storage

import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.testing.FakeGrants
import org.junit.Assert.assertEquals
import org.junit.Test

class UriGrantsTest {
    private fun seg(uri: String) = Segment(uri, uri, 1_000_000L, 0L, 1_000_000L, true, 1, 1)

    @Test
    fun releasesOnlyUnreferencedUris() {
        val grants = FakeGrants().apply { take("u1"); take("u2"); take("u3") }
        val projects = listOf(
            Project("p1", "P1", listOf(seg("u1")), 0L, 0L),
            Project("p2", "P2", listOf(seg("u1"), seg("u2")), 0L, 0L),
        )
        releaseUnreferencedGrants(projects, grants)
        assertEquals(listOf("u3"), grants.released)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.storage.*'`
Expected: FAIL, `Unresolved reference: ProjectRepository` / `UriGrants`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/jjveleber/videoeditor/storage/ProjectRepository.kt`:

```kotlin
package com.jjveleber.videoeditor.storage

import com.jjveleber.videoeditor.model.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/** Stores each project as `<id>.json` in [dir]. Blocking I/O: call off the main thread. */
class ProjectRepository(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(name: String): Project {
        val now = clock()
        val project = Project(UUID.randomUUID().toString(), name, emptyList(), now, now)
        write(project)
        return project
    }

    fun list(): List<Project> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { read(it) }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): Project? = read(fileFor(id))

    fun save(project: Project): Project {
        val saved = project.copy(updatedAt = clock())
        write(saved)
        return saved
    }

    fun rename(id: String, name: String): Project? = load(id)?.let { save(it.copy(name = name)) }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun fileFor(id: String) = File(dir, "$id.json")

    private fun read(file: File): Project? = try {
        if (file.exists()) json.decodeFromString<Project>(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    /** Writes to a temp file then renames, so a crash mid-write never leaves a half-written project. */
    private fun write(project: Project) {
        dir.mkdirs()
        val tmp = File(dir, "${project.id}.json.tmp")
        tmp.writeText(json.encodeToString(project))
        if (!tmp.renameTo(fileFor(project.id))) throw IOException("Could not save project ${project.id}")
    }
}
```

`app/src/main/java/com/jjveleber/videoeditor/storage/UriGrants.kt`:

```kotlin
package com.jjveleber.videoeditor.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import com.jjveleber.videoeditor.model.Project

/** Persistable read access to picked source files. */
interface UriGrants {
    /** Returns false if the provider refused a persistable grant. */
    fun take(uri: String): Boolean
    fun release(uri: String)
    fun persisted(): Set<String>
}

class AndroidUriGrants(private val resolver: ContentResolver) : UriGrants {
    override fun take(uri: String): Boolean = try {
        resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (e: SecurityException) {
        false
    }

    override fun release(uri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Already gone.
        }
    }

    override fun persisted(): Set<String> = resolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
}

/** Releases every persisted grant that no saved project references. */
fun releaseUnreferencedGrants(projects: List<Project>, grants: UriGrants) {
    val referenced = projects.flatMap { p -> p.segments.map { it.sourceUri } }.toSet()
    (grants.persisted() - referenced).forEach(grants::release)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.storage.*'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/storage app/src/test/java/com/jjveleber/videoeditor/storage app/src/test/java/com/jjveleber/videoeditor/testing
git commit -m "feat(storage): add JSON project repository and URI grants

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Source probe, thumbnails, test assets, emulator

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/media/SourceProbe.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/media/ThumbnailLoader.kt`
- Create: `app/src/androidTest/assets/clip_landscape_audio.mp4`, `clip_portrait_silent.mp4`, `clip_rotated.mp4` (generated)
- Create: `app/src/androidTest/java/com/jjveleber/videoeditor/TestAssets.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/media/SourceProbeTest.kt` (JVM)
- Test: `app/src/androidTest/java/com/jjveleber/videoeditor/media/AndroidSourceProberTest.kt` (instrumented)

**Interfaces:**
- Consumes: `Segment` (Task 2)
- Produces:
  - `data class ProbeResult(durationUs: Long, width: Int, height: Int, hasAudio: Boolean)` (width/height are display size)
  - `fun ProbeResult.toSegment(uri: String, id: String = UUID.randomUUID().toString()): Segment` (full range)
  - `fun displaySize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int>`
  - `interface SourceProber { suspend fun probe(uri: String): ProbeResult?; fun displayName(uri: String): String }` (`probe` returns null for unreadable or non-video files)
  - `class AndroidSourceProber(context: Context) : SourceProber`
  - `class ThumbnailLoader(context: Context)` with `suspend fun load(uri: String, timeUs: Long, heightPx: Int): Bitmap?`
  - androidTest helper `fun copyAsset(name: String): String` (returns a `file://` URI string of the asset copied into the app's cacheDir)

- [ ] **Step 1: Generate test clips**

```bash
cd /home/jjveleber/projects/android/video-editor
A=app/src/androidTest/assets && mkdir -p $A
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=size=640x360:rate=30:duration=2 -f lavfi -i sine=frequency=440:duration=2 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest $A/clip_landscape_audio.mp4
ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=size=360x640:rate=24:duration=2 -c:v libx264 -pix_fmt yuv420p -an $A/clip_portrait_silent.mp4
ffmpeg -hide_banner -loglevel error -y -display_rotation 90 -i $A/clip_landscape_audio.mp4 -c copy $A/clip_rotated.mp4
ffprobe -v error -show_entries stream=codec_type,width,height:stream_side_data=rotation -of compact $A/clip_rotated.mp4
ls -la $A
```

Expected: three files (each well under 200 KB); ffprobe shows `rotation=90` (or `-90`) side data on the video stream of `clip_rotated.mp4`.

- [ ] **Step 2: Create an emulator and start it (or connect a device)**

If `adb devices` already lists a device, skip this step. Otherwise:

```bash
echo no | avdmanager create avd -n videoeditor_api34 -k "system-images;android-34;google_apis;x86_64" --force
```

Start it in the background (use the Bash tool's `run_in_background`):

```bash
$ANDROID_HOME/emulator/emulator -avd videoeditor_api34 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect
```

Then wait for boot:

```bash
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done' && adb devices
```

Expected: one `emulator-5554 device` line.

- [ ] **Step 3: Write the failing tests**

`app/src/test/java/com/jjveleber/videoeditor/media/SourceProbeTest.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceProbeTest {
    @Test
    fun displaySizeSwapsForQuarterTurns() {
        assertEquals(1080 to 1920, displaySize(1920, 1080, 90))
        assertEquals(1080 to 1920, displaySize(1920, 1080, 270))
        assertEquals(1080 to 1920, displaySize(1920, 1080, -90))
    }

    @Test
    fun displaySizeKeepsForHalfTurns() {
        assertEquals(1920 to 1080, displaySize(1920, 1080, 0))
        assertEquals(1920 to 1080, displaySize(1920, 1080, 180))
    }

    @Test
    fun toSegmentCoversFullSource() {
        val s = ProbeResult(3_000_000L, 640, 360, false).toSegment("content://x", id = "s")
        assertEquals("s", s.id)
        assertEquals("content://x", s.sourceUri)
        assertEquals(0L, s.startUs)
        assertEquals(3_000_000L, s.endUs)
        assertEquals(3_000_000L, s.sourceDurationUs)
        assertEquals(false, s.hasAudio)
        assertEquals(640 to 360, s.width to s.height)
    }
}
```

`app/src/androidTest/java/com/jjveleber/videoeditor/TestAssets.kt`:

```kotlin
package com.jjveleber.videoeditor

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Copies an androidTest asset into the app's cacheDir and returns its file:// URI string. */
fun copyAsset(name: String): String {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val out = File(instrumentation.targetContext.cacheDir, name)
    instrumentation.context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
    return Uri.fromFile(out).toString()
}
```

`app/src/androidTest/java/com/jjveleber/videoeditor/media/AndroidSourceProberTest.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.videoeditor.copyAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidSourceProberTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prober = AndroidSourceProber(context)

    @Test
    fun probesLandscapeClipWithAudio() = runBlocking {
        val r = prober.probe(copyAsset("clip_landscape_audio.mp4"))!!
        assertEquals(640 to 360, r.width to r.height)
        assertEquals(true, r.hasAudio)
        assertEquals(2_000_000.0, r.durationUs.toDouble(), 100_000.0)
    }

    @Test
    fun probesSilentClip() = runBlocking {
        val r = prober.probe(copyAsset("clip_portrait_silent.mp4"))!!
        assertEquals(360 to 640, r.width to r.height)
        assertEquals(false, r.hasAudio)
    }

    @Test
    fun probesRotatedClipAsPortrait() = runBlocking {
        val r = prober.probe(copyAsset("clip_rotated.mp4"))!!
        assertEquals(360 to 640, r.width to r.height)
    }

    @Test
    fun nonVideoFileIsNull() = runBlocking {
        val f = File(context.cacheDir, "notes.txt").apply { writeText("hello") }
        assertNull(prober.probe(Uri.fromFile(f).toString()))
    }

    @Test
    fun thumbnailLoads() = runBlocking {
        val bmp = ThumbnailLoader(context).load(copyAsset("clip_landscape_audio.mp4"), 500_000L, 72)
        assertNotNull(bmp)
    }
}
```

- [ ] **Step 4: Run JVM test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.media.SourceProbeTest'`
Expected: FAIL, `Unresolved reference: displaySize`.

- [ ] **Step 5: Implement**

`app/src/main/java/com/jjveleber/videoeditor/media/SourceProbe.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.jjveleber.videoeditor.model.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** What we need to know about a source video. width/height are display size (rotation applied). */
data class ProbeResult(val durationUs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

fun ProbeResult.toSegment(uri: String, id: String = UUID.randomUUID().toString()): Segment =
    Segment(id, uri, durationUs, 0L, durationUs, hasAudio, width, height)

fun displaySize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
    if (rotationDegrees % 180 != 0) height to width else width to height

interface SourceProber {
    /** Returns null if the file is unreadable or has no video track. */
    suspend fun probe(uri: String): ProbeResult?
    fun displayName(uri: String): String
}

class AndroidSourceProber(private val context: Context) : SourceProber {
    override suspend fun probe(uri: String): ProbeResult? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            fun meta(key: Int) = retriever.extractMetadata(key)
            if (meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return@withContext null
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext null
            val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: return@withContext null
            val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: return@withContext null
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (w, h) = displaySize(width, height, rotation)
            ProbeResult(
                durationUs = durationMs * 1000,
                width = w,
                height = h,
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    override fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        val queried = try {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return queried ?: parsed.lastPathSegment ?: uri
    }
}
```

`app/src/main/java/com/jjveleber/videoeditor/media/ThumbnailLoader.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailLoader(private val context: Context) {
    private val cache = LruCache<String, Bitmap>(64)

    suspend fun load(uri: String, timeUs: Long, heightPx: Int): Bitmap? {
        val key = "$uri@$timeUs@$heightPx"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, heightPx * 16 / 9, heightPx,
                )
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
        bitmap?.let { cache.put(key, it) }
        return bitmap
    }
}
```

- [ ] **Step 6: Run JVM and instrumented tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.media.SourceProbeTest'`
Expected: PASS (3 tests).

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jjveleber.videoeditor.media.AndroidSourceProberTest`
Expected: PASS (5 tests).

If `probesRotatedClipAsPortrait` fails, check Step 1's ffprobe output first: the asset may be missing rotation metadata (a test-data problem, not a prober bug).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/media app/src/test/java/com/jjveleber/videoeditor/media app/src/androidTest
git commit -m "feat(media): add source probe and thumbnail loader

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Composition plan and exporter

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/media/CompositionPlan.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/media/CompositionBuilder.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/media/Exporter.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/media/CompositionPlanTest.kt` (JVM)
- Test: `app/src/androidTest/java/com/jjveleber/videoeditor/media/ExporterTest.kt` (instrumented)

**Interfaces:**
- Consumes: `Project`, `Segment` (Task 2); `copyAsset` (Task 6)
- Produces:
  - `enum class Quality(val label: String) { P720("720p"), P1080("1080p"), MATCH_FIRST("Match first clip") }`
  - `@Serializable data class ClipPlan(uri: String, startUs: Long, endUs: Long)`
  - `@Serializable data class CompositionPlan(width: Int, height: Int, clips: List<ClipPlan>)`
  - `fun planComposition(project: Project, quality: Quality): CompositionPlan` (throws `IllegalArgumentException` for an empty project)
  - `fun outputSize(firstWidth: Int, firstHeight: Int, quality: Quality): Pair<Int, Int>`
  - `fun buildComposition(plan: CompositionPlan): Composition`
  - `class Exporter(context: Context)` with `suspend fun export(plan: CompositionPlan, onProgress: (Int) -> Unit): Uri` (returns the MediaStore URI; throws on failure; temp file always deleted)

- [ ] **Step 1: Write the failing JVM test**

`app/src/test/java/com/jjveleber/videoeditor/media/CompositionPlanTest.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionPlanTest {
    private fun seg(id: String, w: Int, h: Int, start: Long = 0L, end: Long = 1_000_000L) =
        Segment(id, "content://$id", 5_000_000L, start, end, true, w, h)

    @Test
    fun p720LandscapeAndPortrait() {
        assertEquals(1280 to 720, outputSize(1920, 1080, Quality.P720))
        assertEquals(720 to 1280, outputSize(1080, 1920, Quality.P720))
    }

    @Test
    fun squareCountsAsLandscape() {
        assertEquals(1920 to 1080, outputSize(1000, 1000, Quality.P1080))
    }

    @Test
    fun p1080Portrait() {
        assertEquals(1080 to 1920, outputSize(720, 1280, Quality.P1080))
    }

    @Test
    fun matchFirstKeepsSmallSizes() {
        assertEquals(1280 to 720, outputSize(1280, 720, Quality.MATCH_FIRST))
    }

    @Test
    fun matchFirstCapsFourK() {
        assertEquals(1920 to 1080, outputSize(3840, 2160, Quality.MATCH_FIRST))
        assertEquals(1080 to 1920, outputSize(2160, 3840, Quality.MATCH_FIRST))
    }

    @Test
    fun matchFirstRoundsToEven() {
        assertEquals(640 to 360, outputSize(641, 361, Quality.MATCH_FIRST))
    }

    @Test
    fun planMapsSegmentsInOrderUsingFirstClipForSize() {
        val project = Project(
            "p", "P",
            listOf(seg("a", 1080, 1920, 500_000L, 900_000L), seg("b", 1920, 1080)),
            0L, 0L,
        )
        val plan = planComposition(project, Quality.P720)
        assertEquals(720 to 1280, plan.width to plan.height)
        assertEquals(
            listOf(ClipPlan("content://a", 500_000L, 900_000L), ClipPlan("content://b", 0L, 1_000_000L)),
            plan.clips,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyProjectThrows() {
        planComposition(Project("p", "P", emptyList(), 0L, 0L), Quality.P720)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.media.CompositionPlanTest'`
Expected: FAIL, `Unresolved reference: outputSize`.

- [ ] **Step 3: Implement the plan**

`app/src/main/java/com/jjveleber/videoeditor/media/CompositionPlan.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import com.jjveleber.videoeditor.model.Project
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

enum class Quality(val label: String) {
    P720("720p"),
    P1080("1080p"),
    MATCH_FIRST("Match first clip"),
}

@Serializable
data class ClipPlan(val uri: String, val startUs: Long, val endUs: Long)

/** Everything needed to export, as plain data (serializable so it can be passed to the export service). */
@Serializable
data class CompositionPlan(val width: Int, val height: Int, val clips: List<ClipPlan>)

private const val MAX_LONG_SIDE = 1920

fun planComposition(project: Project, quality: Quality): CompositionPlan {
    require(project.segments.isNotEmpty()) { "Project has no clips" }
    val first = project.segments.first()
    val (width, height) = outputSize(first.width, first.height, quality)
    return CompositionPlan(width, height, project.segments.map { ClipPlan(it.sourceUri, it.startUs, it.endUs) })
}

fun outputSize(firstWidth: Int, firstHeight: Int, quality: Quality): Pair<Int, Int> {
    val portrait = firstHeight > firstWidth
    fun oriented(long: Int, short: Int) = if (portrait) short to long else long to short
    return when (quality) {
        Quality.P720 -> oriented(1280, 720)
        Quality.P1080 -> oriented(1920, 1080)
        Quality.MATCH_FIRST -> {
            val scale = minOf(1.0, MAX_LONG_SIDE.toDouble() / maxOf(firstWidth, firstHeight))
            even(firstWidth * scale) to even(firstHeight * scale)
        }
    }
}

/** Video encoders need even dimensions. */
private fun even(value: Double): Int = (value.roundToInt() / 2 * 2).coerceAtLeast(2)
```

- [ ] **Step 4: Run JVM test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.media.CompositionPlanTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Write the failing instrumented export test**

`app/src/androidTest/java/com/jjveleber/videoeditor/media/ExporterTest.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.videoeditor.copyAsset
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mergesMixedClipsAt720p() = runBlocking {
        val landscape = copyAsset("clip_landscape_audio.mp4")
        val portraitSilent = copyAsset("clip_portrait_silent.mp4")
        val project = Project(
            "p", "test",
            listOf(
                Segment("a", landscape, 2_000_000L, 0L, 1_500_000L, true, 640, 360),
                Segment("b", portraitSilent, 2_000_000L, 0L, 1_500_000L, false, 360, 640),
            ),
            0L, 0L,
        )
        val uri = Exporter(context).export(planComposition(project, Quality.P720)) {}
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, uri)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble()
            assertEquals(3000.0, durationMs, 100.0)
            assertEquals("1280", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("720", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            r.release()
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
```

- [ ] **Step 6: Implement the builder and exporter**

`app/src/main/java/com/jjveleber/videoeditor/media/CompositionBuilder.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

/** One sequence, one clipped item per segment, all scaled to fit (letterboxed) the output size. */
@OptIn(UnstableApi::class)
fun buildComposition(plan: CompositionPlan): Composition {
    val fit = Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT)
    val items = plan.clips.map { clip ->
        val mediaItem = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startUs / 1000)
                    .setEndPositionMs(clip.endUs / 1000)
                    .build(),
            )
            .build()
        EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), listOf(fit)))
            .build()
    }
    return Composition.Builder(EditedMediaItemSequence(items))
        .experimentalSetForceAudioTrack(true)
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()
}
```

`app/src/main/java/com/jjveleber/videoeditor/media/Exporter.kt`:

```kotlin
package com.jjveleber.videoeditor.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports to a temp file in cacheDir, then copies it into Movies/VideoEditor/. */
@OptIn(UnstableApi::class)
class Exporter(private val context: Context) {

    suspend fun export(plan: CompositionPlan, onProgress: (Int) -> Unit): Uri {
        val temp = File(context.cacheDir, "export-${System.currentTimeMillis()}.mp4")
        try {
            withContext(Dispatchers.Main) { transform(buildComposition(plan), temp, onProgress) }
            return withContext(Dispatchers.IO) { saveToMovies(temp) }
        } finally {
            temp.delete()
        }
    }

    /** Must run on the main thread: Transformer is bound to the looper it is built on. */
    private suspend fun transform(composition: Composition, out: File, onProgress: (Int) -> Unit) = coroutineScope {
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        transformer.start(composition, out.absolutePath)
        val poller = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                delay(250)
            }
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            transformer.cancel()
            throw e
        } finally {
            poller.cancel()
        }
    }

    private fun saveToMovies(file: File): Uri {
        val resolver = context.contentResolver
        val name = "VideoEditor_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create output file")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Could not open output file")
            output.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
```

- [ ] **Step 7: Run instrumented test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jjveleber.videoeditor.media.ExporterTest`
Expected: PASS. (The emulator uses software codecs; allow up to a minute.)

If it fails on the audio assertion or with an audio-format error, that means Media3 1.4.1 did not insert silence for the audio-less second clip. Do not loosen the test. Stop and report the exact exception, since the spec's silent-audio approach would need revisiting.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/media app/src/test/java/com/jjveleber/videoeditor/media app/src/androidTest
git commit -m "feat(media): plan compositions and export to Movies via Transformer

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Export controller and foreground service

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/export/ExportController.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/export/ExportService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/jjveleber/videoeditor/export/ExportControllerTest.kt` (JVM)
- Test: `app/src/androidTest/java/com/jjveleber/videoeditor/export/ExportServiceTest.kt` (instrumented)

**Interfaces:**
- Consumes: `CompositionPlan`, `ClipPlan`, `Exporter` (Task 7); `copyAsset` (Task 6); `MainActivity` (Task 1)
- Produces:
  - `sealed interface ExportState { data object Idle; data class Running(val percent: Int); data class Done(val uri: String); data class Failed(val message: String) }`
  - `object ExportController { val state: StateFlow<ExportState>; fun update(state: ExportState); fun reset() }` (`reset()` is ignored while Running)
  - `class ExportService : Service` with `companion fun start(context: Context, plan: CompositionPlan)` (ignored while an export is Running)

- [ ] **Step 1: Write the failing JVM test**

`app/src/test/java/com/jjveleber/videoeditor/export/ExportControllerTest.kt`:

```kotlin
package com.jjveleber.videoeditor.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportControllerTest {
    @After
    fun tearDown() {
        ExportController.update(ExportState.Idle)
    }

    @Test
    fun resetDoesNotInterruptRunning() {
        ExportController.update(ExportState.Running(40))
        ExportController.reset()
        assertEquals(ExportState.Running(40), ExportController.state.value)
    }

    @Test
    fun resetClearsFinishedStates() {
        ExportController.update(ExportState.Done("content://x"))
        ExportController.reset()
        assertEquals(ExportState.Idle, ExportController.state.value)
        ExportController.update(ExportState.Failed("boom"))
        ExportController.reset()
        assertEquals(ExportState.Idle, ExportController.state.value)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.export.ExportControllerTest'`
Expected: FAIL, `Unresolved reference: ExportController`.

- [ ] **Step 3: Implement the controller**

`app/src/main/java/com/jjveleber/videoeditor/export/ExportController.kt`:

```kotlin
package com.jjveleber.videoeditor.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Done(val uri: String) : ExportState
    data class Failed(val message: String) : ExportState
}

/** Process-wide export state shared by ExportService (writer) and the export sheet (reader). */
object ExportController {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun update(state: ExportState) {
        _state.value = state
    }

    fun reset() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }
}
```

- [ ] **Step 4: Run JVM test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.export.ExportControllerTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Write the failing instrumented service test**

`app/src/androidTest/java/com/jjveleber/videoeditor/export/ExportServiceTest.kt`:

```kotlin
package com.jjveleber.videoeditor.export

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.videoeditor.MainActivity
import com.jjveleber.videoeditor.copyAsset
import com.jjveleber.videoeditor.media.ClipPlan
import com.jjveleber.videoeditor.media.CompositionPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun serviceExportsAndReportsDone() = runBlocking {
        // Foreground the app so starting a foreground service is allowed.
        ActivityScenario.launch(MainActivity::class.java).use {
            ExportController.update(ExportState.Idle)
            val plan = CompositionPlan(1280, 720, listOf(ClipPlan(copyAsset("clip_landscape_audio.mp4"), 0L, 1_000_000L)))
            ExportService.start(context, plan)
            val result = withTimeout(120_000) {
                ExportController.state.first { it is ExportState.Done || it is ExportState.Failed }
            }
            assertTrue("got $result", result is ExportState.Done)
            context.contentResolver.delete(Uri.parse((result as ExportState.Done).uri), null, null)
            ExportController.reset()
        }
    }
}
```

- [ ] **Step 6: Implement the service and register it**

`app/src/main/java/com/jjveleber/videoeditor/export/ExportService.kt`:

```kotlin
package com.jjveleber.videoeditor.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jjveleber.videoeditor.media.CompositionPlan
import com.jjveleber.videoeditor.media.Exporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Runs one export at a time in the foreground so it survives backgrounding and screen-off. */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService requires startForeground promptly, even if we then bail out.
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Export", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progressNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive == true) return START_NOT_STICKY
        val planJson = intent?.getStringExtra(EXTRA_PLAN)
        if (planJson == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val plan = Json.decodeFromString<CompositionPlan>(planJson)
        ExportController.update(ExportState.Running(0))
        job = scope.launch {
            val result = try {
                val uri = Exporter(applicationContext).export(plan) { percent ->
                    ExportController.update(ExportState.Running(percent))
                    notifications.notify(PROGRESS_ID, progressNotification(percent))
                }
                ExportState.Done(uri.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportState.Failed(e.message ?: "Export failed")
            }
            ExportController.update(result)
            ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notifications.notify(RESULT_ID, resultNotification(result))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progressNotification(percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Exporting video")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun resultNotification(state: ExportState): Notification {
        val (title, text) = when (state) {
            is ExportState.Done -> "Export complete" to "Saved to Movies/VideoEditor/"
            is ExportState.Failed -> "Export failed" to state.message
            else -> "Export" to ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val EXTRA_PLAN = "plan"
        private const val CHANNEL_ID = "export"
        private const val PROGRESS_ID = 1
        private const val RESULT_ID = 2

        fun start(context: Context, plan: CompositionPlan) {
            if (ExportController.state.value is ExportState.Running) return
            ExportController.update(ExportState.Running(0))
            val intent = Intent(context, ExportService::class.java).putExtra(EXTRA_PLAN, Json.encodeToString(plan))
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
```

Modify `app/src/main/AndroidManifest.xml`: add permissions before `<application>` and the service inside it.

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

```xml
        <service
            android:name=".export.ExportService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

- [ ] **Step 7: Run instrumented test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jjveleber.videoeditor.export.ExportServiceTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main app/src/test/java/com/jjveleber/videoeditor/export app/src/androidTest/java/com/jjveleber/videoeditor/export
git commit -m "feat(export): run exports in a foreground service with shared state

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Project player

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/player/ProjectPlayer.kt`
- Test: `app/src/androidTest/java/com/jjveleber/videoeditor/player/ProjectPlayerTest.kt` (instrumented)

**Interfaces:**
- Consumes: `Project`, `locate`, `startOfSegmentUs`, `totalDurationUs` (Task 2); `copyAsset` (Task 6)
- Produces: `class ProjectPlayer(context: Context)` with `val player: ExoPlayer`, `fun setProject(project: Project?)` (null or empty clears; keeps the current global position), `fun currentGlobalUs(): Long`, `fun seekToGlobal(us: Long)`, `fun release()`. Must be used on the main thread.

- [ ] **Step 1: Write the failing test**

`app/src/androidTest/java/com/jjveleber/videoeditor/player/ProjectPlayerTest.kt`:

```kotlin
package com.jjveleber.videoeditor.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.videoeditor.copyAsset
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun seekToGlobalLandsInSecondSegment() {
        val uri = copyAsset("clip_landscape_audio.mp4")
        val project = Project(
            "p", "P",
            listOf(
                Segment("a", uri, 2_000_000L, 0L, 1_500_000L, true, 640, 360),
                Segment("b", uri, 2_000_000L, 500_000L, 2_000_000L, true, 640, 360),
            ),
            0L, 0L,
        )
        var index = -1
        var positionMs = -1L
        var globalUs = -1L
        instrumentation.runOnMainSync {
            val pp = ProjectPlayer(instrumentation.targetContext)
            pp.setProject(project)
            pp.seekToGlobal(2_000_000L)
            index = pp.player.currentMediaItemIndex
            positionMs = pp.player.currentPosition
            globalUs = pp.currentGlobalUs()
            pp.release()
        }
        assertEquals(1, index)
        assertEquals(500L, positionMs)
        assertEquals(2_000_000L, globalUs)
    }

    @Test
    fun nullProjectClearsPlaylist() {
        var count = -1
        instrumentation.runOnMainSync {
            val pp = ProjectPlayer(instrumentation.targetContext)
            pp.setProject(null)
            count = pp.player.mediaItemCount
            pp.release()
        }
        assertEquals(0, count)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jjveleber.videoeditor.player.ProjectPlayerTest`
Expected: FAIL, compilation error `Unresolved reference: ProjectPlayer`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/jjveleber/videoeditor/player/ProjectPlayer.kt`:

```kotlin
package com.jjveleber.videoeditor.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.locate
import com.jjveleber.videoeditor.model.startOfSegmentUs
import com.jjveleber.videoeditor.model.totalDurationUs

/** Plays a project as a playlist of clipped items and maps player positions to project-global time. */
class ProjectPlayer(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private var project: Project? = null

    fun setProject(project: Project?) {
        val keepUs = currentGlobalUs()
        this.project = project
        if (project == null || project.segments.isEmpty()) {
            player.clearMediaItems()
            return
        }
        val items = project.segments.map { s ->
            MediaItem.Builder()
                .setUri(s.sourceUri)
                .setMediaId(s.id)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(s.startUs / 1000)
                        .setEndPositionMs(s.endUs / 1000)
                        .build(),
                )
                .build()
        }
        val pos = project.locate(keepUs.coerceAtMost(project.totalDurationUs - 1))!!
        player.setMediaItems(items, pos.index, pos.offsetUs / 1000)
        player.prepare()
    }

    fun currentGlobalUs(): Long {
        val p = project ?: return 0L
        if (p.segments.isEmpty()) return 0L
        val index = player.currentMediaItemIndex.coerceIn(0, p.segments.lastIndex)
        return p.startOfSegmentUs(index) + player.currentPosition * 1000
    }

    fun seekToGlobal(us: Long) {
        val pos = project?.locate(us) ?: return
        player.seekTo(pos.index, pos.offsetUs / 1000)
    }

    fun release() {
        player.release()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jjveleber.videoeditor.player.ProjectPlayerTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/player app/src/androidTest/java/com/jjveleber/videoeditor/player
git commit -m "feat(player): preview project as clipped ExoPlayer playlist

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Editor view model

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/ui/editor/EditorViewModel.kt`
- Create: `app/src/test/java/com/jjveleber/videoeditor/testing/FakeProber.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/ui/editor/EditorViewModelTest.kt`

**Interfaces:**
- Consumes: model ops (Tasks 2, 3), `UndoStack` (Task 4), `ProjectRepository`, `UriGrants`, `releaseUnreferencedGrants`, `FakeGrants` (Task 5), `SourceProber`, `ProbeResult`, `toSegment` (Task 6)
- Produces:
  - `data class EditorUiState(project: Project?, selectedId: String?, canUndo: Boolean, canRedo: Boolean, missingUris: Set<String>, isDragging: Boolean, message: String?)` with `val hasMissing: Boolean`, `val canExport: Boolean`
  - `class EditorViewModel(projectId: String, repo: ProjectRepository, prober: SourceProber, grants: UriGrants, io: CoroutineDispatcher = Dispatchers.IO) : ViewModel` with `val state: StateFlow<EditorUiState>`, `fun addClips(uris: List<String>)`, `fun select(id: String?)`, `fun trimStartPreview(segmentId: String, startUs: Long)`, `fun trimEndPreview(segmentId: String, endUs: Long)`, `fun endTrim()`, `fun splitAt(globalUs: Long)`, `fun deleteSelected()`, `fun move(from: Int, to: Int)`, `fun undo()`, `fun redo()`, `fun consumeMessage()`, `fun flush()`
  - Test helper `class FakeProber(results: Map<String, ProbeResult>) : SourceProber` (unknown URIs probe as null; `displayName` is the text after the last `/`)

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/jjveleber/videoeditor/testing/FakeProber.kt`:

```kotlin
package com.jjveleber.videoeditor.testing

import com.jjveleber.videoeditor.media.ProbeResult
import com.jjveleber.videoeditor.media.SourceProber

class FakeProber(private val results: Map<String, ProbeResult>) : SourceProber {
    override suspend fun probe(uri: String): ProbeResult? = results[uri]
    override fun displayName(uri: String): String = uri.substringAfterLast('/')
}
```

`app/src/test/java/com/jjveleber/videoeditor/ui/editor/EditorViewModelTest.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.editor

import com.jjveleber.videoeditor.media.ProbeResult
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.testing.FakeGrants
import com.jjveleber.videoeditor.testing.FakeProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: ProjectRepository
    private val grants = FakeGrants()
    private val good = ProbeResult(4_000_000L, 1920, 1080, true)
    private val prober = FakeProber(
        mapOf(
            "content://a.mp4" to good,
            "content://b.mp4" to good,
            "content://tiny.mp4" to ProbeResult(50_000L, 1920, 1080, true),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = ProjectRepository(tmp.newFolder("projects"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVm(project: Project = repo.create("P")): EditorViewModel {
        val vm = EditorViewModel(project.id, repo, prober, grants, io = dispatcher)
        runCurrent()
        return vm
    }

    private fun TestScope.vmWith(vararg uris: String): EditorViewModel {
        val vm = newVm()
        vm.addClips(uris.toList())
        runCurrent()
        return vm
    }

    @Test
    fun addClipsAppendsTakesGrantsAndAutosaves() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4", "content://b.mp4")
        val project = vm.state.value.project!!
        assertEquals(listOf("content://a.mp4", "content://b.mp4"), project.segments.map { it.sourceUri })
        assertEquals(setOf("content://a.mp4", "content://b.mp4"), grants.taken)
        assertTrue(vm.state.value.canUndo)
        assertEquals(0, repo.load(project.id)!!.segments.size)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(2, repo.load(project.id)!!.segments.size)
    }

    @Test
    fun unusableClipReportsMessageAndAddsTheRest() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4", "content://bad.mp4")
        assertEquals(1, vm.state.value.project!!.segments.size)
        assertEquals("Can't use bad.mp4", vm.state.value.message)
        vm.consumeMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun clipShorterThanMinimumIsUnusable() = runTest(dispatcher) {
        val vm = vmWith("content://tiny.mp4")
        assertEquals(0, vm.state.value.project!!.segments.size)
        assertEquals("Can't use tiny.mp4", vm.state.value.message)
    }

    @Test
    fun refusedGrantWarnsButStillAdds() = runTest(dispatcher) {
        grants.allowTake = false
        val vm = vmWith("content://a.mp4")
        assertEquals(1, vm.state.value.project!!.segments.size)
        assertEquals("a.mp4 may be unavailable after restart", vm.state.value.message)
    }

    @Test
    fun undoAndRedoRestoreProjects() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        vm.addClips(listOf("content://b.mp4"))
        runCurrent()
        vm.undo()
        assertEquals(1, vm.state.value.project!!.segments.size)
        vm.undo()
        assertEquals(0, vm.state.value.project!!.segments.size)
        assertFalse(vm.state.value.canUndo)
        vm.redo()
        assertEquals(1, vm.state.value.project!!.segments.size)
        assertTrue(vm.state.value.canRedo)
    }

    @Test
    fun trimDragIsOneUndoStep() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        val id = vm.state.value.project!!.segments[0].id
        vm.trimStartPreview(id, 500_000L)
        vm.trimStartPreview(id, 1_000_000L)
        assertTrue(vm.state.value.isDragging)
        assertEquals(1_000_000L, vm.state.value.project!!.segments[0].startUs)
        vm.endTrim()
        assertFalse(vm.state.value.isDragging)
        vm.undo()
        val segments = vm.state.value.project!!.segments
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startUs)
    }

    @Test
    fun trimEndDragUpdatesEnd() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        val id = vm.state.value.project!!.segments[0].id
        vm.trimEndPreview(id, 3_000_000L)
        vm.endTrim()
        assertEquals(3_000_000L, vm.state.value.project!!.segments[0].endUs)
    }

    @Test
    fun splitAtPlayhead() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        vm.splitAt(2_000_000L)
        assertEquals(2, vm.state.value.project!!.segments.size)
    }

    @Test
    fun deleteSelectedRemovesAndClearsSelection() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4", "content://b.mp4")
        vm.select(vm.state.value.project!!.segments[0].id)
        vm.deleteSelected()
        assertEquals(listOf("content://b.mp4"), vm.state.value.project!!.segments.map { it.sourceUri })
        assertNull(vm.state.value.selectedId)
    }

    @Test
    fun deletingSegmentDoesNotReleaseGrant() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        vm.select(vm.state.value.project!!.segments[0].id)
        vm.deleteSelected()
        advanceTimeBy(600)
        runCurrent()
        assertTrue(grants.released.isEmpty())
        vm.undo()
        assertEquals(1, vm.state.value.project!!.segments.size)
    }

    @Test
    fun missingSourceOnLoadBlocksExport() = runTest(dispatcher) {
        val gone = Segment("s1", "content://gone.mp4", 4_000_000L, 0L, 4_000_000L, true, 1920, 1080)
        val saved = repo.save(repo.create("P").copy(segments = listOf(gone)))
        val vm = newVm(saved)
        assertTrue(vm.state.value.hasMissing)
        assertFalse(vm.state.value.canExport)
        vm.select("s1")
        vm.deleteSelected()
        assertFalse(vm.state.value.hasMissing)
        vm.addClips(listOf("content://a.mp4"))
        runCurrent()
        assertTrue(vm.state.value.canExport)
    }

    @Test
    fun flushSavesImmediately() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        val id = vm.state.value.project!!.id
        vm.flush()
        assertEquals(1, repo.load(id)!!.segments.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.ui.editor.EditorViewModelTest'`
Expected: FAIL, `Unresolved reference: EditorViewModel`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/jjveleber/videoeditor/ui/editor/EditorViewModel.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjveleber.videoeditor.history.UndoStack
import com.jjveleber.videoeditor.media.SourceProber
import com.jjveleber.videoeditor.media.toSegment
import com.jjveleber.videoeditor.model.MIN_SEGMENT_US
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.model.append
import com.jjveleber.videoeditor.model.delete
import com.jjveleber.videoeditor.model.move
import com.jjveleber.videoeditor.model.splitAt
import com.jjveleber.videoeditor.model.trimEnd
import com.jjveleber.videoeditor.model.trimStart
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.storage.UriGrants
import com.jjveleber.videoeditor.storage.releaseUnreferencedGrants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val project: Project? = null,
    val selectedId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val missingUris: Set<String> = emptySet(),
    val isDragging: Boolean = false,
    val message: String? = null,
) {
    val hasMissing: Boolean get() = project?.segments?.any { it.sourceUri in missingUris } == true
    val canExport: Boolean get() = project != null && project.segments.isNotEmpty() && !hasMissing
}

private const val SAVE_DEBOUNCE_MS = 500L

class EditorViewModel(
    private val projectId: String,
    private val repo: ProjectRepository,
    private val prober: SourceProber,
    private val grants: UriGrants,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var history: UndoStack<Project>? = null
    private var dragBase: Project? = null
    private var saveJob: Job? = null
    private var pendingSave: Project? = null

    init {
        viewModelScope.launch {
            val project = withContext(io) { repo.load(projectId) } ?: return@launch
            history = UndoStack(project)
            val missing = project.segments.map { it.sourceUri }.distinct()
                .filter { prober.probe(it) == null }
                .toSet()
            _state.update { it.copy(project = project, missingUris = missing) }
        }
    }

    fun addClips(uris: List<String>) {
        viewModelScope.launch {
            val added = mutableListOf<Segment>()
            val unusable = mutableListOf<String>()
            val notPersisted = mutableListOf<String>()
            for (uri in uris) {
                val result = prober.probe(uri)
                if (result == null || result.durationUs < MIN_SEGMENT_US) {
                    unusable += withContext(io) { prober.displayName(uri) }
                    continue
                }
                if (!grants.take(uri)) notPersisted += withContext(io) { prober.displayName(uri) }
                added += result.toSegment(uri)
            }
            if (added.isNotEmpty()) edit { it.append(added) }
            val messages = buildList {
                if (unusable.isNotEmpty()) add("Can't use ${unusable.joinToString()}")
                if (notPersisted.isNotEmpty()) add("${notPersisted.joinToString()} may be unavailable after restart")
            }
            if (messages.isNotEmpty()) _state.update { it.copy(message = messages.joinToString("\n")) }
        }
    }

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }

    /** Live trim while dragging. Not recorded in history until [endTrim]. */
    fun trimStartPreview(segmentId: String, startUs: Long) = preview { it.trimStart(segmentId, startUs) }

    fun trimEndPreview(segmentId: String, endUs: Long) = preview { it.trimEnd(segmentId, endUs) }

    fun endTrim() {
        val base = dragBase ?: return
        dragBase = null
        _state.update { it.copy(isDragging = false) }
        val result = _state.value.project ?: return
        if (result != base) commit(result)
    }

    fun splitAt(globalUs: Long) = edit { it.splitAt(globalUs) }

    fun deleteSelected() {
        val id = _state.value.selectedId ?: return
        edit { it.delete(id) }
    }

    fun move(from: Int, to: Int) = edit { it.move(from, to) }

    fun undo() {
        history?.undo()?.let { publish(it); scheduleSave(it) }
    }

    fun redo() {
        history?.redo()?.let { publish(it); scheduleSave(it) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Writes any pending autosave now. Call when the screen stops. Blocking (small file write). */
    fun flush() {
        val pending = pendingSave ?: return
        saveJob?.cancel()
        pendingSave = null
        repo.save(pending)
    }

    override fun onCleared() {
        flush()
        // Safe to release now: the undo history (which could restore a deleted clip) dies with this screen.
        releaseUnreferencedGrants(repo.list(), grants)
    }

    private fun preview(op: (Project) -> Project) {
        val base = dragBase ?: history?.current ?: return
        dragBase = base
        _state.update { it.copy(project = op(base), isDragging = true) }
    }

    private fun edit(op: (Project) -> Project) {
        val current = history?.current ?: return
        val next = op(current)
        if (next != current) commit(next)
    }

    private fun commit(next: Project) {
        history?.push(next) ?: return
        publish(next)
        scheduleSave(next)
    }

    private fun publish(project: Project) {
        val h = history ?: return
        _state.update { s ->
            s.copy(
                project = project,
                canUndo = h.canUndo,
                canRedo = h.canRedo,
                selectedId = s.selectedId?.takeIf { id -> project.segments.any { it.id == id } },
            )
        }
    }

    private fun scheduleSave(project: Project) {
        pendingSave = project
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val toSave = pendingSave ?: return@launch
            withContext(io) { repo.save(toSave) }
            if (pendingSave === toSave) pendingSave = null
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.ui.editor.EditorViewModelTest'`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/ui/editor app/src/test/java/com/jjveleber/videoeditor/ui/editor app/src/test/java/com/jjveleber/videoeditor/testing/FakeProber.kt
git commit -m "feat(editor): add editor view model with undo and autosave

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Home view model and duration formatting

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/ui/home/HomeViewModel.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/ui/Format.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/ui/home/HomeViewModelTest.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/ui/FormatTest.kt`

**Interfaces:**
- Consumes: `ProjectRepository`, `UriGrants`, `releaseUnreferencedGrants`, `FakeGrants` (Task 5); `totalDurationUs` (Task 2)
- Produces:
  - `data class ProjectSummary(id: String, name: String, segmentCount: Int, totalDurationUs: Long, updatedAt: Long)`
  - `class HomeViewModel(repo: ProjectRepository, grants: UriGrants, io: CoroutineDispatcher = Dispatchers.IO) : ViewModel` with `val projects: StateFlow<List<ProjectSummary>>`, `fun refresh()`, `fun create(onCreated: (String) -> Unit)`, `fun rename(id: String, name: String)` (blank names ignored), `fun delete(id: String)`
  - `fun formatDuration(us: Long): String` (`m:ss` or `h:mm:ss`)

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/com/jjveleber/videoeditor/ui/FormatTest.kt`:

```kotlin
package com.jjveleber.videoeditor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun formatsMinutesAndHours() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("1:05", formatDuration(65_000_000L))
        assertEquals("1:02:05", formatDuration(3_725_000_000L))
    }
}
```

`app/src/test/java/com/jjveleber/videoeditor/ui/home/HomeViewModelTest.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.home

import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.testing.FakeGrants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: ProjectRepository
    private val grants = FakeGrants()

    private fun seg(uri: String) = Segment(uri, uri, 2_000_000L, 0L, 2_000_000L, true, 1920, 1080)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = ProjectRepository(tmp.newFolder("projects"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun createAddsProjectAndReturnsId() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, grants, io = dispatcher)
        var opened: String? = null
        vm.create { opened = it }
        runCurrent()
        assertEquals(1, vm.projects.value.size)
        assertEquals("Project 1", vm.projects.value[0].name)
        assertEquals(vm.projects.value[0].id, opened)
    }

    @Test
    fun summaryShowsCountAndDuration() = runTest(dispatcher) {
        repo.save(repo.create("A").copy(segments = listOf(seg("u1"), seg("u2"))))
        val vm = HomeViewModel(repo, grants, io = dispatcher)
        vm.refresh()
        runCurrent()
        val s = vm.projects.value.single()
        assertEquals(2, s.segmentCount)
        assertEquals(4_000_000L, s.totalDurationUs)
    }

    @Test
    fun renameUpdatesListAndIgnoresBlank() = runTest(dispatcher) {
        val id = repo.create("A").id
        val vm = HomeViewModel(repo, grants, io = dispatcher)
        vm.rename(id, "Trip")
        runCurrent()
        assertEquals("Trip", vm.projects.value.single().name)
        vm.rename(id, "   ")
        runCurrent()
        assertEquals("Trip", vm.projects.value.single().name)
    }

    @Test
    fun deleteReleasesOnlyUnreferencedGrants() = runTest(dispatcher) {
        grants.take("u1")
        grants.take("u2")
        repo.save(repo.create("P1").copy(segments = listOf(seg("u1"))))
        val p2 = repo.save(repo.create("P2").copy(segments = listOf(seg("u1"), seg("u2"))))
        val vm = HomeViewModel(repo, grants, io = dispatcher)
        vm.delete(p2.id)
        runCurrent()
        assertEquals(listOf("u2"), grants.released)
        assertEquals(listOf("P1"), vm.projects.value.map { it.name })
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.ui.FormatTest' --tests 'com.jjveleber.videoeditor.ui.home.HomeViewModelTest'`
Expected: FAIL, `Unresolved reference: formatDuration` / `HomeViewModel`.

- [ ] **Step 3: Implement**

`app/src/main/java/com/jjveleber/videoeditor/ui/Format.kt`:

```kotlin
package com.jjveleber.videoeditor.ui

fun formatDuration(us: Long): String {
    val total = us / 1_000_000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
```

`app/src/main/java/com/jjveleber/videoeditor/ui/home/HomeViewModel.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.totalDurationUs
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.storage.UriGrants
import com.jjveleber.videoeditor.storage.releaseUnreferencedGrants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProjectSummary(
    val id: String,
    val name: String,
    val segmentCount: Int,
    val totalDurationUs: Long,
    val updatedAt: Long,
)

private fun Project.toSummary() = ProjectSummary(id, name, segments.size, totalDurationUs, updatedAt)

class HomeViewModel(
    private val repo: ProjectRepository,
    private val grants: UriGrants,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _projects.value = withContext(io) { repo.list().map { it.toSummary() } }
        }
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val project = withContext(io) { repo.create("Project ${_projects.value.size + 1}") }
            _projects.value = withContext(io) { repo.list().map { it.toSummary() } }
            onCreated(project.id)
        }
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            withContext(io) { repo.rename(id, name.trim()) }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(io) {
                repo.delete(id)
                releaseUnreferencedGrants(repo.list(), grants)
            }
            refresh()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.ui.FormatTest' --tests 'com.jjveleber.videoeditor.ui.home.HomeViewModelTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/ui app/src/test/java/com/jjveleber/videoeditor/ui
git commit -m "feat(home): add home view model and duration formatting

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Timeline geometry and timeline strip

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/ui/timeline/TimelineGeometry.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/ui/timeline/TimelineStrip.kt`
- Test: `app/src/test/java/com/jjveleber/videoeditor/ui/timeline/TimelineGeometryTest.kt`

**Interfaces:**
- Consumes: `Project`, `Segment` (Task 2); `ThumbnailLoader` (Task 6)
- Produces:
  - `class TimelineGeometry(durationsUs: List<Long>, pxPerSecond: Float, minBlockPx: Float)` with `val widths: List<Float>`, `val starts: List<Float>`, `val totalWidth: Float`, `fun pxToUs(px: Float): Long`, `fun xToGlobalUs(x: Float): Long`, `fun globalUsToX(us: Long): Float`
  - `fun reorderTargetIndex(widths: List<Float>, from: Int, dragX: Float): Int`
  - `@Composable fun TimelineStrip(project: Project, missingUris: Set<String>, selectedId: String?, playheadUs: Long, isPlaying: Boolean, thumbnails: ThumbnailLoader, onSeek: (Long) -> Unit, onSelect: (String) -> Unit, onTrimStart: (String, Long) -> Unit, onTrimEnd: (String, Long) -> Unit, onTrimDone: () -> Unit, onMove: (Int, Int) -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/jjveleber/videoeditor/ui/timeline/TimelineGeometryTest.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineGeometryTest {
    // 1 s block is below the 24 px minimum (20 px/s), 2 s block is 40 px.
    private val g = TimelineGeometry(listOf(1_000_000L, 2_000_000L), pxPerSecond = 20f, minBlockPx = 24f)

    @Test
    fun widthsRespectMinimum() {
        assertEquals(listOf(24f, 40f), g.widths)
        assertEquals(listOf(0f, 24f), g.starts)
        assertEquals(64f, g.totalWidth, 0.001f)
    }

    @Test
    fun xToGlobalUsInterpolatesWithinBlock() {
        assertEquals(500_000L, g.xToGlobalUs(12f))
        assertEquals(2_000_000L, g.xToGlobalUs(44f))
        assertEquals(0L, g.xToGlobalUs(-10f))
        assertEquals(3_000_000L, g.xToGlobalUs(500f))
    }

    @Test
    fun globalUsToXIsInverse() {
        assertEquals(0f, g.globalUsToX(0L), 0.001f)
        assertEquals(12f, g.globalUsToX(500_000L), 0.001f)
        assertEquals(44f, g.globalUsToX(2_000_000L), 0.001f)
        assertEquals(64f, g.globalUsToX(9_000_000L), 0.001f)
    }

    @Test
    fun pxToUsUsesNominalScale() {
        assertEquals(500_000L, g.pxToUs(10f))
        assertEquals(-500_000L, g.pxToUs(-10f))
    }

    @Test
    fun emptyTimeline() {
        val empty = TimelineGeometry(emptyList(), 20f, 24f)
        assertEquals(0f, empty.totalWidth, 0.001f)
        assertEquals(0L, empty.xToGlobalUs(10f))
        assertEquals(0f, empty.globalUsToX(10L), 0.001f)
    }

    @Test
    fun reorderTargetFollowsDraggedCenter() {
        val widths = listOf(10f, 10f, 10f)
        assertEquals(1, reorderTargetIndex(widths, from = 0, dragX = 12f))
        assertEquals(2, reorderTargetIndex(widths, from = 0, dragX = 25f))
        assertEquals(0, reorderTargetIndex(widths, from = 2, dragX = -25f))
        assertEquals(1, reorderTargetIndex(widths, from = 1, dragX = 0f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.ui.timeline.TimelineGeometryTest'`
Expected: FAIL, `Unresolved reference: TimelineGeometry`.

- [ ] **Step 3: Implement geometry**

`app/src/main/java/com/jjveleber/videoeditor/ui/timeline/TimelineGeometry.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.timeline

/**
 * Lays segments out left to right at [pxPerSecond], with each block at least [minBlockPx] wide.
 * Because of the minimum, x and time are mapped per block, not with one global scale.
 */
class TimelineGeometry(
    private val durationsUs: List<Long>,
    private val pxPerSecond: Float,
    minBlockPx: Float,
) {
    val widths: List<Float> = durationsUs.map { maxOf(it / 1_000_000f * pxPerSecond, minBlockPx) }
    val starts: List<Float> = widths.runningFold(0f) { acc, w -> acc + w }.dropLast(1)
    val totalWidth: Float = widths.sum()
    private val startsUs: List<Long> = durationsUs.runningFold(0L) { acc, d -> acc + d }.dropLast(1)

    /** Converts a drag distance to a time delta at the nominal scale. */
    fun pxToUs(px: Float): Long = (px.toDouble() / pxPerSecond * 1_000_000.0).toLong()

    fun xToGlobalUs(x: Float): Long {
        if (widths.isEmpty()) return 0L
        val i = starts.indexOfLast { it <= x }.coerceAtLeast(0)
        val frac = ((x - starts[i]) / widths[i]).coerceIn(0f, 1f)
        return startsUs[i] + (frac.toDouble() * durationsUs[i]).toLong()
    }

    fun globalUsToX(us: Long): Float {
        if (widths.isEmpty()) return 0f
        val i = startsUs.indexOfLast { it <= us }.coerceAtLeast(0)
        val frac = ((us - startsUs[i]).toDouble() / durationsUs[i]).coerceIn(0.0, 1.0)
        return starts[i] + (frac * widths[i]).toFloat()
    }
}

/** Index the dragged block should move to: the number of other blocks whose center is left of its center. */
fun reorderTargetIndex(widths: List<Float>, from: Int, dragX: Float): Int {
    var x = 0f
    val centers = widths.map { w -> (x + w / 2).also { x += w } }
    val dragged = centers[from] + dragX
    return centers.indices.count { it != from && centers[it] < dragged }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.jjveleber.videoeditor.ui.timeline.TimelineGeometryTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Implement the strip composable**

`app/src/main/java/com/jjveleber/videoeditor/ui/timeline/TimelineStrip.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.timeline

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jjveleber.videoeditor.media.ThumbnailLoader
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import kotlin.math.roundToInt

private val PX_PER_SECOND = 20.dp
private val MIN_BLOCK = 24.dp
private val HANDLE = 16.dp
private val STRIP_HEIGHT = 72.dp

@Composable
fun TimelineStrip(
    project: Project,
    missingUris: Set<String>,
    selectedId: String?,
    playheadUs: Long,
    isPlaying: Boolean,
    thumbnails: ThumbnailLoader,
    onSeek: (Long) -> Unit,
    onSelect: (String) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onTrimDone: () -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerSecond = with(density) { PX_PER_SECOND.toPx() }
    val minBlockPx = with(density) { MIN_BLOCK.toPx() }
    val geometry = remember(project.segments, pxPerSecond, minBlockPx) {
        TimelineGeometry(project.segments.map { it.durationUs }, pxPerSecond, minBlockPx)
    }
    val currentGeometry by rememberUpdatedState(geometry)
    val scroll = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val playheadX = geometry.globalUsToX(playheadUs)

    // Keep the playhead on screen during playback.
    LaunchedEffect(playheadX, isPlaying) {
        if (isPlaying && viewportPx > 0 && (playheadX < scroll.value || playheadX > scroll.value + viewportPx)) {
            scroll.scrollTo((playheadX - viewportPx * 0.2f).roundToInt().coerceAtLeast(0))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .onSizeChanged { viewportPx = it.width }
            .horizontalScroll(scroll),
    ) {
        Box(
            Modifier
                .width(with(density) { geometry.totalWidth.toDp() })
                .fillMaxHeight()
                .pointerInput(Unit) { detectTapGestures(onTap = { onSeek(currentGeometry.xToGlobalUs(it.x)) }) },
        ) {
            project.segments.forEachIndexed { index, segment ->
                SegmentBlock(
                    segment = segment,
                    index = index,
                    geometry = geometry,
                    selected = segment.id == selectedId,
                    missing = segment.sourceUri in missingUris,
                    thumbnails = thumbnails,
                    onSeek = onSeek,
                    onSelect = onSelect,
                    onTrimStart = onTrimStart,
                    onTrimEnd = onTrimEnd,
                    onTrimDone = onTrimDone,
                    onMove = onMove,
                )
            }
            Box(
                Modifier
                    .offset { IntOffset(playheadX.roundToInt(), 0) }
                    .zIndex(2f)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun SegmentBlock(
    segment: Segment,
    index: Int,
    geometry: TimelineGeometry,
    selected: Boolean,
    missing: Boolean,
    thumbnails: ThumbnailLoader,
    onSeek: (Long) -> Unit,
    onSelect: (String) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onTrimDone: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(segment)
    val currentIndex by rememberUpdatedState(index)
    val geo by rememberUpdatedState(geometry)
    var dragX by remember { mutableFloatStateOf(0f) }
    val left = geometry.starts[index]
    val width = geometry.widths[index]
    val shape = RoundedCornerShape(4.dp)
    val heightPx = with(density) { STRIP_HEIGHT.roundToPx() }

    // Re-fetch the thumbnail only when the start moves to a different second, not on every drag frame.
    val thumbnail by produceState<Bitmap?>(null, segment.sourceUri, segment.startUs / 1_000_000, missing) {
        value = if (missing) null else thumbnails.load(segment.sourceUri, segment.startUs, heightPx)
    }

    Box(
        Modifier
            .offset { IntOffset((left + dragX).roundToInt(), 0) }
            .zIndex(if (dragX != 0f) 1f else 0f)
            .width(with(density) { width.toDp() })
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(shape)
            .background(if (missing) Color(0xFFB00020) else Color.DarkGray)
            .border(if (selected) 2.dp else 0.dp, Color.Yellow, shape)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    onSelect(current.id)
                    onSeek(geo.xToGlobalUs(geo.starts[currentIndex] + offset.x))
                })
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = {
                        val to = reorderTargetIndex(geo.widths, currentIndex, dragX)
                        dragX = 0f
                        onMove(currentIndex, to)
                    },
                    onDragCancel = { dragX = 0f },
                ) { change, amount ->
                    change.consume()
                    dragX += amount.x
                }
            },
    ) {
        thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(HANDLE)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    var startAt = 0L
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { startAt = current.startUs; total = 0f },
                        onDragEnd = onTrimDone,
                        onDragCancel = onTrimDone,
                    ) { change, dx ->
                        change.consume()
                        total += dx
                        onTrimStart(current.id, startAt + geo.pxToUs(total))
                    }
                },
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(HANDLE)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    var endAt = 0L
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { endAt = current.endUs; total = 0f },
                        onDragEnd = onTrimDone,
                        onDragCancel = onTrimDone,
                    ) { change, dx ->
                        change.consume()
                        total += dx
                        onTrimEnd(current.id, endAt + geo.pxToUs(total))
                    }
                },
        )
    }
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all unit tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/jjveleber/videoeditor/ui/timeline app/src/test/java/com/jjveleber/videoeditor/ui/timeline
git commit -m "feat(timeline): add scrolling timeline with trim handles and reorder

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Screens, navigation, and app wiring

**Files:**
- Create: `app/src/main/java/com/jjveleber/videoeditor/AppContainer.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/VideoEditorApp.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/ui/home/HomeScreen.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/ui/editor/EditorScreen.kt`
- Create: `app/src/main/java/com/jjveleber/videoeditor/ui/editor/ExportSheet.kt`
- Modify: `app/src/main/java/com/jjveleber/videoeditor/MainActivity.kt` (replace placeholder)
- Modify: `app/src/main/AndroidManifest.xml` (add `android:name=".VideoEditorApp"` to `<application>`)

**Interfaces:**
- Consumes: everything above. `EditorViewModel` / `EditorUiState` (Task 10), `HomeViewModel` / `ProjectSummary` (Task 11), `TimelineStrip` (Task 12), `ProjectPlayer` (Task 9), `ExportController`, `ExportState`, `ExportService.start` (Task 8), `planComposition`, `Quality` (Task 7), `ThumbnailLoader`, `AndroidSourceProber` (Task 6), `ProjectRepository`, `AndroidUriGrants` (Task 5), `canSplitAt` (Task 3), `formatDuration` (Task 11)
- Produces: the runnable app.

- [ ] **Step 1: App container and application class**

`app/src/main/java/com/jjveleber/videoeditor/AppContainer.kt`:

```kotlin
package com.jjveleber.videoeditor

import android.content.Context
import com.jjveleber.videoeditor.media.AndroidSourceProber
import com.jjveleber.videoeditor.media.SourceProber
import com.jjveleber.videoeditor.media.ThumbnailLoader
import com.jjveleber.videoeditor.storage.AndroidUriGrants
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.storage.UriGrants
import java.io.File

class AppContainer(context: Context) {
    val repository = ProjectRepository(File(context.filesDir, "projects"))
    val grants: UriGrants = AndroidUriGrants(context.contentResolver)
    val prober: SourceProber = AndroidSourceProber(context)
    val thumbnails = ThumbnailLoader(context)
}
```

`app/src/main/java/com/jjveleber/videoeditor/VideoEditorApp.kt`:

```kotlin
package com.jjveleber.videoeditor

import android.app.Application

class VideoEditorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

In `app/src/main/AndroidManifest.xml`, change `<application` to `<application android:name=".VideoEditorApp"` (keep the other attributes).

- [ ] **Step 2: Home screen**

`app/src/main/java/com/jjveleber/videoeditor/ui/home/HomeScreen.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjveleber.videoeditor.ui.formatDuration
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: HomeViewModel, onOpen: (String) -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ProjectSummary?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.create(onOpen) }) { Text("New project") }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No projects yet")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(projects, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = {
                            val edited = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(p.updatedAt))
                            Text("${p.segmentCount} clips, ${formatDuration(p.totalDurationUs)}, edited $edited")
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpen(p.id) },
                            onLongClick = { menuFor = p },
                        ),
                    )
                }
            }
        }
    }

    menuFor?.let { p ->
        ProjectMenuDialog(
            project = p,
            onRename = { vm.rename(p.id, it); menuFor = null },
            onDelete = { vm.delete(p.id); menuFor = null },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun ProjectMenuDialog(
    project: ProjectSummary,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(project.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete ${project.name}?") },
            text = { Text("This removes the project. Your original videos are not touched. This can't be undone.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Delete project", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
```

- [ ] **Step 3: Export sheet**

`app/src/main/java/com/jjveleber/videoeditor/ui/editor/ExportSheet.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjveleber.videoeditor.export.ExportController
import com.jjveleber.videoeditor.export.ExportService
import com.jjveleber.videoeditor.export.ExportState
import com.jjveleber.videoeditor.media.Quality
import com.jjveleber.videoeditor.media.planComposition
import com.jjveleber.videoeditor.model.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(project: Project, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exportState by ExportController.state.collectAsStateWithLifecycle()
    var quality by remember { mutableStateOf(Quality.P1080) }
    val close = {
        ExportController.reset()
        onDismiss()
    }
    val start = { ExportService.start(context, planComposition(project, quality)) }

    ModalBottomSheet(onDismissRequest = close) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Export", style = MaterialTheme.typography.titleLarge)
            when (val s = exportState) {
                ExportState.Idle -> {
                    Quality.entries.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().clickable { quality = q },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == quality, onClick = { quality = q })
                            Text(q.label)
                        }
                    }
                    Button(onClick = start) { Text("Start") }
                }
                is ExportState.Running -> {
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${s.percent}%")
                    Text("You can leave the app. Export continues in the background.")
                }
                is ExportState.Done -> {
                    Text("Saved to Movies/VideoEditor/")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.parse(s.uri), "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "No app to play the video", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Open") }
                        TextButton(onClick = close) { Text("Close") }
                    }
                }
                is ExportState.Failed -> {
                    Text("Export failed: ${s.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = start) { Text("Retry") }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Editor screen**

`app/src/main/java/com/jjveleber/videoeditor/ui/editor/EditorScreen.kt`:

```kotlin
package com.jjveleber.videoeditor.ui.editor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.jjveleber.videoeditor.media.ThumbnailLoader
import com.jjveleber.videoeditor.model.canSplitAt
import com.jjveleber.videoeditor.player.ProjectPlayer
import com.jjveleber.videoeditor.ui.timeline.TimelineStrip
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(vm: EditorViewModel, thumbnails: ThumbnailLoader, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val projectPlayer = remember { ProjectPlayer(context) }
    var playheadUs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    val project = state.project

    DisposableEffect(Unit) { onDispose { projectPlayer.release() } }

    // Rebuild the preview after each committed edit (not on every trim-drag frame).
    LaunchedEffect(project?.segments, state.isDragging, state.hasMissing) {
        if (!state.isDragging) projectPlayer.setProject(if (state.hasMissing) null else project)
    }

    LaunchedEffect(Unit) {
        while (true) {
            playheadUs = projectPlayer.currentGlobalUs()
            isPlaying = projectPlayer.player.isPlaying
            delay(50)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        projectPlayer.player.pause()
        vm.flush()
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addClips(uris.map { it.toString() })
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(project?.name.orEmpty(), color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { picker.launch(arrayOf("video/*")) }, enabled = project != null) { Text("Add") }
            TextButton(
                onClick = { vm.splitAt(playheadUs) },
                enabled = project?.canSplitAt(playheadUs) == true && !state.hasMissing,
            ) { Text("Split") }
            TextButton(onClick = vm::deleteSelected, enabled = state.selectedId != null) { Text("Delete") }
            TextButton(onClick = vm::undo, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = vm::redo, enabled = state.canRedo) { Text("Redo") }
            TextButton(onClick = { showExport = true }, enabled = state.canExport) { Text("Export") }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { PlayerView(it).apply { player = projectPlayer.player } },
                modifier = Modifier.fillMaxSize(),
            )
            val notice = when {
                state.hasMissing -> "Some clips are missing (shown in red). Delete them to preview and export."
                project != null && project.segments.isEmpty() -> "Tap Add to add clips"
                else -> null
            }
            notice?.let { Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) }
        }

        if (project != null) {
            TimelineStrip(
                project = project,
                missingUris = state.missingUris,
                selectedId = state.selectedId,
                playheadUs = playheadUs,
                isPlaying = isPlaying,
                thumbnails = thumbnails,
                onSeek = projectPlayer::seekToGlobal,
                onSelect = vm::select,
                onTrimStart = vm::trimStartPreview,
                onTrimEnd = vm::trimEndPreview,
                onTrimDone = vm::endTrim,
                onMove = vm::move,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    if (showExport && project != null) {
        ExportSheet(project, onDismiss = { showExport = false })
    }
}
```

- [ ] **Step 5: Replace MainActivity with navigation**

`app/src/main/java/com/jjveleber/videoeditor/MainActivity.kt`:

```kotlin
package com.jjveleber.videoeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jjveleber.videoeditor.ui.editor.EditorScreen
import com.jjveleber.videoeditor.ui.editor.EditorViewModel
import com.jjveleber.videoeditor.ui.home.HomeScreen
import com.jjveleber.videoeditor.ui.home.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as VideoEditorApp).container
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            val vm: HomeViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer { HomeViewModel(container.repository, container.grants) }
                                },
                            )
                            HomeScreen(vm, onOpen = { id -> nav.navigate("editor/$id") })
                        }
                        composable("editor/{id}") { entry ->
                            val id = entry.arguments?.getString("id")!!
                            val vm: EditorViewModel = viewModel(
                                factory = viewModelFactory {
                                    initializer {
                                        EditorViewModel(id, container.repository, container.prober, container.grants)
                                    }
                                },
                            )
                            EditorScreen(vm, container.thumbnails, onBack = { nav.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Build and run all tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all unit tests pass.

Run: `./gradlew :app:connectedDebugAndroidTest`
Expected: all instrumented tests pass (the ExportServiceTest now launches the real home screen; that is fine).

- [ ] **Step 7: Smoke test on the emulator**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell mkdir -p /sdcard/Movies/TestClips
adb push app/src/androidTest/assets/clip_landscape_audio.mp4 app/src/androidTest/assets/clip_portrait_silent.mp4 /sdcard/Movies/TestClips/
adb shell am start -n com.jjveleber.videoeditor/.MainActivity
```

Drive with `adb shell input tap X Y` and check each state with a screenshot (`adb exec-out screencap -p > "$SCRATCH/shot.png"`, then Read the PNG; use the session scratchpad dir). Get coordinates from `adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`.

Verify, with a screenshot for each:
1. Home shows "No projects yet" and a "New project" button.
2. "New project" opens the editor showing "Tap Add to add clips".
3. Add opens the system picker; pick both clips from Movies/TestClips; two blocks appear on the timeline and the preview plays.
4. Split is enabled mid-clip; tapping it adds a third block. Undo removes it. Redo restores it.
5. Export, 720p, Start: progress then "Saved to Movies/VideoEditor/". Confirm with `adb shell ls /sdcard/Movies/VideoEditor/`.
6. Back, then home lists the project with "3 clips".

Report any step that fails with its screenshot; do not mark the task done until all six pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main
git commit -m "feat(ui): add home and editor screens, export sheet, navigation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Fire Max 11 install and device checklist

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: the built APK
- Produces: README with build/install steps; a completed device checklist reported to the user

- [ ] **Step 1: Write README.md**

```markdown
# Video Editor (Fire Max 11)

On-device video editor for Amazon Fire tablets (Fire OS 8 / Android 11+). Trim, split, delete sections, reorder, and merge clips, with undo. Exports H.264/AAC MP4 to `Movies/VideoEditor/`.

## Build

    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    ./gradlew :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
    ./gradlew :app:testDebugUnitTest    # JVM tests
    ./gradlew :app:connectedDebugAndroidTest   # needs an emulator or device on adb

## Install on the Fire tablet

1. On the tablet: Settings > Device Options > About Fire Tablet, tap Serial Number 7 times. Then Settings > Device Options > Developer Options > enable "USB debugging" (ADB).
2. Connect the tablet to the Windows PC by USB and accept the debugging prompt.
3. WSL cannot see USB devices directly, so use the Windows adb:

       cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/jjvel/Downloads/video-editor.apk
       /mnt/c/Users/jjvel/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r 'C:\Users\jjvel\Downloads\video-editor.apk'

   Alternative without adb: copy the APK to the tablet, open it in Files, and allow installing from unknown sources.
```

- [ ] **Step 2: Build the APK and stage it for the user**

```bash
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/jjvel/Downloads/video-editor.apk
ls -la /mnt/c/Users/jjvel/Downloads/video-editor.apk
```

- [ ] **Step 3: Ask the user to install and run the device checklist**

The device is the user's; hand them this checklist and wait for results:

1. Phone footage, portrait and landscape mixed in one project
2. A screen recording
3. A long clip (10+ minutes): timeline scrolls, trim handles are usable
4. A clip with no audio
5. Trim, split, delete a middle section, reorder, undo and redo across the above
6. Export at 720p, 1080p, and Match first clip; each plays in the Fire's gallery/video app
7. Close the app mid-edit and reopen: project restored with the latest edit
8. Press Home during an export: notification shows progress, export completes

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: add build and Fire tablet install instructions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
