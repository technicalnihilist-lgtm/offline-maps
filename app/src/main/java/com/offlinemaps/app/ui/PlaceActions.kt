package com.offlinemaps.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.offlinemaps.core.common.GeoPoint
import com.offlinemaps.core.common.PlaceLinks

/**
 * Copy and share actions for a place.
 *
 * Kept out of the composables so the same behaviour backs the saved list, the destination card and
 * anywhere else a coordinate is shown, and so the clipboard/share paths stay one implementation.
 */
object PlaceActions {

    fun copyCoordinates(context: Context, point: GeoPoint) {
        val text = PlaceLinks.coordinateText(point)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", text))
        // Android 13+ shows its own copy confirmation; older versions need one from us.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shares the place as text: name, coordinates, an OpenStreetMap link and a `geo:` URI. A chooser
     * is used rather than a bare ACTION_SEND so the user sees what they are sending it to.
     */
    fun sharePlace(context: Context, name: String, point: GeoPoint) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name.ifBlank { "Location" })
            putExtra(Intent.EXTRA_TEXT, PlaceLinks.shareText(name, point))
        }
        val chooser = Intent.createChooser(intent, "Share location").apply {
            // Required when starting from an Activity context on some OEM builds.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
            .onFailure { Toast.makeText(context, "No app available to share with", Toast.LENGTH_SHORT).show() }
    }
}
