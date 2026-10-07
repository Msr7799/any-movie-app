package com.forgepulse.anymovie

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Small production HTTP client used by the Android app.
 *
 * The Android APK never talks to TMDB with a secret. TMDB requests are proxied
 * through [BuildConfig.API_BASE_URL]. A compatibility fallback keeps older
 * deployments useful: when /api/v1/tmdb/search is not deployed yet, the client
 * asks /api/v1/movie-metadata for the best matching title instead of presenting
 * a generic "search failed" message.
 */
class ApiClient(private val baseUrl: String = BuildConfig.API_BASE_URL) {
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun health(callback: (Result<ApiServiceStatus>) -> Unit) = execute(callback) {
        val root = requestJson("GET", "/api/v1/health")
        val services = root.optJSONObject("services") ?: JSONObject()
        val control = root.optJSONObject("control") ?: JSONObject()
        val mongo = control.optJSONObject("mongo") ?: JSONObject()
        ApiServiceStatus(
            status = root.optString("status", "unknown"),
            version = root.optString("version", "unknown"),
            tmdbReady = services.optBoolean("tmdb", false),
            searchReady = (services.optBoolean("tavily") || services.optBoolean("serper")) && services.optBoolean("geminiSearch"),
            suggestionsReady = services.optBoolean("geminiSuggestions"),
            storageReady = mongo.optBoolean("connected", false),
            region = root.optString("region").ifBlank { null },
            requestId = root.optString("requestId").ifBlank { null },
        )
    }

    fun search(
        query: String,
        movieLanguage: String,
        subtitleLanguage: String,
        allowShortClips: Boolean,
        resultLimit: Int,
        searchProvider: String,
        callback: (Result<SearchResponse>) -> Unit,
    ) = execute(callback) {
        val modernBody = JSONObject()
            .put("query", query)
            .put("movieLanguage", movieLanguage)
            .put("subtitleLanguage", subtitleLanguage)
            .put("allowShortClips", allowShortClips)
            .put("resultLimit", resultLimit.coerceIn(5, 30))
            .put("searchProvider", if (searchProvider == "serper") "serper" else "tavily")
        try {
            parseSearch(requestJson(
                method = "POST",
                path = "/api/v1/search",
                body = modernBody,
                readTimeoutMs = 90_000,
            ))
        } catch (error: ApiException) {
            // The live Vercel deployment shown by the user still reports API 1.4.0.
            // That older strict request schema rejects newer fields such as
            // resultLimit/searchProvider with HTTP 400. Retry once with the legacy
            // body so search keeps working while the 2.3 server deployment rolls out.
            if (error.statusCode != 400) throw error
            val legacyBody = JSONObject()
                .put("query", query)
                .put("movieLanguage", movieLanguage)
                .put("subtitleLanguage", subtitleLanguage)
                .put("allowShortClips", allowShortClips)
            parseSearch(requestJson(
                method = "POST",
                path = "/api/v1/search",
                body = legacyBody,
                readTimeoutMs = 90_000,
            ))
        }
    }

    fun inspectMedia(
        url: String,
        originUrl: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        callback: (Result<SearchResult>) -> Unit,
    ) = execute(callback) {
        val root = requestJson(
            method = "POST",
            path = "/api/v1/media",
            body = JSONObject().put("url", url).apply {
                if (!originUrl.isNullOrBlank()) put("originUrl", originUrl)
                if (requestHeaders.isNotEmpty()) put("requestHeaders", JSONObject(requestHeaders))
            },
            readTimeoutMs = 90_000,
        )
        parseSearchItem(root.getJSONObject("result"))
    }

