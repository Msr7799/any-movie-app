package com.forgepulse.anymovie

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Personal-library source of truth.
 *
 * The public catalog is stored by the server/MongoDB. This repository stores only
 * per-user relationships to catalog items (watchlist, rating, progress, etc.). It
 * always keeps a local copy so guest/offline use continues to work, and mirrors the
 * same entries under users/{uid}/library when Firebase is available.
 */
class UserLibraryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("personal_library_v1", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<(List<UserLibraryEntry>) -> Unit>()
    private val lock = Any()
    private var entries: MutableMap<String, UserLibraryEntry> = loadLocal().associateBy { it.catalogId }.toMutableMap()
    private var auth: FirebaseAuth? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var remoteListener: ValueEventListener? = null
    private var observedUid: String? = null

    fun start() {
        publish()
        if (FirebaseApp.getApps(appContext).isEmpty()) return
        auth = FirebaseAuth.getInstance()
        val current = auth?.currentUser
        if (current != null) {
            attach(current.uid)
        } else {
            auth?.signInAnonymously()?.addOnSuccessListener { result ->
                result.user?.uid?.let(::attach)
            }
        }
        authListener = FirebaseAuth.AuthStateListener { state ->
            state.currentUser?.uid?.let(::attach)
        }.also { listener -> auth?.addAuthStateListener(listener) }
    }

    fun stop() {
        val uid = observedUid
        val listener = remoteListener
        if (uid != null && listener != null && FirebaseApp.getApps(appContext).isNotEmpty()) {
            FirebaseDatabase.getInstance().reference.child("users").child(uid).child("library").removeEventListener(listener)
        }
        remoteListener = null
        observedUid = null
        authListener?.let { listener -> auth?.removeAuthStateListener(listener) }
        authListener = null
        auth = null
    }

    fun observe(listener: (List<UserLibraryEntry>) -> Unit) {
        listeners += listener
        listener(snapshot())
    }

    fun removeObserver(listener: (List<UserLibraryEntry>) -> Unit) {
        listeners -= listener
    }

    fun snapshot(): List<UserLibraryEntry> = synchronized(lock) {
        entries.values.sortedWith(compareByDescending<UserLibraryEntry> { it.lastWatchedAt }.thenByDescending { it.addedAt })
    }

    fun contains(catalogId: String): Boolean = synchronized(lock) { entries.containsKey(catalogId) }

    fun findByTmdbId(tmdbId: Int): UserLibraryEntry? = synchronized(lock) {
        entries.values.firstOrNull { it.tmdbId == tmdbId }
    }

    fun containsTmdb(tmdbId: Int): Boolean = findByTmdbId(tmdbId) != null

    fun upsertTmdbResult(result: TmdbSearchResult, watchlist: Boolean = true) {
        val existing = findByTmdbId(result.tmdbId)
        val id = existing?.catalogId ?: "tmdb_${if (result.mediaType == "tv") "tv" else "movie"}_${result.tmdbId}"
        put((existing ?: UserLibraryEntry(
            catalogId = id,
            tmdbId = result.tmdbId,
            mediaType = result.mediaType,
            title = result.title,
            poster = result.poster,
            backdrop = result.backdrop,
            overview = result.overview,
            year = result.year,
            tmdbRating = result.rating,
            watchlist = watchlist,
        )).copy(
            tmdbId = result.tmdbId,
            mediaType = result.mediaType,
            title = result.title,
            poster = result.poster ?: existing?.poster,
            backdrop = result.backdrop ?: existing?.backdrop,
            overview = result.overview ?: existing?.overview,
            year = result.year ?: existing?.year,
            tmdbRating = result.rating ?: existing?.tmdbRating,
            watchlist = if (existing == null) watchlist else existing.watchlist || watchlist,
        ))
    }

    fun upsertMetadata(metadata: MovieMetadata, watchlist: Boolean = true) {
        val tmdbId = metadata.tmdbId ?: return
        val search = TmdbSearchResult(
            tmdbId = tmdbId,
            mediaType = metadata.mediaType,
            title = metadata.title,
            originalTitle = metadata.originalTitle,
            overview = metadata.overview,
            releaseDate = metadata.releaseDate,
            year = metadata.year,
            poster = metadata.poster,
            backdrop = metadata.backdrop,
            rating = metadata.rating,
        )
        upsertTmdbResult(search, watchlist)
        mutate(findByTmdbId(tmdbId)?.catalogId ?: return) {
            it.copy(genres = metadata.genres)
        }
    }

    fun upsertCatalogItem(item: LibraryItem, watchlist: Boolean = true) {
        val id = item.catalogId ?: return
        val byId = synchronized(lock) { entries[id] }
        val byTmdb = item.tmdbId?.let(::findByTmdbId)
        val current = byId ?: byTmdb

        // When a TMDB-only personal entry later appears in the published catalog,
        // migrate it to the server catalog id while preserving progress and ratings.
        if (byId == null && byTmdb != null && byTmdb.catalogId != id) {
            synchronized(lock) { entries.remove(byTmdb.catalogId) }
            remoteRef(byTmdb.catalogId)?.removeValue()
        }

        val entry = (current ?: UserLibraryEntry(
            catalogId = id,
            tmdbId = item.tmdbId,
            mediaType = item.mediaType,
            title = item.title,
            poster = item.poster,
            backdrop = item.backdrop,
            overview = item.overview,
            year = item.year,
            tmdbRating = item.rating,
            genres = item.categories,
            watchlist = watchlist,
        )).copy(
            catalogId = id,
            tmdbId = item.tmdbId ?: current?.tmdbId,
            mediaType = item.mediaType,
            title = item.title,
            poster = item.poster ?: current?.poster,
            backdrop = item.backdrop ?: current?.backdrop,
            overview = item.overview ?: current?.overview,
            year = item.year ?: current?.year,
            tmdbRating = item.rating ?: current?.tmdbRating,
            genres = if (item.categories.isNotEmpty()) item.categories else current?.genres.orEmpty(),
            watchlist = if (current == null) watchlist else current.watchlist || watchlist,
        )
        put(entry)
    }

    fun setWatchlist(catalogId: String, value: Boolean) = mutate(catalogId) { it.copy(watchlist = value) }
    fun setFavorite(catalogId: String, value: Boolean) = mutate(catalogId) { it.copy(favorite = value) }
    fun setRating(catalogId: String, rating: Int) = mutate(catalogId) { it.copy(rating = rating.coerceIn(0, 10)) }
    fun setProgress(catalogId: String, progressMs: Long, durationMs: Long) = mutate(catalogId) {
        val watched = durationMs > 0 && progressMs >= (durationMs * 0.92).toLong()
        it.copy(
            progressMs = progressMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            watched = watched || it.watched,
            lastWatchedAt = System.currentTimeMillis(),
        )
    }

    fun remove(catalogId: String) {
        synchronized(lock) { entries.remove(catalogId) }
        saveLocal()
        publish()
        remoteRef(catalogId)?.removeValue()
    }

    private fun mutate(catalogId: String, transform: (UserLibraryEntry) -> UserLibraryEntry) {
        val updated = synchronized(lock) {
            val current = entries[catalogId] ?: return
            transform(current).also { entries[catalogId] = it }
        }
        saveLocal()
        publish()
        upload(updated)
    }

    private fun put(entry: UserLibraryEntry) {
        synchronized(lock) { entries[entry.catalogId] = entry }
        saveLocal()
        publish()
        upload(entry)
    }

    private fun attach(uid: String) {
        if (observedUid == uid && remoteListener != null) return
        val database = runCatching { FirebaseDatabase.getInstance() }.getOrNull() ?: return
        remoteListener?.let { old -> observedUid?.let { database.reference.child("users").child(it).child("library").removeEventListener(old) } }
        observedUid = uid
        val ref = database.reference.child("users").child(uid).child("library")

        // Upload any local guest entries first; Firebase updates are per catalog id and
        // therefore cannot overwrite another user's data.
        snapshot().forEach(::upload)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remote = snapshot.children.mapNotNull(::entryFromSnapshot)
                synchronized(lock) {
                    for (item in remote) {
                        val local = entries[item.catalogId]
                        entries[item.catalogId] = if (local == null) item else newerOf(local, item)
                    }
                }
                saveLocal()
                publish()
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        remoteListener = listener
        ref.addValueEventListener(listener)
    }

    private fun newerOf(local: UserLibraryEntry, remote: UserLibraryEntry): UserLibraryEntry {
        val localFreshness = maxOf(local.lastWatchedAt, local.addedAt)
        val remoteFreshness = maxOf(remote.lastWatchedAt, remote.addedAt)
        return if (remoteFreshness >= localFreshness) remote else local
    }

    private fun upload(entry: UserLibraryEntry) {
        val ref = remoteRef(entry.catalogId) ?: return
        ref.setValue(mapOf(
            "catalogId" to entry.catalogId,
            "tmdbId" to entry.tmdbId,
            "mediaType" to entry.mediaType,
            "title" to entry.title,
            "poster" to entry.poster,
            "backdrop" to entry.backdrop,
            "overview" to entry.overview,
            "year" to entry.year,
            "tmdbRating" to entry.tmdbRating,
            "genres" to entry.genres,
            "watchlist" to entry.watchlist,
            "favorite" to entry.favorite,
            "rating" to entry.rating,
            "progressMs" to entry.progressMs,
            "durationMs" to entry.durationMs,
            "watched" to entry.watched,
            "addedAt" to entry.addedAt,
            "lastWatchedAt" to entry.lastWatchedAt,
            "updatedAt" to ServerValue.TIMESTAMP,
        ))
    }

    private fun remoteRef(catalogId: String) = auth?.currentUser?.uid?.let { uid ->
        runCatching { FirebaseDatabase.getInstance().reference.child("users").child(uid).child("library").child(catalogId) }.getOrNull()
    }

    private fun entryFromSnapshot(snapshot: DataSnapshot): UserLibraryEntry? {
        val id = snapshot.child("catalogId").getValue(String::class.java) ?: snapshot.key ?: return null
        val title = snapshot.child("title").getValue(String::class.java)?.trim().orEmpty()
        if (title.isEmpty()) return null
        return UserLibraryEntry(
            catalogId = id,
            tmdbId = snapshot.child("tmdbId").getValue(Long::class.java)?.toInt(),
            mediaType = snapshot.child("mediaType").getValue(String::class.java) ?: "movie",
            title = title,
            poster = snapshot.child("poster").getValue(String::class.java),
            backdrop = snapshot.child("backdrop").getValue(String::class.java),
            overview = snapshot.child("overview").getValue(String::class.java),
            year = snapshot.child("year").getValue(Long::class.java)?.toInt(),
            tmdbRating = snapshot.child("tmdbRating").getValue(Double::class.java),
            genres = snapshot.child("genres").children.mapNotNull { it.getValue(String::class.java) },
            watchlist = snapshot.child("watchlist").getValue(Boolean::class.java) ?: false,
            favorite = snapshot.child("favorite").getValue(Boolean::class.java) ?: false,
            rating = snapshot.child("rating").getValue(Long::class.java)?.toInt()?.coerceIn(0, 10) ?: 0,
            progressMs = snapshot.child("progressMs").getValue(Long::class.java) ?: 0L,
            durationMs = snapshot.child("durationMs").getValue(Long::class.java) ?: 0L,
            watched = snapshot.child("watched").getValue(Boolean::class.java) ?: false,
            addedAt = snapshot.child("addedAt").getValue(Long::class.java) ?: System.currentTimeMillis(),
            lastWatchedAt = snapshot.child("lastWatchedAt").getValue(Long::class.java) ?: 0L,
        )
    }

    private fun publish() {
        val value = snapshot()
        listeners.forEach { it(value) }
    }

    private fun saveLocal() {
        val array = JSONArray()
        snapshot().forEach { item ->
            array.put(JSONObject()
                .put("catalogId", item.catalogId)
                .put("tmdbId", item.tmdbId)
                .put("mediaType", item.mediaType)
                .put("title", item.title)
                .put("poster", item.poster)
                .put("backdrop", item.backdrop)
                .put("overview", item.overview)
                .put("year", item.year)
                .put("tmdbRating", item.tmdbRating)
                .put("genres", JSONArray(item.genres))
                .put("watchlist", item.watchlist)
                .put("favorite", item.favorite)
                .put("rating", item.rating)
                .put("progressMs", item.progressMs)
                .put("durationMs", item.durationMs)
                .put("watched", item.watched)
                .put("addedAt", item.addedAt)
                .put("lastWatchedAt", item.lastWatchedAt))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    private fun loadLocal(): List<UserLibraryEntry> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.optString("catalogId").trim()
                val title = row.optString("title").trim()
                if (id.isEmpty() || title.isEmpty()) continue
                add(UserLibraryEntry(
                    catalogId = id,
                    tmdbId = row.optInt("tmdbId").takeIf { it > 0 },
                    mediaType = row.optString("mediaType", "movie"),
                    title = title,
                    poster = row.optString("poster").ifBlank { null },
                    backdrop = row.optString("backdrop").ifBlank { null },
                    overview = row.optString("overview").ifBlank { null },
                    year = row.optInt("year").takeIf { it > 0 },
                    tmdbRating = row.optDouble("tmdbRating").takeIf { !it.isNaN() && it > 0.0 },
                    genres = row.optJSONArray("genres")?.let { array -> buildList { for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(::add) } }.orEmpty(),
                    watchlist = row.optBoolean("watchlist", false),
                    favorite = row.optBoolean("favorite", false),
                    rating = row.optInt("rating", 0).coerceIn(0, 10),
                    progressMs = row.optLong("progressMs", 0L),
                    durationMs = row.optLong("durationMs", 0L),
                    watched = row.optBoolean("watched", false),
                    addedAt = row.optLong("addedAt", System.currentTimeMillis()),
                    lastWatchedAt = row.optLong("lastWatchedAt", 0L),
                ))
            }
        }
    }.getOrDefault(emptyList())
}
