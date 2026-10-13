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
