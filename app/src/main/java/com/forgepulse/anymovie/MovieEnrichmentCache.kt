package com.forgepulse.anymovie

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest

/** TTL metadata cache, never stores provider API keys or credentials. */
class MovieEnrichmentCache(context: Context) {
    private val preferences = context.getSharedPreferences("cinema_enrichment_v1", Context.MODE_PRIVATE)
    private val ttlMs = 14L * 24 * 60 * 60 * 1000
    private fun key(title: String) = MessageDigest.getInstance("SHA-256")
        .digest(title.lowercase().trim().toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }

    fun get(title: String): MovieMetadata? = runCatching {
        val value = preferences.getString(key(title), null) ?: return@runCatching null
        val json = JSONObject(value)
        if (System.currentTimeMillis() - json.optLong("cachedAt") > ttlMs) return@runCatching null
        MovieMetadata(
            title = json.optString("title"),
            originalTitle = json.optString("originalTitle").ifBlank { null },
            overview = json.optString("overview").ifBlank { null },
            releaseDate = json.optString("releaseDate").ifBlank { null },
            year = json.optInt("year").takeIf { it > 0 },
            runtime = json.optInt("runtime").takeIf { it > 0 },
            rating = json.optDouble("rating").takeIf { !it.isNaN() },
            genres = strings(json.optJSONArray("genres")),
            countries = emptyList(), languages = emptyList(),
            poster = json.optString("poster").ifBlank { null },
            backdrop = json.optString("backdrop").ifBlank { null },
            images = emptyList(), directors = emptyList(), writers = emptyList(), cast = emptyList(),
            imdbId = null,
            tmdbId = json.optInt("tmdbId").takeIf { it > 0 },
            wikidataId = null,
            mediaType = json.optString("mediaType", "movie"),
        )
    }.getOrNull()

    fun put(searchTitle: String, info: MovieMetadata) {
        val json = JSONObject()
            .put("cachedAt", System.currentTimeMillis())
            .put("title", info.title)
            .put("originalTitle", info.originalTitle)
            .put("overview", info.overview?.take(3000))
            .put("releaseDate", info.releaseDate)
            .put("year", info.year)
            .put("runtime", info.runtime)
            .put("rating", info.rating)
            .put("poster", info.poster)
            .put("backdrop", info.backdrop)
            .put("tmdbId", info.tmdbId)
            .put("mediaType", info.mediaType)
            .put("genres", JSONArray(info.genres))
        preferences.edit().putString(key(searchTitle), json.toString()).apply()
    }

    private fun strings(array: JSONArray?) = buildList {
        if (array != null) for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(::add)
    }
}
