package com.forgepulse.anymovie

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class AdminApiClient(context: Context, private val baseUrl: String = BuildConfig.API_BASE_URL) {
    private val sessionStore = SecureAdminSession(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cookie: String? = sessionStore.read()

    val authenticated: Boolean get() = !cookie.isNullOrBlank()

    fun login(username: String, password: String, callback: (Result<JSONObject>) -> Unit) = request(
        "/api/admin/login", "POST", JSONObject().put("username", username).put("password", password), callback,
    )

    fun session(callback: (Result<JSONObject>) -> Unit) = request("/api/admin/session", "GET", null, callback)
    fun health(callback: (Result<JSONObject>) -> Unit) = request("/api/v1/health", "GET", null, callback)
    fun catalog(callback: (Result<JSONObject>) -> Unit) = request("/api/admin/catalog", "GET", null, callback)
    fun history(callback: (Result<JSONObject>) -> Unit) = request("/api/admin/history", "GET", null, callback)
    fun settings(callback: (Result<JSONObject>) -> Unit) = request("/api/admin/settings", "GET", null, callback)
    fun importCatalog(catalog: Any, rightsConfirmed: Boolean, callback: (Result<JSONObject>) -> Unit) = request(
        "/api/admin/catalog", "POST", JSONObject().put("rightsConfirmed", rightsConfirmed).put("catalog", catalog), callback,
    )
    fun saveSecrets(values: Map<String, String>, callback: (Result<JSONObject>) -> Unit) = request(
        "/api/admin/settings", "PATCH", JSONObject().put("secrets", JSONObject(values)), callback,
    )
    fun deleteMovie(id: String, callback: (Result<JSONObject>) -> Unit) = request(
        "/api/admin/catalog?id=${URLEncoder.encode(id, Charsets.UTF_8.name())}", "DELETE", null, callback,
    )
    fun searchTmdb(query: String, callback: (Result<JSONObject>) -> Unit) = request(
        "/api/v1/tmdb/search?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}&type=multi", "GET", null, callback,
    )
    fun addFromTmdb(tmdbId: Int, mediaType: String, callback: (Result<JSONObject>) -> Unit) = request(
        "/api/admin/catalog/from-tmdb", "POST",
        JSONObject().put("tmdbId", tmdbId).put("mediaType", if (mediaType == "tv") "tv" else "movie"),
        callback,
    )
    fun renameHistory(movieId: String, title: String, callback: (Result<JSONObject>) -> Unit) = request(
        "/api/admin/history", "PATCH", JSONObject().put("movieId", movieId).put("title", title), callback,
    )
    fun deleteHistory(movieId: String, callback: (Result<JSONObject>) -> Unit) = request(
        "/api/admin/history?movieId=${URLEncoder.encode(movieId, Charsets.UTF_8.name())}", "DELETE", null, callback,
    )
    fun updateMovie(
        id: String,
        title: String,
        categories: List<String>,
        sortOrder: Int,
        status: String,
        sources: JSONArray,
        callback: (Result<JSONObject>) -> Unit,
    ) = request(
        "/api/admin/catalog",
        "PATCH",
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("categories", JSONArray(categories))
            .put("sortOrder", sortOrder)
            .put("status", status)
            .put("sources", sources),
        callback,
    )
    fun logout(callback: (Result<JSONObject>) -> Unit) = request("/api/admin/logout", "POST", JSONObject(), callback)

    fun clearSession() {
        cookie = null
        sessionStore.clear()
    }

    private fun request(path: String, method: String, body: JSONObject?, callback: (Result<JSONObject>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    cookie?.let { setRequestProperty("Cookie", it) }
                    if (body != null) doOutput = true
                }
                try {
                    body?.let { connection.outputStream.use { stream -> stream.write(it.toString().toByteArray(Charsets.UTF_8)) } }
                    val status = connection.responseCode
                    connection.getHeaderField("Set-Cookie")?.substringBefore(';')?.takeIf { it.contains('=') }?.let {
                        cookie = it
                        sessionStore.write(it)
                    }
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                    if (status !in 200..299) error(json.optJSONObject("error")?.optString("message") ?: json.optString("error", "HTTP $status"))
                    json
                } finally { connection.disconnect() }
            }
            main.post { callback(result) }
        }
    }
}
