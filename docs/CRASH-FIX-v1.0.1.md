# Postmortem: crash after installing a region (reported on-device, fixed in v1.0.1)

Reported symptom: the app crashed after installing a region. The build sandbox has no device, so this
was traced by reasoning about what a phone does differently from CI, then confirmed by pulling the
dependency apart.

1. **Static scan.** The APK's dex references `javax.xml.stream`, `java.awt`, `javax.imageio` and
   `java.lang.instrument` — none of which exist on Android.
2. **`java.awt` and `javax.imageio` ruled out** as the runtime cause: they come from
   xmlgraphics/SVG helpers that the import path never touches.
3. **janino found in the import path.** GraphHopper 4+ builds its weighting through
   `CustomModelParser`, which compiles expressions with janino. janino emits **JVM bytecode** and
   defines classes at runtime; ART executes only DEX, so it throws on a device — during import and
   again on every graph load. Confirmed against the published jar:
   `com/graphhopper/routing/weighting/custom/CustomModelParser.class` references
   `org/codehaus/janino`. **Robolectric runs on a JVM, which is exactly why CI passed while the phone
   crashed** — a real limitation of that test strategy, worth remembering.
4. **First fix attempt: GraphHopper 2.4** (last release with zero janino references). It compiled,
   but importing failed with `UnsupportedOperationException: Cannot create DataReader` — in 2.x the
   OSM reader moved to a separate artifact (`graphhopper-reader-osm`).
5. **That artifact cannot work on a phone either**: `graphhopper-reader-osm:2.4` has `OSMReader`,
   `OSMInput`, `OSMInputFile` and `OSMXMLHelper` all referencing `javax.xml.stream`. Android has no
   StAX implementation at all, so the reader cannot be loaded even for a `.pbf` input.
6. **Conclusion: no modern GraphHopper can import OSM on Android.** The abandoned
   `com.graphhopper:graphhopper-android` artifact stops at 0.11.0 (2019). The architecture was wrong,
   not just the version.

## The fix

Routing now loads a **pre-built graph** and never imports on the device.

* `tools/build_routing_graph.sh` builds a graph on a JVM (GraphHopper 2.4 + reader-osm) from any
  `.osm.pbf`. For Andorra: 8.2 s, a 5.1 MB graph directory, 236 KB zipped.
* `app/src/main/assets/routing_andorra.zip` ships that graph. Installing the sample region unpacks it
  (with a zip-slip guard) and calls `RoutingEngine.load()`.
* `RoutingEngine.load()` returns `Result<Unit>` and catches `Throwable`, so a bad package produces a
  message, never a crash.
* `PackageInstaller` now catches `Throwable` — previously only `Exception`, so a
  `NoClassDefFoundError` escaped and killed the process — and rejects raw `.osm.pbf` packages with an
  explanation.
* The Downloads screen refuses `ROUTING_OSM` packages up-front instead of downloading gigabytes that
  could never be installed.
* `android:largeHeap="true"`, because loading a graph is memory-hungry on a small heap.

Loading is also simply better: well under a second versus ~8 s of import plus a ~130 MB memory spike.

## Offline routing — verified for real

`RoutingEngineIntegrationTest` loads the **exact graph shipped in the APK**:

1. unpacks `routing_andorra.zip` (the same asset the installer uses) into a temp directory,
2. calls `engine.load()` and asserts success,
3. routes Andorra la Vella → Escaldes-Engordany for car, bike and foot,
4. asserts a real road distance, positive duration, ≥3 manoeuvres, OSM street names, and monotonically
   increasing manoeuvre indices,
5. asserts a missing graph returns `NO_ROUTING_DATA` rather than throwing, and that a Tokyo point is
   reported as outside coverage.

`RoutingEngineAndroidTest` repeats load-and-route under **Robolectric's real android-all runtime**,
plus the missing-graph case, so the on-device path is exercised against Android's class library rather
than a desktop JVM.

Measured while building the shipped graph:

```
graph built in 8264 ms  -> nodes: 2 337, edges: 2 754
route (car)  dist=1264.7 m  instructions=5
graph directory 5.1 MB, zipped 236 KB
```
