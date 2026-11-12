package com.offlinemaps.core.routing

import java.io.File

/**
 * Checks that an unpacked routing package really is a graph this app can load (OPTIMIZATION.md I-05).
 *
 * A routing package is a zip of a GraphHopper graph directory, so anything can arrive inside it: a
 * re-zipped tile archive, a truncated download, a graph built with a different encoder set. Until
 * now every one of those reached `GraphHopper.importOrLoad()` and came back as
 * "The routing graph could not be loaded (IllegalStateException: Encoders do not match ...)" - or,
 * when the graph was not a graph at all, as an attempt to *import* it, which on Android cannot work
 * because GraphHopper's OSM reader needs `javax.xml.stream`.
 *
 * Everything here is a cheap file check, done before the engine is asked to open anything, so a bad
 * package is rejected with a sentence the user can act on and never reaches the loader. The check is
 * deliberately read-only: it never writes to, moves or deletes the directory.
 */
object RoutingGraphPackage {

    /**
     * Files GraphHopper 2.4 needs to open a graph without importing it.
     *
     * `string_index_keys` / `string_index_vals` are not listed: they carry street names, so a graph
     * that somehow lost them can still route (guidance just has no names), and rejecting it would
     * throw away a working graph.
     */
    val REQUIRED_FILES = listOf("properties", "nodes", "edges", "geometry", "location_index")

    /** Key in the graph's `properties` file that names the flag encoders it was built with. */
    const val FLAG_ENCODERS_KEY = "graph.flag_encoders"

    /** What [inspect] found. */
    sealed interface Verdict {
        /** The directory holds a graph built with the encoders this app asks for. */
        data object Usable : Verdict

        /** The directory cannot be loaded, for [problem]. */
        data class Unusable(val problem: Problem) : Verdict
    }

    /** Why a package cannot be used. Each maps to one sentence in [describe]. */
    enum class Problem {
        /** Nothing has been unpacked here yet. */
        MISSING_DIRECTORY,

        /** A file GraphHopper needs is not in the package. */
        MISSING_FILE,

        /** A required file is there but has no bytes - a truncated or half-written unpack. */
        EMPTY_FILE,

        /** `properties` is readable but says nothing about encoders, so this is not a graph. */
        NO_ENCODERS,

        /** The graph was built with a different set of vehicles than the app can ask for. */
        WRONG_FLAGS,
    }

    /**
     * Inspects [directory], the root of an unpacked graph package.
     *
     * [expectedFlags] is the encoder list the app loads graphs with, `car,bike,foot` by default.
     * Order does not matter: the graph's list is compared as a set of encoder names, ignoring the
     * `|speed_factor=...` parameters GraphHopper stores next to each name.
     */
    fun inspect(directory: File, expectedFlags: String = RoutingEngine.ENCODING_FLAGS): Verdict {
        if (!directory.isDirectory) return Verdict.Unusable(Problem.MISSING_DIRECTORY)
        for (name in REQUIRED_FILES) {
            val file = File(directory, name)
            if (!file.isFile) return Verdict.Unusable(Problem.MISSING_FILE)
            if (file.length() == 0L) return Verdict.Unusable(Problem.EMPTY_FILE)
        }
        val built = encodersIn(File(directory, "properties"))
            ?: return Verdict.Unusable(Problem.NO_ENCODERS)
        if (built != encoderNames(expectedFlags)) return Verdict.Unusable(Problem.WRONG_FLAGS)
        return Verdict.Usable
    }

    /**
     * The encoder names recorded in a graph's `properties` file, or null when the file carries no
     * `graph.flag_encoders` entry.
     *
     * The file is GraphHopper's `StorableProperties` store: a small binary header followed by
     * newline-separated `key=value` text, so it is read as ISO-8859-1 (never fails on a stray byte)
     * and scanned for the one key we need rather than being parsed as a whole. The read stops at the
     * first match, and at [MAX_PROPERTY_LINES] lines, so a large or hostile file cannot turn this
     * check into a slow one.
     */
    fun encodersIn(properties: File): Set<String>? {
        if (!properties.isFile) return null
        val prefix = "$FLAG_ENCODERS_KEY="
        properties.bufferedReader(Charsets.ISO_8859_1).use { reader ->
            var line = reader.readLine()
            var read = 0
            while (line != null && read < MAX_PROPERTY_LINES) {
                val at = line.indexOf(prefix)
                if (at >= 0) {
                    val names = encoderNames(line.substring(at + prefix.length))
                    return names.ifEmpty { null }
                }
                line = reader.readLine()
                read++
            }
        }
        return null
    }

    /** Encoder names in a `graph.flag_encoders` value such as `car|speed_factor=5.0,bike|...`. */
    private fun encoderNames(flags: String): Set<String> = flags
        .split(',')
        .map { it.substringBefore('|').trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * The sentence shown when a package is rejected. Each one says what is wrong and what to do,
     * and none of them contains a class name, an encoder string or a file path.
     */
    fun describe(problem: Problem): String = when (problem) {
        Problem.MISSING_DIRECTORY ->
            "No routing graph is installed for this region yet."
        Problem.MISSING_FILE, Problem.EMPTY_FILE ->
            "This routing package is incomplete - part of the routing graph is missing or empty, so " +
                "it cannot be used. Delete it and download it again."
        Problem.NO_ENCODERS ->
            "This file is not a routing graph package, so it cannot be used for routing. " +
                "Delete it and download it again."
        Problem.WRONG_FLAGS ->
            "This routing package was built for different vehicle types than this app uses, so it " +
                "cannot be used for routing. Delete it and download it again."
    }

    /** Upper bound on lines read from `properties` while looking for [FLAG_ENCODERS_KEY]. */
    private const val MAX_PROPERTY_LINES = 4096
}
