package com.offlinemaps.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks a generated style document against the MapLibre style specification.
 *
 * MapLibre parses the style when it loads it and, on a violation, rejects the offending layer or
 * property - it does not fail loudly in the app, it just draws something other than what the
 * generator meant. That is exactly the class of mistake a unit test can catch: the document is
 * plain JSON, so every rule below is checkable without a device, a tile server or a renderer.
 *
 * The property tables are the specification's own: they were taken from the `latest.json`
 * reference that ships with `@maplibre/maplibre-gl-style-spec` 26.6.0 (style spec v8) rather than
 * typed from memory, so an unknown property here is an unknown property to MapLibre.
 *
 * This is a structural check, not a byte-for-byte port of the JavaScript validator: it covers the
 * rules that decide whether a document loads and whether a layer is kept - root shape, source
 * definitions, layer types and property names, source references and `source-layer`, zoom bounds,
 * colours, enumerations and expression shape - and it deliberately does not evaluate expressions
 * against feature data, check that a property's value type matches its spec type, or require
 * `glyphs` for text layers (the app's document declares none; see OPTIMIZATION.md).
 *
 * Test-side only: nothing in the app calls this.
 */
internal object StyleSpecValidator {

    private val LAYER_KEYS = setOf(
        "id", "type", "metadata", "source", "source-layer", "minzoom", "maxzoom", "filter",
        "layout", "paint",
    )

    private val TERRAIN_KEYS = setOf("source", "exaggeration")

    private val DEM_ENCODINGS = setOf("terrarium", "mapbox", "custom")

    /** Layer types that draw from a source; every other type must not name one. */
    private val SOURCELESS_TYPES = setOf("background")

    private val ENUMERATED: Map<String, Set<String>> = mapOf(
        "visibility" to setOf("visible", "none"),
        "line-cap" to setOf("butt", "round", "square"),
        "line-join" to setOf("bevel", "round", "miter"),
        "symbol-placement" to setOf("point", "line", "line-center"),
        "resampling" to setOf("linear", "nearest"),
    )

    private val HEX_COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    private val COLOR_FUNCTIONS = listOf("rgb(", "rgba(", "hsl(", "hsla(")

    private val ROOT_KEYS = setOf(
        "bearing", "center", "centerAltitude", "font-faces", "glyphs",
        "layers", "light", "metadata", "name", "pitch",
        "projection", "roll", "sky", "sources", "sprite",
        "state", "terrain", "transition", "version", "zoom",
    )
    private val LAYER_TYPES = setOf("background", "circle", "color-relief", "fill", "fill-extrusion", "heatmap", "hillshade", "line", "raster", "symbol")
    private val SOURCE_TYPES = setOf("vector", "raster", "raster-dem", "geojson", "video", "image")

    private val EXPRESSION_OPERATORS = setOf(
        "!", "!=", "%", "*", "+", "-",
        "/", "<", "<=", "==", ">", ">=",
        "^", "abs", "accumulated", "acos", "all", "any",
        "array", "asin", "at", "atan", "boolean", "case",
        "ceil", "coalesce", "collator", "concat", "cos", "distance",
        "downcase", "e", "elevation", "feature-state", "floor", "format",
        "geometry-type", "get", "global-state", "has", "heatmap-density", "id",
        "image", "in", "index-of", "interpolate", "interpolate-hcl", "interpolate-lab",
        "is-supported-script", "join", "length", "let", "line-progress", "literal",
        "ln", "ln2", "log10", "log2", "match", "max",
        "min", "number", "number-format", "object", "pi", "properties",
        "resolved-locale", "rgb", "rgba", "round", "semiliteral", "sin",
        "slice", "split", "sqrt", "step", "string", "tan",
        "to-boolean", "to-color", "to-number", "to-rgba", "to-string", "typeof",
        "upcase", "var", "within", "zoom",
    )

    private val LAYOUT_PROPERTIES: Map<String, Set<String>> = mapOf(
        "background" to setOf(
            "visibility",
        ),
        "fill" to setOf(
            "fill-sort-key", "visibility",
        ),
        "circle" to setOf(
            "circle-sort-key", "visibility",
        ),
        "heatmap" to setOf(
            "visibility",
        ),
        "fill-extrusion" to setOf(
            "fill-extrusion-rounded-corner-distance", "visibility",
        ),
        "line" to setOf(
            "line-cap", "line-join", "line-miter-limit", "line-round-limit", "line-sort-key",
            "visibility",
        ),
        "symbol" to setOf(
            "icon-allow-overlap", "icon-anchor", "icon-ignore-placement", "icon-image", "icon-keep-upright",
            "icon-offset", "icon-optional", "icon-overlap", "icon-padding", "icon-pitch-alignment",
            "icon-rotate", "icon-rotation-alignment", "icon-size", "icon-text-fit", "icon-text-fit-padding",
            "symbol-avoid-edges", "symbol-height-anchor", "symbol-height-offset", "symbol-placement", "symbol-sort-key",
            "symbol-spacing", "symbol-z-order", "text-allow-overlap", "text-anchor", "text-field",
            "text-font", "text-ignore-placement", "text-justify", "text-keep-upright", "text-letter-spacing",
            "text-line-height", "text-max-angle", "text-max-width", "text-offset", "text-optional",
            "text-overlap", "text-padding", "text-pitch-alignment", "text-radial-offset", "text-rotate",
            "text-rotation-alignment", "text-size", "text-transform", "text-variable-anchor", "text-variable-anchor-offset",
            "text-writing-mode", "visibility",
        ),
        "raster" to setOf(
            "visibility",
        ),
        "hillshade" to setOf(
            "visibility",
        ),
        "color-relief" to setOf(
            "visibility",
        ),
    )

    private val PAINT_PROPERTIES: Map<String, Set<String>> = mapOf(
        "fill" to setOf(
            "fill-antialias", "fill-color", "fill-layer-blend", "fill-layer-opacity", "fill-opacity",
            "fill-outline-color", "fill-pattern", "fill-translate", "fill-translate-anchor",
        ),
        "fill-extrusion" to setOf(
            "fill-extrusion-base", "fill-extrusion-color", "fill-extrusion-height", "fill-extrusion-opacity", "fill-extrusion-pattern",
            "fill-extrusion-translate", "fill-extrusion-translate-anchor", "fill-extrusion-vertical-gradient",
        ),
        "line" to setOf(
            "line-blur", "line-color", "line-dasharray", "line-gap-width", "line-gradient",
            "line-layer-blend", "line-layer-opacity", "line-offset", "line-opacity", "line-pattern",
            "line-translate", "line-translate-anchor", "line-width",
        ),
        "circle" to setOf(
            "circle-blur", "circle-color", "circle-opacity", "circle-pitch-alignment", "circle-pitch-scale",
            "circle-radius", "circle-stroke-color", "circle-stroke-opacity", "circle-stroke-width", "circle-translate",
            "circle-translate-anchor",
        ),
        "heatmap" to setOf(
            "heatmap-color", "heatmap-intensity", "heatmap-opacity", "heatmap-radius", "heatmap-weight",
        ),
        "symbol" to setOf(
            "icon-color", "icon-halo-blur", "icon-halo-color", "icon-halo-width", "icon-opacity",
            "icon-translate", "icon-translate-anchor", "text-color", "text-halo-blur", "text-halo-color",
            "text-halo-width", "text-opacity", "text-translate", "text-translate-anchor",
        ),
        "raster" to setOf(
            "raster-brightness-max", "raster-brightness-min", "raster-contrast", "raster-fade-duration", "raster-hue-rotate",
            "raster-layer-blend", "raster-opacity", "raster-resampling", "raster-saturation", "resampling",
        ),
        "hillshade" to setOf(
            "hillshade-accent-color", "hillshade-exaggeration", "hillshade-highlight-color", "hillshade-illumination-altitude", "hillshade-illumination-anchor",
            "hillshade-illumination-direction", "hillshade-method", "hillshade-shadow-color", "resampling",
        ),
        "color-relief" to setOf(
            "color-relief-color", "color-relief-opacity", "resampling",
        ),
        "background" to setOf(
            "background-color", "background-opacity", "background-pattern",
        ),
    )

    /** Every way [json] departs from the spec. Empty means it conforms. */
    fun violations(json: String): List<String> {
        val parsed = try {
            Json.parseToJsonElement(json)
        } catch (failure: Exception) {
            return listOf("style: not valid JSON (${failure.message})")
        }
        val style = parsed as? JsonObject ?: return listOf("style: the document must be a JSON object")
        return violations(style)
    }

    /** Every way [style] departs from the spec. Empty means it conforms. */
    fun violations(style: JsonObject): List<String> {
        val violations = mutableListOf<String>()
        checkRoot(style, violations)
        val sources = style["sources"] as? JsonObject
        if (sources != null) checkSources(sources, violations)
        val layers = style["layers"] as? JsonArray
        if (layers != null) checkLayers(layers, sources, violations)
        if (style.containsKey("terrain")) checkTerrain(style["terrain"], sources, violations)
        return violations
    }

    private fun checkRoot(style: JsonObject, out: MutableList<String>) {
        for (key in style.keys) {
            if (key !in ROOT_KEYS) out += "root: unknown property \"$key\""
        }
        if (number(style["version"]) != 8.0) out += "root: \"version\" must be the number 8"
        if (style.containsKey("name") && string(style["name"]) == null) {
            out += "root: \"name\" must be a string"
        }
        if (style["sources"] !is JsonObject) out += "root: \"sources\" must be an object"
        val layers = style["layers"]
        if (layers !is JsonArray || layers.isEmpty()) out += "root: \"layers\" must be a non-empty array"
    }

    private fun checkSources(sources: JsonObject, out: MutableList<String>) {
        for ((id, element) in sources) {
            val source = element as? JsonObject
            if (source == null) {
                out += "sources.$id: must be an object"
                continue
            }
            val type = string(source["type"])
            if (type == null || type !in SOURCE_TYPES) {
                out += "sources.$id: \"type\" must be one of $SOURCE_TYPES"
                continue
            }
            if (!source.containsKey("tiles") && !source.containsKey("url") && type != "geojson") {
                out += "sources.$id: needs \"tiles\" or \"url\""
            }
            when (val tiles = source["tiles"]) {
                null -> Unit
                is JsonArray -> {
                    if (tiles.isEmpty()) out += "sources.$id: \"tiles\" must not be empty"
                    for ((index, tile) in tiles.withIndex()) {
                        val template = string(tile)
                        if (template == null) {
                            out += "sources.$id.tiles[$index]: must be a string"
                            continue
                        }
                        for (placeholder in listOf("{z}", "{x}", "{y}")) {
                            if (!template.contains(placeholder)) {
                                out += "sources.$id.tiles[$index]: \"$template\" has no $placeholder"
                            }
                        }
                    }
                }
                else -> out += "sources.$id: \"tiles\" must be an array"
            }
            if (source.containsKey("maxzoom")) {
                val zoom = number(source["maxzoom"])
                if (zoom == null || zoom < 0 || zoom > 24) {
                    out += "sources.$id: \"maxzoom\" must be a number in 0..24"
                }
            }
            if (source.containsKey("tileSize")) {
                val size = number(source["tileSize"])
                if (size == null || size <= 0) out += "sources.$id: \"tileSize\" must be a positive number"
            }
            if (type == "raster-dem") {
                val encoding = string(source["encoding"])
                if (encoding != null && encoding !in DEM_ENCODINGS) {
                    out += "sources.$id: \"encoding\" must be one of $DEM_ENCODINGS"
                }
            } else if (source.containsKey("encoding")) {
                out += "sources.$id: \"encoding\" is only valid on a raster-dem source"
            }
        }
    }

    private fun checkLayers(layers: JsonArray, sources: JsonObject?, out: MutableList<String>) {
        val seen = mutableSetOf<String>()
        for ((index, element) in layers.withIndex()) {
            val layer = element as? JsonObject
            if (layer == null) {
                out += "layers[$index]: must be an object"
                continue
            }
            val id = string(layer["id"])
            val where: String
            if (id == null || id.isBlank()) {
                out += "layers[$index]: \"id\" must be a non-empty string"
                where = "layers[$index]"
            } else {
                if (!seen.add(id)) out += "layers[$index]: duplicate layer id \"$id\""
                where = "layer \"$id\""
            }
            val type = string(layer["type"])
            if (type == null || type !in LAYER_TYPES) {
                out += "$where: unknown layer type ${layer["type"]}"
                continue
            }
            for (key in layer.keys) {
                if (key !in LAYER_KEYS) out += "$where: unknown property \"$key\""
            }
            checkSourceReference(layer, type, sources, where, out)
            checkZoomBounds(layer, where, out)
            checkPropertyBlock(layer["layout"], LAYOUT_PROPERTIES[type].orEmpty(), "layout", where, out)
            checkPropertyBlock(layer["paint"], PAINT_PROPERTIES[type].orEmpty(), "paint", where, out)
        }
    }

    private fun checkSourceReference(
        layer: JsonObject,
        type: String,
        sources: JsonObject?,
        where: String,
        out: MutableList<String>,
    ) {
        if (type in SOURCELESS_TYPES) {
            if (layer.containsKey("source")) out += "$where: a $type layer cannot carry a \"source\""
            if (layer.containsKey("source-layer")) out += "$where: a $type layer cannot carry a \"source-layer\""
            return
        }
        val sourceId = string(layer["source"])
        if (sourceId == null) {
            out += "$where: a $type layer needs a \"source\""
            return
        }
        val source = sources?.get(sourceId) as? JsonObject
        if (source == null) {
            out += "$where: source \"$sourceId\" is not declared in \"sources\""
            return
        }
        val sourceType = string(source["type"])
        when (type) {
            "hillshade" -> if (sourceType != "raster-dem") {
                out += "$where: a hillshade layer needs a raster-dem source, \"$sourceId\" is $sourceType"
            }
            "raster" -> if (sourceType != "raster" && sourceType != "raster-dem") {
                out += "$where: a raster layer needs a raster source, \"$sourceId\" is $sourceType"
            }
            else -> if (sourceType != "vector" && sourceType != "geojson") {
                out += "$where: a $type layer cannot draw from a $sourceType source"
            }
        }
        if (sourceType == "vector") {
            if (string(layer["source-layer"]) == null) {
                out += "$where: a layer over the vector source \"$sourceId\" needs a \"source-layer\""
            }
        } else if (layer.containsKey("source-layer")) {
            out += "$where: \"source-layer\" is only valid for a vector source"
        }
    }

    private fun checkZoomBounds(layer: JsonObject, where: String, out: MutableList<String>) {
        val min = zoomOf(layer, "minzoom", where, out)
        val max = zoomOf(layer, "maxzoom", where, out)
        if (min != null && max != null && min > max) {
            out += "$where: \"minzoom\" $min is above \"maxzoom\" $max"
        }
    }

    private fun zoomOf(layer: JsonObject, key: String, where: String, out: MutableList<String>): Double? {
        if (!layer.containsKey(key)) return null
        val value = number(layer[key])
        if (value == null || value < 0 || value > 24) {
            out += "$where: \"$key\" must be a number in 0..24"
            return null
        }
        return value
    }

    private fun checkPropertyBlock(
        block: JsonElement?,
        allowed: Set<String>,
        kind: String,
        where: String,
        out: MutableList<String>,
    ) {
        if (block == null) return
        val properties = block as? JsonObject
        if (properties == null) {
            out += "$where: \"$kind\" must be an object"
            return
        }
        for ((key, value) in properties) {
            val at = "$where.$kind.$key"
            if (key !in allowed) {
                out += "$at: unknown property"
                continue
            }
            checkValue(at, key, value, out)
        }
    }

    private fun checkValue(at: String, key: String, value: JsonElement, out: MutableList<String>) {
        if (value is JsonArray) {
            // An array whose first element is a known operator is an expression; anything else is a
            // literal array - a font stack, a dash pattern, a list of match labels.
            val head = string(value.firstOrNull())
            if (head != null && head in EXPRESSION_OPERATORS) checkExpression(at, value, out) else checkLiteralArray(at, key, value, out)
            return
        }
        if (key.endsWith("-color")) {
            val text = string(value)
            if (text != null && !isColor(text)) out += "$at: \"$text\" is not a colour"
        }
        val allowed = ENUMERATED[key] ?: return
        val text = string(value) ?: return
        if (text !in allowed) out += "$at: \"$text\" must be one of $allowed"
    }

    private fun checkLiteralArray(at: String, key: String, value: JsonArray, out: MutableList<String>) {
        if (value.isEmpty()) out += "$at: must not be empty"
        if (key == "text-font" && value.any { string(it) == null }) {
            out += "$at: must be an array of font names"
        }
        if (key == "line-dasharray" && value.any { number(it) == null }) {
            out += "$at: must be an array of numbers"
        }
    }

    /**
     * Expression shape, not expression value.
     *
     * The stop counts are the part that actually bites: MapLibre wants
     * `["interpolate", interpolation, input, stop1_input, stop1_output, ...]`, and a document that
     * leaves out the input expression - as this generator did - has an odd number of trailing
     * arguments and is rejected as "Expected an even number of arguments". That is the reason this
     * check exists.
     */
    private fun checkExpression(at: String, expression: JsonArray, out: MutableList<String>) {
        val operator = string(expression.firstOrNull()) ?: return
        if (operator !in EXPRESSION_OPERATORS) {
            out += "$at: unknown expression \"$operator\""
            return
        }
        when (operator) {
            "interpolate" -> {
                if (expression.size < 5 || (expression.size - 3) % 2 != 0) {
                    out += "$at: \"interpolate\" needs an interpolation type, an input expression and " +
                        "whole stop pairs, but has ${expression.size - 1} arguments"
                }
                val interpolation = expression.getOrNull(1)
                if (interpolation !is JsonArray) {
                    out += "$at: \"interpolate\" needs its interpolation type as an array, e.g. [\"linear\"]"
                } else {
                    val kind = string(interpolation.firstOrNull())
                    val expected = when (kind) {
                        "linear" -> 1
                        "exponential" -> 2
                        "cubic-bezier" -> 5
                        else -> null
                    }
                    when {
                        expected == null -> out += "$at: unknown interpolation type \"$kind\""
                        interpolation.size != expected -> out += "$at: \"$kind\" interpolation takes ${expected - 1} argument(s)"
                    }
                }
            }
            "step" -> {
                if (expression.size < 3 || (expression.size - 3) % 2 != 0) {
                    out += "$at: \"step\" needs an input, an output and whole stop pairs, but has ${expression.size - 1} arguments"
                }
            }
        }
        // Positions that hold literals rather than expressions: the interpolation type, the label
        // list of a match, and everything inside a literal/within.
        val literalPositions = when (operator) {
            "interpolate" -> setOf(1)
            "match" -> (2 until expression.size - 1).toSet()
            "literal", "within" -> (1 until expression.size).toSet()
            else -> emptySet()
        }
        for ((index, child) in expression.withIndex()) {
            if (index == 0 || index in literalPositions || child !is JsonArray) continue
            val nested = string(child.firstOrNull())
            when {
                nested == null -> Unit
                nested in EXPRESSION_OPERATORS -> checkExpression(at, child, out)
                else -> out += "$at: unknown expression \"$nested\""
            }
        }
    }

    private fun checkTerrain(element: JsonElement?, sources: JsonObject?, out: MutableList<String>) {
        val terrain = element as? JsonObject
        if (terrain == null) {
            out += "terrain: must be an object"
            return
        }
        for (key in terrain.keys) {
            if (key !in TERRAIN_KEYS) out += "terrain: unknown property \"$key\""
        }
        val sourceId = string(terrain["source"])
        if (sourceId == null) {
            out += "terrain: needs a \"source\""
            return
        }
        val source = sources?.get(sourceId) as? JsonObject
        when {
            source == null -> out += "terrain: source \"$sourceId\" is not declared in \"sources\""
            string(source["type"]) != "raster-dem" -> out += "terrain: source \"$sourceId\" must be a raster-dem source"
        }
        if (terrain.containsKey("exaggeration") && number(terrain["exaggeration"]) == null) {
            out += "terrain: \"exaggeration\" must be a number"
        }
    }

    private fun isColor(text: String): Boolean {
        val value = text.trim()
        if (HEX_COLOR.matches(value)) return true
        return COLOR_FUNCTIONS.any { value.startsWith(it) }
    }

    private fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(element: JsonElement?): Double? =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
}
