package com.offlinemaps.app.map

import com.google.common.truth.Truth.assertThat
import com.offlinemaps.core.common.GeoPoint
import org.junit.Test

/**
 * The overlay sources are fed from state that frequently re-emits the same value; these tests pin
 * down that an unchanged payload is never serialised again, while every real change still gets
 * through.
 */
class OverlayPayloadTrackerTest {

    private val source = "app-route-source"

    @Test
    fun firstPushBuildsAndStoresPayload() {
        val tracker = OverlayPayloadTracker()
        var builds = 0

        val payload = tracker.payloadFor(source, "a") {
            builds++
            "{\"first\":true}"
        }

        assertThat(payload).isEqualTo("{\"first\":true}")
        assertThat(builds).isEqualTo(1)
        assertThat(tracker.writes).isEqualTo(1)
        assertThat(tracker.skips).isEqualTo(0)
        assertThat(tracker.lastPayload(source)).isEqualTo("{\"first\":true}")
        assertThat(tracker.trackedSources()).isEqualTo(1)
    }

    @Test
    fun equalKeySkipsTheBuild() {
        val tracker = OverlayPayloadTracker()
        var builds = 0
        val build = {
            builds++
            "{\"n\":$builds}"
        }

        tracker.payloadFor(source, "a", build)
        val second = tracker.payloadFor(source, "a", build)

        assertThat(second).isNull()
        assertThat(builds).isEqualTo(1)
        assertThat(tracker.skips).isEqualTo(1)
        assertThat(tracker.lastPayload(source)).isEqualTo("{\"n\":1}")
    }

    @Test
    fun differentKeyRebuilds() {
        val tracker = OverlayPayloadTracker()
        var builds = 0
        val build = {
            builds++
            "{\"n\":$builds}"
        }

        tracker.payloadFor(source, "a", build)
        val second = tracker.payloadFor(source, "b", build)

        assertThat(second).isEqualTo("{\"n\":2}")
        assertThat(builds).isEqualTo(2)
        assertThat(tracker.writes).isEqualTo(2)
        assertThat(tracker.skips).isEqualTo(0)
    }

    @Test
    fun nullKeyIsTreatedAsAValueNotAsAbsent() {
        val tracker = OverlayPayloadTracker()
        var builds = 0
        val build = {
            builds++
            "{}"
        }

        assertThat(tracker.payloadFor(source, null, build)).isEqualTo("{}")
        assertThat(tracker.payloadFor(source, null, build)).isNull()
        assertThat(builds).isEqualTo(1)
    }

    @Test
    fun sourcesAreTrackedIndependently() {
        val tracker = OverlayPayloadTracker()

        tracker.payloadFor("puck", "same-key") { "puck" }
        val route = tracker.payloadFor("route", "same-key") { "route" }

        assertThat(route).isEqualTo("route")
        assertThat(tracker.trackedSources()).isEqualTo(2)
        assertThat(tracker.writes).isEqualTo(2)
    }

    @Test
    fun geometryListsAreComparedByValueNotByIdentity() {
        val tracker = OverlayPayloadTracker()
        val geometry = listOf(GeoPoint(42.5, 1.5), GeoPoint(42.6, 1.6), GeoPoint(42.7, 1.7))
        var builds = 0
        val build = {
            builds++
            "{\"type\":\"LineString\"}"
        }

        tracker.payloadFor(source, geometry, build)
        // A freshly built, content-equal list must also be recognised as unchanged.
        val rebuilt = geometry.map { it.copy() }
        val second = tracker.payloadFor(source, rebuilt, build)

        assertThat(second).isNull()
        assertThat(builds).isEqualTo(1)

        // One point moved: the geometry really did change, so it must be rebuilt.
        val moved = listOf(geometry[0], geometry[1], GeoPoint(42.71, 1.7))
        assertThat(tracker.payloadFor(source, moved, build)).isEqualTo("{\"type\":\"LineString\"}")
        assertThat(builds).isEqualTo(2)
    }

    @Test
    fun puckKeyComparesPositionAndRadius() {
        val tracker = OverlayPayloadTracker()
        val fix = GeoPoint(19.07, 72.87)
        var builds = 0
        val build = {
            builds++
            "puck"
        }

        tracker.payloadFor("puck", PuckKey(fix, 12f), build)
        // Same position, same radius: skipped.
        assertThat(tracker.payloadFor("puck", PuckKey(fix, 12f), build)).isNull()
        // Same position, materially different accuracy ring: rebuilt.
        assertThat(tracker.payloadFor("puck", PuckKey(fix, 30f), build)).isEqualTo("puck")
        // Moved: rebuilt.
        assertThat(tracker.payloadFor("puck", PuckKey(GeoPoint(19.08, 72.87), 30f), build)).isEqualTo("puck")
        assertThat(builds).isEqualTo(3)
    }

    @Test
    fun resetForcesTheNextPushToRebuild() {
        val tracker = OverlayPayloadTracker()
        var builds = 0
        val build = {
            builds++
            "{}"
        }

        tracker.payloadFor(source, "a", build)
        // A style reload recreates the sources empty, so the cache must not claim they are current.
        tracker.reset()
        val afterReset = tracker.payloadFor(source, "a", build)

        assertThat(afterReset).isEqualTo("{}")
        assertThat(builds).isEqualTo(2)
        assertThat(tracker.trackedSources()).isEqualTo(1)
    }

    /** Mirrors `MapController`'s private puck key: the position plus the derived ring radius. */
    private data class PuckKey(val point: GeoPoint, val radius: Float)
}
