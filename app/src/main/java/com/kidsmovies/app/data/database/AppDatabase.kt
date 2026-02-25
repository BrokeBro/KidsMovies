package com.kidsmovies.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kidsmovies.app.data.database.dao.*
import com.kidsmovies.app.data.database.entities.*
import com.kidsmovies.app.pairing.PairingDao
import com.kidsmovies.app.pairing.PairingState
import com.kidsmovies.app.sync.CachedDeviceOverrides
import com.kidsmovies.app.sync.CachedGlobalSettings
import com.kidsmovies.app.sync.CachedSchedule
import com.kidsmovies.app.sync.CachedSettingsDao
import com.kidsmovies.app.sync.ScheduleConverters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        Video::class,
        Tag::class,
        VideoTagCrossRef::class,
        ScanFolder::class,
        AppSettings::class,
        ParentalControl::class,
        VideoCollection::class,
        VideoCollectionCrossRef::class,
        ViewingSession::class,
        PairingState::class,
        CachedGlobalSettings::class,
        CachedDeviceOverrides::class,
        CachedSchedule::class
    ],
    version = 13,
    exportSchema = false
)
@TypeConverters(ScheduleConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao
    abstract fun tagDao(): TagDao
    abstract fun scanFolderDao(): ScanFolderDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun parentalControlDao(): ParentalControlDao
    abstract fun collectionDao(): CollectionDao
    abstract fun viewingSessionDao(): ViewingSessionDao
    abstract fun pairingDao(): PairingDao
    abstract fun cachedSettingsDao(): CachedSettingsDao

    companion object {
        private const val DATABASE_NAME = "kids_movies_database"

        // Migration from version 1 to 2: Add playbackPosition, collectionId to videos and collections table
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add new columns to videos table
                db.execSQL("ALTER TABLE videos ADD COLUMN playbackPosition INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE videos ADD COLUMN collectionId INTEGER DEFAULT NULL")

                // Create collections table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        sortOrder INTEGER NOT NULL DEFAULT 0,
                        dateCreated INTEGER NOT NULL DEFAULT 0,
                        dateModified INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // Migration from version 2 to 3: Add video_collection_cross_ref table for many-to-many relationship
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create video_collection_cross_ref table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS video_collection_cross_ref (
                        videoId INTEGER NOT NULL,
                        collectionId INTEGER NOT NULL,
                        PRIMARY KEY(videoId, collectionId),
                        FOREIGN KEY(videoId) REFERENCES videos(id) ON DELETE CASCADE,
                        FOREIGN KEY(collectionId) REFERENCES collections(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_video_collection_cross_ref_videoId ON video_collection_cross_ref(videoId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_video_collection_cross_ref_collectionId ON video_collection_cross_ref(collectionId)")
            }
        }

        // Migration from version 3 to 4: Add navigation tab visibility settings
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add navigation tab visibility columns to app_settings
                db.execSQL("ALTER TABLE app_settings ADD COLUMN showAllMoviesTab INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN showFavouritesTab INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN showCollectionsTab INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN showRecentTab INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN showOnlineTab INTEGER NOT NULL DEFAULT 1")
            }
        }

        // Migration from version 4 to 5: Add thumbnailPath to collections
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections ADD COLUMN thumbnailPath TEXT DEFAULT NULL")
            }
        }

        // Migration from version 5 to 6: Add viewing_sessions table for metrics
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS viewing_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        videoId INTEGER NOT NULL,
                        collectionId INTEGER DEFAULT NULL,
                        videoTitle TEXT NOT NULL,
                        collectionName TEXT DEFAULT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER DEFAULT NULL,
                        durationWatched INTEGER NOT NULL DEFAULT 0,
                        videoDuration INTEGER NOT NULL DEFAULT 0,
                        completed INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(videoId) REFERENCES videos(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_viewing_sessions_videoId ON viewing_sessions(videoId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_viewing_sessions_startTime ON viewing_sessions(startTime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_viewing_sessions_collectionId ON viewing_sessions(collectionId)")
            }
        }

        // Migration from version 6 to 7: Add tabOrder to app_settings
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN tabOrder TEXT NOT NULL DEFAULT 'all_movies,favourites,collections,recent,online'")
            }
        }

        // Migration from version 7 to 8: Add parental control sync tables
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create pairing_state table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS pairing_state (
                        id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                        familyId TEXT DEFAULT NULL,
                        childUid TEXT DEFAULT NULL,
                        parentUid TEXT DEFAULT NULL,
                        deviceName TEXT NOT NULL DEFAULT 'Kid''s Device',
                        pairedAt INTEGER DEFAULT NULL,
                        isPaired INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // Create cached_global_settings table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_global_settings (
                        id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        appEnabled INTEGER NOT NULL DEFAULT 1,
                        softOffEnabled INTEGER NOT NULL DEFAULT 1,
                        lastSyncedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // Create cached_device_overrides table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_device_overrides (
                        id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                        appEnabled INTEGER NOT NULL DEFAULT 1,
                        maxViewingMinutesOverride INTEGER DEFAULT NULL,
                        allowedCollectionsJson TEXT DEFAULT NULL,
                        isRevoked INTEGER NOT NULL DEFAULT 0,
                        lastSyncedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)

                // Create cached_schedules table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_schedules (
                        scheduleId TEXT PRIMARY KEY NOT NULL,
                        label TEXT NOT NULL DEFAULT '',
                        daysOfWeek TEXT DEFAULT NULL,
                        startTime TEXT NOT NULL DEFAULT '00:00',
                        endTime TEXT NOT NULL DEFAULT '23:59',
                        maxViewingMinutes INTEGER DEFAULT NULL,
                        allowedCollectionsJson TEXT DEFAULT NULL,
                        blockedVideosJson TEXT DEFAULT NULL,
                        allowedVideosJson TEXT DEFAULT NULL,
                        appliesToDevicesJson TEXT DEFAULT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        lastSyncedAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // Migration from version 8 to 9: Add TMDB artwork support
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add TMDB artwork path to videos
                db.execSQL("ALTER TABLE videos ADD COLUMN tmdbArtworkPath TEXT DEFAULT NULL")

                // Add TMDB artwork path and parent collection to collections
                db.execSQL("ALTER TABLE collections ADD COLUMN tmdbArtworkPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE collections ADD COLUMN parentCollectionId INTEGER DEFAULT NULL")

                // Add TMDB settings to app_settings
                db.execSQL("ALTER TABLE app_settings ADD COLUMN tmdbApiKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN autoFetchArtwork INTEGER NOT NULL DEFAULT 1")
            }
        }

        // Migration from version 9 to 10: Add TV show/season support
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add collection type and season info to collections
                db.execSQL("ALTER TABLE collections ADD COLUMN collectionType TEXT NOT NULL DEFAULT 'REGULAR'")
                db.execSQL("ALTER TABLE collections ADD COLUMN seasonNumber INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE collections ADD COLUMN tmdbShowId INTEGER DEFAULT NULL")

                // Add episode info to videos
                db.execSQL("ALTER TABLE videos ADD COLUMN seasonNumber INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE videos ADD COLUMN episodeNumber INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE videos ADD COLUMN tmdbEpisodeId INTEGER DEFAULT NULL")
            }
        }

        // Migration from version 10 to 11: Add parental lock to collections
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add isEnabled field to collections for parental control
                db.execSQL("ALTER TABLE collections ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        // Migration from version 11 to 12: Add OneDrive/SharePoint streaming support
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN source_type TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE videos ADD COLUMN remote_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE videos ADD COLUMN remote_url TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE videos ADD COLUMN remote_url_expiry INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration from version 12 to 13: Add franchise collection support
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add TMDB collection ID to collections for franchise tracking
                db.execSQL("ALTER TABLE collections ADD COLUMN tmdbCollectionId INTEGER DEFAULT NULL")
                // Add TMDB movie ID to videos for franchise detection
                db.execSQL("ALTER TABLE videos ADD COLUMN tmdbMovieId INTEGER DEFAULT NULL")
                // Add auto-create franchise collections setting
                db.execSQL("ALTER TABLE app_settings ADD COLUMN autoCreateFranchiseCollections INTEGER NOT NULL DEFAULT 1")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getDatabasePath(context: Context): String {
            return context.getDatabasePath(DATABASE_NAME).absolutePath
        }
    }

    private class DatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    // Initialize default settings
                    database.appSettingsDao().insert(AppSettings())

                    // Initialize parental control with default values
                    database.parentalControlDao().insert(ParentalControl())

                    // Create default system tags
                    val systemTags = listOf(
                        Tag(
                            name = "Enabled",
                            color = "#4CAF50",
                            description = "Videos that are enabled for viewing",
                            isSystemTag = true
                        ),
                        Tag(
                            name = "Disabled",
                            color = "#F44336",
                            description = "Videos that are disabled by parent",
                            isSystemTag = true
                        ),
                        Tag(
                            name = "Educational",
                            color = "#2196F3",
                            description = "Educational content",
                            isSystemTag = true
                        ),
                        Tag(
                            name = "Entertainment",
                            color = "#FF9800",
                            description = "Entertainment content",
                            isSystemTag = true
                        )
                    )
                    database.tagDao().insertAll(systemTags)
                }
            }
        }
    }
}