    fun recordPlaybackHistory(visitorId: String, item: LibraryItem, progressMs: Long, durationMs: Long) {
        executor.execute {
            runCatching {
                val movieId = item.catalogId?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) }
                    ?: "android-${MessageDigest.getInstance("SHA-256").digest(item.uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)}"
                val movie = JSONObject()
                    .put("id", movieId)
                    .put("title", item.title.take(180))
                    .apply { item.poster?.takeIf { url -> url.startsWith("https://") && url.length <= 4_000 }?.let { put("poster", it) } }
                requestJson(
                    "POST",
                    "/api/v1/history",
                    JSONObject()
                        .put("visitorId", visitorId)
                        .put("movieId", movieId)
                        .put("movie", movie)
                        .put("progress", progressMs.coerceAtLeast(0L))
                        .put("duration", durationMs.coerceAtLeast(0L))
                        .put("watchedAt", System.currentTimeMillis()),
                )
            }.onFailure { error ->
                if (BuildConfig.DEBUG) Log.w(TAG, "Could not sync playback history: ${error.message}")
            }
        }
    }

    fun suggestions(query: String, movieLanguage: String, callback: (List<String>) -> Unit) {
        executor.execute {
            val values = runCatching {
                val root = requestJson(
                    method = "POST",
                    path = "/api/v1/suggestions",
                    body = JSONObject().put("query", query).put("movieLanguage", movieLanguage),
                )
                root.optJSONArray("suggestions")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val title = item.optString("title").trim()
                            val year = item.optString("year").trim()
                            if (title.isNotEmpty()) add(if (year.isEmpty()) title else "$title ($year)")
                        }
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
            main.post { callback(values) }
        }
    }

    fun movieMetadata(title: String, callback: (Result<MovieMetadata>) -> Unit) = execute(callback) {
        movieMetadataBlocking(title)
    }

    /**
     * Full TMDB search when the new route is deployed. On an older live server
     * (the 1.4.x deployment did not expose /api/v1/tmdb/search), fall back to the
     * legacy metadata endpoint so the Dashboard search still returns a useful
     * best match instead of failing with HTTP 404.
     */
    fun tmdbSearch(query: String, type: String = "multi", callback: (Result<List<TmdbSearchResult>>) -> Unit) = execute(callback) {
        val clean = query.trim()
        val encoded = encode(clean)
        val safeType = if (type == "movie" || type == "tv") type else "multi"
        try {
            parseTmdbSearch(requestJson("GET", "/api/v1/tmdb/search?q=$encoded&type=$safeType"))
        } catch (error: ApiException) {
            if (!error.isMissingRoute()) throw error
            val metadata = movieMetadataBlocking(clean)
            metadata.tmdbId?.let { id ->
                listOf(metadata.toSearchResult(id))
            } ?: emptyList()
        }
    }

    fun tmdbDetails(tmdbId: Int, mediaType: String, callback: (Result<MovieMetadata>) -> Unit) = execute(callback) {
        val safeType = if (mediaType == "tv") "tv" else "movie"
        requestJson("GET", "/api/v1/tmdb/details?id=$tmdbId&type=$safeType")
            .getJSONObject("metadata")
            .let(::parseMovieMetadata)
    }

    /**
     * Compatibility helper. The production Dashboard no longer needs to mutate
     * the public catalog when a user saves a TMDB title. This endpoint now resolves
     * metadata only; publishing playback sources remains admin-only.
     */
    fun resolveCatalogFromTmdb(tmdbId: Int, mediaType: String, callback: (Result<LibraryItem>) -> Unit) = execute(callback) {
        val root = requestJson(
            "POST",
            "/api/v1/catalog/from-tmdb",
            JSONObject().put("tmdbId", tmdbId).put("mediaType", if (mediaType == "tv") "tv" else "movie"),
        )
        parseCatalogItem(root.getJSONObject("movie"), requirePlayable = false)
            ?: throw ApiException(502, "INVALID_CATALOG_RESPONSE", "استجابة الكتالوج غير صالحة.", endpoint = "/api/v1/catalog/from-tmdb")
    }

    @Deprecated("Use resolveCatalogFromTmdb")
    fun ensureCatalogFromTmdb(tmdbId: Int, mediaType: String, callback: (Result<LibraryItem>) -> Unit) =
        resolveCatalogFromTmdb(tmdbId, mediaType, callback)

    /**
     * Unified public catalog: Movies_Player is the shared source of truth when
     * configured, with the existing Android search-server catalog as fallback.
     * A failed website request never prevents offline/local Firebase usage.
     */
    fun catalog(callback: (Result<List<LibraryItem>>) -> Unit) = execute(callback) {
        val all = linkedMapOf<String, LibraryItem>()
        val failures = mutableListOf<Exception>()
        val endpoints = buildList {
            BuildConfig.SITE_BASE_URL.takeIf { it.startsWith("https://") }?.let {
                add(it to "/api/catalog")
            }
            add(baseUrl to "/api/v1/catalog")
        }
        for ((origin, path) in endpoints) {
            try {
                val movies = requestJson("GET", path, origin = origin).optJSONArray("movies") ?: JSONArray()
                for (index in 0 until movies.length()) {
                    val item = movies.optJSONObject(index) ?: continue
                    parseCatalogItem(item, requirePlayable = true)?.let { parsed ->
                        // Preserve the site's ordering and retain search-server-only items.
                        all.putIfAbsent(parsed.catalogId ?: parsed.uri, parsed)
                    }
                }
            } catch (error: Exception) {
                failures.add(error)
            }
        }
        if (all.isEmpty() && failures.size == endpoints.size) throw failures.first()
        all.values.toList()
    }

    fun close() = executor.shutdownNow()

    private fun movieMetadataBlocking(title: String): MovieMetadata {
        val cleanTitle = MovieTitleNormalizer.normalize(title)
        val root = requestJson("GET", "/api/v1/movie-metadata?title=${encode(cleanTitle)}")
        return parseMovieMetadata(root.getJSONObject("metadata"))
    }

    private fun parseTmdbSearch(root: JSONObject): List<TmdbSearchResult> {
        val array = root.optJSONArray("results") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.optInt("tmdbId")
                val title = row.optString("title").trim()
                if (id <= 0 || title.isEmpty()) continue
                add(TmdbSearchResult(
                    tmdbId = id,
                    mediaType = row.optString("mediaType", "movie"),
                    title = title,
                    originalTitle = row.optString("originalTitle").ifBlank { null },
                    overview = row.optString("overview").ifBlank { null },
                    releaseDate = row.optString("releaseDate").ifBlank { null },
                    year = row.optInt("year").takeIf { it > 0 },
                    poster = row.optString("poster").ifBlank { null },
                    backdrop = row.optString("backdrop").ifBlank { null },
                    rating = row.optDouble("rating").takeIf { !it.isNaN() },
                ))
            }
        }
    }

    private fun MovieMetadata.toSearchResult(id: Int) = TmdbSearchResult(
        tmdbId = id,
        mediaType = mediaType,
        title = title,
        originalTitle = originalTitle,
        overview = overview,
        releaseDate = releaseDate,
        year = year,
        poster = poster,
        backdrop = backdrop,
        rating = rating,
    )

    private fun <T> execute(callback: (Result<T>) -> Unit, block: () -> T) {
        executor.execute {
            val result = runCatching(block).onFailure { error ->
                if (BuildConfig.DEBUG) {
                    val apiError = error as? ApiException
                    Log.w(
                        TAG,
                        "API request failed endpoint=${apiError?.endpoint ?: "?"} status=${apiError?.statusCode ?: "network"} code=${apiError?.code ?: error.javaClass.simpleName} requestId=${apiError?.requestId ?: "-"}: ${error.message}",
                        error,
                    )
                }
            }
            main.post { callback(result) }
        }
    }

    private fun requestJson(
        method: String,
        path: String,
        body: JSONObject? = null,
        readTimeoutMs: Int = 30_000,
        origin: String = baseUrl,
    ): JSONObject {
        val endpoint = origin.trimEnd('/') + path
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Client", "any-movie-android/${BuildConfig.VERSION_NAME}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val requestId = connection.getHeaderField("X-Request-Id")
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val errorObject = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
                val code = errorObject?.optString("code")?.ifBlank { null }
                val message = errorObject?.optString("message")?.ifBlank { null }
                    ?: when (status) {
                        404 -> "المسار غير موجود على نسخة الخادم المنشورة."
                        429 -> "تم الوصول إلى حد الطلبات مؤقتاً. حاول بعد قليل."
                        in 500..599 -> "الخادم أو إحدى خدماته الخارجية لم تستجب الآن."
                        else -> "فشل طلب الخادم (HTTP $status)."
                    }
                throw ApiException(
                    statusCode = status,
                    code = code,
                    message = message,
                    requestId = errorObject?.optString("requestId")?.ifBlank { null } ?: requestId,
                    endpoint = path.substringBefore('?'),
                )
            }
            if (text.isBlank()) return JSONObject()
            return runCatching { JSONObject(text) }.getOrElse { cause ->
                throw ApiException(status, "INVALID_JSON", "الخادم أعاد استجابة غير صالحة.", requestId, path.substringBefore('?'), cause)
            }
        } catch (error: ApiException) {
            throw error
        } catch (error: Exception) {
            throw ApiException(
                statusCode = null,
                code = "NETWORK_ERROR",
                message = error.message?.takeIf { it.isNotBlank() } ?: "تعذر الاتصال بالخادم.",
                endpoint = path.substringBefore('?'),
                cause = error,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun ApiException.isMissingRoute() = statusCode == 404 || code == "NOT_FOUND" || code == "METHOD_NOT_ALLOWED"

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun parseCatalogItem(movie: JSONObject, requirePlayable: Boolean): LibraryItem? {
        val sources = movie.optJSONArray("sources") ?: JSONArray()
        val source = (0 until sources.length()).asSequence().mapNotNull { sources.optJSONObject(it) }
            .firstOrNull { it.optString("url").isNotBlank() }
        val url = source?.optString("url")?.trim().orEmpty()
        if (requirePlayable && url.isEmpty()) return null
        val title = movie.optString("title").trim()
        val id = movie.optString("id").trim()
        if (title.isEmpty() || id.isEmpty()) return null
        return LibraryItem(
            title = title,
            uri = url,
            kind = if (url.contains(".m3u8", true)) "hls" else if (url.isNotEmpty()) "video" else "catalog",
            source = source?.optString("quality", "HLS") ?: "TMDB",
            pageUrl = movie.optString("pageUrl").ifBlank { null },
            categories = (movie.optJSONArray("categories") ?: movie.optJSONArray("genres"))?.toStringList().orEmpty(),
            catalogId = id,
            tmdbId = movie.optInt("tmdbId").takeIf { it > 0 },
            mediaType = movie.optString("mediaType", "movie"),
            poster = movie.optString("poster").ifBlank { null },
            backdrop = movie.optString("backdrop").ifBlank { null },
            overview = movie.optString("description").ifBlank { null },
            year = movie.optInt("year").takeIf { it > 0 },
            rating = movie.optDouble("tmdbRating").takeIf { !it.isNaN() },
            status = movie.optString("status", "published"),
        )
    }

    private fun parseMovieMetadata(item: JSONObject): MovieMetadata {
        fun strings(key: String): List<String> = item.optJSONArray(key)?.toStringList().orEmpty()
        fun people(key: String): List<MoviePerson> = item.optJSONArray(key)?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index) ?: continue
                    add(MoviePerson(row.optString("name"), row.optString("role").ifBlank { null }, row.optString("image").ifBlank { null }))
                }
            }
        }.orEmpty()
        return MovieMetadata(
            title = item.optString("title"),
            originalTitle = item.optString("originalTitle").ifBlank { null },
            overview = item.optString("overview").ifBlank { null },
            releaseDate = item.optString("releaseDate").ifBlank { null },
            year = item.optInt("year").takeIf { it > 0 },
            runtime = item.optInt("runtime").takeIf { it > 0 },
            rating = item.optDouble("rating").takeIf { !it.isNaN() },
            genres = strings("genres"),
            countries = strings("countries"),
            languages = strings("languages"),
            poster = item.optString("poster").ifBlank { null },
            backdrop = item.optString("backdrop").ifBlank { null },
            images = strings("images"),
            directors = people("directors"),
            writers = people("writers"),
            cast = people("cast"),
            imdbId = item.optString("imdbId").ifBlank { null },
            tmdbId = item.optInt("tmdbId").takeIf { it > 0 },
            wikidataId = item.optString("wikidataId").ifBlank { null },
            trailers = strings("trailers"),
            mediaType = item.optString("mediaType", "movie"),
        )
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }

    private fun parseSearch(root: JSONObject): SearchResponse {
        val array = root.optJSONArray("results")
        val results = buildList {
            if (array != null) for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parseSearchItem(item))
            }
        }
        return SearchResponse(
            root.optString("understoodTitle"),
            root.optString("summary"),
            results,
            root.optJSONObject("meta")?.optString("searchProvider", "tavily") ?: "tavily",
        )
    }

    private fun parseStringArray(item: JSONObject, key: String): List<String> {
        val array = item.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty() && value !in this) add(value)
            }
        }
    }

    private fun parseStringMap(item: JSONObject, key: String): Map<String, String> {
        val obj = item.optJSONObject(key) ?: return emptyMap()
        return buildMap {
            val names = obj.keys()
            while (names.hasNext()) {
                val name = names.next()
                val value = obj.optString(name).trim()
                if (name.isNotBlank() && value.isNotEmpty()) put(name, value)
            }
        }
    }

    private fun parseSearchItem(item: JSONObject) = SearchResult(
        id = item.optString("id"),
        title = item.optString("title"),
        provider = item.optString("provider"),
        url = item.optString("url"),
        description = item.optString("description"),
        reason = item.optString("reason"),
        contentType = item.optString("contentType", "availability_page"),
        playable = item.optBoolean("playable"),
        playUrl = item.optString("playUrl").takeIf(String::isNotBlank),
        hlsUrl = item.optString("hlsUrl").takeIf(String::isNotBlank),
        kind = item.optString("kind").takeIf(String::isNotBlank),
        downloadable = item.optBoolean("downloadable"),
        downloadUrl = item.optString("downloadUrl").takeIf(String::isNotBlank),
        detectedBy = item.optString("detectedBy").takeIf(String::isNotBlank),
        hlsMaster = item.optBoolean("hlsMaster"),
        hlsVariantCount = item.optInt("hlsVariantCount"),
        hlsAudioRenditionCount = item.optInt("hlsAudioRenditionCount"),
        hlsSubtitleRenditionCount = item.optInt("hlsSubtitleRenditionCount"),
        subtitleLanguages = parseStringArray(item, "subtitleLanguages"),
        subtitleEvidence = item.optString("subtitleEvidence").takeIf(String::isNotBlank),
        hlsDurationSeconds = item.optInt("hlsDurationSeconds"),
        hlsLive = item.optBoolean("hlsLive"),
        hlsEncrypted = item.optBoolean("hlsEncrypted"),
        hlsDrmProtected = item.optBoolean("hlsDrmProtected"),
        playbackHeaders = parseStringMap(item, "playbackHeaders"),
    )

    companion object { private const val TAG = "AnyMovieApi" }
}
