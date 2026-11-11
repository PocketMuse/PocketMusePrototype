package com.example.pocketmuseprototype.conversion

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal data class HqqTemplate(val id: String, val directory: File, val files: Map<String, Long>)

internal class BundledHqqTemplate(private val context: Context) {
    fun load(): HqqTemplate {
        val root = File(context.filesDir, "conversion-templates")
        Files.createDirectories(root.toPath())
        val target = File(root, ID)
        if (!verified(target)) {
            val staging = File(root, "$ID.partial")
            removeFiles(staging)
            Files.createDirectory(staging.toPath())
            try {
                for (name in hashes.keys) {
                    context.assets.open("$ID/$name").use { input ->
                        File(staging, name).outputStream().use { input.copyTo(it) }
                    }
                }
                if (!verified(staging)) throw IOException("Bundled template SHA-256 mismatch")
                removeFiles(target)
                Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE)
            } finally {
                removeFiles(staging)
            }
        }
        val files = try {
            val manifest = Json.parseToJsonElement(File(target, "manifest.json").readText()).jsonObject
            if (manifest.getValue("unet_quantization").jsonPrimitive.content != "hqq-b128" ||
                manifest.getValue("template_size").jsonPrimitive.long != File(target, "graph.bin").length()
            ) throw IOException("Incompatible HQQ template")
            val entries = manifest.getValue("files").jsonArray
            entries.associate {
                val entry = it.jsonObject
                entry.getValue("name").jsonPrimitive.content to entry.getValue("size").jsonPrimitive.long
            }.also { files ->
                if (files.size != entries.size || !files.hasValidOutputEntries()) {
                    throw IOException("Invalid template outputs")
                }
            }
        } catch (invalid: SerializationException) {
            throw IOException("Invalid HQQ manifest", invalid)
        }
        return HqqTemplate(ID, target, files)
    }

    private fun verified(directory: File): Boolean =
        Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) && hashes.all { (name, hash) ->
            val file = File(directory, name)
            Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.sha256() == hash
        }

    private fun removeFiles(directory: File) {
        val path = directory.toPath()
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            for (name in hashes.keys) Files.deleteIfExists(path.resolve(name))
        }
        Files.deleteIfExists(path)
    }

    companion object {
        const val ID = "sd15-hqq-b128-manual-v1"
        private val hashes = mapOf(
            "graph.bin" to "ea5fe7499768394097d627beb2ce29ec2b96f10ab76c4bd9bdf1fc425a3933ed",
            "manifest.json" to "5ba4e454c0345ad5cb138af374d34af6b0199b41c7b176e5bb70387640f04b80"
        )
    }
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHexString()
}
