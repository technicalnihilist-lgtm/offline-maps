package com.offlinemaps.core.map

import com.offlinemaps.core.common.MapTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a MapLibre style document for the app.
 *
 * The style is generated in code rather than shipped as a static JSON file for three reasons:
 * light/dark themes share one definition, the 3D building and terrain layers can be toggled at
 * runtime, and a self-hoster can point the app at their own tile server without editing assets.
 *
 * Source schema is OpenMapTiles v3 (as served by OpenFreeMap). Layer names follow that schema:
 * water, waterway, landcover, landuse, park, boundary, transportation, building, place, poi.
 *
 * Building heights: OpenMapTiles publishes `render_height`. When a building only carries `levels`,
 * height is estimated at 3 m per level; with no height data at all we fall back to 4 m. Those
 * estimates are labelled as such in the UI - we never claim a photorealistic model.
 */
object MapStyleFactory {

    private data class Palette(
        val background: String,
        val land: String,
        val landcover: String,
        val park: String,
        val water: String,
        val roadMinor: String,
        val roadMajor: String,
        val roadCasing: String,
        val boundary: String,
        val building: String,
        val label: String,
        val labelHalo: String,
        val roadLabel: String,
        val hillshade: String,
    )

    private val LIGHT = Palette(
        background = "#f4f1ea", land = "#f7f4ee", landcover = "#e9efe0", park = "#dcebcf",
        water = "#a9cbe3", roadMinor = "#ffffff", roadMajor = "#ffd9a0", roadCasing = "#dcd6c8",
        boundary = "#b0a89a", building = "#e3ddd1", label = "#3a3a3a", labelHalo = "#ffffff",
        roadLabel = "#6b6b6b", hillshade = "#000000",
    )

    private val DARK = Palette(
        background = "#12161c", land = "#171c23", landcover = "#1b2430", park = "#16241c",
        water = "#0f2233", roadMinor = "#2a3340", roadMajor = "#3b4757", roadCasing = "#0c1015",
        boundary = "#39434f", building = "#242b35", label = "#d7dde5", labelHalo = "#0b0e12",
        roadLabel = "#9aa5b1", hillshade = "#ffffff",
    )

    fun buildJson(options: StyleOptions): String {
        val palette = if (options.theme == MapTheme.DARK) DARK else LIGHT
        val style = buildJsonObject {
            put("version", 8)
            put("name", "Offline Maps ${options.theme.name.lowercase()}")
            putJsonObject("sources") {
                putJsonObject("osm") {
                    put("type", "vector")
                    putJsonArray("tiles") { add(options.config.vectorTileUrlTemplate) }
                    put("maxzoom", options.config.maxZoom)
                    put("attribution", options.config.attribution)
                }
                if (options.showTerrain) {
                    putJsonObject("terrainSource") {
                        put("type", "raster-dem")
                        putJsonArray("tiles") { add(options.config.terrainTileUrlTemplate) }
                        put("tileSize", 256)
                        put("maxzoom", options.maxTerrainZoom)
                        put("encoding", "terrarium")
                    }
                }
            }
            if (options.showTerrain) {
                putJsonObject("terrain") {
                    put("source", "terrainSource")
                    put("exaggeration", 1.3)
                }
            }
            put("layers", buildLayers(options, palette))
        }
        return style.toString()
    }

