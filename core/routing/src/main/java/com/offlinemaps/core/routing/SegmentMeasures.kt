package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint

/**
 * The two measures [GeoMath.crossTrackDistanceMeters] needs from the segment itself - its length
 * and its bearing from its start - remembered per segment of one route geometry
 * (OPTIMIZATION.md J-20).
 *
 * Neither number depends on the fix being measured: the length of segment 17 and its bearing are
 * properties of the route. Measuring them inside the cross-track call therefore repeated them for
 * every fix that touched the same segment, and the follow window touches the same forty-odd
 * segments over and over - a fix per second, moving a few metres, so the window slides by a segment
 * every few seconds while every segment in it is re-measured on every fix. The grid scan has the
 * same shape: a fix that lands far from the route walks the segments of the cells it visits, and the
 * next such fix usually visits the same ones.
 *
 * Each segment is measured the first time it is asked for and never again, so the cost of a session
 * is one length and one bearing per segment of the route at most, spread over the fixes that touch
 * them, instead of two measurements per segment per fix. Nothing is measured up front: a session
 * that never leaves the first few segments never pays for the rest of the geometry.
 *
 * The answers are the ones the three-argument [GeoMath.crossTrackDistanceMeters] returns, because
 * the pair is measured exactly as it measures them and handed to the overload that takes them.
 *
 * Not thread-safe: one instance belongs to one tracker and is used from the thread that feeds it
 * fixes, which is the same rule [RouteSegmentIndex] follows.
 */
internal class SegmentMeasures(private val geometry: List<GeoPoint>) {

    private val count = (geometry.size - 1).coerceAtLeast(0)
    private val lengths = DoubleArray(count)
    private val bearings = DoubleArray(count)
    private val measured = BooleanArray(count)

    /**
     * Segments measured since this instance was built. Diagnostics only - the tests pin the work a
     * fix costs with this rather than with timing, the way [RouteSegmentIndex.evaluations] does.
     */
    var measurements: Long = 0
        private set

    /** Length of segment [index] in metres, measured once. */
    fun lengthMeters(index: Int): Double {
        measure(index)
        return lengths[index]
    }

    /** Bearing along segment [index] from its start, measured once. */
    fun bearingDegrees(index: Int): Double {
        measure(index)
        return bearings[index]
    }

    /**
     * Perpendicular distance from [point] to segment [index], the same number
     * [GeoMath.crossTrackDistanceMeters] returns for the segment's two endpoints, with the
     * segment's own measures reused instead of re-measured.
     */
    fun crossTrackDistanceMeters(point: GeoPoint, index: Int): Double {
        measure(index)
        return GeoMath.crossTrackDistanceMeters(
            point,
            geometry[index],
            geometry[index + 1],
            lengths[index],
            bearings[index],
        )
    }

    private fun measure(index: Int) {
        if (measured[index]) return
        lengths[index] = GeoMath.distanceMeters(geometry[index], geometry[index + 1])
        bearings[index] = GeoMath.bearingDegrees(geometry[index], geometry[index + 1])
        measured[index] = true
        measurements++
    }
}
