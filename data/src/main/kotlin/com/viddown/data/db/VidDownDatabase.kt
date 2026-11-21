package com.viddown.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 (nothing has been released yet, so tables are still added to version 1; from the first release on every change needs a
 * migration and the exported schema files under data/schemas are committed). Bookmarks joined in Phase 5 (still version 1: nothing has been released).
 */
@Database(entities = [DownloadEntity::class, PageVisitEntity::class, BookmarkEntity::class], version = 2, exportSchema = true)
abstract class VidDownDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun pageVisitDao(): PageVisitDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val FILE_NAME = "viddown.db"

        fun create(context: Context): VidDownDatabase =
            Room.databaseBuilder(context.applicationContext, VidDownDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Version 2: the subtitle language a download was asked to save next to the video. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN subtitleLang TEXT")
            }
        }
    }
}
