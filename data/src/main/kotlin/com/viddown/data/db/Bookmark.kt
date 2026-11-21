package com.viddown.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A page the user saved. One row per address; saving the same address again does nothing. */
@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String?,
    val createdAtMillis: Long,
)

@Dao
interface BookmarkDao {
    /** Returns the new row id, or -1 when the address was already saved. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks ORDER BY createdAtMillis DESC, id DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE url = :url")
    fun observeCount(url: String): Flow<Int>

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String): Int

    @Query("DELETE FROM bookmarks")
    suspend fun clear(): Int
}
