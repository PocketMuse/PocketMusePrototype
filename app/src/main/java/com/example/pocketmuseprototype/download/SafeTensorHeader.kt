package com.example.pocketmuseprototype.download

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun File.hasCheckpointHeader(): Boolean = RandomAccessFile(this, "r").use { file ->
    if (file.length() <= Long.SIZE_BYTES) return false
    val headerSize = java.lang.Long.reverseBytes(file.readLong())
    if (headerSize !in 2..MAX_HEADER_BYTES || headerSize >= file.length() - Long.SIZE_BYTES) return false
    val header = ByteArray(headerSize.toInt()).also(file::readFully)
    if (header[0] != '{'.code.toByte()) return false
    val entries = try {
        val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(header))
        Json.parseToJsonElement(json.toString()) as? JsonObject ?: return false
    } catch (_: java.nio.charset.CharacterCodingException) {
        return false
    } catch (_: SerializationException) {
        return false
    }
    val ranges = entries.mapNotNull { (name, element) ->
        val tensor = element as? JsonObject ?: return false
        if (name == "__metadata__") {
            if (tensor.values.any { (it as? JsonPrimitive)?.isString != true }) return false
            return@mapNotNull null
        }
        val dtype = tensor["dtype"] as? JsonPrimitive ?: return false
        val width = dtype.takeIf { it.isString }?.content?.let(dtypeBytes::get) ?: return false
        val shape = tensor["shape"] as? JsonArray ?: return false
        val dimensions = shape.map { (it as? JsonPrimitive)?.unsignedLong() ?: return false }
        val tensorBytes = if (0L in dimensions) 0L else {
            dimensions.fold(width) { bytes, dimension ->
                if (bytes > Long.MAX_VALUE / dimension) return false
                bytes * dimension
            }
        }
        val offsets = tensor["data_offsets"] as? JsonArray ?: return false
        if (offsets.size != 2) return false
        val start = (offsets[0] as? JsonPrimitive)?.unsignedLong() ?: return false
        val end = (offsets[1] as? JsonPrimitive)?.unsignedLong() ?: return false
        if (start > end || end - start != tensorBytes) return false
        start to end
    }.sortedWith(compareBy({ it.first }, { it.second }))
    var offset = 0L
    for ((start, end) in ranges) {
        if (start != offset) return false
        offset = end
    }
    offset == file.length() - Long.SIZE_BYTES - headerSize
}

private fun JsonPrimitive.unsignedLong(): Long? = longOrNull?.takeIf { !isString && it >= 0 }

private val dtypeBytes = mapOf(
    "BOOL" to 1L, "U8" to 1L, "I8" to 1L, "F8_E5M2" to 1L, "F8_E4M3" to 1L,
    "I16" to 2L, "U16" to 2L, "F16" to 2L, "BF16" to 2L,
    "I32" to 4L, "U32" to 4L, "F32" to 4L,
    "I64" to 8L, "U64" to 8L, "F64" to 8L
)

// Keep header parsing bounded on devices. Catalog candidates are pruned SD 1.5 checkpoints.
private const val MAX_HEADER_BYTES = 4L * 1024 * 1024
