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
