package com.forgepulse.anymovie

import java.util.concurrent.atomic.AtomicReference

object PlaybackHeaders {
    private val current = AtomicReference<Map<String, String>>(emptyMap())

    fun set(headers: Map<String, String>) {
        current.set(headers.filterKeys { key ->
            key.lowercase() in setOf("accept", "accept-language", "origin", "referer", "range", "user-agent")
        }.filterValues { it.isNotBlank() })
    }

    fun snapshot(): Map<String, String> = current.get()

    fun clear() = current.set(emptyMap())
}
