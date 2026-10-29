package com.offlinemaps.core.common

import java.util.Locale
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

    /** ETA as a clock time using the device's locale. */
    fun eta(epochMillis: Long, is24Hour: Boolean = true): String {
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        return java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(epochMillis))
    }

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
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((degrees % 360.0 + 360.0) % 360.0) / 45.0).roundToLong() % 8
        return dirs[idx.toInt()]
    }

    fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
        if (abs(count) == 1) "$count $singular" else "$count $plural"
}
