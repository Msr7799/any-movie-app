package com.forgepulse.anymovie

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("any_movie_state_v2", Context.MODE_PRIVATE)
    private val lock = Any()
    @Volatile private var searchHistoryCache: List<SavedSearch>? = null
    @Volatile private var playbackHistoryCache: List<LibraryItem>? = null

    fun saveDraft(draft: SearchDraft) {
        prefs.edit().putString(KEY_DRAFT, draftToJson(draft).toString()).apply()
    }

    fun loadDraft(): SearchDraft = prefs.getString(KEY_DRAFT, null)
        ?.let { runCatching { draftFromJson(JSONObject(it)) }.getOrNull() }
        ?: SearchDraft()

    fun saveSearch(draft: SearchDraft, response: SearchResponse) = synchronized(lock) {
        val values = loadSearchHistoryLocked().toMutableList()
        values.removeAll {
            it.draft.query.equals(draft.query, ignoreCase = true) &&
                it.draft.movieLanguage == draft.movieLanguage &&
                it.draft.subtitleLanguage == draft.subtitleLanguage &&
                it.draft.allowShortClips == draft.allowShortClips &&
                it.draft.resultLimit == draft.resultLimit
        }
        values.add(0, SavedSearch(draft, response, System.currentTimeMillis()))
        val saved = values.take(MAX_SEARCHES)
        persistSearchHistory(saved)
        searchHistoryCache = saved
        saveDraft(draft)
    }

    fun loadSearchHistory(): List<SavedSearch> = synchronized(lock) { loadSearchHistoryLocked() }

    private fun loadSearchHistoryLocked(): List<SavedSearch> = searchHistoryCache
        ?: parseSearchHistory(prefs.getString(KEY_SEARCH_HISTORY, null)).also { searchHistoryCache = it }

    /** Offline-first catalog snapshot, intentionally excludes provider credentials. */
    fun saveCatalog(items: List<LibraryItem>) = synchronized(lock) {
        val array = JSONArray()
        items.take(250).forEach { array.put(libraryToJson(it)) }
        prefs.edit().putString(KEY_CATALOG, array.toString()).apply()
    }

    fun loadCatalog(): List<LibraryItem> = runCatching {
        prefs.getString(KEY_CATALOG, null)?.let { value ->
            val array = JSONArray(value)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::libraryFromJson) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun savePlayback(item: LibraryItem) = synchronized(lock) {
        val values = loadPlaybackHistoryLocked().toMutableList()
        values.removeAll { it.uri == item.uri }
        values.add(0, item)
        val saved = values.take(MAX_PLAYBACK)
        persistPlaybackHistory(saved)
        playbackHistoryCache = saved
    }

    fun loadPlaybackHistory(): List<LibraryItem> = synchronized(lock) { loadPlaybackHistoryLocked() }

    private fun loadPlaybackHistoryLocked(): List<LibraryItem> = playbackHistoryCache
        ?: parsePlaybackHistory(prefs.getString(KEY_PLAYBACK_HISTORY, null)).also { playbackHistoryCache = it }

    fun setResultsCollapsed(collapsed: Boolean) {
        prefs.edit().putBoolean(KEY_RESULTS_COLLAPSED, collapsed).apply()
    }

    fun resultsCollapsed(): Boolean = prefs.getBoolean(KEY_RESULTS_COLLAPSED, false)

    fun exportCloudState(): CloudStatePayload = CloudStatePayload(
        draftJson = prefs.getString(KEY_DRAFT, null),
        searchHistoryJson = prefs.getString(KEY_SEARCH_HISTORY, null),
        playbackHistoryJson = prefs.getString(KEY_PLAYBACK_HISTORY, null),
        resultsCollapsed = resultsCollapsed(),
    )

    /**
     * Merges cloud history with local state without discarding either side. Search
     * entries are de-duplicated by query+filters and the newest copy wins. Playback
     * entries are de-duplicated by URI with local ordering taking priority.
     */
    fun mergeCloudState(payload: CloudStatePayload): Boolean = synchronized(lock) {
        var changed = false

        val cloudSearches = parseSearchHistory(payload.searchHistoryJson)
        if (cloudSearches.isNotEmpty()) {
            val current = loadSearchHistoryLocked()
            val merged = (current + cloudSearches)
                .groupBy { savedSearchKey(it) }
                .mapNotNull { (_, values) -> values.maxByOrNull { it.savedAt } }
                .sortedByDescending { it.savedAt }
                .take(MAX_SEARCHES)
            if (merged != current) {
                persistSearchHistory(merged)
                searchHistoryCache = merged
                changed = true
            }
        }

        val cloudPlayback = parsePlaybackHistory(payload.playbackHistoryJson)
        if (cloudPlayback.isNotEmpty()) {
            val current = loadPlaybackHistoryLocked()
            val merged = buildList {
                val seen = mutableSetOf<String>()
                (current + cloudPlayback).forEach { item ->
                    if (seen.add(item.uri)) add(item)
                }
            }.take(MAX_PLAYBACK)
            if (merged != current) {
                persistPlaybackHistory(merged)
                playbackHistoryCache = merged
                changed = true
            }
        }

        if (loadDraft().query.isBlank() && !payload.draftJson.isNullOrBlank()) {
            val valid = runCatching { draftFromJson(JSONObject(payload.draftJson)) }.getOrNull()
            if (valid != null) {
                saveDraft(valid)
                changed = true
            }
        }
        changed
    }

    private fun savedSearchKey(item: SavedSearch): String = listOf(
        item.draft.query.lowercase(),
        item.draft.movieLanguage,
        item.draft.subtitleLanguage,
        item.draft.allowShortClips.toString(),
        item.draft.resultLimit.toString(),
    ).joinToString("|")

    private fun parseSearchHistory(raw: String?): List<SavedSearch> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val draft = item.optJSONObject("draft")?.let(::draftFromJson) ?: continue
                    val response = item.optJSONObject("response")?.let(::responseFromJson) ?: continue
                    add(SavedSearch(draft, response, item.optLong("savedAt", 0L)))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parsePlaybackHistory(raw: String?): List<LibraryItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { add(libraryFromJson(it)) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistPlaybackHistory(values: List<LibraryItem>) {
        val array = JSONArray()
        values.take(MAX_PLAYBACK).forEach { array.put(libraryToJson(it)) }
        prefs.edit().putString(KEY_PLAYBACK_HISTORY, array.toString()).apply()
    }

    private fun persistSearchHistory(values: List<SavedSearch>) {
        val array = JSONArray()
        values.forEach { saved ->
            array.put(JSONObject()
                .put("draft", draftToJson(saved.draft))
                .put("response", responseToJson(saved.response))
                .put("savedAt", saved.savedAt))
        }
        prefs.edit().putString(KEY_SEARCH_HISTORY, array.toString()).apply()
    }

    private fun draftToJson(value: SearchDraft) = JSONObject()
        .put("query", value.query)
        .put("movieLanguage", value.movieLanguage)
        .put("subtitleLanguage", value.subtitleLanguage)
        .put("allowShortClips", value.allowShortClips)
        .put("resultLimit", value.resultLimit)
        .put("searchProvider", value.searchProvider)

    private fun draftFromJson(value: JSONObject) = SearchDraft(
        query = value.optString("query"),
        movieLanguage = value.optString("movieLanguage", "any"),
        subtitleLanguage = value.optString("subtitleLanguage", "any"),
        allowShortClips = value.optBoolean("allowShortClips"),
        resultLimit = value.optInt("resultLimit", 10).coerceIn(5, 30),
        searchProvider = value.optString("searchProvider", "tavily").takeIf { it == "serper" } ?: "tavily",
    )

    private fun responseToJson(value: SearchResponse): JSONObject {
        val results = JSONArray()
        value.results.forEach { results.put(resultToJson(it)) }
        return JSONObject()
            .put("understoodTitle", value.understoodTitle)
            .put("summary", value.summary)
            .put("searchProvider", value.searchProvider)
            .put("results", results)
    }

    private fun responseFromJson(value: JSONObject): SearchResponse {
        val array = value.optJSONArray("results")
        val results = buildList {
            if (array != null) for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(resultFromJson(it)) }
            }
        }
        return SearchResponse(
            value.optString("understoodTitle"),
            value.optString("summary"),
            results,
            value.optString("searchProvider", "tavily"),
        )
    }

    private fun resultToJson(value: SearchResult) = JSONObject()
        .put("id", value.id)
        .put("title", value.title)
        .put("provider", value.provider)
        .put("url", value.url)
        .put("description", value.description)
        .put("reason", value.reason)
        .put("contentType", value.contentType)
        .put("playable", value.playable)
        .put("downloadable", value.downloadable)
        .put("hlsMaster", value.hlsMaster)
        .put("hlsVariantCount", value.hlsVariantCount)
        .put("hlsAudioRenditionCount", value.hlsAudioRenditionCount)
        .put("hlsSubtitleRenditionCount", value.hlsSubtitleRenditionCount)
        .put("subtitleLanguages", stringListToJson(value.subtitleLanguages))
        .put("hlsDurationSeconds", value.hlsDurationSeconds)
        .put("hlsLive", value.hlsLive)
        .put("hlsEncrypted", value.hlsEncrypted)
        .put("hlsDrmProtected", value.hlsDrmProtected)
        .put("playbackHeaders", stringMapToJson(value.playbackHeaders))
        .apply {
            value.playUrl?.let { put("playUrl", it) }
            value.hlsUrl?.let { put("hlsUrl", it) }
            value.kind?.let { put("kind", it) }
            value.downloadUrl?.let { put("downloadUrl", it) }
            value.detectedBy?.let { put("detectedBy", it) }
            value.subtitleEvidence?.let { put("subtitleEvidence", it) }
        }

    private fun resultFromJson(value: JSONObject) = SearchResult(
        id = value.optString("id"),
        title = value.optString("title"),
        provider = value.optString("provider"),
        url = value.optString("url"),
        description = value.optString("description"),
        reason = value.optString("reason"),
        contentType = value.optString("contentType", "availability_page"),
        playable = value.optBoolean("playable"),
        playUrl = value.optString("playUrl").takeIf(String::isNotBlank),
        hlsUrl = value.optString("hlsUrl").takeIf(String::isNotBlank),
        kind = value.optString("kind").takeIf(String::isNotBlank),
        downloadable = value.optBoolean("downloadable"),
        downloadUrl = value.optString("downloadUrl").takeIf(String::isNotBlank),
        detectedBy = value.optString("detectedBy").takeIf(String::isNotBlank),
        hlsMaster = value.optBoolean("hlsMaster"),
        hlsVariantCount = value.optInt("hlsVariantCount"),
        hlsAudioRenditionCount = value.optInt("hlsAudioRenditionCount"),
        hlsSubtitleRenditionCount = value.optInt("hlsSubtitleRenditionCount"),
        subtitleLanguages = jsonToStringList(value.optJSONArray("subtitleLanguages")),
        subtitleEvidence = value.optString("subtitleEvidence").takeIf(String::isNotBlank),
        hlsDurationSeconds = value.optInt("hlsDurationSeconds"),
        hlsLive = value.optBoolean("hlsLive"),
        hlsEncrypted = value.optBoolean("hlsEncrypted"),
        hlsDrmProtected = value.optBoolean("hlsDrmProtected"),
        playbackHeaders = jsonToStringMap(value.optJSONObject("playbackHeaders")),
    )

    private fun libraryToJson(value: LibraryItem) = JSONObject()
        .put("title", value.title)
        .put("uri", value.uri)
        .put("kind", value.kind)
        .put("source", value.source)
        .put("contentType", value.contentType)
        .put("downloadable", value.downloadable)
        .put("hlsMaster", value.hlsMaster)
        .put("hlsVariantCount", value.hlsVariantCount)
        .put("hlsAudioRenditionCount", value.hlsAudioRenditionCount)
        .put("hlsSubtitleRenditionCount", value.hlsSubtitleRenditionCount)
        .put("subtitleLanguages", stringListToJson(value.subtitleLanguages))
        .put("hlsDurationSeconds", value.hlsDurationSeconds)
        .put("hlsLive", value.hlsLive)
        .put("hlsEncrypted", value.hlsEncrypted)
        .put("hlsDrmProtected", value.hlsDrmProtected)
        .put("playbackHeaders", stringMapToJson(value.playbackHeaders))
        .put("categories", stringListToJson(value.categories))
        .put("mediaType", value.mediaType)
        .put("status", value.status)
        .apply {
            value.pageUrl?.let { put("pageUrl", it) }
            value.downloadUrl?.let { put("downloadUrl", it) }
            value.detectedBy?.let { put("detectedBy", it) }
            value.subtitleEvidence?.let { put("subtitleEvidence", it) }
            value.catalogId?.let { put("catalogId", it) }
            value.tmdbId?.let { put("tmdbId", it) }
            value.poster?.let { put("poster", it) }
            value.backdrop?.let { put("backdrop", it) }
            value.overview?.let { put("overview", it) }
            value.year?.let { put("year", it) }
            value.rating?.let { put("rating", it) }
        }

    private fun libraryFromJson(value: JSONObject) = LibraryItem(
        title = value.optString("title"),
        uri = value.optString("uri"),
        kind = value.optString("kind", "video"),
        source = value.optString("source"),
        pageUrl = value.optString("pageUrl").takeIf(String::isNotBlank),
        contentType = value.optString("contentType", "full_movie"),
        downloadable = value.optBoolean("downloadable"),
        downloadUrl = value.optString("downloadUrl").takeIf(String::isNotBlank),
        detectedBy = value.optString("detectedBy").takeIf(String::isNotBlank),
        hlsMaster = value.optBoolean("hlsMaster"),
        hlsVariantCount = value.optInt("hlsVariantCount"),
        hlsAudioRenditionCount = value.optInt("hlsAudioRenditionCount"),
        hlsSubtitleRenditionCount = value.optInt("hlsSubtitleRenditionCount"),
        subtitleLanguages = jsonToStringList(value.optJSONArray("subtitleLanguages")),
        subtitleEvidence = value.optString("subtitleEvidence").takeIf(String::isNotBlank),
        hlsDurationSeconds = value.optInt("hlsDurationSeconds"),
        hlsLive = value.optBoolean("hlsLive"),
        hlsEncrypted = value.optBoolean("hlsEncrypted"),
        hlsDrmProtected = value.optBoolean("hlsDrmProtected"),
        playbackHeaders = jsonToStringMap(value.optJSONObject("playbackHeaders")),
        categories = jsonToStringList(value.optJSONArray("categories")),
        catalogId = value.optString("catalogId").takeIf(String::isNotBlank),
        tmdbId = value.optInt("tmdbId").takeIf { it > 0 },
        mediaType = value.optString("mediaType", "movie"),
        poster = value.optString("poster").takeIf(String::isNotBlank),
        backdrop = value.optString("backdrop").takeIf(String::isNotBlank),
        overview = value.optString("overview").takeIf(String::isNotBlank),
        year = value.optInt("year").takeIf { it > 0 },
        rating = value.optDouble("rating").takeIf { !it.isNaN() },
        status = value.optString("status", "published"),
    )

    private fun stringMapToJson(values: Map<String, String>) = JSONObject().apply {
        values.forEach { (key, value) -> if (key.isNotBlank() && value.isNotBlank()) put(key, value) }
    }

    private fun jsonToStringMap(value: JSONObject?): Map<String, String> = buildMap {
        if (value == null) return@buildMap
        val names = value.keys()
        while (names.hasNext()) {
            val name = names.next()
            val item = value.optString(name).trim()
            if (name.isNotBlank() && item.isNotBlank()) put(name, item)
        }
    }

    private fun stringListToJson(values: List<String>) = JSONArray().apply {
        values.distinct().forEach { put(it) }
    }

    private fun jsonToStringList(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty() && value !in this) add(value)
        }
    }

    private companion object {
        const val KEY_CATALOG = "cinema_catalog_cache"
        const val KEY_DRAFT = "search_draft"
        const val KEY_SEARCH_HISTORY = "search_history"
        const val KEY_PLAYBACK_HISTORY = "playback_history"
        const val KEY_RESULTS_COLLAPSED = "results_collapsed"
        const val MAX_SEARCHES = 15
        const val MAX_PLAYBACK = 50
    }
}
