# Optimisation backlog

The daily maintenance runs work through this list **one item per run**, top to bottom: take the first
unchecked item, implement it, verify it, tick it, add a row to the log at the bottom.

Rules for every item:

* **No UI changes.** No layout, colour, spacing, typography, animation or wording changes. If an
  improvement cannot be made without a visible change, skip the item and note why in the log.
* **No feature removal, no new features, no data-format changes.** Do not touch the places pack, the
  region catalogue, the bundled routing graph or the release workflow.
* **Verify before ticking.** `./gradlew test` must stay green (CI runs it) and `:app:assembleDebug`
  must still build. A red run means the item is not done.
* Small, surgical commits. One commit per item where practical.
* If a run finds a new problem that is not on this list, add it here with the next free number rather
  than fixing it out of order.

---

## A · Startup and first frame

- [x] A-01 Lazy-initialise `ServiceLocator` members that the first screen does not need (Room, search index, download engine), so cold start does not touch disk for them.
- [x] A-02 Move `SearchIndex` database opening off the main thread; assert with StrictMode in debug builds that no disk I/O happens on the main thread during startup.
- [x] A-03 Defer `MapLibre.getInstance` and style construction until the map view is actually attached, and measure the difference in startup time.
- [x] A-04 Cache the generated style JSON in memory keyed by (theme, quality, terrain, buildings) instead of rebuilding the string on every call.
- [x] A-05 `StyleFileWriter`: skip rewriting the style file when the serialised content is unchanged (hash compare) to avoid pointless disk writes on every map open.
- [x] A-06 Preload the bundled places pack index once and record how long the first search takes afterwards.
- [x] A-07 Add a `Trace`/`Timber`-style timing wrapper around cold start phases and log the breakdown in debug builds only.

## B · Map rendering and camera

- [x] B-01 Audit `MapController` for work done per frame: avoid allocating new GeoJSON strings when the route/location has not changed.
- [x] B-02 Only update the location puck source when the position moved more than a few metres or the accuracy changed materially.
- [x] B-03 Avoid rebuilding the whole style when only the 3D-building visibility changes — toggle layer visibility instead.
- [ ] B-04 Clamp camera animations: skip `animateCamera` when the target is within a few pixels of the current position.
- [ ] B-05 Cancel in-flight camera animations before starting a new one, so rapid taps do not queue.
- [ ] B-06 Check that the terrain source is not requested at zooms where it is not needed, and cap DEM maxzoom sensibly.
- [ ] B-07 Reduce the route line layer's point count with a cheap Douglas–Peucker simplification above a threshold, keeping the original for progress maths.
- [ ] B-08 Verify layers are removed when a route is cleared, so repeated routing does not leak sources.

## C · Downloads

- [ ] C-01 Throttle `DownloadManager` progress writes to the store to at most a few per second (currently every chunk).
- [ ] C-02 Persist the ETag/Last-Modified alongside the `.part` file so a resume after a restart is validated, not guessed.
- [ ] C-03 Add jitter to the retry backoff so a flaky network does not produce synchronised retries.
- [ ] C-04 Reuse one `OkHttpClient` across the app (connection pool + shared dispatcher) and confirm no per-request client is created.
- [ ] C-05 Check free space again before resuming, not just before starting.
- [ ] C-06 Make cancellation delete the partial file deterministically, including when the process is killed mid-write.
- [ ] C-07 Verify the checksum on resume of a file that was already complete (skip re-download entirely).
- [ ] C-08 Stream unzip in `PackageInstaller` with a bounded buffer and confirm large graphs do not allocate a full byte array.
- [ ] C-09 Confirm `TileRegionEstimator` never returns 0 for a valid region, and clamp absurd estimates.
- [ ] C-10 Guard against two downloads of the same package key racing each other.

## D · Offline search

- [ ] D-01 Profile the FTS query and add an index or a narrowed SELECT if it scans more than expected.
- [ ] D-02 Debounce the search input in `MapViewModel` (target ~120 ms) so each keystroke does not query SQLite.
- [ ] D-03 Cancel the previous search coroutine when a new query arrives.
- [ ] D-04 Add an index on `places(region_id)` and `places(lat, lon)` if `nearby` queries scan.
- [ ] D-05 Cap `nearby` result counts and confirm the bbox filter is applied in SQL, not in Kotlin.
- [ ] D-06 Handle a query of only punctuation/whitespace without hitting the database.
- [ ] D-07 Confirm `toFtsTerms` output is always a syntactically valid FTS4 expression for weird input (quotes, `*`, `NEAR`).

## E · Routing and navigation

