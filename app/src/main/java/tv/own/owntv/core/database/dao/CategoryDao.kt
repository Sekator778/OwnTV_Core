package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.model.MediaType

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(categories: List<CategoryEntity>): List<Long>

    @Query("SELECT * FROM categories WHERE sourceId IN (:sourceIds) AND mediaType = :type ORDER BY sortOrder ASC, name ASC")
    fun observe(sourceIds: List<Long>, type: MediaType): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories WHERE sourceId IN (:sourceIds) AND mediaType = :type")
    fun count(sourceIds: List<Long>, type: MediaType): Flow<Int>

    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND mediaType = :type")
    suspend fun clear(sourceId: Long, type: MediaType)
}
