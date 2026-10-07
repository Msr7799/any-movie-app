package com.forgepulse.anymovie

data class SearchResult(
    val id: String,
    val title: String,
    val provider: String,
    val url: String,
    val description: String,
    val reason: String,
    val contentType: String,
    val playable: Boolean,
    val playUrl: String?,
    val hlsUrl: String?,
    val kind: String?,
    val downloadable: Boolean,
    val downloadUrl: String?,
    val detectedBy: String?,
    val hlsMaster: Boolean,
    val hlsVariantCount: Int,
    val hlsAudioRenditionCount: Int,
    val hlsSubtitleRenditionCount: Int,
    val subtitleLanguages: List<String>,
    val subtitleEvidence: String?,
    val hlsDurationSeconds: Int,
    val hlsLive: Boolean,
    val hlsEncrypted: Boolean,
    val hlsDrmProtected: Boolean,
    val playbackHeaders: Map<String, String>,
)

data class SearchResponse(
    val understoodTitle: String,
    val summary: String,
    val results: List<SearchResult>,
    val searchProvider: String = "tavily",
)

data class MoviePerson(val name: String, val role: String?, val image: String?)
data class MovieMetadata(
    val title: String,
    val originalTitle: String?,
    val overview: String?,
    val releaseDate: String?,
    val year: Int?,
    val runtime: Int?,
    val rating: Double?,
    val genres: List<String>,
    val countries: List<String>,
    val languages: List<String>,
    val poster: String?,
    val backdrop: String?,
    val images: List<String>,
    val directors: List<MoviePerson>,
    val writers: List<MoviePerson>,
    val cast: List<MoviePerson>,
    val imdbId: String?,
    val tmdbId: Int?,
    val wikidataId: String?,
    val trailers: List<String> = emptyList(),
    val mediaType: String = "movie",
)

data class TmdbSearchResult(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val rating: Double? = null,
)

data class ApiServiceStatus(
    val status: String,
    val version: String,
    val tmdbReady: Boolean,
    val searchReady: Boolean,
    val suggestionsReady: Boolean,
    val storageReady: Boolean,
    val region: String? = null,
    val requestId: String? = null,
)

class ApiException(
    val statusCode: Int?,
    val code: String?,
    override val message: String,
    val requestId: String? = null,
    val endpoint: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

data class LibraryItem(
    val title: String,
    val uri: String,
    val kind: String,
    val source: String,
    val pageUrl: String? = null,
    val contentType: String = "full_movie",
    val downloadable: Boolean = false,
    val downloadUrl: String? = null,
    val detectedBy: String? = null,
    val hlsMaster: Boolean = false,
    val hlsVariantCount: Int = 0,
    val hlsAudioRenditionCount: Int = 0,
    val hlsSubtitleRenditionCount: Int = 0,
    val subtitleLanguages: List<String> = emptyList(),
    val subtitleEvidence: String? = null,
    val hlsDurationSeconds: Int = 0,
    val hlsLive: Boolean = false,
    val hlsEncrypted: Boolean = false,
    val hlsDrmProtected: Boolean = false,
    val playbackHeaders: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
    val catalogId: String? = null,
    val tmdbId: Int? = null,
    val mediaType: String = "movie",
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val status: String = "published",
)

data class UserLibraryEntry(
    val catalogId: String,
    val tmdbId: Int? = null,
    val mediaType: String = "movie",
    val title: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    val tmdbRating: Double? = null,
    val genres: List<String> = emptyList(),
    val watchlist: Boolean = false,
    val favorite: Boolean = false,
    val rating: Int = 0,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val watched: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastWatchedAt: Long = 0L,
) {
    val progressFraction: Float
        get() = if (durationMs <= 0L) 0f else (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

data class SearchDraft(
    val query: String = "",
    val movieLanguage: String = "any",
    val subtitleLanguage: String = "any",
    val allowShortClips: Boolean = false,
    val resultLimit: Int = 10,
    val searchProvider: String = "tavily",
)

data class SavedSearch(
    val draft: SearchDraft,
    val response: SearchResponse,
    val savedAt: Long,
)

data class SubtitlePreferences(
    var sizeSp: Int = 22,
    var opacity: Int = 72,
    var bottomPosition: Int = 12,
    var foreground: Int = 0xFFFFFFFF.toInt(),
    var background: Int = 0xFF000000.toInt(),
    var bold: Boolean = true,
    var shadow: Boolean = true,
)

data class PictureAdjustments(
    var temperature: Int = 0,
    var tint: Int = 0,
    var brightness: Int = 0,
    var contrast: Int = 0,
    var highlights: Int = 0,
    var shadows: Int = 0,
    var vibrance: Int = 0,
    var saturation: Int = 0,
    var sharpness: Int = 0,
    var clarity: Int = 0,
    var vignette: Int = 0,
    var zoom: Int = 100,
    var crop: Boolean = false,
)

data class CloudStatePayload(
    val draftJson: String?,
    val searchHistoryJson: String?,
    val playbackHistoryJson: String?,
    val resultsCollapsed: Boolean,
)
