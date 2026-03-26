package com.kidsmovies.app.artwork

/**
 * Unified content rating levels for comparing movie and TV certifications.
 * Used to filter TMDB artwork based on parental settings.
 */
enum class ContentRating(val level: Int, val label: String) {
    UNRATED(0, "Unrated"),
    G(1, "G"),
    PG(2, "PG"),
    PG13(3, "PG-13"),
    R(4, "R"),
    NC17(5, "NC-17");

    companion object {
        /** Map a US movie certification string to a ContentRating */
        fun fromMovieCertification(cert: String): ContentRating = when (cert.uppercase().trim()) {
            "G" -> G
            "PG" -> PG
            "PG-13" -> PG13
            "R" -> R
            "NC-17" -> NC17
            else -> UNRATED
        }

        /** Map a US TV rating string to a ContentRating */
        fun fromTvRating(rating: String): ContentRating = when (rating.uppercase().trim()) {
            "TV-Y", "TV-G" -> G
            "TV-Y7", "TV-Y7-FV", "TV-PG" -> PG
            "TV-14" -> PG13
            "TV-MA" -> R
            else -> UNRATED
        }

        /** Parse a unified rating label (as stored in settings) to ContentRating */
        fun fromLabel(label: String): ContentRating = when (label.uppercase().trim()) {
            "G" -> G
            "PG" -> PG
            "PG-13" -> PG13
            "R" -> R
            "NC-17" -> NC17
            "" -> NC17 // Empty string = no filtering
            else -> PG // Default
        }

        /** All options for UI pickers (excluding NC17 and UNRATED) */
        val pickerOptions = listOf(G, PG, PG13, R)

        /** Labels for kids app picker including "No filtering" */
        val pickerLabels = listOf("G", "PG", "PG-13", "R", "No filtering")

        /** Labels for parent app picker including "Not set" (child controls) */
        val parentPickerLabels = listOf("Not set (child controls)", "G", "PG", "PG-13", "R", "No filtering")
    }

    /** Check if content with this rating is allowed under the given max rating */
    fun isAllowedBy(maxRating: ContentRating): Boolean = this.level <= maxRating.level
}
