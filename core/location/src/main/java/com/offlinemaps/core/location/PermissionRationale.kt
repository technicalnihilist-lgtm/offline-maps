package com.offlinemaps.core.location

/** Copy shown in the permission rationale dialog. Kept here so it cannot drift between screens. */
object PermissionRationale {
    const val LOCATION_TITLE = "Why location access?"

    const val LOCATION_BODY =
        "Your position is used to show where you are on the map and to give turn-by-turn " +
            "directions. It stays on this device - we never upload your location or your trips. " +
            "Without this permission the map still works; only the blue location dot and live " +
            "navigation are unavailable."

    const val BACKGROUND_TITLE = "Navigation with the screen off"

    const val BACKGROUND_BODY =
        "To keep giving directions while your screen is off, the app needs to run a navigation " +
            "service. It only runs while you have an active route, shows a permanent notification, " +
            "and stops as soon as you end navigation."

    const val STORAGE_BODY =
        "Downloaded map regions are stored on this device so you can navigate without a network. " +
            "You can delete any region at any time from the Downloads screen."
}
