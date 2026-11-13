package com.offlinemaps.core.map

import com.offlinemaps.core.common.MapTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The style the app hands to MapLibre must be a document MapLibre will accept.
 *
 * MapLibre validates a style when it loads it and, when a property or expression does not parse,
 * it rejects that layer or property instead of telling anyone: the map still draws, it just does
 * not draw what the generator meant. That is how this suite found its first defect - every
 * `interpolate` in `MapStyleFactory` was missing its input expression
 * (`["interpolate", ["linear"], 8, 0.5, 16, 2.5]` instead of
 * `["interpolate", ["linear"], ["zoom"], 8, 0.5, 16, 2.5]`), which the specification reports as
 * "Expected an even number of arguments" and which silently cost the app its roads, waterway,
 * boundaries, building footprints and place labels.
 *
 * Two kinds of test here: the documents the app actually generates must conform for every theme and
 * toggle, and [StyleSpecValidator] itself must bite - a checker that never reports anything would
 * make the first half worthless.
 */
class StyleSpecConformanceTest {

    @Before
    @After
    fun resetCache() = MapStyleFactory.clearStyleCache()

    // ---- the documents the app generates -------------------------------------------------

    @Test
    fun `every theme and toggle combination conforms to the spec`() {
        for (theme in MapTheme.values()) {
            for (buildings3D in listOf(true, false)) {
                for (terrain in listOf(true, false)) {
                    val options = StyleOptions(theme = theme, show3DBuildings = buildings3D, showTerrain = terrain)
                    assertConforms("theme=$theme 3d=$buildings3D terrain=$terrain", MapStyleFactory.buildJson(options))
                }
            }
        }
    }

    @Test
    fun `a self-hosted tile server conforms`() {
        val config = MapConfig(
            vectorTileUrlTemplate = "https://tiles.example.org/planet/{z}/{x}/{y}.pbf",
            tileJsonUrl = null,
            terrainTileUrlTemplate = "https://dem.example.org/{z}/{x}/{y}.png",
            maxZoom = 16,
            terrainMaxZoom = 15,
            attribution = "© OpenStreetMap contributors · self-hosted",
            styleUrl = "https://tiles.example.org/styles/basic",
        )
        assertConforms("self-hosted", MapStyleFactory.buildJson(StyleOptions(config = config)))
    }

    @Test
    fun `a terrain cap outside the DEM range still conforms after clamping`() {
        for (cap in listOf(-5, 0, 12, 99)) {
            val options = StyleOptions(maxTerrainZoom = cap)
            val style = MapStyleFactory.buildJson(options)
            assertConforms("maxTerrainZoom=$cap", style)
            val sources = document(style)["sources"] as JsonObject
            val dem = sources["terrainSource"] as JsonObject
            assertEquals(
                "maxTerrainZoom=$cap",
                options.effectiveTerrainMaxZoom.toDouble(),
                number(dem["maxzoom"]) ?: -1.0,
                0.0,
            )
        }
    }

    @Test
    fun `the document still declares every layer the map draws`() {
        val ids = layers(document(MapStyleFactory.buildJson(StyleOptions()))).map { string(it["id"]) }
        val expected = listOf(
            "background", "hillshade", "land", "landcover", "landuse", "park", "water", "waterway",
            "boundary", "road-casing", "road", "building-flat", MapStyleFactory.BUILDING_3D_LAYER_ID,
            "place-labels", "road-labels", "poi-labels",
        )
        assertEquals(expected, ids)
    }

    @Test
    fun `every interpolate expression names its input`() {
        // The regression this suite was written for: the input expression was missing, so the stop
        // pairs were off by one and MapLibre rejected the property.
        val style = document(MapStyleFactory.buildJson(StyleOptions()))
        val found = mutableListOf<JsonArray>()
        collectInterpolations(style, found)
        assertTrue("the document should interpolate something", found.isNotEmpty())
        for (expression in found) {
            val input = expression.getOrNull(2)
            assertTrue("interpolate has no input expression: $expression", input is JsonArray)
            assertEquals("interpolate input is not zoom: $expression", "zoom", string((input as JsonArray).firstOrNull()))
            assertEquals("interpolate stop pairs are not whole: $expression", 0, (expression.size - 3) % 2)
        }
        assertTrue("waterway width is not zoom-interpolated", found.any { it.toString().contains("0.5") })
    }

    // ---- the validator bites --------------------------------------------------------------

    @Test
    fun `a version other than 8 is rejected`() {
        assertRejected(document().edit { put("version", JsonPrimitive(7)) }, "version")
    }

    @Test
    fun `a document with no layers is rejected`() {
        assertRejected(document().edit { put("layers", JsonArray(emptyList())) }, "layers")
    }

    @Test
    fun `an unknown root property is rejected`() {
        assertRejected(document().edit { put("layerss", JsonArray(emptyList())) }, "unknown property")
    }

    @Test
    fun `a duplicate layer id is rejected`() {
        assertRejected(document().withLayer("land") { put("id", JsonPrimitive("background")) }, "duplicate")
    }

    @Test
    fun `an unknown layer type is rejected`() {
        assertRejected(document().withLayer("park") { put("type", JsonPrimitive("blob")) }, "unknown layer type")
    }

    @Test
    fun `an unknown paint property is rejected`() {
        assertRejected(document().withPaint("water", "fill-shade", JsonPrimitive("#ffffff")), "unknown property")
    }

    @Test
    fun `a layer over an undeclared source is rejected`() {
        assertRejected(document().withLayer("water") { put("source", JsonPrimitive("nope")) }, "not declared")
    }

