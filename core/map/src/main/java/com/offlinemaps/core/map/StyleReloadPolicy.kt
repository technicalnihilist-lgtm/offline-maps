package com.offlinemaps.core.map

/**
 * Decides how a change of [StyleOptions] should reach a live map.
 *
 * Reloading a style document is the expensive path: MapLibre rebuilds every layer, re-creates the
 * sources the style declares, re-requests their tiles, and the app has to re-create its own overlay
 * layers on the new style. Most option changes really do need that (theme colours, terrain, the
 * building min-zoom, a different tile server). The 3D-building toggle does not: the extruded layer
 * is always in the document ([MapStyleFactory.BUILDING_3D_LAYER_ID]) and only its `layout.visibility`
 * differs, which can be written straight onto the live style.
 *
 * Kept as a pure decision so it is testable without a map, a context or a renderer.
 */
object StyleReloadPolicy {

    /**
     * True when [pending] differs from [applied] in the 3D-building flag and in nothing else, i.e.
     * when the whole difference is a layer visibility that can be toggled in place.
     */
    fun isBuildingVisibilityOnlyChange(applied: StyleOptions, pending: StyleOptions): Boolean {
        if (applied == pending) return false
        if (applied.show3DBuildings == pending.show3DBuildings) return false
        // Re-applying the pending flag to the applied options yields the pending options only when
        // every other field already matched.
        return applied.copy(show3DBuildings = pending.show3DBuildings) == pending
    }
}