- [ ] E-01 Confirm the GraphHopper instance is closed on `onTrimMemory`/`onLowMemory` and reloaded lazily.
- [ ] E-02 Avoid reloading the graph when the requested profile set is unchanged (already partly done) — add a test.
- [ ] E-03 Window the `NavigationTracker` segment search so it is O(k) per fix, and add a benchmark test with a 5000-point route.
- [ ] E-04 Throttle reroute attempts (currently time-based) and add a distance-travelled condition so a stationary off-route fix does not loop.
- [ ] E-05 Handle a GPS fix with null accuracy or bearing without treating it as precise.
- [ ] E-06 Ignore fixes older than the last accepted fix (out-of-order delivery).
- [ ] E-07 Confirm arrival is announced once and navigation stops cleanly.
- [ ] E-08 Reduce instruction-list churn in `GuidanceAnnouncer` — no allocations per fix.

## F · Voice guidance and service

- [ ] F-01 Initialise `TextToSpeech` once and reuse it; shut down in `onDestroy`.
- [ ] F-02 Use `QUEUE_FLUSH` vs `QUEUE_ADD` deliberately so instructions do not overlap.
- [ ] F-03 Throttle foreground-service notification updates (at most once per instruction change).
- [ ] F-04 Confirm the service stops itself when navigation ends, including on task removal.
- [ ] F-05 Handle a TTS engine that fails to initialise without breaking navigation.

## G · Location

- [ ] G-01 Unregister location updates reliably when the last collector leaves.
- [ ] G-02 Drop duplicate fixes and apply a minimum-distance filter in explore mode.
- [ ] G-03 Handle provider being disabled mid-navigation (keep last known, warn, do not crash).
- [ ] G-04 Avoid holding a wake lock longer than needed.

## H · Memory and battery

- [ ] H-01 Audit for retained references in `ServiceLocator` singletons (no Activity/Context leaks).
- [ ] H-02 Confirm `onTrimMemory` releases caches (style cache, search cursors, bitmap buffers).
- [ ] H-03 Cap the number of cached recent searches and saved places rendered at once.
- [ ] H-04 Confirm no coroutine outlives its ViewModel scope; look for `GlobalScope` or unscoped launches.
- [ ] H-05 Check that map style rebuilds do not leave old sources/layers alive.

## I · Correctness and resilience

- [ ] I-01 Audit every `!!` and unchecked cast; replace with explicit failure paths.
- [ ] I-02 Make sure every install/download failure path produces a user-readable message and no crash (catch `Throwable` where a native library is involved).
- [ ] I-03 Validate region catalogue JSON defensively (missing fields, inverted bounds, zero zoom range).
- [ ] I-04 Guard against a corrupted places pack (truncated gzip, invalid JSON line) without killing search.
- [ ] I-05 Guard against a corrupted routing graph package (missing files, wrong flags) with a clear message.
- [ ] I-06 Confirm behaviour when storage is removed mid-download.
- [ ] I-07 Confirm behaviour when the system kills the process during an install.
- [ ] I-08 Sanity-check coordinate parsing for out-of-range values (|lat| > 90, |lon| > 180).

## J · Tests, docs and code health

- [ ] J-01 Raise coverage on `DownloadsViewModel` state mapping (pure parts) with plain JVM tests.
- [ ] J-02 Add tests for `RegionCatalogParser` edge cases.
- [ ] J-03 Add a test that the style JSON validates against the MapLibre spec (port the node check into a Gradle test or a scripted check).
- [ ] J-04 Remove dead code and unused imports across modules; confirm no unused public API.
- [ ] J-05 Make logging consistent (one tag per class, no `println`).
- [ ] J-06 Update README/docs where behaviour changed; keep the known-limitations list honest.
- [ ] J-07 Confirm the CI workflow fails loudly on test failure and does not silently skip modules.
- [ ] J-08 Add a lint task to CI (`./gradlew lintDebug`) and fix what it reports, without changing UI.

---

## Log

