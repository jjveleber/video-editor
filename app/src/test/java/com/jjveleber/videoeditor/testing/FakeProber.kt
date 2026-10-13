package com.jjveleber.videoeditor.testing

import com.jjveleber.videoeditor.media.ProbeResult
import com.jjveleber.videoeditor.media.SourceProber

class FakeProber(private val results: Map<String, ProbeResult>) : SourceProber {
    override suspend fun probe(uri: String): ProbeResult? = results[uri]
    override fun displayName(uri: String): String = uri.substringAfterLast('/')
}
