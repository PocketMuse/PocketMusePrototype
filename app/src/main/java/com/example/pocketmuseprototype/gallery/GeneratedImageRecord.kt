package com.example.pocketmuseprototype.gallery

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "generated_images")
internal data class GeneratedImageRecord(
    @PrimaryKey val id: String,
    val fileName: String,
    val createdAt: Long,
    val modelName: String,
    val prompt: String,
    val steps: Int,
)

@Dao
internal interface GeneratedImageDao {
    @Query("SELECT * FROM generated_images ORDER BY createdAt DESC, rowid DESC")
    fun observeAll(): Flow<List<GeneratedImageRecord>>

    @Query("SELECT * FROM generated_images")
    suspend fun all(): List<GeneratedImageRecord>

    @Query("SELECT * FROM generated_images WHERE id = :id")
    suspend fun find(id: String): GeneratedImageRecord?

    @Insert
    suspend fun insert(record: GeneratedImageRecord)

    @Query("DELETE FROM generated_images WHERE id = :id")
    suspend fun delete(id: String)
}
