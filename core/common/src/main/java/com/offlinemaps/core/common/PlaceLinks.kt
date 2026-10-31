package com.offlinemaps.core.common

/**
 * Formats a coordinate for sharing or copying.
 *
 * Pure so the exact strings the app puts on the clipboard and into a share sheet can be asserted in
 * a plain JVM test - getting these wrong is invisible until someone pastes a link that goes nowhere.
 */
object PlaceLinks {

    /** `geo:` URI understood by Android navigation and map apps. */
    fun geoUri(point: GeoPoint, label: String? = null): String {
        val base = "geo:${point.latitude.format6()},${point.longitude.format6()}"
        val query = label?.takeIf { it.isNotBlank() }?.let { "?q=${encode(it)}" }.orEmpty()
        return base + query
    }

    /** Plain OpenStreetMap link, which works in any browser without an app installed. */
    fun osmLink(point: GeoPoint, zoom: Int = 16): String =
        "https://www.openstreetmap.org/?mlat=${point.latitude.format6()}&mlon=${point.longitude.format6()}" +
            "#map=$zoom/${point.latitude.format6()}/${point.longitude.format6()}"

    /**
     * Body text for the share sheet: a human-readable name, the coordinates, and both links. Kept
     * multi-line so it reads well in a chat message rather than as one long line.
     */
    fun shareText(name: String, point: GeoPoint): String = buildString {
        append(name.ifBlank { "Location" })
        append('\n')
        append(point.format(6))
        append('\n')
        append(osmLink(point))
        append('\n')
        append(geoUri(point, name))
    }

    /** Just the coordinates, for the copy action. */
    fun coordinateText(point: GeoPoint): String = point.format(6)

    private fun Double.format6(): String = String.format(java.util.Locale.US, "%.6f", this)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
