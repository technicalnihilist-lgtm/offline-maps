package com.offlinemaps.app.di

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.offlinemaps.core.routing.GraphInstallTransaction
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The debris of a routing install the process did not survive is settled on the startup pass
 * (OPTIMIZATION.md I-07).
 *
 * [ServiceLocator.settleInterruptedRoutingInstall] is the one place the app reaches the swap's
 * recovery, so the wiring is checked here against the directory the locator actually points the
 * routing engine at - not against a temporary root that only the core test knows about. The second
 * case guards the other half of the property: a device that never installed anything must not have
 * a packages directory created for it just to look for debris.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ServiceLocatorInstallRecoveryTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun graphRoot(): File {
        val base = context().getExternalFilesDir(null) ?: context().filesDir
        return File(base, "packages/routing/current")
    }

    private fun graphAt(directory: File, vararg names: String) {
        directory.mkdirs()
        names.forEach { File(directory, it).writeText("graph:$it") }
    }

    @Test
    fun `a swap interrupted between its renames is undone at startup`() {
        val root = graphRoot()
        // The state a kill leaves: the working graph parked under `.previous`, a half-unpacked
        // package in `.staging`, and nothing live.
        graphAt(GraphInstallTransaction.previousFor(root), "nodes", "edges", "properties")
        graphAt(GraphInstallTransaction.stagingFor(root), "nodes", "half-written")

        val recovery = ServiceLocator(context()).settleInterruptedRoutingInstall()

        assertThat(recovery.restoredPrevious).isTrue()
        assertThat(recovery.discardedStaging).isTrue()
        assertThat(root.listFiles()?.map { it.name })
            .containsExactly("nodes", "edges", "properties")
        assertThat(GraphInstallTransaction.previousFor(root).exists()).isFalse()
        assertThat(GraphInstallTransaction.stagingFor(root).exists()).isFalse()
    }

    @Test
    fun `settling an interrupted install creates nothing on a clean device`() {
        val recovery = ServiceLocator(context()).settleInterruptedRoutingInstall()

        assertThat(recovery.changed).isFalse()
        val base = context().getExternalFilesDir(null) ?: context().filesDir
        assertThat(File(base, "packages").exists()).isFalse()
    }
}