    private fun buildLayers(options: StyleOptions, p: Palette): JsonArray = buildJsonArray {
        add(layer("background", "background") {
            putJsonObject("paint") { put("background-color", p.background) }
        })

        if (options.showTerrain) {
            add(layer("hillshade", "hillshade") {
                put("source", "terrainSource")
                putJsonObject("paint") {
                    put("hillshade-exaggeration", 0.25)
                    put("hillshade-shadow-color", p.hillshade)
                }
            })
        }

        add(layer("land", "background") {
            putJsonObject("paint") { put("background-color", p.land) }
        })

        add(layer("landcover", "fill") {
            put("source", "osm"); put("source-layer", "landcover")
            putJsonObject("paint") {
                put("fill-color", p.landcover)
                put("fill-opacity", 0.85)
            }
        })

        add(layer("landuse", "fill") {
            put("source", "osm"); put("source-layer", "landuse")
            putJsonObject("paint") {
                put("fill-color", p.landcover)
                put("fill-opacity", 0.5)
            }
        })

        add(layer("park", "fill") {
            put("source", "osm"); put("source-layer", "park")
            putJsonObject("paint") { put("fill-color", p.park) }
        })

        add(layer("water", "fill") {
            put("source", "osm"); put("source-layer", "water")
            putJsonObject("paint") { put("fill-color", p.water) }
        })

        add(layer("waterway", "line") {
            put("source", "osm"); put("source-layer", "waterway")
            putJsonObject("layout") { put("line-cap", "round") }
            putJsonObject("paint") {
                put("line-color", p.water)
                put("line-width", expr("interpolate", arrayOf("linear"), 8, 0.5, 16, 2.5))
            }
        })

        add(layer("boundary", "line") {
            put("source", "osm"); put("source-layer", "boundary")
            putJsonObject("paint") {
                put("line-color", p.boundary)
                put("line-width", expr("interpolate", arrayOf("linear"), 2, 0.6, 8, 1.6))
                put("line-dasharray", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(1))))
            }
        })

        add(layer("road-casing", "line") {
            put("source", "osm"); put("source-layer", "transportation")
            putJsonObject("layout") { put("line-cap", "round"); put("line-join", "round") }
            putJsonObject("paint") {
                put("line-color", p.roadCasing)
                put(
                    "line-width",
                    expr(
                        "interpolate", arrayOf("linear"), 10,
                        expr(
                            "match", arrayOf("get", "class"),
                            arrayOf("motorway"), 2.0, arrayOf("trunk"), 1.8, arrayOf("primary"), 1.4,
                            arrayOf("secondary"), 1.0, arrayOf("tertiary"), 0.8, 0.4,
                        ),
                        16,
                        expr(
                            "match", arrayOf("get", "class"),
                            arrayOf("motorway"), 14.0, arrayOf("trunk"), 12.0, arrayOf("primary"), 9.0,
                            arrayOf("secondary"), 7.0, arrayOf("tertiary"), 5.0, 3.0,
                        ),
                    ),
                )
            }
        })

        add(layer("road", "line") {
            put("source", "osm"); put("source-layer", "transportation")
            putJsonObject("layout") { put("line-cap", "round"); put("line-join", "round") }
            putJsonObject("paint") {
                put(
                    "line-color",
                    expr("match", arrayOf("get", "class"), arrayOf("motorway", "trunk"), p.roadMajor, p.roadMinor),
                )
                put(
                    "line-width",
                    expr(
                        "interpolate", arrayOf("linear"), 10,
                        expr(
                            "match", arrayOf("get", "class"),
                            arrayOf("motorway"), 1.4, arrayOf("trunk"), 1.2, arrayOf("primary"), 1.0,
                            arrayOf("secondary"), 0.7, arrayOf("tertiary"), 0.5, 0.3,
                        ),
                        16,
                        expr(
                            "match", arrayOf("get", "class"),
                            arrayOf("motorway"), 11.0, arrayOf("trunk"), 9.0, arrayOf("primary"), 7.0,
                            arrayOf("secondary"), 5.0, arrayOf("tertiary"), 3.5, 2.0,
                        ),
                    ),
                )
            }
        })

        // Buildings: flat footprints at mid zoom, extruded above the configured zoom.
        add(layer("building-flat", "fill") {
            put("source", "osm"); put("source-layer", "building")
            put("minzoom", 13)
            putJsonObject("paint") {
                put("fill-color", p.building)
                put("fill-opacity", expr("interpolate", arrayOf("linear"), 13, 0.0, 15, 0.7))
            }
        })

        if (options.show3DBuildings) {
            add(buildJsonObject {
                put("id", "building-3d")
                put("type", "fill-extrusion")
                put("source", "osm")
                put("source-layer", "building")
                put("minzoom", options.buildingsMinZoom)
                putJsonObject("paint") {
                    put("fill-extrusion-color", p.building)
                    // Height: real data first, then a documented estimate (3 m per level, else 4 m).
                    put(
                        "fill-extrusion-height",
                        expr(
                            "coalesce",
                            arrayOf("get", "render_height"),
                            arrayOf("get", "height"),
                            arrayOf("to-number", arrayOf("*", arrayOf("coalesce", arrayOf("get", "levels"), 3), 3)),
                            4,
                        ),
                    )
                    put(
                        "fill-extrusion-base",
                        expr(
                            "coalesce",
                            arrayOf("get", "render_min_height"),
                            arrayOf("get", "min_height"),
                            0,
                        ),
                    )
                    put("fill-extrusion-opacity", 0.92)
                }
            })
        }

        add(layer("place-labels", "symbol") {
            put("source", "osm"); put("source-layer", "place")
            putJsonObject("layout") {
                put("text-field", expr("get", "name"))
                put(
                    "text-size",
                    expr(
                        "interpolate", arrayOf("linear"), 3,
                        expr("match", arrayOf("get", "class"), arrayOf("country"), 15, arrayOf("state"), 12, 10),
                        8,
                        expr(
                            "match", arrayOf("get", "class"),
                            arrayOf("country"), 17, arrayOf("city"), 15, arrayOf("town"), 13, 11,
                        ),
                    ),
                )
                put("text-font", JsonArray(listOf(JsonPrimitive("Noto Sans Regular"))))
                put("text-max-width", 8)
            }
            putJsonObject("paint") {
                put("text-color", p.label)
                put("text-halo-color", p.labelHalo)
                put("text-halo-width", 1.2)
            }
        })

        add(layer("road-labels", "symbol") {
            put("source", "osm"); put("source-layer", "transportation_name")
            put("minzoom", 14)
            putJsonObject("layout") {
                put("text-field", expr("get", "name"))
                put("symbol-placement", "line")
                put("text-size", 11)
                put("text-font", JsonArray(listOf(JsonPrimitive("Noto Sans Regular"))))
            }
            putJsonObject("paint") {
                put("text-color", p.roadLabel)
                put("text-halo-color", p.labelHalo)
                put("text-halo-width", 1.0)
            }
        })

        add(layer("poi-labels", "symbol") {
            put("source", "osm"); put("source-layer", "poi")
            put("minzoom", 15)
            putJsonObject("layout") {
                put("text-field", expr("get", "name"))
                put("text-size", 11)
                put("text-font", JsonArray(listOf(JsonPrimitive("Noto Sans Regular"))))
                put("text-max-width", 9)
            }
            putJsonObject("paint") {
                put("text-color", p.label)
                put("text-halo-color", p.labelHalo)
                put("text-halo-width", 1.0)
            }
        })
    }

    /** Builds a MapLibre expression array from mixed Kotlin values. */
    private fun expr(vararg values: Any?): JsonElement = toJson(values.toList())

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Array<*> -> JsonArray(value.map { toJson(it) })
        is List<*> -> JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun layer(id: String, type: String, block: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("id", id)
            put("type", type)
            block()
        }
}
