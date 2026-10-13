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
