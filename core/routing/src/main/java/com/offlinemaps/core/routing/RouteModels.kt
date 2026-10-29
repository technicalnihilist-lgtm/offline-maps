package com.offlinemaps.core.routing

import com.offlinemaps.core.common.GeoBounds
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.TravelProfile

/** What the user should do at a manoeuvre. Derived from GraphHopper's instruction sign. */
enum class ManeuverType {
    DEPART, CONTINUE, TURN_LEFT, TURN_SLIGHT_LEFT, TURN_SHARP_LEFT,
    TURN_RIGHT, TURN_SLIGHT_RIGHT, TURN_SHARP_RIGHT, KEEP_LEFT, KEEP_RIGHT,
    U_TURN, ROUNDABOUT_ENTER, ROUNDABOUT_EXIT, ARRIVE, UNKNOWN,
}

/** One turn instruction with its position along the route geometry. */
data class Maneuver(
    val type: ManeuverType,
    /** GraphHopper sign, kept for roundabout numbering and diagnostics. */
    val sign: Int,
    val streetName: String?,
    /** Distance of the leg that this manoeuvre starts, in metres. */
    val legDistanceMeters: Double,
    val legTimeSeconds: Long,
    /** Index into [RoutePlan.geometry] where this manoeuvre begins. */
    val geometryIndex: Int,
    /** Distance from the route start to this manoeuvre, in metres. */
    val distanceFromStartMeters: Double,
    /** Human readable instruction, e.g. "Turn right onto Carrer Mossèn Tremosa". */
    val text: String,
)

/** A calculated route that can be displayed and navigated. */
data class RoutePlan(
    val profile: TravelProfile,
    val geometry: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val maneuvers: List<Maneuver>,
    val ascendMeters: Double = 0.0,
    val descendMeters: Double = 0.0,
    val origin: GeoPoint,
    val destination: GeoPoint,
    /** Cumulative distance from the start at each geometry index; same size as [geometry]. */
    val cumulativeMeters: DoubleArray = DoubleArray(0),
) {
    val bounds: GeoBounds? get() = GeoBounds.of(geometry)
    val isEmpty: Boolean get() = geometry.size < 2
}

/** Result of a routing request - either a plan or an explanation the UI can show verbatim. */
sealed interface RouteResult {
    data class Success(val plan: RoutePlan) : RouteResult
    data class Failure(val message: String, val kind: FailureKind) : RouteResult
}

enum class FailureKind {
    /** No routing graph installed for the area at all. */
    NO_ROUTING_DATA,

    /** A routing graph exists, but one endpoint falls outside it. */
    OUTSIDE_COVERAGE,

    /** Graph present and endpoints inside it, but no connected path exists. */
    NO_ROUTE,

    /** Engine error - corrupt graph, import failure, out of memory. */
    ENGINE_ERROR,
}
