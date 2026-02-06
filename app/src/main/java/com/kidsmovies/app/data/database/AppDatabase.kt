package com.kidsmovies.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kidsmovies.app.data.database.dao.*
import com.kidsmovies.app.data.database.entities.*
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
        VideoCollectionCrossRef::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao
    abstract fun tagDao(): TagDao
    abstract fun scanFolderDao(): ScanFolderDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun parentalControlDao(): ParentalControlDao
    abstract fun collectionDao(): CollectionDao

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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
