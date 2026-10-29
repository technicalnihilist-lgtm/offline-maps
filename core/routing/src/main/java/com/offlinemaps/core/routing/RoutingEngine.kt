package com.offlinemaps.core.routing

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.Instruction
import com.graphhopper.util.PointList
import com.offlinemaps.core.common.GeoMath
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.TravelProfile
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Offline routing backed by GraphHopper running fully on the device.
 *
 * The routing graph is built once from an OpenStreetMap extract the user downloaded, then loaded
 * from disk on subsequent launches (measured at ~0.3 s for a small region). No network is used at
 * any point during routing.
 *
 * GraphHopper 10 rejects the legacy `fastest`/`shortest` weightings for these profiles and requires
 * an explicit custom model per profile. The speed models below cap speed by road class and honour
 * OSM `maxspeed` where present - simplified, and documented as a known limitation.
 */
class RoutingEngine(
    private val graphRoot: File,
    private val encodedValues: String = ENCODED_VALUES,
) {
    private val lock = ReentrantLock()
    private var hopper: GraphHopper? = null
    private var loadedProfiles: List<TravelProfile> = emptyList()

    val isLoaded: Boolean get() = hopper != null
    val availableProfiles: List<TravelProfile> get() = loadedProfiles

    /** True when a graph directory exists on disk for this region. */
    fun hasGraph(): Boolean = graphRoot.isDirectory && (graphRoot.listFiles()?.isNotEmpty() == true)

    /**
     * Imports an OSM extract into the on-device graph, or loads an existing graph.
     * [onProgress] receives coarse phase updates for the UI.
     */
    fun importOrLoad(
        osmFile: File?,
        profiles: List<TravelProfile> = TravelProfile.entries,
        onProgress: (String) -> Unit = {},
    ) = lock.withLock {
        val existing = hopper
        if (existing != null) {
            if (existing.profiles.map { it.name }.toSet() == profiles.map { it.id }.toSet()) return@withLock
            existing.close()
            hopper = null
        }
        graphRoot.mkdirs()
        val h = GraphHopper()
        h.setGraphHopperLocation(graphRoot.absolutePath)
        h.setEncodedValuesString(encodedValues)
        h.setProfiles(buildProfiles(profiles))
        h.setStoreOnFlush(true)
        h.setAllowWrites(true)
        if (osmFile != null && osmFile.exists()) {
            onProgress("Reading OpenStreetMap extract")
            h.setOSMFile(osmFile.absolutePath)
            h.importOrLoad()
        } else {
            onProgress("Loading routing graph")
            h.load()
        }
        hopper = h
        loadedProfiles = profiles
        onProgress("Routing ready")
    }

    fun close() = lock.withLock {
        hopper?.close()
        hopper = null
        loadedProfiles = emptyList()
    }

    /** Calculates a route. Never throws: failures come back as [RouteResult.Failure]. */
    fun route(
        from: GeoPoint,
        to: GeoPoint,
        profile: TravelProfile,
        locale: String = "en",
    ): RouteResult = lock.withLock {
        val h = hopper ?: return RouteResult.Failure(
            "No routing data is installed. Download a region's routing package first.",
            FailureKind.NO_ROUTING_DATA,
        )
        if (profile !in loadedProfiles) {
            return RouteResult.Failure(
                "${profile.label} routing is not available for the installed graph.",
                FailureKind.NO_ROUTING_DATA,
            )
        }
        return try {
            val request = GHRequest(from.latitude, from.longitude, to.latitude, to.longitude)
                .setProfile(profile.id)
                .setLocale(locale)
            request.putHint("instructions", true)
            request.putHint("calc_points", true)
            val response = h.route(request)
            if (response.hasErrors()) {
                val message = response.errors.firstOrNull()?.message ?: "Routing failed"
                val kind = when {
                    message.contains("PointNotFoundException", ignoreCase = true) ||
                        message.contains("Cannot find point", ignoreCase = true) ||
                        message.contains("out of bounds", ignoreCase = true) -> FailureKind.OUTSIDE_COVERAGE
                    message.contains("ConnectionNotFound", ignoreCase = true) ||
                        message.contains("not found", ignoreCase = true) -> FailureKind.NO_ROUTE
                    else -> FailureKind.ENGINE_ERROR
                }
                RouteResult.Failure(
                    when (kind) {
                        FailureKind.OUTSIDE_COVERAGE -> "One of these points is outside the downloaded routing area."
                        FailureKind.NO_ROUTE -> "No route found between these points in the downloaded map data."
                        else -> message
                    },
                    kind,
                )
            } else {
                val best = response.best
                RouteResult.Success(
                    RoutePlanBuilder.build(
                        profile = profile,
                        points = best.points,
                        instructions = best.instructions,
                        distance = best.distance,
                        timeMillis = best.time,
                        ascend = best.ascend,
                        descend = best.descend,
                        origin = from,
                        destination = to,
                    ),
                )
            }
        } catch (e: OutOfMemoryError) {
            RouteResult.Failure(
                "The device ran out of memory while routing. Try a smaller downloaded region.",
                FailureKind.ENGINE_ERROR,
            )
        } catch (e: Exception) {
            RouteResult.Failure(e.message ?: "Routing failed", FailureKind.ENGINE_ERROR)
        }
    }

    /** Nearest routable point to [point], used to snap a map long-press onto the road network. */
    fun snap(point: GeoPoint, profile: TravelProfile): GeoPoint? {
        // GraphHopper exposes no public snapping API; a zero-length request returns the snapped
        // position as the first geometry point.
        val result = route(point, point, profile)
        return (result as? RouteResult.Success)?.plan?.geometry?.firstOrNull()
    }

    private fun buildProfiles(profiles: List<TravelProfile>): List<Profile> = profiles.map { profile ->
        when (profile) {
            TravelProfile.CAR -> Profile("car").setCustomModel(carModel())
            TravelProfile.BIKE -> Profile("bike").setCustomModel(bikeModel())
            TravelProfile.FOOT -> Profile("foot").setCustomModel(footModel())
        }
    }

    companion object {
        const val ENCODED_VALUES =
            "car_access,car_average_speed,bike_access,bike_average_speed,foot_access,foot_average_speed," +
                "road_class,road_environment,road_access,max_speed"

        /**
         * Simplified car speed model: cap by road class, honour OSM maxspeed when tagged.
         * All statements use LIMIT so the effective speed is the minimum of the applicable caps.
         */
        fun carModel(): CustomModel = CustomModel().setDistanceInfluence(70.0)
            .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "130"))
            .addToSpeed(Statement.If("max_speed > 0", Statement.Op.LIMIT, "max_speed"))
            .addToSpeed(Statement.If("road_class == MOTORWAY", Statement.Op.LIMIT, "110"))
            .addToSpeed(Statement.If("road_class == TRUNK", Statement.Op.LIMIT, "90"))
            .addToSpeed(Statement.If("road_class == PRIMARY", Statement.Op.LIMIT, "80"))
            .addToSpeed(Statement.If("road_class == SECONDARY", Statement.Op.LIMIT, "65"))
            .addToSpeed(Statement.If("road_class == TERTIARY", Statement.Op.LIMIT, "55"))
            .addToSpeed(Statement.If("road_class == RESIDENTIAL", Statement.Op.LIMIT, "35"))
            .addToSpeed(Statement.If("road_class == SERVICE", Statement.Op.LIMIT, "20"))
            .addToSpeed(Statement.If("road_class == TRACK", Statement.Op.LIMIT, "15"))

        fun bikeModel(): CustomModel = CustomModel().setDistanceInfluence(30.0)
            .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "25"))
            .addToSpeed(Statement.If("road_class == PRIMARY", Statement.Op.LIMIT, "18"))
            .addToSpeed(Statement.If("road_class == TRACK", Statement.Op.LIMIT, "12"))

        fun footModel(): CustomModel = CustomModel().setDistanceInfluence(0.0)
            .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "5"))
            .addToSpeed(Statement.If("road_class == STEPS", Statement.Op.LIMIT, "2"))
    }
}

