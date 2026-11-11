package com.example.pocketmuseprototype.conversion

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

// The native rename can succeed just before the Converter removes this marker.
private const val OWNERSHIP_MARKER = ".sd15-converter.json"

internal fun outputAttributes(
    directory: File,
    expected: Map<String, Long>
): Map<String, BasicFileAttributes>? {
    if (expected.isEmpty() || !Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return null
    val entries = Files.newDirectoryStream(directory.toPath()).use { paths ->
        paths.associate { it.fileName.toString() to Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
    }
    if (entries.values.any { !it.isRegularFile }) return null
    val outputs = entries - OWNERSHIP_MARKER
    if (outputs.mapValues { it.value.size() } != expected) return null
    return outputs
}
