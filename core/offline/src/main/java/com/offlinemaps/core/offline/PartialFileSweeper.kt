package com.offlinemaps.core.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Deletes partial download files that no durable download record claims any more.
 *
 * [DownloadManager.cancel] removes `<name>.part` and `<name>.meta` once the transfer has stopped,
 * but a process killed between the cancel and that delete leaves both files on disk with no record
 * pointing at them: nothing would ever resume them, so they would sit in the packages directory
 * until the app was uninstalled. This sweep closes that window. It runs once per process, after the
 * first frame, over a directory that only ever holds downloaded packages.
 *
 * The caller answers whether a name is still claimed rather than the sweeper guessing from the
 * name, and a name it cannot classify is kept: deleting a partial that a paused download still
 * needs would cost the user the bytes already transferred.
 */
object PartialFileSweeper {

    /** Sidecar suffixes written by the engine, in the order they are matched. */
    private val PARTIAL_SUFFIXES = listOf(".part", ".meta")

    /**
     * @param directory the packages directory; a directory that does not exist is not an error.
     * @param isClaimed true when a download record still refers to [baseName].
     * @return the names of the files that were deleted, for logging and tests.
     */
    suspend fun sweep(directory: File, isClaimed: suspend (baseName: String) -> Boolean): List<String> =
        withContext(Dispatchers.IO) {
            val files = directory.listFiles() ?: return@withContext emptyList()
            val deleted = mutableListOf<String>()
            for (file in files) {
                if (!file.isFile) continue
                val suffix = PARTIAL_SUFFIXES.firstOrNull { file.name.endsWith(it) } ?: continue
                val baseName = file.name.removeSuffix(suffix)
                if (baseName.isEmpty()) continue
                if (isClaimed(baseName)) continue
                if (file.delete()) deleted += file.name
            }
            deleted
        }
}