/** Turns GraphHopper's raw response into our [RoutePlan], including manoeuvre indices. */
object RoutePlanBuilder {

    fun build(
        profile: TravelProfile,
        points: PointList,
        instructions: List<Instruction>,
        distance: Double,
        timeMillis: Long,
        ascend: Double,
        descend: Double,
        origin: GeoPoint,
        destination: GeoPoint,
    ): RoutePlan {
        val geometry = ArrayList<GeoPoint>(points.size())
        for (i in 0 until points.size()) geometry.add(GeoPoint(points.getLat(i), points.getLon(i)))

        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            cumulative[i] = cumulative[i - 1] + GeoMath.distanceMeters(geometry[i - 1], geometry[i])
        }

        val maneuvers = ArrayList<Maneuver>(instructions.size)
        var searchFrom = 0
        instructions.forEachIndexed { index, instruction ->
            val type = if (index == 0) ManeuverType.DEPART else InstructionMapper.typeForSign(instruction.sign)
            val street = instruction.name?.takeIf { it.isNotBlank() }
            val startPoint = instruction.points.takeIf { it.size() > 0 }?.let {
                GeoPoint(it.getLat(0), it.getLon(0))
            }
            val geometryIndex = if (startPoint == null) searchFrom else indexOfNearest(geometry, startPoint, searchFrom)
            searchFrom = geometryIndex.coerceAtLeast(searchFrom)
            maneuvers.add(
                Maneuver(
                    type = if (index == instructions.size - 1 && type == ManeuverType.UNKNOWN) ManeuverType.ARRIVE else type,
                    sign = instruction.sign,
                    streetName = street,
                    legDistanceMeters = instruction.distance,
                    legTimeSeconds = instruction.time / 1000,
                    geometryIndex = geometryIndex,
                    distanceFromStartMeters = cumulative.getOrElse(geometryIndex) { 0.0 },
                    text = InstructionMapper.text(type, street),
                ),
            )
        }

        return RoutePlan(
            profile = profile,
            geometry = geometry,
            distanceMeters = distance,
            durationSeconds = timeMillis / 1000,
            maneuvers = maneuvers,
            ascendMeters = ascend,
            descendMeters = descend,
            origin = origin,
            destination = destination,
            cumulativeMeters = cumulative,
        )
    }

    private fun indexOfNearest(geometry: List<GeoPoint>, target: GeoPoint, from: Int): Int {
        var bestIndex = from.coerceIn(0, (geometry.size - 1).coerceAtLeast(0))
        var bestDistance = Double.MAX_VALUE
        for (i in from until geometry.size) {
            val d = GeoMath.distanceMeters(geometry[i], target)
            if (d < bestDistance) {
                bestDistance = d
                bestIndex = i
                if (d < 0.5) break
            }
        }
        return bestIndex
    }
}
