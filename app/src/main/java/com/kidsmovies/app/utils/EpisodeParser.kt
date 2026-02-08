package com.kidsmovies.app.utils

/**
 * Utility class for parsing TV show episode and season information from filenames and folder names.
 *
 * Supports various naming patterns:
 * - S01E05, s01e05 (most common)
 * - 1x05, 01x05
 * - Season 1 Episode 5
 * - E05, Episode 5 (episode only)
 * - 105, 0105 (season+episode concatenated)
 */
object EpisodeParser {

    data class EpisodeInfo(
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val showName: String? = null
    ) {
        fun hasEpisodeInfo() = episodeNumber != null
        fun hasSeasonInfo() = seasonNumber != null
    }

    data class SeasonInfo(
        val seasonNumber: Int,
        val showName: String? = null
    )

    // Patterns for episode detection (ordered by specificity)
    private val episodePatterns = listOf(
        // S01E05, S1E5 - most common format
        Regex("""(?i)S(\d{1,2})[\s._-]*E(\d{1,3})"""),
        // 1x05, 01x05 - alternate format
        Regex("""(?i)(\d{1,2})x(\d{1,3})"""),
        // Season 1 Episode 5
        Regex("""(?i)Season\s*(\d{1,2})\s*Episode\s*(\d{1,3})"""),
        // S01.E05 or S01-E05 with separators
        Regex("""(?i)S(\d{1,2})[.\-_]E(\d{1,3})"""),
    )

    // Patterns for episode-only detection
    private val episodeOnlyPatterns = listOf(
        // E05, Episode 5
        Regex("""(?i)\bE(\d{1,3})\b"""),
        Regex("""(?i)\bEpisode\s*(\d{1,3})\b"""),
        // Just a number at start like "05 - Episode Name" or "05."
        Regex("""^(\d{1,3})[\s.\-_]"""),
    )

    // Patterns for season detection in folder names
    private val seasonPatterns = listOf(
        // Season 1, Season 01
        Regex("""(?i)\bSeason\s*(\d{1,2})\b"""),
        // S01, S1
        Regex("""(?i)\bS(\d{1,2})\b"""),
        // Series 1 (UK naming)
        Regex("""(?i)\bSeries\s*(\d{1,2})\b"""),
    )

    // Quality/codec tags to remove for cleaner show name extraction
    private val cleanupPatterns = listOf(
        Regex("""(?i)\b(720p|1080p|2160p|4k|uhd|hdr|bluray|bdrip|webrip|web-dl|hdtv|dvdrip|x264|x265|h264|h265|hevc|aac|ac3|dts)\b"""),
        Regex("""(?i)\[.*?\]"""), // Anything in brackets
        Regex("""(?i)\(.*?\)"""), // Anything in parentheses (be careful with year)
        Regex("""\.(mkv|mp4|avi|mov|wmv|flv|webm)$""", RegexOption.IGNORE_CASE),
    )

    /**
     * Parse episode information from a video filename.
     *
     * @param filename The video filename (with or without path)
     * @return EpisodeInfo with detected season/episode numbers, or empty if not detected
     */
    fun parseEpisode(filename: String): EpisodeInfo {
        val name = filename.substringAfterLast("/").substringAfterLast("\\")

        // Try full episode patterns (with season)
        for (pattern in episodePatterns) {
            val match = pattern.find(name)
            if (match != null) {
                val season = match.groupValues[1].toIntOrNull()
                val episode = match.groupValues[2].toIntOrNull()
                val showName = extractShowName(name, match.range.first)
                return EpisodeInfo(season, episode, showName)
            }
        }

        // Try episode-only patterns
        for (pattern in episodeOnlyPatterns) {
            val match = pattern.find(name)
            if (match != null) {
                val episode = match.groupValues[1].toIntOrNull()
                if (episode != null && episode <= 999) { // Sanity check
                    val showName = extractShowName(name, match.range.first)
                    return EpisodeInfo(null, episode, showName)
                }
            }
        }

        return EpisodeInfo(null, null, null)
    }

