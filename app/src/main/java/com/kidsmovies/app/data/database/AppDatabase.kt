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
        VideoCollection::class
    ],
    version = 2,
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigration(MIGRATION_1_2)
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
