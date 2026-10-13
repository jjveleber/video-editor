package com.jjveleber.videoeditor.media

import java.io.File

private const val PREFIX = "export-"
private const val SUFFIX = ".mp4"

/** Where an export is encoded before it is copied into Movies/VideoEditor/. */
fun exportTempFile(dir: File): File = File(dir, "$PREFIX${System.currentTimeMillis()}$SUFFIX")

/**
 * Deletes temp files left by exports that never finished (the app was killed mid-export, so
 * [Exporter]'s own cleanup never ran). Only call when no export can be running, such as at process start.
 */
fun deleteLeftoverExports(dir: File) {
    dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }?.forEach { it.delete() }
}
