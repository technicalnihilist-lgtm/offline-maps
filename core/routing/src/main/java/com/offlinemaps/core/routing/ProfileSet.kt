package com.offlinemaps.core.routing

import com.offlinemaps.core.common.TravelProfile

/**
 * Canonical form of a requested routing profile set (OPTIMIZATION.md E-02).
 *
 * [RoutingEngine.load] decides whether a graph import is needed by comparing the requested set with
 * the one the loaded graph already serves, and that comparison is only exact if the request itself
 * is canonical. `[CAR, BIKE]` and `[BIKE, CAR]` describe the same graph, and `[CAR, CAR]` is one
 * profile, not two - asking GraphHopper to declare the same profile twice is meaningless, and
 * [RoutingEngine.availableProfiles] would report a duplicate to the UI.
 *
 * Normalising once on the way in makes the no-reload path independent of the order and of any
 * duplicates in the caller's list, without changing which profiles a graph ends up serving.
 */
object ProfileSet {

    /**
     * [profiles] with duplicates removed, in [TravelProfile] declaration order, so two calls that
     * name the same profiles always produce the same list.
     */
    fun canonical(profiles: List<TravelProfile>): List<TravelProfile> {
        if (profiles.isEmpty()) return emptyList()
        val requested = profiles.toHashSet()
        return TravelProfile.entries.filter { it in requested }
    }

    /**
     * True when the profile names a loaded graph serves are exactly the ones [requested] asks for.
     *
     * Compared as sets: a graph that serves `car,bike,foot` is the graph asked for by
     * `[FOOT, CAR, BIKE]`. [requested] is canonicalised here as well, so a caller cannot force a
     * reload by reordering or repeating the same profiles.
     */
    fun matches(loadedProfileNames: Collection<String>, requested: List<TravelProfile>): Boolean =
        loadedProfileNames.toHashSet() == canonical(requested).mapTo(HashSet()) { it.id }
}
