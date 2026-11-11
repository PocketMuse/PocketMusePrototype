package com.example.pocketmuseprototype.download

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import java.io.IOException
import kotlinx.coroutines.flow.Flow

internal enum class DownloadStatus {
    Queued, Downloading, Verifying, Downloaded, Failed, Cancelling, Deleting;

    val pending: Boolean
        get() = this == Queued || this == Downloading || this == Verifying
}

internal enum class DownloadError {
    AuthRequired, AuthUnavailable, Forbidden, NotFound, RateLimited, Server, Network,
    InvalidMetadata, InvalidLength, InvalidHash, InvalidCheckpoint,
    StorageUnavailable, NoSpace, FileWrite, MissingFile, Cleanup, Scheduling, Interrupted
}

internal class DownloadFailure(val code: DownloadError) : IOException()

@Entity(tableName = "models")
internal data class LibraryModel(
    @PrimaryKey val storageKey: String,
    val modelId: Long,
    val modelVersionId: Long,
    val fileId: Long,
    val modelName: String,
    val versionName: String,
    val fileName: String,
    val fileSize: Long,
    val sha256: String?,
    val thumbnailUrl: String?,
    val workId: String,
    val downloadStatus: DownloadStatus = DownloadStatus.Queued,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val errorCode: DownloadError? = null
)

internal interface LibraryStore {
    fun observe(): Flow<List<LibraryModel>>
    suspend fun all(): List<LibraryModel>
    suspend fun find(key: String): LibraryModel?
    suspend fun insert(model: LibraryModel)
    suspend fun update(model: LibraryModel)
    suspend fun delete(model: LibraryModel)
}

@Dao
internal interface LibraryDao : LibraryStore {
    @Query("SELECT * FROM models ORDER BY rowid DESC")
    override fun observe(): Flow<List<LibraryModel>>

    @Query("SELECT * FROM models ORDER BY rowid")
    override suspend fun all(): List<LibraryModel>

    @Query("SELECT * FROM models WHERE storageKey = :key")
    override suspend fun find(key: String): LibraryModel?

    @Insert
    override suspend fun insert(model: LibraryModel)

    @Update
    override suspend fun update(model: LibraryModel)

    @Delete
    override suspend fun delete(model: LibraryModel)
}

@Database(entities = [LibraryModel::class], version = 1)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun models(): LibraryDao

    companion object {
        const val NAME = "model-library.db"
    }
}
