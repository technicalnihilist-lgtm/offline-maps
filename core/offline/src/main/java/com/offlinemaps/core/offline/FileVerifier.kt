package com.offlinemaps.core.offline

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streaming checksum verification for downloaded packages.
 *
 * A package is verified after it lands and again on resume, and a routing graph or a country's
 * places pack is hundreds of megabytes, so the cost of verification is the disk read. [verify]
 * therefore reads the file once even when the catalogue supplies both a SHA-256 and an MD5: it
 * used to call [sha256] and then [md5], each opening the file and walking it end to end, so a
 * package carrying both checksums paid for two full passes over bytes that had just been written
 * (OPTIMIZATION.md J-26). Both digests are now fed from the same 64 KB buffer in the same loop.
 *
 * The hex encoding is done from a table into one `StringBuilder` rather than with `"%02x".format`
 * per byte, which built a `Formatter` and a throwaway `String` for each of the thirty-two bytes of
 * a digest - sixty-four of each when both checksums were supplied.
 */
object FileVerifier {

    /** Read size for the single verification pass. One buffer, not one per pass. */
    private const val BUFFER_BYTES = 64 * 1024

    private const val HEX_DIGITS = "0123456789abcdef"

    fun sha256(file: File): String = digestHex(file, SHA256)

    fun md5(file: File): String = digestHex(file, MD5)

    private fun digestHex(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        readInto(file, listOf(md))
        return hex(md.digest())
    }

    /**
     * Feeds every digest in [digests] from one pass over [file].
     *
     * The file is opened once and its bytes are handed to each digest in the same loop, so two
     * checksums cost one read rather than two.
     */
    private fun readInto(file: File, digests: List<MessageDigest>) {
        val buffer = ByteArray(BUFFER_BYTES)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                for (index in digests.indices) digests[index].update(buffer, 0, read)
            }
        }
        verifyPasses.incrementAndGet()
    }

    /** Lower-case hex of [bytes], the same text `"%02x".format` produces for each unsigned byte. */
    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return out.toString()
    }

    /**
     * Verifies [file] against whichever checksums are supplied. Returns null when it matches,
     * or a human readable reason when it does not.
     *
     * SHA-256 is still reported before MD5, so a file that fails both produces the same message as
     * before. When both are supplied the MD5 is now computed even if the SHA-256 already failed -
     * that work happens inside the single pass over bytes already being read, and the answer the
     * caller sees is unchanged.
     */
    fun verify(file: File, sha256: String?, md5: String?): String? {
        val checkSha = !sha256.isNullOrBlank()
        val checkMd5 = !md5.isNullOrBlank()
        if (!checkSha && !checkMd5) return null
        if (!file.exists() || file.length() == 0L) return "Downloaded file is empty."
        val digests = ArrayList<MessageDigest>(2)
        if (checkSha) digests.add(MessageDigest.getInstance(SHA256))
        if (checkMd5) digests.add(MessageDigest.getInstance(MD5))
        readInto(file, digests)
        var next = 0
        if (checkSha) {
            val actual = hex(digests[next++].digest())
            if (!actual.equals(sha256, ignoreCase = true)) {
                return "Checksum mismatch (SHA-256). The file is incomplete or corrupted; please retry."
            }
        }
        if (checkMd5) {
            val actual = hex(digests[next].digest())
            if (!actual.equals(md5, ignoreCase = true)) {
                return "Checksum mismatch (MD5). The file is incomplete or corrupted; please retry."
            }
        }
        return null
    }

    /**
     * How many times [verify] has read a file. A test hook, never read by the app: it is what pins
     * the verification to one pass instead of one pass per checksum (OPTIMIZATION.md J-26).
     */
    internal fun verifyPasses(): Int = verifyPasses.get()

    private val verifyPasses = AtomicInteger(0)

    private const val SHA256 = "SHA-256"
    private const val MD5 = "MD5"
}
