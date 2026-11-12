package com.offlinemaps.core.offline

import java.io.File

/**
 * Whether the folder a package is being written into is still there and still writable.
 *
 * Storage can disappear underneath a running transfer: an SD card is ejected, a USB stick is
 * unplugged, a read-only mount replaces the folder, or the system clears the directory mid-download.
 * Nothing in the engine could tell that apart from a dropped connection, so the resulting
 * `IOException` was retried like a network failure and finally surfaced as
 * "Network error: /storage/.../file.pbf.part: open failed: ENOENT (No such file or directory)" -
 * blaming the link, naming a filesystem path, and spending the retry budget on a condition no retry
 * can fix.
 *
 * The check is made against the directory rather than against an open file handle on purpose: a
 * handle on an unlinked file keeps accepting writes on Linux, so a card pulled mid-transfer only
 * shows up at the next open, rename or size measurement. Asking the directory answers the question
 * that actually matters - is the place the bytes belong to still usable?
 */
object TargetStorage {

    /**
     * The one sentence shown when the folder a package saves into is gone or refuses writes.
     *
     * It names what happened and what the user can do about it, and covers a removed card and a
     * read-only mount alike: from the outside both mean "this location cannot be used any more".
     */
    const val UNAVAILABLE_SENTENCE: String =
        "The storage this download saves to is no longer available. Reconnect it, or choose a " +
            "location on internal storage, and try again."

    /** True when [directory] exists, is a directory, and accepts writes. */
    fun isUsable(directory: File): Boolean = directory.isDirectory && directory.canWrite()

    /** The reason to report for [directory], or null when it can still be written to. */
    fun problem(directory: File): String? = if (isUsable(directory)) null else UNAVAILABLE_SENTENCE
}
