package com.offlinemaps.app.install

import com.google.common.truth.Truth.assertThat
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.DataFormatException
import java.util.zip.ZipException
import org.junit.Test

/**
 * Every way a package install can fail has to end in a sentence a user can act on (OPTIMIZATION.md
 * I-02). The mapping is the only part of a failed install anyone sees, so each branch is pinned
 * here; the installer itself needs a Context and a ServiceLocator and cannot run as a plain JVM test.
 */
class InstallFailuresTest {

    @Test
    fun `a damaged zip is described as damaged, not with its own internal message`() {
        val message = InstallFailures.describe(
            ZipException("invalid entry CRC (expected 0x0 but got 0x1f)"),
            "Andorra",
        )

        assertThat(message).contains("damaged or incomplete")
        assertThat(message).contains("Andorra")
        assertThat("an internal CRC value must not reach the user", message).doesNotContain("CRC")
    }

    @Test
    fun `a truncated gzip stream is described as damaged`() {
        val message = InstallFailures.describe(EOFException(), "Andorra")

        assertThat(message).contains("damaged or incomplete")
        assertThat(message).contains("download it again")
    }

    @Test
    fun `a broken deflate stream is described as damaged`() {
        val message = InstallFailures.describe(DataFormatException("incorrect data check"), "Andorra")

        assertThat(message).contains("damaged or incomplete")
        assertThat(message).doesNotContain("incorrect data check")
    }

    @Test
    fun `running out of memory keeps its own actionable sentence`() {
        val message = InstallFailures.describe(OutOfMemoryError(), "Andorra")

        assertThat(message).contains("Not enough memory")
        assertThat(message).contains("Close other apps")
    }

    @Test
    fun `a missing routing class is reported as a packaging problem`() {
        val message = InstallFailures.describe(
            NoClassDefFoundError("javax/xml/stream/XMLInputFactory"),
            "Andorra",
        )

        assertThat(message).contains("packaging problem")
        assertThat(message).contains("please report it")
    }

    @Test
    fun `a refused storage permission is named as such`() {
        val message = InstallFailures.describe(SecurityException("EACCES"), "Andorra")

        assertThat(message).contains("Storage permission was refused")
    }

    @Test
    fun `an unrecognised exception falls back to its own message`() {
        val message = InstallFailures.describe(IllegalStateException("no such table: places"), "Andorra")

        assertThat(message).isEqualTo("no such table: places")
    }

    @Test
    fun `an unrecognised exception with no message still names the subject and the type`() {
        val message = InstallFailures.describe(IllegalStateException(), "Andorra")

        assertThat(message).contains("Andorra")
        assertThat(message).contains("IllegalStateException")
        assertThat(message).isNotEmpty()
    }

    @Test
    fun `a plain io error keeps whatever message it carries`() {
        // Not an archive problem: an IOException here is usually a write failing, and its message is
        // more use than a generic "damaged" claim.
        val message = InstallFailures.describe(IOException("No space left on device"), "Andorra")

        assertThat(message).isEqualTo("No space left on device")
    }

    @Test
    fun `cancellation is told apart from failure`() {
        assertThat(InstallFailures.isCancellation(CancellationException("cancelled"))).isTrue()
        assertThat(InstallFailures.isCancellation(ZipException("bad entry"))).isFalse()
        assertThat(InstallFailures.isCancellation(OutOfMemoryError())).isFalse()
    }
}
