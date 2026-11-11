package com.offlinemaps.core.data

/**
 * Bounds on the recent-search cache.
 *
 * [VISIBLE] is how many entries the saved-places panel shows, and it is the limit every observer
 * uses. [STORED] is how many rows the database keeps: without it the table grows for the life of
 * the install, one row per distinct query ever typed, even though only [VISIBLE] can be shown, so
 * every emission of the flow mapped rows nobody can see. Pruning to [VISIBLE] therefore changes
 * nothing on screen — the store can no longer hold more than the UI could ever render.
 */
object RecentSearchLimits {
    const val VISIBLE: Int = 20
    const val STORED: Int = VISIBLE
}
