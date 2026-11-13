package com.example.pocketmuseprototype.runtime

import android.content.Context
import com.example.pocketmuseprototype.conversion.sha256
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object BundledClipTokenizer {
    private const val ASSET_DIRECTORY = "sd15-tokenizer"
    private val hashes = mapOf(
        "vocab.json" to "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349",
        "merges.txt" to "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a",
    )

    /** Returns an app-private directory only after both tokenizer files pass SHA-256 validation. */
    suspend fun prepare(context: Context, directory: File = context.noBackupFilesDir.resolve(ASSET_DIRECTORY)): File =
        withContext(Dispatchers.IO) {
            synchronized(this@BundledClipTokenizer) {
                Files.createDirectories(directory.toPath())
                for ((name, hash) in hashes) {
                    val target = directory.resolve(name)
                    val staging = directory.resolve("$name.partial")
                    Files.deleteIfExists(staging.toPath())
                    if (target.isFile && target.sha256() == hash) continue

                    try {
                        context.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                            staging.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (staging.sha256() != hash) throw IOException("Bundled tokenizer SHA-256 mismatch: $name")
                        Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(staging.toPath())
                    }
                }
                directory
            }
        }
}
