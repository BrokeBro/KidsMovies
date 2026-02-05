package com.kidsmovies.app.utils

object ColorSchemes {

    data class ColorScheme(
        val name: String,
        val displayName: String,
        val primaryColor: String,
        val primaryDarkColor: String,
        val accentColor: String
    )

    private val schemes = listOf(
        ColorScheme(
            name = "blue",
            displayName = "Ocean Blue",
            primaryColor = "#2196F3",
            primaryDarkColor = "#1976D2",
            accentColor = "#03A9F4"
        ),
        ColorScheme(
            name = "green",
            displayName = "Forest Green",
            primaryColor = "#4CAF50",
            primaryDarkColor = "#388E3C",
            accentColor = "#8BC34A"
        ),
        ColorScheme(
            name = "purple",
            displayName = "Royal Purple",
            primaryColor = "#9C27B0",
            primaryDarkColor = "#7B1FA2",
            accentColor = "#E040FB"
        ),
        ColorScheme(
            name = "orange",
            displayName = "Sunset Orange",
            primaryColor = "#FF9800",
            primaryDarkColor = "#F57C00",
            accentColor = "#FFB74D"
        ),
        ColorScheme(
            name = "pink",
            displayName = "Cotton Candy",
            primaryColor = "#E91E63",
            primaryDarkColor = "#C2185B",
            accentColor = "#FF4081"
        ),
        ColorScheme(
            name = "red",
            displayName = "Cherry Red",
            primaryColor = "#F44336",
            primaryDarkColor = "#D32F2F",
            accentColor = "#FF5252"
        )
    )

    fun getScheme(name: String): ColorScheme {
        return schemes.find { it.name == name } ?: schemes.first()
    }

    fun getAllSchemes(): List<ColorScheme> = schemes
}
