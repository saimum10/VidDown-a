package com.viddown.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One visited page. Browsing history is separate from download history and is cleared separately. */
@Entity(tableName = "page_visits")
data class PageVisitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String?,
    val visitedAtMillis: Long,
)

@Dao
interface PageVisitDao {
    @Insert
    suspend fun insert(entity: PageVisitEntity)

    /** The latest visit of each distinct URL, newest first. */
    @Query(
        "SELECT * FROM page_visits WHERE id IN (SELECT MAX(id) FROM page_visits GROUP BY url) " +
            "ORDER BY visitedAtMillis DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<PageVisitEntity>>

    @Query("DELETE FROM page_visits")
    suspend fun clear(): Int

    /** Removes every visit of one address ("remove from history"). */
    @Query("DELETE FROM page_visits WHERE url = :url")
    suspend fun deleteByUrl(url: String): Int

    @Query("SELECT COUNT(*) FROM page_visits")
    suspend fun count(): Int
}
