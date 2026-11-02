package com.offlinemaps.core.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.offlinemaps.core.common.MapTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    @Before
    fun setUp() {
        StyleFileWriter(context).resetWriteStats()
    }

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
    fun `rewriting unchanged options leaves the file untouched`() {
        val writer = StyleFileWriter(context)
        val file = writer.write(StyleOptions(theme = MapTheme.LIGHT))
        // A sentinel mtime: any real rewrite would stamp the current time over it.
        assertTrue("could not stamp the file", file.setLastModified(1_000L))
        val before = file.readText()

        val again = writer.write(StyleOptions(theme = MapTheme.LIGHT))

        assertEquals("same options must resolve to the same file", file.absolutePath, again.absolutePath)
        assertEquals("the style must not have been rewritten", 1_000L, file.lastModified())
        assertEquals("content must be unchanged", before, again.readText())
        assertEquals(1L, writer.writeCount())
        assertEquals(1L, writer.skippedWriteCount())
    }

    @Test
    fun `changed options rewrite the file`() {
        val writer = StyleFileWriter(context)
        val withTerrain = writer.write(StyleOptions(theme = MapTheme.LIGHT))
        val before = withTerrain.readText()
        assertTrue("could not stamp the file", withTerrain.setLastModified(1_000L))

        val withoutTerrain = writer.write(StyleOptions(theme = MapTheme.LIGHT, showTerrain = false))

        assertEquals(withTerrain.absolutePath, withoutTerrain.absolutePath)
        assertNotEquals("a different style must be written", before, withoutTerrain.readText())
        assertNotEquals("a different style must hit the disk", 1_000L, withoutTerrain.lastModified())
        assertEquals(2L, writer.writeCount())
        assertEquals(0L, writer.skippedWriteCount())
    }

    @Test
    fun `a file that no longer matches is written again`() {
        val writer = StyleFileWriter(context)
        val file = writer.write(StyleOptions(theme = MapTheme.DARK))
        val expected = file.readText()

        // Simulate a truncated file from a killed process: the size check alone must catch it.
        file.writeText("{}")
        val rewritten = writer.write(StyleOptions(theme = MapTheme.DARK))

        assertEquals("the full style must be restored", expected, rewritten.readText())
        assertEquals(2L, writer.writeCount())
        assertEquals(0L, writer.skippedWriteCount())
    }

    @Test
    fun `a deleted style file is recreated`() {
        val writer = StyleFileWriter(context)
        val file = writer.write(StyleOptions(theme = MapTheme.LIGHT))
        val expected = file.readText()
        assertTrue(file.delete())

        val recreated = writer.write(StyleOptions(theme = MapTheme.LIGHT))

        assertTrue("the style file must come back", recreated.exists())
        assertEquals(expected, recreated.readText())
        assertEquals(2L, writer.writeCount())
        assertEquals(0L, writer.skippedWriteCount())
    }

    @Test
    fun `a new writer instance still skips an unchanged file`() {
        // DownloadsViewModel builds a fresh writer per call, so the decision cannot depend on
        // instance state; this one has no in-memory record and must hash what is on disk.
        StyleFileWriter(context).write(StyleOptions(theme = MapTheme.LIGHT))
        // Drop the in-memory record so the second writer has to hash what is on disk.
        StyleFileWriter(context).resetWriteStats()
        val file = java.io.File(StyleFileWriter(context).styleDirectory(), "style-light.json")
        assertTrue(file.setLastModified(1_000L))

        val second = StyleFileWriter(context)
        second.write(StyleOptions(theme = MapTheme.LIGHT))

        assertEquals("a fresh writer must not rewrite identical content", 1_000L, file.lastModified())
        assertEquals(1L, second.skippedWriteCount())
    }

    @Test
    fun `clearing removes the style directory`() {
        val writer = StyleFileWriter(context)
        val file = writer.write(StyleOptions(theme = MapTheme.LIGHT))
        writer.clear()
        assertTrue("style file should be gone", !file.exists())
    }
}
