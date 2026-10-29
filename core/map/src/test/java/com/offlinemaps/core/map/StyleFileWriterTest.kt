package com.offlinemaps.core.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.offlinemaps.core.common.MapTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The offline tile downloader needs a *style URL*, not a style object, so the generated style has to
 * be written to disk first. If that file is wrong or the URL is malformed, MapLibre's
 * `createOfflineRegion` silently downloads nothing - which is exactly the "download does not work"
 * failure mode this test is here to prevent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StyleFileWriterTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        StyleFileWriter(context).clear()
    }

    @Test
    fun `writes a style file and returns a file url`() {
        val writer = StyleFileWriter(context)
        val url = writer.writeUrl(StyleOptions(theme = MapTheme.LIGHT))

        assertTrue("expected a file:// url, got $url", url.startsWith("file://"))
        val path = url.removePrefix("file://")
        val file = java.io.File(path)
        assertTrue("style file should exist at $path", file.exists())
        assertTrue("style file should not be empty", file.length() > 100L)
    }

    @Test
    fun `written style is valid maplibre json with the expected layers`() {
        val writer = StyleFileWriter(context)
        val json = writer.write(StyleOptions(theme = MapTheme.DARK)).readText()

        assertTrue("must declare style version 8", json.contains("\"version\":8"))
        assertTrue("must reference the vector source", json.contains("openfreemap") || json.contains("tiles"))
        assertTrue("must include extruded buildings", json.contains("fill-extrusion"))
        assertTrue("must include terrain", json.contains("raster-dem"))
        assertTrue("dark theme should use the dark palette", json.contains("#12161c"))
    }

    @Test
    fun `light and dark styles are written to different files`() {
        val writer = StyleFileWriter(context)
        val light = writer.write(StyleOptions(theme = MapTheme.LIGHT))
        val dark = writer.write(StyleOptions(theme = MapTheme.DARK))
        assertTrue(light.absolutePath != dark.absolutePath)
        assertTrue(light.exists() && dark.exists())
    }

    @Test
    fun `terrain can be turned off in the generated style`() {
        val json = StyleFileWriter(context)
            .write(StyleOptions(theme = MapTheme.LIGHT, showTerrain = false))
            .readText()
        assertTrue("terrain source should be absent", !json.contains("raster-dem"))
        assertEquals(false, json.contains("hillshade"))
    }

    @Test
    fun `clearing removes the style directory`() {
        val writer = StyleFileWriter(context)
        val file = writer.write(StyleOptions(theme = MapTheme.LIGHT))
        writer.clear()
        assertTrue("style file should be gone", !file.exists())
    }
}
