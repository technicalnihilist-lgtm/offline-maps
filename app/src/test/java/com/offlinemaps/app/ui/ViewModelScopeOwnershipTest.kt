package com.offlinemaps.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test

/**
 * Nothing a screen starts may go on running once that screen is gone (OPTIMIZATION.md H-04).
 *
 * `viewModelScope` is the only scope a view model may launch in, because it is the only one that
 * `onCleared` cancels; `GlobalScope`, a `CoroutineScope(...)` built in the view model and a scope
 * handed in from outside all survive it. The audit below reads the app's own sources, so the
 * property is checked against what is committed rather than against a copy of it, and the last
 * test pins down the cancellation the audit is relying on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelScopeOwnershipTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `no view model in the app launches a coroutine outside its own scope`() {
        val sources = kotlinSources()

        // Guards the audit itself: a path that stopped resolving would otherwise pass silently.
        assertThat(sources.size).isAtLeast(50)
        assertThat(sources.values.count { ViewModelCoroutineAudit.isViewModelSource(it) }).isAtLeast(4)

        assertThat(ViewModelCoroutineAudit.audit(sources).map { it.toString() }).isEmpty()
    }

    @Test
    fun `GlobalScope is reported in any source file`() {
        val findings = ViewModelCoroutineAudit.audit(
            mapOf(
                "Something.kt" to """
                    class Something {
                        fun go() { GlobalScope.launch { work() } }
                    }
                """.trimIndent(),
            ),
        )

        assertThat(findings.map { it.detail }).contains("GlobalScope is not cancelled by onCleared")
    }

    @Test
    fun `a view model launching on a foreign scope is reported`() {
        val source = """
            class BadViewModel(private val scope: CoroutineScope) : ViewModel() {
                init {
                    scope.launch { collect() }
                }
            }
        """.trimIndent()

        val findings = ViewModelCoroutineAudit.audit(mapOf("Bad.kt" to source))

        assertThat(findings.map { it.detail }).contains("launch on 'scope' outlives the view model")
    }

    @Test
    fun `a view model that builds its own scope is reported`() {
        val source = """
            class BadViewModel : ViewModel() {
                private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

                fun go() { scope.launch { work() } }
            }
        """.trimIndent()

        val findings = ViewModelCoroutineAudit.audit(mapOf("Bad.kt" to source))

        assertThat(findings.map { it.detail }).contains(
            "view model builds its own CoroutineScope, which onCleared cannot cancel",
        )
    }

    @Test
    fun `work launched in the view model's own scope is accepted`() {
        val source = """
            class GoodViewModel : ViewModel() {
                private val debouncer = QueryDebouncer(viewModelScope)

                init {
                    viewModelScope.launch { collect() }
                    viewModelScope
                        .launch { collect() }
                }
            }
        """.trimIndent()

        assertThat(ViewModelCoroutineAudit.audit(mapOf("Good.kt" to source))).isEmpty()
    }

    @Test
    fun `a process-lifetime manager is not a view model and keeps its own scope`() {
        val source = """
            class NavigationManager(private val locator: ServiceLocator) {
                private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

                fun start() { scope.launch { loop() } }
            }
        """.trimIndent()

        assertThat(ViewModelCoroutineAudit.audit(mapOf("NavigationManager.kt" to source))).isEmpty()
    }

    @Test
    fun `clearing a view model cancels work started in its scope`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val store = ViewModelStore()
        val provider = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ProbeViewModel() as T
            },
        )

        val probe = provider[ProbeViewModel::class.java]
        assertThat(probe.started).isTrue()

        store.clear()

        assertThat(probe.awaitCancelled()).isTrue()
    }

    /** A view model that parks in its own scope, so a clear has something to cancel. */
    private class ProbeViewModel : ViewModel() {

        @Volatile
        var started = false
            private set

        @Volatile
        private var cancelled = false

        init {
            viewModelScope.launch {
                started = true
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }

        /** Cancellation may be resumed on another thread, so wait for it rather than assume. */
        fun awaitCancelled(): Boolean {
            val deadline = System.currentTimeMillis() + 2_000
            while (!cancelled && System.currentTimeMillis() < deadline) Thread.sleep(10)
            return cancelled
        }
    }

    /**
     * Every Kotlin file under the app's and the core modules' main sources, keyed by a path relative
     * to the repository root.
     *
     * The unit test runs from the module directory, so the sources are found by walking up from the
     * working directory to the first directory that holds `src/main/java`.
     */
    private fun kotlinSources(): Map<String, String> {
        val module = moduleDirectory()
        val repository = module.parentFile ?: module
        val roots = buildList {
            add(File(module, "src/main/java"))
            File(repository, "core").listFiles().orEmpty()
                .map { File(it, "src/main/java") }
                .filter { it.isDirectory }
                .sortedBy { it.path }
                .forEach { add(it) }
        }

        val sources = linkedMapOf<String, String>()
        roots.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .forEach { file -> sources[file.relativeTo(repository).path] = file.readText() }
        }
        return sources
    }

    private fun moduleDirectory(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "src/main/java").isDirectory) return directory
            if (File(directory, "app/src/main/java").isDirectory) return File(directory, "app")
            directory = directory.parentFile
        }
        throw AssertionError("no src/main/java found from ${File("").absolutePath}")
    }
}