    /**
     * Parse season information from a folder name.
     *
     * @param folderName The folder name
     * @return SeasonInfo if a season pattern is found, null otherwise
     */
    fun parseSeason(folderName: String): SeasonInfo? {
        for (pattern in seasonPatterns) {
            val match = pattern.find(folderName)
            if (match != null) {
                val season = match.groupValues[1].toIntOrNull()
                if (season != null) {
                    // Extract show name from what comes before the season pattern
                    val showName = folderName.substring(0, match.range.first).trim()
                        .replace(Regex("""[\s._-]+$"""), "")
                        .takeIf { it.isNotBlank() }
                    return SeasonInfo(season, showName)
                }
            }
        }
        return null
    }

    /**
     * Detect if a folder structure looks like a TV show.
     * A folder is likely a TV show if it contains subfolders that look like seasons.
     *
     * @param subfolderNames List of subfolder names within a potential show folder
     * @return True if the folder structure appears to be a TV show
     */
    fun detectTvShowStructure(subfolderNames: List<String>): Boolean {
        var seasonCount = 0
        for (folder in subfolderNames) {
            if (parseSeason(folder) != null) {
                seasonCount++
            }
        }
        // At least 1 season folder indicates a TV show structure
        return seasonCount >= 1
    }

    /**
     * Clean a video or folder name to get a cleaner title for TMDB search.
     * Removes quality tags, codec info, and other common noise.
     *
     * @param name The name to clean
     * @return Cleaned name suitable for search
     */
    fun cleanName(name: String): String {
        var cleaned = name

        // Remove file extension first
        cleaned = cleaned.substringBeforeLast(".")

        // Remove episode patterns to get show name
        for (pattern in episodePatterns) {
            cleaned = pattern.replace(cleaned, " ")
        }

        // Remove quality/codec patterns
        for (pattern in cleanupPatterns) {
            cleaned = pattern.replace(cleaned, " ")
        }

        // Replace common separators with spaces
        cleaned = cleaned.replace(Regex("""[._-]+"""), " ")

        // Remove extra whitespace
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()

        return cleaned
    }

    /**
     * Extract the show name from before the episode pattern.
     */
    private fun extractShowName(filename: String, patternStart: Int): String? {
        if (patternStart <= 0) return null

        val beforePattern = filename.substring(0, patternStart)
        return cleanName(beforePattern).takeIf { it.isNotBlank() }
    }

    /**
     * Parse a combined season+episode number like "105" for S01E05 or "0105".
     * This is a fallback for unusual naming.
     *
     * @param number The number string to parse
     * @return EpisodeInfo if parseable, null otherwise
     */
    fun parseCombinedNumber(number: String): EpisodeInfo? {
        val num = number.toIntOrNull() ?: return null

        return when {
            // 4 digit: 0105 = S01E05
            num in 100..9999 && number.length == 4 -> {
                EpisodeInfo(num / 100, num % 100)
            }
            // 3 digit: 105 = S1E05 or 512 = S5E12
            num in 100..999 -> {
                EpisodeInfo(num / 100, num % 100)
            }
            else -> null
        }
    }

    /**
     * Determine sort order for videos within a season.
     * Returns a comparable value that handles missing episode numbers gracefully.
     *
     * @param video A video with potential season/episode info
     * @return Sort key (lower = first)
     */
    fun getSortOrder(seasonNumber: Int?, episodeNumber: Int?, title: String): Int {
        if (episodeNumber != null) {
            // Season * 1000 + episode for proper ordering
            val season = seasonNumber ?: 0
            return season * 1000 + episodeNumber
        }

        // Fallback: try to extract a number from the title
        val numberMatch = Regex("""^(\d+)""").find(title)
        if (numberMatch != null) {
            return numberMatch.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
        }

        // No number found, sort alphabetically (return high value)
        return Int.MAX_VALUE
    }
}
