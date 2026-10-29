package com.offlinemaps.core.offline

import java.io.File
import java.security.MessageDigest

/** Streaming checksum verification for downloaded packages. */
object FileVerifier {

    fun sha256(file: File): String = digest(file, "SHA-256")
    fun md5(file: File): String = digest(file, "MD5")

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies [file] against whichever checksums are supplied. Returns null when it matches,
     * or a human readable reason when it does not.
     */
    fun verify(file: File, sha256: String?, md5: String?): String? {
        if (sha256.isNullOrBlank() && md5.isNullOrBlank()) return null
        if (!file.exists() || file.length() == 0L) return "Downloaded file is empty."
        if (!sha256.isNullOrBlank()) {
            val actual = sha256(file)
            if (!actual.equals(sha256, ignoreCase = true)) {
                return "Checksum mismatch (SHA-256). The file is incomplete or corrupted; please retry."
            }
        }
        if (!md5.isNullOrBlank()) {
            val actual = md5(file)
            if (!actual.equals(md5, ignoreCase = true)) {
                return "Checksum mismatch (MD5). The file is incomplete or corrupted; please retry."
            }
        }
        return null
    }
}
