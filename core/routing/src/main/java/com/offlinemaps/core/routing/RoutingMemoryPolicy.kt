package com.offlinemaps.core.routing

import android.content.ComponentCallbacks2

/**
 * Decides whether an `onTrimMemory` level is a good moment to give the routing graph back
 * (OPTIMIZATION.md E-01).
 *
 * The graph is the largest thing this app holds - GraphHopper keeps it resident, and a country is
 * hundreds of megabytes - so it is the right thing to release when Android says memory is short.
 * It is also the most expensive thing to rebuild, so the levels that only mean "the app is visible
 * and doing fine" are deliberately not acted on: releasing there would only be undone by the next
 * route request, and the reload would land in the middle of a session.
 *
 * Pure function on purpose, so the decision is unit-testable without a device.
 */
object RoutingMemoryPolicy {

    /** Levels that mean the process is under real pressure or no longer in the foreground. */
    private val RELEASING_LEVELS = setOf(
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
    )

    /**
     * True when the graph should be closed at [level]. An unknown level keeps it: the app is
     * better off holding a graph it may need than paying a reload for a signal it does not know.
     */
    fun shouldRelease(level: Int): Boolean = level in RELEASING_LEVELS
}
