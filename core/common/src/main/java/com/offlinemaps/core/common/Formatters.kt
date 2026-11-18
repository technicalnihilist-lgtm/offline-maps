package com.offlinemaps.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToLong

/** Locale-aware formatting for the strings the UI shows everywhere. */
object Formatters {

    /** Metric distance, e.g. "350 m", "1.2 km", "18 km". */
    fun distance(meters: Double, metric: Boolean = true): String {
        if (!meters.isFinite() || meters < 0) return "-"
        return if (metric) {
            when {
                meters < 1000 -> "${meters.roundToLong()} m"
                meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
                else -> "${(meters / 1000.0).roundToLong()} km"
            }
        } else {
            val feet = meters * 3.28084
            if (feet < 1000) "${feet.roundToLong()} ft" else String.format(Locale.US, "%.1f mi", meters / 1609.344)
        }
    }

    /** Short duration, e.g. "45 s", "12 min", "1 h 25 min". */
    fun duration(seconds: Long): String {
        if (seconds < 0) return "-"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }

    /**
     * ETA as a clock time using the device's locale.
     *
     * The navigation panel reads this on every fix, and `SimpleDateFormat` parses its pattern and
     * resolves its date symbols in the constructor, so building one per read made a per-second
     * update pay for a formatter it then threw away (OPTIMIZATION.md J-24). One formatter per
     * pattern is kept per thread and reused, with the mutable `Date` it formats into.
     *
     * `SimpleDateFormat` is not thread-safe, which is why the cache is a `ThreadLocal` rather than a
     * shared field. The cached instance is rebuilt when the pattern, the default locale or the
     * default time zone changes, so a language or time-zone change is reflected on the next read
     * exactly as a freshly built formatter would have reflected it.
     */
    fun eta(epochMillis: Long, is24Hour: Boolean = true): String {
        val slot = if (is24Hour) 0 else 1
        val pattern = if (is24Hour) ETA_24_HOUR else ETA_12_HOUR
        return etaFormat(slot, pattern, Locale.getDefault(), TimeZone.getDefault().id).format(epochMillis)
    }

    /**
     * The formatter this thread last built for [eta], and what it was built for.
     *
     * The time zone is applied explicitly rather than left to the constructor, so the cached
     * instance carries the zone it was keyed on and a change of zone is a miss instead of a wrong
     * clock time.
     */
    private class EtaFormat(val pattern: String, val locale: Locale, val zoneId: String) {
        private val format = SimpleDateFormat(pattern, locale).apply {
            timeZone = TimeZone.getTimeZone(zoneId)
        }
        private val instant = Date()

        fun matches(pattern: String, locale: Locale, zoneId: String): Boolean =
            this.pattern == pattern && this.locale == locale && this.zoneId == zoneId

        fun format(epochMillis: Long): String {
            instant.time = epochMillis
            return format.format(instant)
        }
    }

    /** One slot per pattern: a caller that alternates 24-hour and 12-hour reads keeps both. */
    private const val ETA_SLOTS = 2

    private val etaFormats = ThreadLocal<Array<EtaFormat?>>()
    private val etaFormatBuildCount = AtomicInteger(0)

    private fun etaFormat(slot: Int, pattern: String, locale: Locale, zoneId: String): EtaFormat {
        val slots = etaFormats.get() ?: arrayOfNulls<EtaFormat>(ETA_SLOTS).also { etaFormats.set(it) }
        val cached = slots[slot]
        if (cached != null && cached.matches(pattern, locale, zoneId)) return cached
        val built = EtaFormat(pattern, locale, zoneId)
        etaFormatBuildCount.incrementAndGet()
        slots[slot] = built
        return built
    }

    /**
     * How many ETA formatters have been built. A test hook, never read by the app: it is what pins
     * [eta] to one build per thread instead of one per call (OPTIMIZATION.md J-24).
     */
    internal fun etaFormatsBuilt(): Int = etaFormatBuildCount.get()

    /** Byte sizes in binary units, e.g. "512 KB", "1.4 GB". */
    fun bytes(bytes: Long): String {
        if (bytes < 0) return "-"
        val kb = 1024.0
        return when {
            bytes < kb -> "$bytes B"
            bytes < kb * kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            bytes < kb * kb * kb -> String.format(Locale.US, "%.1f MB", bytes / (kb * kb))
            else -> String.format(Locale.US, "%.2f GB", bytes / (kb * kb * kb))
        }
    }

    fun speed(metersPerSecond: Double, metric: Boolean = true): String {
        if (!metersPerSecond.isFinite()) return "-"
        return if (metric) "${(metersPerSecond * 3.6).roundToLong()} km/h"
        else "${(metersPerSecond * 2.23694).roundToLong()} mph"
    }

    /** Download rate, e.g. "2.4 MB/s". */
    fun rate(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "-" else "${bytes(bytesPerSecond)}/s"

    /** Remaining download time from bytes left and current rate. */
    fun remainingTime(bytesLeft: Long, bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0 || bytesLeft <= 0) "-" else duration(bytesLeft / bytesPerSecond)

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).roundToLong()}%"

    fun accuracy(meters: Float?): String =
        if (meters == null || !meters.isFinite()) "unknown" else "+/- ${meters.roundToLong()} m"

    fun bearingCompass(degrees: Double): String {
        val idx = (((degrees % 360.0 + 360.0) % 360.0) / 45.0).roundToLong() % 8
        return COMPASS_POINTS[idx.toInt()]
    }

    fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        if (abs(count) == 1) "$count $singular" else "$count $plural"

    private const val ETA_24_HOUR = "HH:mm"
    private const val ETA_12_HOUR = "h:mm a"

    /** The eight compass octants, in order from north. Built once, not once per call. */
    private val COMPASS_POINTS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
}
