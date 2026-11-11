package com.offlinemaps.core.map

import android.content.ComponentCallbacks2

/**
 * Decides whether an `onTrimMemory` level is a good moment to drop the generated style documents
 * (OPTIMIZATION.md H-02).
 *
 * The style cache is small - at most [MapStyleFactory.MAX_CACHED_STYLES] documents of a few tens of
 * kilobytes - so it is not what makes a trim matter; the routing graph is. It is released anyway
 * because it is the only cache this app holds on the UI side and a trim is exactly the moment to
 * give it back, but only at levels that mean real pressure: rebuilding a document is work on the
 * main thread when the map view next attaches, so a level that merely says "the app is in the
 * background and doing fine" ([ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]) or "memory is a little
 * tight but the app is visible" ([ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE]) is not worth
 * the rebuild, which a return to the app would pay immediately.
 *
 * Pure function on purpose, so the decision is unit-testable without a device.
 */
object StyleCacheMemoryPolicy {

    /** Levels that mean the process is under real pressure or is a background LRU victim. */
    private val RELEASING_LEVELS = setOf(
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
    )

    /**
     * True when the cached documents should be dropped at [level]. An unknown level keeps them: a
     * document it may need is worth more than the few tens of kilobytes a guess would recover.
     */
    fun shouldRelease(level: Int): Boolean = level in RELEASING_LEVELS
}
