package com.offlinemaps.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SavedPlaceEntity::class,
        RecentSearchEntity::class,
        DownloadEntity::class,
        InstalledRegionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun downloadDao(): DownloadDao
    abstract fun installedRegionDao(): InstalledRegionDao

    companion object {
        private const val NAME = "offline_maps.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }

        /** Used by tests to get an isolated in-memory database. */
        fun inMemory(context: Context): AppDatabase = Room.inMemoryDatabaseBuilder(
            context, AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }
}
