package app.marlboroadvance.mpvex.database.dao

import androidx.annotation.Keep
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity

@Keep // <-- YEH R8 COMPILER KO CRASH KARNE SE ROKEGA
@Dao
interface PlaybackStateDao {
  @Upsert
  suspend fun upsert(playbackStateEntity: PlaybackStateEntity)

  @Query("SELECT * FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle LIMIT 1")
  suspend fun getVideoDataByTitle(mediaTitle: String): PlaybackStateEntity?

  @Query("DELETE FROM PlaybackStateEntity")
  suspend fun clearAllPlaybackStates()

  @Query("DELETE FROM PlaybackStateEntity WHERE mediaTitle = :mediaTitle")
  suspend fun deleteByTitle(mediaTitle: String)

  @Query(
    """
    UPDATE PlaybackStateEntity 
    SET mediaTitle = :newTitle 
    WHERE mediaTitle = :oldTitle
  """,
  )
  suspend fun updateMediaTitle(
    oldTitle: String,
    newTitle: String,
  )

  @Query("SELECT * FROM PlaybackStateEntity")
  suspend fun getAllPlaybackStates(): List<PlaybackStateEntity>

  @Upsert
  suspend fun upsertAll(playbackStates: List<PlaybackStateEntity>)
}
