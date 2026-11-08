package com.offlinemaps.core.routing

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The release decision for the routing graph on a memory trim (OPTIMIZATION.md E-01).
 *
 * The point of pinning it is the negative half: a level that means "the app is visible and fine"
 * must not free the graph, or every preview would pay for a reload it did not need.
 */
@RunWith(RobolectricTestRunner::class)
class RoutingMemoryPolicyTest {

    @Test
    fun `levels that mean real pressure or no visible ui release the graph`() {
        val releasing = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        releasing.forEach { level ->
            assertTrue("level $level should release the graph", RoutingMemoryPolicy.shouldRelease(level))
        }
    }

    @Test
    fun `a visible app with memory to spare keeps the graph`() {
        val keeping = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            0,
            -1,
            Int.MAX_VALUE,
        )
        keeping.forEach { level ->
            assertFalse("level $level should keep the graph", RoutingMemoryPolicy.shouldRelease(level))
        }
    }

    @Test
    fun `the two trimming signals are not the same level`() {
        // A regression that collapsed the policy to "any trim releases" would pass the positive
        // cases above, so the visible-with-memory case is pinned separately.
        assertFalse(RoutingMemoryPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertTrue(RoutingMemoryPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    }
}