    @Test
    fun `a background layer carrying a source is rejected`() {
        assertRejected(document().withLayer("background") { put("source", JsonPrimitive("osm")) }, "cannot carry")
    }

    @Test
    fun `a layer over a vector source without a source-layer is rejected`() {
        assertRejected(document().withLayer("water") { remove("source-layer") }, "source-layer")
    }

    @Test
    fun `a hillshade layer over a vector source is rejected`() {
        assertRejected(document().withLayer("hillshade") { put("source", JsonPrimitive("osm")) }, "raster-dem")
    }

    @Test
    fun `a colour that is not a colour is rejected`() {
        assertRejected(document().withPaint("water", "fill-color", JsonPrimitive("blueish")), "not a colour")
    }

    @Test
    fun `an unknown visibility is rejected`() {
        assertRejected(document().withLayout(MapStyleFactory.BUILDING_3D_LAYER_ID, "visibility", JsonPrimitive("hidden")), "must be one of")
    }

    @Test
    fun `an unknown line-cap is rejected`() {
        assertRejected(document().withLayout("waterway", "line-cap", JsonPrimitive("flat")), "must be one of")
    }

    @Test
    fun `an unknown expression operator is rejected`() {
        val lerp = json("""["interpolate",["linear"],["zoom"],8,["lerp",1,2]]""")
        assertRejected(document().withPaint("waterway", "line-width", lerp), "unknown expression")
    }

    @Test
    fun `an interpolate without its input expression is rejected`() {
        val broken = json("""["interpolate",["linear"],8,0.5,16,2.5]""")
        assertRejected(document().withPaint("waterway", "line-width", broken), "interpolate")
    }

    @Test
    fun `an interpolate with an odd stop count is rejected`() {
        val broken = json("""["interpolate",["linear"],["zoom"],8,0.5,16]""")
        assertRejected(document().withPaint("waterway", "line-width", broken), "interpolate")
    }

    @Test
    fun `an unknown interpolation type is rejected`() {
        val broken = json("""["interpolate",["smooth"],["zoom"],8,0.5,16,2.5]""")
        assertRejected(document().withPaint("waterway", "line-width", broken), "interpolation")
    }

    @Test
    fun `minzoom above maxzoom is rejected`() {
        val layer = document().withLayer("waterway") {
            put("minzoom", JsonPrimitive(10))
            put("maxzoom", JsonPrimitive(5))
        }
        assertRejected(layer, "above")
    }

    @Test
    fun `terrain over a vector source is rejected`() {
        val style = document().edit { put("terrain", JsonObject(mapOf("source" to JsonPrimitive("osm")))) }
        assertRejected(style, "raster-dem")
    }

    @Test
    fun `an unknown DEM encoding is rejected`() {
        assertRejected(document().withSource("terrainSource", "encoding", JsonPrimitive("warped")), "encoding")
    }

    @Test
    fun `tiles that are not an array are rejected`() {
        assertRejected(document().withSource("osm", "tiles", JsonPrimitive("https://x/{z}/{x}/{y}.pbf")), "tiles")
    }

    @Test
    fun `a tile template without its placeholders is rejected`() {
        val style = document().withSource("osm", "tiles", json("""["https://tiles.example.org/planet.pbf"]"""))
        assertRejected(style, "{z}")
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun assertConforms(label: String, style: String) {
        val violations = StyleSpecValidator.violations(style)
        assertTrue("$label does not conform:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun assertRejected(style: JsonObject, expected: String) {
        val violations = StyleSpecValidator.violations(style)
        assertTrue("expected a violation mentioning \"$expected\", got none", violations.isNotEmpty())
        assertTrue(
            "expected a violation mentioning \"$expected\", got:\n${violations.joinToString("\n")}",
            violations.any { it.contains(expected) },
        )
    }

    private fun document(style: String = MapStyleFactory.buildJson(StyleOptions())): JsonObject =
        Json.parseToJsonElement(style) as JsonObject

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun layers(style: JsonObject): List<JsonObject> = (style["layers"] as JsonArray).map { it as JsonObject }

    private fun JsonObject.edit(block: MutableMap<String, JsonElement>.() -> Unit): JsonObject =
        JsonObject(toMutableMap().apply(block))

    private fun JsonObject.withLayer(id: String, block: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val edited = JsonArray(
            layers(this).map { layer ->
                if (string(layer["id"]) == id) JsonObject(layer.toMutableMap().apply(block)) else layer
            },
        )
        return edit { put("layers", edited) }
    }

    private fun JsonObject.withPaint(id: String, key: String, value: JsonElement): JsonObject =
        withLayer(id) {
            val paint = (get("paint") as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            paint[key] = value
            put("paint", JsonObject(paint))
        }

    private fun JsonObject.withLayout(id: String, key: String, value: JsonElement): JsonObject =
        withLayer(id) {
            val layout = (get("layout") as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            layout[key] = value
            put("layout", JsonObject(layout))
        }

    private fun JsonObject.withSource(id: String, key: String, value: JsonElement): JsonObject {
        val sources = (this["sources"] as JsonObject).toMutableMap()
        val source = (sources[id] as JsonObject).toMutableMap()
        source[key] = value
        sources[id] = JsonObject(source)
        return edit { put("sources", JsonObject(sources)) }
    }

    private fun collectInterpolations(element: JsonElement, into: MutableList<JsonArray>) {
        when (element) {
            is JsonArray -> {
                if (string(element.firstOrNull()) == "interpolate") into += element
                for (child in element) collectInterpolations(child, into)
            }
            is JsonObject -> for (child in element.values) collectInterpolations(child, into)
            else -> Unit
        }
    }

    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(element: JsonElement?): Double? =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
}
