package com.example.pocketmuseprototype.catalog

import java.time.Instant
import java.time.format.DateTimeParseException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** File metadata for a catalog candidate. Names, URLs and hashes are remote, unverified data. */
internal data class CatalogCheckpoint(
    val modelId: Long,
    val modelName: String,
    val modelVersionId: Long,
    val versionName: String,
    val fileId: Long,
    val fileName: String,
    val sizeKB: Double,
    val downloadUrl: String,
    val sha256: String?,
    val thumbnailUrl: String?
)

internal fun ModelDto.selectCheckpoint(): CatalogCheckpoint? {
    if (type != "Checkpoint" || availability != "Public" || mode != null) return null
    val modelId = id?.takeIf { it > 0 } ?: return null
    val modelName = name?.takeIf { it.isNotBlank() } ?: return null
    val version = modelVersions.orEmpty().filter { it.baseModel == "SD 1.5" }
        .map { it to (it.publishedAt.toInstantOrNull() ?: return null) }
        .maxWithOrNull(compareBy({ it.second }, { it.first.id }))
        ?.first ?: return null
    val versionId = version.id?.takeIf { it > 0 } ?: return null
    val versionName = version.name?.takeIf { it.isNotBlank() } ?: return null

    val thumbnailUrl = version.images.orEmpty().firstNotNullOfOrNull { image ->
        image.url?.takeIf { image.type == "image" && it.isHttpsUrl() }
    }
    return version.files.orEmpty().mapNotNull { file ->
        file.toCandidate(modelId, modelName, versionId, versionName, thumbnailUrl)
            ?.let { candidate -> file to candidate }
    }.minWithOrNull(
        compareBy({ it.first.metadata?.fp != "fp16" }, { !it.first.primary }, { it.second.fileId })
    )?.second
}

private fun FileDto.toCandidate(
    modelId: Long,
    modelName: String,
    versionId: Long,
    versionName: String,
    thumbnailUrl: String?
): CatalogCheckpoint? {
    if (type != "Model" || metadata?.format != "SafeTensor" || metadata.size != "pruned") return null
    if (metadata.fp != "fp16" && metadata.fp != "fp32") return null
    val fileId = id?.takeIf { it > 0 } ?: return null
    val fileName = name?.takeIf { it.endsWith(".safetensors", ignoreCase = true) } ?: return null
    val fileSize = sizeKB?.takeIf { it.isFinite() && it > 0 } ?: return null
    val url = downloadUrl?.takeIf { it.isHttpsUrl() } ?: return null
    return CatalogCheckpoint(
        modelId, modelName, versionId, versionName, fileId, fileName, fileSize, url,
        hashes?.sha256, thumbnailUrl
    )
}

private fun String?.toInstantOrNull(): Instant? = try {
    this?.let(Instant::parse)
} catch (_: DateTimeParseException) {
    null
}

private fun String.isHttpsUrl(): Boolean = toHttpUrlOrNull()?.let {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty()
} == true
