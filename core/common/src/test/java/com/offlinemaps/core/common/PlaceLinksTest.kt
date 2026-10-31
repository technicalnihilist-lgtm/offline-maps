package com.offlinemaps.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These strings end up on someone's clipboard and in a share sheet, so the exact shape matters: a
 * malformed `geo:` URI opens the wrong app, and a broken OSM link 404s silently.
 */
class PlaceLinksTest {

    private val mumbai = GeoPoint(19.076090, 72.877426)

    @Test
    fun `geo uri carries six decimals and an optional label`() {
        assertEquals("geo:19.076090,72.877426", PlaceLinks.geoUri(mumbai))
        assertEquals("geo:19.076090,72.877426?q=Gateway%20of%20India", PlaceLinks.geoUri(mumbai, "Gateway of India"))
    }

    @Test
    fun `geo uri omits a blank label`() {
        assertEquals("geo:19.076090,72.877426", PlaceLinks.geoUri(mumbai, "   "))
    }

    @Test
    fun `label encoding is url safe and spaces are not plus signs`() {
        val uri = PlaceLinks.geoUri(mumbai, "Café & Bar 24/7")
        assertTrue("spaces must encode as %20, was $uri", uri.contains("%20"))
        assertTrue("ampersand must be encoded, was $uri", uri.contains("%26"))
        assertTrue("no raw spaces allowed, was $uri", !uri.contains(" "))
    }

    @Test
    fun `osm link points at the coordinate and a zoom`() {
        val link = PlaceLinks.osmLink(mumbai, zoom = 15)
        assertEquals(
            "https://www.openstreetmap.org/?mlat=19.076090&mlon=72.877426#map=15/19.076090/72.877426",
            link,
        )
    }

    @Test
    fun `osm link defaults to zoom 16`() {
        assertTrue(PlaceLinks.osmLink(mumbai).contains("#map=16/"))
    }

    @Test
    fun `share text is multi line and carries both links`() {
        val text = PlaceLinks.shareText("Gateway of India", mumbai)
        val lines = text.lines()
        assertEquals("Gateway of India", lines[0])
        assertEquals("19.076090, 72.877426", lines[1])
        assertTrue(lines[2].startsWith("https://www.openstreetmap.org/"))
        assertTrue(lines[3].startsWith("geo:"))
    }

    @Test
    fun `an unnamed place still shares something readable`() {
        assertTrue(PlaceLinks.shareText("", mumbai).startsWith("Location"))
    }

    @Test
    fun `coordinate text matches the copy action`() {
        assertEquals("19.076090, 72.877426", PlaceLinks.coordinateText(mumbai))
    }

    @Test
    fun `southern and western hemispheres keep their signs`() {
        val capeTown = GeoPoint(-33.924870, 18.424055)
        val quito = GeoPoint(-0.180653, -78.467838)
        assertEquals("geo:-33.924870,18.424055", PlaceLinks.geoUri(capeTown))
        assertEquals("geo:-0.180653,-78.467838", PlaceLinks.geoUri(quito))
        assertTrue(PlaceLinks.osmLink(quito).contains("mlat=-0.180653&mlon=-78.467838"))
    }
}