| Date | Item | What changed | CI run |
|---|---|---|---|
| — | — | backlog created | — |
| 2026-11-01 | A-01 | Cold start no longer touches the download path: `ServiceLocator` members were already lazy, so the eager part was `OfflineMapsApp.onCreate` calling `restorePendingDownloads()`, which read the download table and could build `DownloadManager` + `OkHttpClient` + storage probe before the first frame. The restore now runs from `MainActivity` off a `decorView` post (once per process, and it skips building the engine when nothing is resumable); new `ServiceLocatorLazinessTest` pins the laziness down. versionCode 6 / 1.0.5. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41285453638 |
| 2026-11-01 | A-02 | `SearchRepository` is now a suspending facade: every index call runs on an injected `Dispatchers.IO` dispatcher, so `offline_search.db` is opened and queried on a worker thread instead of the main thread (the main caller was `MapViewModel.setSearchQuery`, on `viewModelScope`). New `StartupIoAudit` installs a StrictMode disk-IO policy in debuggable builds, records violations raised between `Application.onCreate` and the first frame, and reports them when that window closes, with `assertNoDiskIoOnMainThread()` as the assertion itself; `StartupIoAuditTest` (5 tests) and two dispatcher tests in `SearchIndexTest` cover it. versionCode 7 / 1.0.6. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41289754797 |
| 2026-11-01 | A-03 | `MapLibre.getInstance` no longer runs in `Application.onCreate`: the new idempotent `MapLibreBootstrap` is called from the `MapCanvas` `AndroidView` factory, so the native setup happens when the map view is attached and never on a screen that shows no map. `MapCanvas` now takes the small `StyleOptions` value instead of a style string, and `MapStyleFactory.buildJson` only runs on first attach or when those options really changed (it used to run on every read of `MapUiState.styleJson`, i.e. every recomposition); the generated document is unchanged. New `StartupTrace` logs MapLibre init cost and time to the first applied style once per cold start, in debuggable builds only. `StartupTraceTest` (5) and `MapLibreBootstrapTest` (4). versionCode 8 / 1.0.7. No on-device timing was taken, so the delta is instrumented, not measured. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41298063195 |
| 2026-11-01 | A-04 | `MapStyleFactory.buildJson` is memoised: a bounded, access-ordered LRU (8 entries) keyed on the whole `StyleOptions` value - theme, 3D buildings, terrain, both zoom thresholds and every `MapConfig` field, with quality entering via `buildingsMinZoom` - so the key is exact. Documents are built outside the lock (a racing build simply loses the insert), hit/miss counters and `clearStyleCache()` are exposed for diagnostics and for H-02, and the old generator body was renamed `render` and left untouched, so the emitted JSON is byte-identical. `MapStyleFactoryCacheTest` (6 tests) covers reuse, identity after eviction, key completeness over 8 variants, bounded eviction and 64 concurrent callers. versionCode 9 / 1.0.8. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41302630699 |
| 2026-11-02 | A-05 | `StyleFileWriter.write` hashes the serialised style (SHA-256 of the UTF-8 bytes) and skips the write when the file on disk already matches — length check first, then an in-memory record of the last bytes this process wrote, falling back to hashing the file when there is no record (a fresh process, or a file this class did not write). A missing, truncated or different file is still written in full, so a killed process cannot leave a stale style. File name, emitted JSON and `file://` URL unchanged; write/skip counters exposed for diagnostics. `StyleFileWriterTest` gained 6 tests (unchanged rewrite keeps the mtime, changed options rewrite, truncated file restored, deleted file recreated, fresh-writer disk-hash path, counters) alongside the 4 existing ones. versionCode 10 / 1.0.9. Local Gradle was not run (no Android SDK in the maintenance sandbox), so the green `./gradlew test` + `:app:assembleDebug` below is the CI result, not a local one. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41389040784 |
| 2026-11-02 | A-06 | The search index behind offline search was opened lazily by the first query, so the first search of a cold start paid for opening `offline_search.db`, loading the FTS segment list and preparing the join. `SearchIndex.warmUp()` now does exactly that work, read-only; the new `SearchPrewarm` keeps it to one run per process (a failing warm-up is recorded as an empty index, never thrown) and records the warm-up cost plus the cost of the first search that follows. `ServiceLocator.prewarmSearch()` runs it from `MainActivity` once the first frame is out, never from `Application.onCreate`, so cold start does not wait for it, and repeat calls touch neither a dispatcher nor the disk. `SearchRepository` times the first search and hands both numbers to an injected logger, which the app points at a debug-only logcat line (tag `SearchPrewarm`) - nothing is shown on screen. `SearchPrewarmTest` (5 tests) plus 3 new tests in `SearchIndexTest`. versionCode 11 / 1.0.10. Local Gradle was not run (no JDK or Android SDK in the maintenance sandbox), so the green run below is the CI result; the speed-up is instrumented, not measured on a device - the build logs the numbers, it does not assert a delta. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41401114349 |
| 2026-11-02 | A-07 | `StartupTrace` is now a general Trace/Timber-style wrapper instead of two hand-wired measurements: `beginPhase`/`endPhase` for spans that cannot be a block, `trace(label) { }` for the ones that can (closed even when the block throws), `firstFrameReached()` for the first-frame mark, and `finishStartup()`, which logs one `cold start breakdown:` line listing every phase in the order it finished plus the totals. `begin()` clears the phases and re-arms the line, so a recreated process logs once, not twice. The MapLibre entry points stayed as thin aliases over the same machinery, so their numbers and their existing log line are byte-for-byte what A-03 emitted. Wired at the cold-start call sites only (`Application.onCreate`, `MainActivity.onCreate`, MapLibre init, the post-first-frame work); the logger is still a no-op outside debuggable builds and nothing is written to disk or shown on screen. `StartupTracePhasesTest` (10 tests) covers per-phase timing, a throwing block, return values, ordering and totals, the first-frame mark, the once-per-cold-start line, `begin()` reset and the no-`begin` no-op paths. The first CI run went red on one of those new tests - my own assertion `doesNotContain("old")` matched the word "cold" - and the fixed commit is the green run below. versionCode 12 / 1.0.11. Local Gradle was not run (no JDK or Android SDK in the maintenance sandbox), so the green `./gradlew test` + `:app:assembleDebug` is the CI result, not a local one; the timing line is instrumented, nothing asserts a delta. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41407826987 |
| 2026-11-02 | B-01 | `MapController` re-serialised the overlay GeoJSON on every call: the puck, route line and destination marker each built a fresh document and handed it to MapLibre even when nothing had changed, and the route formatted every coordinate of a multi-thousand-point plan with `String.format` each time. The new `OverlayPayloadTracker` keeps the key last pushed per source (position + ring radius for the puck, the point for the destination, the geometry list compared by value for the route, plus a canonical key for the empty payload) and the document is built only when that key changes; the tracker is reset on every style load, which recreates the sources, and on `destroy()`. Emitted JSON, layers and the style document are unchanged, and no UI, feature or data-format change. `OverlayPayloadTrackerTest` (8 tests). versionCode 13 / 1.0.12. Local Gradle was not run (no JDK or Android SDK in the maintenance sandbox), so the green `./gradlew test` + `:app:assembleDebug` below is the CI result, not a local one; the saving is structural (allocation removed), not measured on a device. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41413947000 |
| 2026-11-02 | B-02 | The puck source was rewritten for every GNSS fix, so a stationary receiver's metre-level wander and its breathing accuracy estimate each cost a fresh document and a source update. The new `PuckUpdateGate` keeps the last accepted fix and lets a new one through only when it moved at least 3 m from that fix, or when the accuracy changed materially (>= 2.5 m and >= 25% of the previous value); sub-threshold steps accumulate against the accepted fix, so the puck lags by at most 3 m and never freezes. The gate is reset when the puck is hidden and on every style load (which recreates the source), so the first fix afterwards is always drawn; `MapController` still applies its exact-payload dedupe behind it. Emitted GeoJSON, layers and styling are untouched. `PuckUpdateGateTest` (11 tests). versionCode 14 / 1.0.13. Local Gradle was not run (no JDK or Android SDK in the maintenance sandbox), so the green `./gradlew test` + `:app:assembleDebug` below is the CI result, not a local one; the first CI run was red on one of the new tests - my own expected skip counter for the drift case was wrong (the gate keeps gating past the first accept), and the fixed commit is the green run below. The saving is structural (updates not issued), not measured on a device. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41422441086 |
| 2026-11-03 | B-03 | The extruded building layer used to be added to or dropped from the generated document, so flipping the 3D-buildings setting went through `MapLibreMap.setStyle` - every layer rebuilt, the overlay sources re-created and the style's tiles re-requested, on the main thread. The layer is now always emitted and `show3DBuildings` only sets its `layout.visibility`, so that change alone is one layout-property write on the live style; `StyleReloadPolicy.isBuildingVisibilityOnlyChange` (pure Kotlin) decides when the fast path applies and every other option change (theme, terrain, min-zooms, tile server, attribution) still reloads, so the map can never disagree with the settings. `MapController.setBuildingsVisible` now records what it last wrote and is skipped until a style has loaded, in which case `MapCanvas` falls back to the reload path rather than dropping the change. A hidden layer draws nothing, so the frame is what a reloaded style produced; no UI, feature or data-format change. `BuildingLayerVisibilityTest` (6 tests) and `StyleReloadPolicyTest` (8 tests). versionCode 15 / 1.0.14. Local Gradle was not run (no JDK or Android SDK in the maintenance sandbox), so the green `./gradlew test` + `:app:assembleDebug` below is the CI result, not a local one; the saving is structural (no style reload), not measured on a device. | https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/41584812824 |
