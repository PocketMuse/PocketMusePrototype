package com.example.pocketmuseprototype.catalog

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

internal const val CATALOG_BATCH_SIZE = 8
private val modelsJson = Json { ignoreUnknownKeys = true }

internal class CivitaiApi(
    client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) {
    private val service = Retrofit.Builder()
        .baseUrl("https://civitai.com/")
        .client(client)
        .addConverterFactory(modelsJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CivitaiService::class.java)

    suspend fun models(cursor: String?): ModelsResponse {
        val response = try {
            service.models(cursor)
        } catch (error: SerializationException) {
            throw IOException("Invalid Civitai models response", error)
        }
        response.errorBody()?.close()
        if (!response.isSuccessful) throw IOException("Civitai HTTP ${response.code()}")
        return response.body() ?: throw IOException("Missing Civitai models response")
    }
}

private interface CivitaiService {
    @GET(
        "api/v1/models?types=Checkpoint&baseModels=SD%201.5&limit=$CATALOG_BATCH_SIZE" +
            "&sort=Most%20Downloaded&nsfw=false&primaryFileOnly=false"
    )
    suspend fun models(@Query("cursor") cursor: String?): Response<ModelsResponse>
}

@Serializable
internal data class ModelsResponse(val items: List<ModelDto>, val metadata: PageMetadata)

@Serializable
internal data class PageMetadata(val nextCursor: String? = null)

@Serializable
internal data class ModelDto(
    val id: Long? = null,
    val name: String? = null,
    val type: String? = null,
    val availability: String? = null,
    val mode: String? = null,
    val modelVersions: List<VersionDto>? = null
)

@Serializable
internal data class VersionDto(
    val id: Long? = null,
    val name: String? = null,
    val baseModel: String? = null,
    val baseModelType: String? = null,
    val publishedAt: String? = null,
    val files: List<FileDto>? = null,
    val images: List<ImageDto>? = null
)

@Serializable
internal data class FileDto(
    val id: Long? = null,
    val name: String? = null,
    val type: String? = null,
    val sizeKB: Double? = null,
    val downloadUrl: String? = null,
    val primary: Boolean = false,
    val metadata: FileMetadata? = null,
    val hashes: FileHashes? = null
)

@Serializable
internal data class FileMetadata(val format: String? = null, val size: String? = null, val fp: String? = null)

@Serializable
internal data class FileHashes(@SerialName("SHA256") val sha256: String? = null)

@Serializable
internal data class ImageDto(val url: String? = null, val type: String? = null)
