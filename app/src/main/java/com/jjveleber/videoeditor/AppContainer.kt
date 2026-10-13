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
