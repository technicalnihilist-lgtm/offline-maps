package com.offlinemaps.core.offline

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipFile

/**
 * Streaming zip extraction with one fixed-size buffer.
 *
 * Routing graph packages are the largest thing the app ever unpacks - a country-sized GraphHopper
 * graph is a few hundred files adding up to hundreds of megabytes. Nothing here is ever sized from
 * the package: a single [DEFAULT_BUFFER_SIZE] array is allocated once per extraction and reused for
 * every entry, so peak allocation stays constant (~64 KiB) however big the archive or its biggest
 * member is. `ZipEntry.size` is deliberately not trusted - it is attacker-controlled metadata and
 * says nothing about how much the entry actually inflates to.
 */
object ZipExtractor {

    /**
     * Chunk size for the copy loop: 64 KiB is large enough to keep the number of read/write calls
     * low on a graph with hundreds of small files, and small enough to be irrelevant to the heap.
     */
    const val DEFAULT_BUFFER_SIZE = 64 * 1024

    /**
     * Extracts [zip] into [destination], creating it if needed.
     *
     * Entries whose path would land outside [destination] (via `..` or an absolute path) are
     * rejected with a [SecurityException] before anything is written for them.
     */
    fun extract(zip: File, destination: File, bufferSize: Int = DEFAULT_BUFFER_SIZE) {
        require(bufferSize > 0) { "bufferSize must be positive, was $bufferSize" }
        destination.mkdirs()
        val root = destination.canonicalFile
        val rootPrefix = root.path + File.separator
        // Allocated once, reused for every entry: the whole point of this class.
        val buffer = ByteArray(bufferSize)
        ZipFile(zip).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = File(root, entry.name)
                val canonical = target.canonicalPath
                if (canonical != root.path && !canonical.startsWith(rootPrefix)) {
                    throw SecurityException("Unsafe path in routing package: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> copy(input, output, buffer) }
                    }
                }
            }
        }
    }

    /**
     * Copies [input] to [output] through [buffer], returning the byte count. Exposed for tests so
     * the streaming contract can be exercised without a zip: no read ever asks for more than
     * `buffer.size` bytes, and no array is allocated per call.
     */
    internal fun copy(input: InputStream, output: OutputStream, buffer: ByteArray): Long {
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }
}
