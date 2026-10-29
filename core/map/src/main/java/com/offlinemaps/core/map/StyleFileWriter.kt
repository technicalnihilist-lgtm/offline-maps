package com.offlinemaps.core.map

import android.content.Context
import java.io.File

/**
 * Writes the generated map style to a file and hands back a `file://` URL.
 *
 * MapLibre's offline region API needs a *style URL*, not a style object: it fetches the style and
 * then every source it references. Because this app generates its style in code (so light/dark, 3D
 * and terrain toggles need no separate asset files), the style has to be materialised on disk first.
 * Writing it into app storage and passing `file://…` is what makes offline tile downloads work with a
 * generated style.
 */
class StyleFileWriter(private val context: Context) {

    /** Directory MapLibre reads the offline style from. Cleared on demand. */
    fun styleDirectory(): File = File(context.filesDir, "offline-style").apply { mkdirs() }

    /** Writes [options] to disk and returns the absolute path. */
    fun write(options: StyleOptions): File {
        val file = File(styleDirectory(), "style-${options.theme.name.lowercase()}.json")
        file.writeText(MapStyleFactory.buildJson(options))
        return file
    }

    /** Same, as a `file://` URL that MapLibre's offline downloader accepts. */
    fun writeUrl(options: StyleOptions): String = "file://" + write(options).absolutePath

    fun clear() {
        styleDirectory().deleteRecursively()
    }
}
