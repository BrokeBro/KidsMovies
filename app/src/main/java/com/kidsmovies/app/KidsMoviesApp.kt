package com.kidsmovies.app

import android.app.Application
import com.kidsmovies.app.data.database.AppDatabase
import com.kidsmovies.app.data.repository.*

class KidsMoviesApp : Application() {

    companion object {
        lateinit var instance: KidsMoviesApp
            private set
    }

    val database by lazy { AppDatabase.getInstance(this) }

    val videoRepository by lazy { VideoRepository(database.videoDao()) }
    val tagRepository by lazy { TagRepository(database.tagDao()) }
    val settingsRepository by lazy { SettingsRepository(database.appSettingsDao(), database.scanFolderDao()) }
    val parentalControlRepository by lazy { ParentalControlRepository(database.parentalControlDao()) }
    val collectionRepository by lazy { CollectionRepository(database.collectionDao()) }
    val metricsRepository by lazy { MetricsRepository(database.viewingSessionDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
