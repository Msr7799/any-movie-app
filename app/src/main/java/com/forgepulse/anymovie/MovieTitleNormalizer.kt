package com.forgepulse.anymovie

/** Remove website noise from the title before Gemini/TMDB lookup, without changing display names. */
object MovieTitleNormalizer {
    private val prefixes = Regex("^(?:(?:مشاهدة|تحميل)\\s+)?(?:فيلم|مسلسل|movie|film)\\s+", RegexOption.IGNORE_CASE)
    private val tail = Regex("\\s*(?:مترجم(?:ة)?|مدبلج(?:ة)?|اون\\s*لاين|أون\\s*لاين|online|streaming|مشاهدة\\s*مباشرة|بجودة\\s*عالية|النسخة\\s*الكاملة|full\\s*movie|HD|FHD|UHD|4K|1080p|720p|480p)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val extension = Regex("\\.(?:m3u8|mp4|mkv|webm|mov)$", RegexOption.IGNORE_CASE)
    private val multiSpace = Regex("\\s+")

    fun normalize(raw: String): String {
        val clean = raw.trim().substringBefore(" | ").substringBefore(" - شاهد")
            .replace(extension, "")
            .replace(Regex("[._]"), " ")
            .replace(prefixes, "")
            .replace(tail, " ")
            .replace(Regex("\\s*[-–—|]+\\s*$"), "")
            .replace(multiSpace, " ")
            .trim()
        return clean.takeIf { it.length >= 3 && !isGeneric(it) } ?: raw.trim().take(180)
    }

    fun isGeneric(raw: String): Boolean = raw.trim().matches(
        Regex("(?:master|index|playlist|video|stream|unknown|untitled)(?:[ ._-]?[0-9]+)?(?:\\.m3u8)?", RegexOption.IGNORE_CASE)
    )
}
