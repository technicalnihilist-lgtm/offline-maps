package com.offlinemaps.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The startup disk-I/O audit (OPTIMIZATION.md A-02).
 *
 * The StrictMode policy itself only reports on a device; what is checked here is the part this app
 * owns: violations are recorded, the report is bounded, and the assertion fails when the main
 * thread touched the disk during startup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StartupIoAuditTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        StartupIoAudit.reset()
    }

    @Test
    fun `a clean startup passes the assertion`() {
        StartupIoAudit.assertNoDiskIoOnMainThread()
        assertThat(StartupIoAudit.violations()).isEmpty()
    }

    @Test
    fun `a recorded violation makes the assertion fail`() {
        StartupIoAudit.record("android.database.sqlite.SQLiteConnection.open <- com.offlinemaps.core.search.SearchIndex.count")

        val error = runCatching { StartupIoAudit.assertNoDiskIoOnMainThread() }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error!!.message).contains("SQLiteConnection.open")
    }

    @Test
    fun `violations from a stack trace are recorded with the frames that caused them`() {
        StartupIoAudit.record(
            arrayOf(
                StackTraceElement("com.offlinemaps.core.search.SearchIndex", "search", "SearchIndex.kt", 100),
                StackTraceElement("com.offlinemaps.app.ui.MapViewModel", "setSearchQuery", "MapViewModel.kt", 200),
            ),
        )

        val recorded = StartupIoAudit.violations()
        assertThat(recorded).hasSize(1)
        assertThat(recorded.first()).contains("SearchIndex.search")
        assertThat(recorded.first()).contains("MapViewModel.setSearchQuery")
    }

    @Test
    fun `the audit stays off when it is not a debuggable build`() {
        StartupIoAudit.install(app, enabled = false)

        assertThat(StartupIoAudit.isInstalled).isFalse()
    }

    @Test
    fun `the recorded report is bounded`() {
        repeat(50) { StartupIoAudit.record("violation $it") }

        assertThat(StartupIoAudit.violations().size).isAtMost(20)
    }
}
