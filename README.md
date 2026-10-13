# Video Editor (Fire Max 11)

On-device video editor for Amazon Fire tablets (Fire OS 8 / Android 11+). Trim, split, delete sections, reorder, and merge clips, with undo. Exports H.264/AAC MP4 to `Movies/VideoEditor/`.

## Build

    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
    ./gradlew :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
    ./gradlew :app:testDebugUnitTest    # JVM tests
    ./gradlew :app:connectedDebugAndroidTest   # needs an emulator or device on adb

## Install on the Fire tablet

There are two ways to get the app onto the tablet. Route A needs only the tablet. Route B uses a PC and a USB cable.

### Route A: download on the tablet (no PC needed)

1. On the Fire Max 11, open the **Silk Browser**.
2. Go to the latest release page: https://github.com/jjveleber/video-editor/releases/latest
3. Under **Assets**, tap the file named `video-editor-vX.Y.Z.apk` (for example `video-editor-v0.1.0.apk`). If Silk warns that the file might be harmful, tap **Download anyway**.
4. When the download finishes, tap **Open** in the download bar. You can also open it later from the **Files** app, in the **Downloads** folder.
5. The first time, Fire OS blocks installs from Silk. Allow it, using either of these:
   - Tap **Settings** on the prompt that appears, turn on **Allow from this source**, then press Back.
   - Or go to **Settings > Security & Privacy > Apps from Unknown Sources**, tap **Silk Browser**, and turn on **Allow from this source**.
6. Tap **Install**, wait for it to finish, then tap **Open** (or **Done**).
7. From then on, find **Video Editor** in the app grid on the home screen (swipe up or open the **Library** if it is not on the first page).

Optional: once installed, you can go back to **Apps from Unknown Sources** and turn Silk off again. The app stays installed.

### Route B: install from this WSL2 PC with adb

1. On the tablet: **Settings > Device Options > About Fire Tablet**, tap **Serial Number** 7 times. This unlocks Developer Options.
2. Go to **Settings > Device Options > Developer Options** and turn on **USB debugging** (ADB).
3. Connect the tablet to the Windows PC by USB and tap **Allow** on the debugging prompt on the tablet.
4. WSL cannot see USB devices directly, so use the Windows adb. From the repo folder in WSL:

       cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/jjvel/Downloads/video-editor.apk
       /mnt/c/Users/jjvel/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r 'C:\Users\jjvel\Downloads\video-editor.apk'

5. When it prints `Success`, find **Video Editor** in the app grid on the tablet.

## First run

1. Open **Video Editor** and tap **New project**.
2. Tap **Add** and pick one or more videos. Add again any time to append more clips.
3. Edit on the timeline at the bottom: drag a clip's edge handles to trim it, tap **Split** to cut at the playhead, and select a clip and tap **Delete** to remove it. **Undo** and **Redo** step through your changes.
4. To reorder clips, tap **Arrange**. Every clip shows as a same-size card with its position and length, so long clips are as easy to move as short ones. Drag a card to a new spot, or tap a card to select it and use **Move left** or **Move right**. **Undo** works here too. Tap **Done** to go back to the editor.
5. Tap **Export**, then **Start**. You can leave the app while it works: a notification shows "Exporting video" with progress, then "Export complete" when done (or "Export failed" with the reason).
6. Exported videos are saved to **Movies/VideoEditor/** on the tablet. Tap **Open** in the export sheet, or find them in the Photos or Files app.

Projects are saved automatically and listed on the home screen.

## Updating to a newer version

Download or copy the newer APK and install it over the top, the same way as the first install (Route A steps 2 to 6, or Route B step 4). Do not uninstall first. Your projects are kept.

## Uninstalling

Press and hold **Video Editor** in the app grid and tap **Uninstall** (or go to **Settings > Apps & Notifications > Manage All Applications > Video Editor > Uninstall**). This deletes your projects. Videos you already exported stay in **Movies/VideoEditor/**.
