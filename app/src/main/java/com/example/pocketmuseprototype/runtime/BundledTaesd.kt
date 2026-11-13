package com.example.pocketmuseprototype.runtime

import android.content.Context
import com.example.pocketmuseprototype.conversion.sha256
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object BundledTaesd {
    private const val DIRECTORY = "taesd"
    private const val MODEL = "taesd.mnn"
    private const val SIZE = 4_901_708L
    private const val SHA256 = "7f2f997fd230d9d752e741c71e79f6595384df7add2eaa8697ef11376cd0d808"
    private val installLock = Mutex()

    /**
     * Returns the verified, absolute ASCII file at `noBackupFilesDir/taesd/taesd.mnn`.
     * The app owns the file. Callers keep it intact while Runtime uses it.
     * Installation runs on an I/O worker and serializes calls in the app process.
     * Cancellation can skip a queued call, and a copy already in progress can finish.
     * Throws [IOException] if the path, installation or integrity check fails.
     */
    suspend fun prepare(context: Context): File = withContext(Dispatchers.IO) {
        installLock.withLock {
            val directory = context.noBackupFilesDir.resolve(DIRECTORY)
            val target = directory.resolve(MODEL)
            if (!target.isAbsolute || target.path.any { it !in ' '..'~' }) {
                throw IOException("TAESD requires an absolute ASCII path")
            }
            Files.createDirectories(directory.toPath())
            val staging = directory.resolve("$MODEL.partial")
            Files.deleteIfExists(staging.toPath())
            if (target.isVerified()) return@withLock target

            try {
                context.assets.open("$DIRECTORY/$MODEL").use { input ->
                    Files.copy(input, staging.toPath())
                }
                if (!staging.isVerified()) throw IOException("Bundled TAESD size or SHA-256 mismatch")
                Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(staging.toPath())
            }
            target
        }
    }

    private fun File.isVerified(): Boolean =
        Files.isRegularFile(toPath(), NOFOLLOW_LINKS) && length() == SIZE && sha256() == SHA256
}
