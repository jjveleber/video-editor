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
