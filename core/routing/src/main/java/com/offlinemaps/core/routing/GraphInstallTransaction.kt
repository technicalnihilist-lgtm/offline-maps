package com.offlinemaps.core.routing

import java.io.File
import java.io.IOException

/**
 * Unpacks a routing package beside the live graph and swaps it in with two renames
 * (OPTIMIZATION.md I-07).
 *
 * The installer used to `deleteRecursively()` the graph directory and unpack straight into it, which
 * is not safe against the one thing the platform does without warning: kill the process. A kill
 * during the unpack left a half-written directory where a working graph had been, and because the
 * region was already recorded as routing-ready, the app never retried the install - the region
 * stayed broken until the user deleted and downloaded it again. A package that unpacked but turned
 * out not to be a graph (I-05) destroyed the previous graph the same way.
 *
 * So the unpack happens in `<name>.staging` next to the graph, and only a directory that passed its
 * checks is moved into place:
 *
 *  1. delete a stale `.staging`, create a fresh one, run the caller's unpack into it;
 *  2. on rejection (or any throw) delete `.staging` - the live graph is never touched;
 *  3. on acceptance rename `current` -> `current.previous`, `.staging` -> `current`, delete
 *     `.previous`.
 *
 * Every kill window leaves something [recover] can settle: before the first rename the live graph is
 * still there, between the two renames `.previous` holds it and is renamed back, after the second
 * the new graph is live and only a stale `.previous` is left to delete.
 *
 * All the moves happen inside one directory, so every rename is on the same filesystem and atomic.
 * The staging and previous names are dotless siblings rather than hidden files on purpose: they are
 * ours, they are transient, and nothing else in the app reads them.
 */
object GraphInstallTransaction {

    /** Suffix of the directory a package is unpacked into before it is published. */
    const val STAGING_SUFFIX = ".staging"

    /** Suffix of the directory the live graph is moved to for the length of the swap. */
    const val PREVIOUS_SUFFIX = ".previous"

    /** What [stage] did with the package it was given. */
    sealed interface StageResult {
        /** The staged graph is live at the root it was staged for. */
        data object Published : StageResult

        /** The caller rejected the package; the live graph was not touched. */
        data class Rejected(val reason: Throwable) : StageResult

        /** The package was good but could not be renamed into place. */
        data class SwapFailed(val reason: Throwable) : StageResult
    }

    /** What [recover] settled. Every field false means the process died nowhere near a swap. */
    data class Recovery(
        val restoredPrevious: Boolean = false,
        val discardedPrevious: Boolean = false,
        val discardedStaging: Boolean = false,
    ) {
        val changed: Boolean get() = restoredPrevious || discardedPrevious || discardedStaging
    }

    /**
     * Guards [stage] and [recover] against each other.
     *
     * The recovery runs once per process on a background thread after the first frame, while the
     * downloads screen can already be starting an install; without the lock the recovery's delete of
     * a stale `.staging` could land in the middle of an unpack.
     */
    private val lock = Any()

    fun stagingFor(root: File): File = sibling(root, STAGING_SUFFIX)

    fun previousFor(root: File): File = sibling(root, PREVIOUS_SUFFIX)

    /**
     * Unpacks a package into a fresh staging directory beside [root] and publishes it.
     *
     * [body] receives the staging directory and returns success only if it unpacked and validated a
     * graph there. Anything it throws (or returns as a failure) is reported back as
     * [StageResult.Rejected] with the live graph untouched, so the caller can turn it into the
     * message it already shows for a bad package.
     */
    fun stage(root: File, body: (staging: File) -> Result<Unit>): StageResult = synchronized(lock) {
        // Settle whatever an earlier kill left first: a swap interrupted between its renames must be
        // undone before this install can stage anything, or the graph it restores would be lost.
        recoverLocked(root)

        val staging = stagingFor(root)
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            return StageResult.SwapFailed(IOException("could not create ${staging.name}"))
        }

        val prepared = runCatching { body(staging) }.getOrElse { Result.failure(it) }
        prepared.exceptionOrNull()?.let { reason ->
            staging.deleteRecursively()
            return StageResult.Rejected(reason)
        }

        val previous = previousFor(root)
        previous.deleteRecursively()
        if (root.exists() && !root.renameTo(previous)) {
            staging.deleteRecursively()
            return StageResult.SwapFailed(IOException("could not move ${root.name} aside"))
        }
        if (!staging.renameTo(root)) {
            // Put the graph that was working back where the engine expects it before giving up.
            if (previous.exists()) previous.renameTo(root)
            staging.deleteRecursively()
            return StageResult.SwapFailed(IOException("could not rename ${staging.name} to ${root.name}"))
        }
        previous.deleteRecursively()
        StageResult.Published
    }

    /**
     * Settles the debris of an install the process did not survive, without unpacking anything.
     *
     * Cheap enough to run on every launch: it lists and, at most, renames or deletes directories
     * that only exist for the length of an install, so on a healthy device it touches nothing.
     */
    fun recover(root: File): Recovery = synchronized(lock) { recoverLocked(root) }

    private fun recoverLocked(root: File): Recovery {
        val staging = stagingFor(root)
        val previous = previousFor(root)
        var recovery = Recovery()

        // A staging directory only survives a kill during the unpack: the graph it was going to
        // replace is still live, so the half-written copy is simply dropped.
        if (staging.exists()) {
            recovery = recovery.copy(discardedStaging = staging.deleteRecursively())
        }
        if (!previous.exists()) return recovery

        // The swap was killed between its two renames: the live graph is sitting in `.previous`.
        // An empty `current` counts as missing - the second rename creates it and fills it in one
        // step, so a directory that exists but is empty is a leftover, not a graph.
        if (!root.exists() || isEmptyDirectory(root)) {
            if (root.exists()) root.delete()
            recovery = recovery.copy(restoredPrevious = previous.renameTo(root))
        } else {
            // The second rename already happened; only the old graph's removal was interrupted.
            recovery = recovery.copy(discardedPrevious = previous.deleteRecursively())
        }
        return recovery
    }

    private fun isEmptyDirectory(directory: File): Boolean =
        directory.isDirectory && directory.listFiles()?.isEmpty() != false

    private fun sibling(root: File, suffix: String): File {
        val absolute = root.absoluteFile
        return File(absolute.parentFile ?: absolute, absolute.name + suffix)
    }
}
