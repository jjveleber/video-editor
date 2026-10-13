package com.jjveleber.videoeditor

import android.app.Application
import com.jjveleber.videoeditor.media.deleteLeftoverExports

class VideoEditorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // No export survives a process restart (ExportService is not sticky), so any temp file here is a leftover.
        deleteLeftoverExports(cacheDir)
    }
}
