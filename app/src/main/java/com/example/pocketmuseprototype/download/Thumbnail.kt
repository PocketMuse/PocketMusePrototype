package com.example.pocketmuseprototype.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.IOException

internal fun writeWebpThumbnail(bytes: ByteArray, target: File) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outMimeType !in supportedImages || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw IOException()
    }
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_THUMBNAIL_SIDE) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException()
    @Suppress("DEPRECATION")
    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    try {
        target.outputStream().use { if (!bitmap.compress(format, 82, it)) throw IOException() }
    } finally {
        bitmap.recycle()
    }
}

private val supportedImages = setOf("image/jpeg", "image/png", "image/webp")
private const val MAX_THUMBNAIL_SIDE = 384
