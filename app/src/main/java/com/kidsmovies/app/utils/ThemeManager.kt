package com.kidsmovies.app.utils

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.kidsmovies.app.KidsMoviesApp
import com.kidsmovies.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages dynamic color scheme application throughout the app
 */
object ThemeManager {

    private var cachedScheme: ColorSchemes.ColorScheme? = null
    private var cachedSchemeName: String? = null

    /**
     * Apply the current color scheme to an activity.
     * Call this in onCreate after setContentView or in onResume.
     */
    suspend fun applyTheme(activity: Activity) {
        val app = activity.application as? KidsMoviesApp ?: return
        val settings = withContext(Dispatchers.IO) {
            app.settingsRepository.getSettings()
        }
        val schemeName = settings?.colorScheme ?: "blue"
        val scheme = ColorSchemes.getScheme(schemeName)

        // Cache the scheme
        cachedScheme = scheme
        cachedSchemeName = schemeName

        withContext(Dispatchers.Main) {
            applySchemeToActivity(activity, scheme)
        }
    }

    /**
     * Apply scheme synchronously using cached value (for quick UI updates)
     */
    fun applyThemeSync(activity: Activity) {
        val scheme = cachedScheme ?: ColorSchemes.getScheme("blue")
        applySchemeToActivity(activity, scheme)
    }

    /**
     * Get the current cached color scheme
     */
    fun getCurrentScheme(): ColorSchemes.ColorScheme {
        return cachedScheme ?: ColorSchemes.getScheme("blue")
    }

    /**
     * Clear cached scheme (call when scheme changes)
     */
    fun clearCache() {
        cachedScheme = null
        cachedSchemeName = null
    }

    private fun applySchemeToActivity(activity: Activity, scheme: ColorSchemes.ColorScheme) {
        val primaryColor = Color.parseColor(scheme.primaryColor)
        val primaryDarkColor = Color.parseColor(scheme.primaryDarkColor)
        val accentColor = Color.parseColor(scheme.accentColor)
        val backgroundColor = Color.parseColor(scheme.backgroundColor)
        val cardColor = Color.parseColor(scheme.cardColor)

        // Apply to window
        activity.window?.apply {
            statusBarColor = primaryDarkColor
            navigationBarColor = backgroundColor

            // Set light status bar icons if primary is light
            val isLightColor = ColorUtils.calculateLuminance(primaryDarkColor) > 0.5
            decorView.systemUiVisibility = if (isLightColor) {
                decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }

        // Apply to root view background
        val rootView = activity.findViewById<View>(android.R.id.content)
        rootView?.setBackgroundColor(backgroundColor)

        // Apply to all views recursively
        applyToViewHierarchy(rootView, scheme)
    }

    private fun applyToViewHierarchy(view: View?, scheme: ColorSchemes.ColorScheme) {
        if (view == null) return

        val primaryColor = Color.parseColor(scheme.primaryColor)
        val primaryDarkColor = Color.parseColor(scheme.primaryDarkColor)
        val accentColor = Color.parseColor(scheme.accentColor)
        val backgroundColor = Color.parseColor(scheme.backgroundColor)
        val cardColor = Color.parseColor(scheme.cardColor)

        when (view) {
            is TabLayout -> {
                view.setSelectedTabIndicatorColor(primaryColor)
                view.setTabTextColors(
                    ContextCompat.getColor(view.context, R.color.text_secondary),
                    primaryColor
                )
                view.tabIconTint = android.content.res.ColorStateList.valueOf(primaryColor)
                view.setBackgroundColor(backgroundColor)
            }

            is MaterialButton -> {
                if (view.backgroundTintList != null) {
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
                }
            }

            is FloatingActionButton -> {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            }

            is MaterialCardView -> {
                view.setCardBackgroundColor(cardColor)
            }

            is TextInputLayout -> {
                view.boxStrokeColor = primaryColor
                view.hintTextColor = android.content.res.ColorStateList.valueOf(primaryColor)
                view.setBoxBackgroundColorResource(android.R.color.transparent)
            }

            is AppBarLayout -> {
                view.setBackgroundColor(backgroundColor)
            }

            is ProgressBar -> {
                view.indeterminateTintList = android.content.res.ColorStateList.valueOf(primaryColor)
            }
        }

        // Check for specific view IDs that need theming
        when (view.id) {
            R.id.loadingOverlay -> {
                // Keep overlay as is
            }
        }

        // Handle background colors for specific backgrounds
        val background = view.background
        if (background is ColorDrawable) {
            val currentColor = background.color
            // Replace background_dark with scheme background
            if (currentColor == Color.parseColor("#1A1A2E") ||
                currentColor == ContextCompat.getColor(view.context, R.color.background_dark)) {
                view.setBackgroundColor(backgroundColor)
            }
            // Replace card background with scheme card color
            if (currentColor == Color.parseColor("#16213E") ||
                currentColor == ContextCompat.getColor(view.context, R.color.background_card)) {
                view.setBackgroundColor(cardColor)
            }
        }

        // Recursively apply to children
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyToViewHierarchy(view.getChildAt(i), scheme)
            }
        }
    }

    /**
     * Create a color state list for tab icons
     */
    fun createTabIconColorStateList(context: Context, scheme: ColorSchemes.ColorScheme): android.content.res.ColorStateList {
        val primaryColor = Color.parseColor(scheme.primaryColor)
        val inactiveColor = ContextCompat.getColor(context, R.color.text_secondary)

        val states = arrayOf(
            intArrayOf(android.R.attr.state_selected),
            intArrayOf()
        )
        val colors = intArrayOf(primaryColor, inactiveColor)
        return android.content.res.ColorStateList(states, colors)
    }

    /**
     * Get primary color int from current scheme
     */
    fun getPrimaryColor(): Int {
        return Color.parseColor(getCurrentScheme().primaryColor)
    }

    /**
     * Get accent color int from current scheme
     */
    fun getAccentColor(): Int {
        return Color.parseColor(getCurrentScheme().accentColor)
    }

    /**
     * Get background color int from current scheme
     */
    fun getBackgroundColor(): Int {
        return Color.parseColor(getCurrentScheme().backgroundColor)
    }

    /**
     * Get card color int from current scheme
     */
    fun getCardColor(): Int {
        return Color.parseColor(getCurrentScheme().cardColor)
    }
}
