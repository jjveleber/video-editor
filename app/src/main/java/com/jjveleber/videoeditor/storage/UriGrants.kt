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

/** Releases every persisted grant that no saved project references and that is not in [alsoKeep]. */
fun releaseUnreferencedGrants(projects: List<Project>, grants: UriGrants, alsoKeep: Set<String> = emptySet()) {
    val referenced = projects.flatMap { p -> p.segments.map { it.sourceUri } }.toSet() + alsoKeep
    (grants.persisted() - referenced).forEach(grants::release)
}
