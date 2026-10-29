package com.offlinemaps.core.offline

import android.os.StatFs
import java.io.File

/** Real free-space probe backed by [StatFs]. */
class AndroidStorageProbe : StorageProbe {
    override fun availableBytes(directory: File): Long {
        val dir = if (directory.exists()) directory else directory.parentFile ?: directory
        return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
    }
}

object StorageGuard {
    /** Keep this much headroom free so the device stays usable after a download finishes. */
    const val SAFETY_MARGIN_BYTES = 200L * 1024 * 1024

    /**
     * True when [requiredBytes] plus the safety margin fits. [limitBytes] applies the user's own
     * storage cap for offline maps (0 or less disables the cap).
     */
    fun canFit(
        availableBytes: Long,
        requiredBytes: Long,
        limitBytes: Long = 0L,
        usedByMapsBytes: Long = 0L,
    ): Boolean {
        if (requiredBytes + SAFETY_MARGIN_BYTES > availableBytes) return false
        if (limitBytes > 0 && usedByMapsBytes + requiredBytes > limitBytes) return false
        return true
    }

    fun reasonFor(
        availableBytes: Long,
        requiredBytes: Long,
        limitBytes: Long = 0L,
        usedByMapsBytes: Long = 0L,
    ): String? = when {
        requiredBytes + SAFETY_MARGIN_BYTES > availableBytes ->
            "Not enough free space: need ${requiredBytes / (1024 * 1024)} MB plus headroom, " +
                "only ${availableBytes / (1024 * 1024)} MB available."
        limitBytes > 0 && usedByMapsBytes + requiredBytes > limitBytes ->
            "This would exceed your offline map storage limit of ${limitBytes / (1024 * 1024)} MB."
        else -> null
    }
}
