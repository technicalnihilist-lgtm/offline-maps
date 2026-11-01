package com.offlinemaps.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Debug-build audit that no disk I/O runs on the main thread while the app is starting
 * (OPTIMIZATION.md A-02).
 *
 * A debuggable build installs a [StrictMode] thread policy that reports disk reads and writes.
 * Every violation raised while the startup window is open (from `Application.onCreate` until the
 * first frame is laid out) is recorded and, when the window closes, printed as one readable report
 * so a regression that puts SQLite or file access back on the main thread is impossible to miss.
 *
 * The report is deliberately not fatal: the platform and third-party libraries do small reads on
 * the main thread that this app cannot remove, and a debug build must not crash on those.
 * [assertNoDiskIoOnMainThread] is the assertion itself and is what the unit tests drive; call it
 * from a startup hook when a build should fail hard on any violation.
 */
object StartupIoAudit {

    private const val TAG = "StartupIoAudit"

    /** Cap on recorded violations, so a pathological startup cannot grow this list without bound. */
    private const val MAX_RECORDED = 20

    private val violations = CopyOnWriteArrayList<String>()

    @Volatile
    private var installed = false

    /** True once the StrictMode policy has been installed for this process. */
    val isInstalled: Boolean get() = installed

    /** Installs the audit in debuggable builds; a no-op (and cheap) everywhere else. */
    fun install(app: Application, enabled: Boolean = isDebuggable(app)) {
        if (!enabled || installed) return
        installed = true
        val handler = Handler(Looper.getMainLooper())
        val builder = StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            attachListener(builder, Executor { command -> handler.post(command) })
        } else {
            // penaltyListener() is API 28+; older devices keep the log-only penalty.
            builder.penaltyLog()
        }
        StrictMode.setThreadPolicy(builder.build())
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun attachListener(builder: StrictMode.ThreadPolicy.Builder, executor: Executor) {
        builder.penaltyListener(executor) { violation -> record(violation.stackTrace) }
    }

    /** Closes the startup window: logs a one-line all-clear, or the violations that were seen. */
    fun finishStartupWindow() {
        if (!installed) return
        val found = violations.toList()
        if (found.isEmpty()) {
            Log.d(TAG, "startup window closed: no disk I/O on the main thread")
            return
        }
        Log.e(
            TAG,
            "startup window closed: ${found.size} disk I/O violation(s) on the main thread, " +
                "these block the first frame:\n" + found.joinToString("\n"),
        )
    }

    /** Fails when any main-thread disk I/O was recorded — the assertion behind this audit. */
    fun assertNoDiskIoOnMainThread() {
        val found = violations.toList()
        check(found.isEmpty()) {
            "Disk I/O ran on the main thread during startup:\n" + found.joinToString("\n")
        }
    }

    fun record(stackTrace: Array<StackTraceElement>) {
        record(stackTrace.take(8).joinToString(" <- ") { it.toString() })
    }

    fun record(violation: String) {
        if (violations.size < MAX_RECORDED) violations += violation
    }

    fun violations(): List<String> = violations.toList()

    /** Drops everything recorded so far; used by tests. */
    fun reset() {
        violations.clear()
    }

    private fun isDebuggable(app: Application): Boolean =
        (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
