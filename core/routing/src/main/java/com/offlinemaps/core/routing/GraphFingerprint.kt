package com.offlinemaps.core.routing

import java.io.File

/**
 * Identifies the graph files a loaded engine was built from (OPTIMIZATION.md J-10).
 *
 * [RoutingEngine.load] skips the import when the live graph already serves the requested profile
 * set, which is what keeps a second `load` cheap (E-02). The profile set alone is not enough to
 * decide that, though: installing a routing package deletes the graph directory and unpacks the new
 * package into the same path, and the install then calls `load` with the same profiles as before -
 * so the shortcut returned success while the engine kept answering from the graph it had read out of
 * the *old* files, until the process restarted.
 *
 * A fingerprint of the directory closes that gap. It is the list of the directory's own entries with
 * their size and modification time: a package swap renames a freshly unpacked directory into place,
 * so every entry is a new file with a new timestamp, and the fingerprint of the new graph cannot
 * equal the one recorded when the old graph was loaded. Nothing is hashed - the point is to answer
 * "are these the files that were loaded", not "are these bytes correct" - so the cost is one
 * `listFiles` plus a `length()`/`lastModified()` per entry, and only when a load is already being
 * asked to do nothing.
 *
 * Two limits are deliberate. Entries inside a subdirectory are not walked: the swap replaces the
 * whole tree, so the top-level names, sizes and times already differ. And a package reinstalled
 * inside the same millisecond with byte-identical files would be indistinguishable - it would also
 * be a graph the engine could not tell apart from the one it holds.
 */
data class GraphFingerprint(val entries: List<Entry>) {

    /** One entry of the graph directory: enough to notice a rewrite, a resize or a replacement. */
    data class Entry(val name: String, val length: Long, val lastModified: Long)

    companion object {
        /**
         * The fingerprint of [directory], or null when it is not a readable directory with entries
         * in it - which [RoutingEngine.hasGraph] already treats as "no graph installed", so a null
         * here can never match a recorded fingerprint and simply forces the normal load path.
         */
        fun of(directory: File): GraphFingerprint? {
            val children = directory.listFiles() ?: return null
            if (children.isEmpty()) return null
            return GraphFingerprint(
                children
                    .map { Entry(it.name, it.length(), it.lastModified()) }
                    .sortedBy { it.name },
            )
        }
    }
}
