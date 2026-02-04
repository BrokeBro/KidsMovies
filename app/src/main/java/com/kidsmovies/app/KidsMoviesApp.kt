package com.kidsmovies.app

import android.app.Application
import com.kidsmovies.app.data.database.AppDatabase
import com.kidsmovies.app.data.repository.*

class KidsMoviesApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val videoRepository: VideoRepository by lazy {
        VideoRepository(database.videoDao(), database.tagDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(database.appSettingsDao(), database.scanFolderDao())
    }

    val parentalControlRepository: ParentalControlRepository by lazy {
        ParentalControlRepository(database.parentalControlDao())
    }

    val tagRepository: TagRepository by lazy {
        TagRepository(database.tagDao())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: KidsMoviesApp
            private set
    }
}
