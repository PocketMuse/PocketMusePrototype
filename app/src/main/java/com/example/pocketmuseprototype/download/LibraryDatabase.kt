package com.example.pocketmuseprototype.download

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

internal enum class ModelConversionStatus {
    NotStarted, WaitingForLcm, Queued, Validating, Converting, Finalizing, Converted, Failed, Cancelling, Cancelled;

    val pending: Boolean
        get() = this == Queued || this == Validating || this == Converting || this == Finalizing

    val canCancel: Boolean
        get() = pending || this == WaitingForLcm

    val canStart: Boolean
        get() = this == NotStarted || this == Failed || this == Cancelled
}

internal const val LEGACY_HQQ_RECIPE = "sd15-hqq-b128-v1"
internal const val STATIC_LCM_RECIPE = "sd15-static-lcm-v1"

internal data class LegacyOutput(
    val workId: String,
    val templateId: String,
    val files: String,
    val hashes: String
)

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
    val errorCode: DownloadError? = null,
    @ColumnInfo(defaultValue = "'NotStarted'")
    val conversionStatus: ModelConversionStatus = ModelConversionStatus.NotStarted,
    val conversionWorkId: String? = null,
    val conversionProgress: Int? = null,
    val conversionErrorCode: String? = null,
    val conversionTemplateId: String? = null,
    val conversionFiles: String? = null,
    val conversionHashes: String? = null,
    val conversionRecipeId: String? = null,
    @Embedded(prefix = "legacyOutput_") val legacyOutput: LegacyOutput? = null
) {
    val canStartConversion: Boolean
        get() = conversionStatus.canStart ||
            (conversionStatus == ModelConversionStatus.Converted && conversionRecipeId == LEGACY_HQQ_RECIPE)
}

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

@Database(entities = [LibraryModel::class], version = 3)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun models(): LibraryDao

    companion object {
        const val NAME = "model-library.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN conversionStatus TEXT NOT NULL DEFAULT 'NotStarted'")
                db.execSQL("ALTER TABLE models ADD COLUMN conversionWorkId TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN conversionProgress INTEGER")
                db.execSQL("ALTER TABLE models ADD COLUMN conversionErrorCode TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN conversionTemplateId TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN conversionFiles TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN conversionHashes TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE models ADD COLUMN conversionRecipeId TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN legacyOutput_workId TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN legacyOutput_templateId TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN legacyOutput_files TEXT")
                db.execSQL("ALTER TABLE models ADD COLUMN legacyOutput_hashes TEXT")
                db.execSQL("UPDATE models SET conversionRecipeId = 'sd15-hqq-b128-v1' WHERE conversionWorkId IS NOT NULL")
            }
        }
    }
}
