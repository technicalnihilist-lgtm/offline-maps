package com.offlinemaps.core.offline

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Covers the streaming contract of [ZipExtractor]: bounded, reused buffer; same files out. */
class ZipExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val zip = temp.newFile("package.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                if (name.endsWith("/")) {
                    out.putNextEntry(ZipEntry(name))
                    out.closeEntry()
                } else {
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        return zip
    }

    @Test
    fun `extracts nested files and directories`() {
        val zip = zipOf(
            "graph/" to ByteArray(0),
            "graph/properties" to "graph.location=here".toByteArray(),
            "graph/nodes/nodes" to "nodes".toByteArray(),
        )
        val destination = File(temp.root, "out")

        ZipExtractor.extract(zip, destination)

        assertThat(File(destination, "graph/properties").readText()).isEqualTo("graph.location=here")
        assertThat(File(destination, "graph/nodes/nodes").readText()).isEqualTo("nodes")
        assertThat(File(destination, "graph/nodes").isDirectory).isTrue()
    }

    @Test
    fun `extracts a large entry correctly through a tiny buffer`() {
        // 4 MiB of incompressible data, unpacked with a 1 KiB buffer: a whole-entry allocation
        // would need the full 4 MiB, this needs 1 KiB.
        val payload = ByteArray(4 * 1024 * 1024).also { Random(7).nextBytes(it) }
        val zip = zipOf("graph/big" to payload)
        val destination = File(temp.root, "out")

        ZipExtractor.extract(zip, destination, bufferSize = 1024)

        val extracted = File(destination, "graph/big")
        assertThat(extracted.length()).isEqualTo(payload.size.toLong())
        assertThat(extracted.readBytes().contentEquals(payload)).isTrue()
    }

    @Test
    fun `copy never reads more than the supplied buffer and reuses it`() {
        val buffer = ByteArray(1024)
        var largestRequest = 0
        var sameInstance = true
        val source = ByteArray(64 * 1024) { (it % 251).toByte() }
        val input = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < source.size) source[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                largestRequest = maxOf(largestRequest, len)
                if (b !== buffer) sameInstance = false
                if (pos >= source.size) return -1
                val n = minOf(len, source.size - pos)
                System.arraycopy(source, pos, b, off, n)
                pos += n
                return n
            }
        }
        val sink = ByteArrayOutputStream()

        val written = ZipExtractor.copy(input, sink, buffer)

        assertThat(written).isEqualTo(source.size.toLong())
        assertThat(largestRequest).isAtMost(1024)
        assertThat(sameInstance).isTrue()
        assertThat(sink.toByteArray().contentEquals(source)).isTrue()
    }

    @Test
    fun `default buffer stays small and constant`() {
        // Guards against a future "just size it from the entry" change.
        assertThat(ZipExtractor.DEFAULT_BUFFER_SIZE).isAtLeast(8 * 1024)
        assertThat(ZipExtractor.DEFAULT_BUFFER_SIZE).isAtMost(128 * 1024)
    }

    @Test
    fun `rejects entries that escape the destination`() {
        val zip = zipOf("../escaped.txt" to "nope".toByteArray())
        val destination = File(temp.root, "out/nested")

        assertThrows(SecurityException::class.java) {
            ZipExtractor.extract(zip, destination)
        }
        assertThat(File(temp.root, "out/escaped.txt").exists()).isFalse()
    }

    @Test
    fun `overwrites a previously extracted file`() {
        val destination = File(temp.root, "out")
        File(destination, "graph").mkdirs()
        File(destination, "graph/properties").writeText("stale-and-longer-than-the-new-content")

        ZipExtractor.extract(zipOf("graph/properties" to "fresh".toByteArray()), destination)

        assertThat(File(destination, "graph/properties").readText()).isEqualTo("fresh")
    }

    @Test
    fun `rejects a non-positive buffer size`() {
        assertThrows(IllegalArgumentException::class.java) {
            ZipExtractor.extract(zipOf("a" to ByteArray(0)), File(temp.root, "out"), bufferSize = 0)
        }
    }

    @Test
    fun `copy of an empty stream writes nothing`() {
        val sink = ByteArrayOutputStream()
        val written = ZipExtractor.copy(ByteArrayInputStream(ByteArray(0)), sink, ByteArray(16))
        assertThat(written).isEqualTo(0L)
        assertThat(sink.size()).isEqualTo(0)
    }
}
