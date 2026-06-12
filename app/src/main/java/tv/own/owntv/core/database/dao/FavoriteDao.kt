package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.FavoriteEntity
import tv.own.owntv.core.model.MediaType

/** Write side of favorites; per-type favorite content lists live in the content DAOs (joins). */
@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId")
    suspend fun remove(profileId: Long, type: MediaType, itemId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE profileId = :profileId AND mediaType = :type AND itemId = :itemId)")
    fun isFavorite(profileId: Long, type: MediaType, itemId: Long): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM favorites WHERE profileId = :profileId AND mediaType = :type")
    fun count(profileId: Long, type: MediaType): Flow<Int>

    /** Favorite item ids for a type, so lists can mark rows without a per-row query. */
    @Query("SELECT itemId FROM favorites WHERE profileId = :profileId AND mediaType = :type")
    fun observeFavoriteIds(profileId: Long, type: MediaType): Flow<List<Long>>

    /** Everything, for Backup & Restore. */
    @Query("SELECT * FROM favorites")
    suspend fun getAllOnce(): List<FavoriteEntity>
}
