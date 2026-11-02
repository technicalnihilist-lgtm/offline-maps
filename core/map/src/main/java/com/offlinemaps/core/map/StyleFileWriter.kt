package com.offlinemaps.core.map

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes the generated map style to a file and hands back a `file://` URL.
 *
 * MapLibre's offline region API needs a *style URL*, not a style object: it fetches the style and
 * then every source it references. Because this app generates its style in code (so light/dark, 3D
 * and terrain toggles need no separate asset files), the style has to be materialised on disk first.
 * Writing it into app storage and passing `file://…` is what makes offline tile downloads work with a
 * generated style.
 *
 * The style is rebuilt from the same options every time the map opens or an offline region is
 * registered, and [MapStyleFactory] returns the identical document for identical options. Rewriting
 * the file in that case only dirties a page and costs a write on the caller's thread, so the content
 * is hashed first and an unchanged file is left alone.
 */
class StyleFileWriter(private val context: Context) {

    /** Directory MapLibre reads the offline style from. Cleared on demand. */
    fun styleDirectory(): File = File(context.filesDir, "offline-style").apply { mkdirs() }

    /** Writes [options] to disk and returns the absolute path. */
    fun write(options: StyleOptions): File {
        val file = File(styleDirectory(), "style-${options.theme.name.lowercase()}.json")
        val bytes = MapStyleFactory.buildJson(options).toByteArray(Charsets.UTF_8)
        val digest = digestOf(bytes)
        if (isAlreadyWritten(file, digest, bytes.size.toLong())) {
            skips.incrementAndGet()
            return file
        }
        file.writeBytes(bytes)
        writes.incrementAndGet()
        synchronized(written) { written[file.absolutePath] = Written(digest, bytes.size.toLong()) }
        return file
    }

    /** Same, as a `file://` URL that MapLibre's offline downloader accepts. */
    fun writeUrl(options: StyleOptions): String = "file://" + write(options).absolutePath

    fun clear() {
        val directory = styleDirectory()
        synchronized(written) { written.keys.removeAll { it.startsWith(directory.absolutePath) } }
        directory.deleteRecursively()
    }

    /**
     * True when [file] already holds exactly these [bytes].
     *
     * The in-memory record answers the common case (the same process opening the map again) without
     * touching the file at all beyond a stat for the size. A missing record - a fresh process, or a
     * file this class did not write - falls back to hashing what is on disk, which is still far
     * cheaper than rewriting it.
     */
    private fun isAlreadyWritten(file: File, digest: String, length: Long): Boolean {
        if (!file.isFile) return false
        if (file.length() != length) return false
        val known = synchronized(written) { written[file.absolutePath] }
        if (known != null) return known.digest == digest && known.length == length
        val onDisk = digestOf(file.readBytes())
        if (onDisk != digest) return false
        synchronized(written) { written[file.absolutePath] = Written(digest, length) }
        return true
    }

    private data class Written(val digest: String, val length: Long)

    private companion object {
        /** Last content written per absolute path, so a repeat write needs no read. */
        val written = HashMap<String, Written>()
        val writes = AtomicLong()
        val skips = AtomicLong()

        fun digestOf(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Files actually written since the last [resetWriteStats]. Diagnostics only. */
    internal fun writeCount(): Long = writes.get()

    /** Writes skipped because the file already matched. Diagnostics only. */
    internal fun skippedWriteCount(): Long = skips.get()

    /** Forgets the write counters and the in-memory records. Only tests need this. */
    internal fun resetWriteStats() {
        synchronized(written) { written.clear() }
        writes.set(0)
        skips.set(0)
    }
}
