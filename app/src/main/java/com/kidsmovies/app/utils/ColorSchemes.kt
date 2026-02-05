package com.kidsmovies.app.utils

data class ColorScheme(
    val name: String,
    val displayName: String,
    val primaryColor: String,
    val primaryDarkColor: String,
    val accentColor: String,
    val backgroundColor: String,
    val cardColor: String
)

object ColorSchemes {

    private val schemes = listOf(
        ColorScheme(
            name = "blue",
            displayName = "Ocean Blue",
            primaryColor = "#2196F3",
            primaryDarkColor = "#1976D2",
            accentColor = "#03A9F4",
            backgroundColor = "#1A1A2E",
            cardColor = "#16213E"
        ),
        ColorScheme(
            name = "green",
            displayName = "Forest Green",
            primaryColor = "#4CAF50",
            primaryDarkColor = "#388E3C",
            accentColor = "#8BC34A",
            backgroundColor = "#1A2E1A",
            cardColor = "#213E21"
        ),
        ColorScheme(
            name = "purple",
            displayName = "Royal Purple",
            primaryColor = "#9C27B0",
            primaryDarkColor = "#7B1FA2",
            accentColor = "#E040FB",
            backgroundColor = "#2E1A2E",
            cardColor = "#3E2140"
        ),
        ColorScheme(
            name = "orange",
            displayName = "Sunset Orange",
            primaryColor = "#FF9800",
            primaryDarkColor = "#F57C00",
            accentColor = "#FFB74D",
            backgroundColor = "#2E2A1A",
            cardColor = "#3E3521"
        ),
        ColorScheme(
            name = "pink",
            displayName = "Candy Pink",
            primaryColor = "#E91E63",
            primaryDarkColor = "#C2185B",
            accentColor = "#FF4081",
            backgroundColor = "#2E1A24",
            cardColor = "#3E2130"
        ),
        ColorScheme(
            name = "red",
            displayName = "Ruby Red",
            primaryColor = "#F44336",
            primaryDarkColor = "#D32F2F",
            accentColor = "#FF5252",
            backgroundColor = "#2E1A1A",
            cardColor = "#3E2121"
        )
    )

    fun getScheme(name: String): ColorScheme {
        return schemes.find { it.name == name } ?: schemes[0]
    }

    fun getAllSchemes(): List<ColorScheme> = schemes
}
