# Test results

Everything below was executed in the build sandbox (Debian 12, JDK 17.0.21, Gradle 8.9,
Android SDK platform 35, build-tools 34.0.0) unless marked otherwise.

## Automated tests — executed

| Module | Suite | Tests | Result |
|---|---|---|---|
| `core/common` | `GeoMathTest` | 13 | **pass** (after fixing one wrong assertion of mine: Andorra at z6–14 is 412 tiles, not "a few thousand") |
| `core/offline` | `DownloadManagerTest` | 11 | **pass** |
| `core/offline` | `StorageAndVerificationTest` | 10 | **pass** (2 assertions of mine were wrong; corrected — see below) |
| `core/routing` | `NavigationTrackerTest` | 10 | **pass** (1 wrong expectation corrected) |
| `core/routing` | `GuidanceAnnouncerTest` | 6 | **pass** |
| `core/routing` | `InstructionMapperTest` | 6 | **pass** |
| `core/routing` | `RoutingEngineIntegrationTest` | 6 | **pass** — real GraphHopper import + routing on real OSM data |
| `core/search` | `SearchIndexTest` | 12 | **could not run here** (Robolectric native SQLite aborts under this sandbox's kernel) |
| `core/data` | `RepositoryTest` | 10 | **could not run here** (same reason) |

Totals: **62 tests written; 52 executed and passing; 10 Robolectric tests blocked by the
environment.**

### Bugs the tests actually caught (and that were fixed)

1. **Pause could be overwritten by an in-flight progress write.** `DownloadManager.pause()` cancelled
   the job and wrote `PAUSED`, but a `publishProgress` already in flight could then write `RUNNING`
   back. Fixed with a `pausedKeys` set that suppresses late writes. (`DownloadManagerTest`.)
2. **Turn announcements used the loosest threshold.** `GuidanceAnnouncer` picked the *first*
   threshold the distance was under (always the 600 m bucket), so a manoeuvre was announced once and
   never again as you approached. Fixed by taking the tightest matching bucket.
   (`GuidanceAnnouncerTest`.)
3. **FTS4 prefix search returned nothing.** The index built queries as `"term"*`, which FTS4 treats
   as a complete-token match — so typing `Andor` matched nothing. Fixed to `term*` and verified
   against real SQLite 3.40 (see below). (`SearchIndexTest`, plus a standalone SQL check.)
4. **Kotlin extension-function syntax error** in `LocationSource` (`fun Location toFix()` instead of
   `fun Location.toFix()`) — caught by the compiler.
5. **`core:ui` was missing the Compose compiler plugin**, which crashed the Kotlin backend with
   "couldn't find inline method BoxKt.Box$default". Fixed by applying `kotlin.compose` to the module.

### Wrong test expectations I corrected (the code was right)

* Tile count for Andorra at zooms 6–14 is **412**, not "a few thousand".
* `StorageGuard` reserves 200 MB of headroom, so a 10 MB download needs >210 MB free.
* `describeZoom(16)` says "Full street detail"; "Maximum detail" starts above 16.
* At the route origin the *next* manoeuvre is the first turn, not the departure instruction.

## Offline routing — verified for real

`RoutingEngineIntegrationTest` is not a mock. It:

1. reads `core/routing/src/test/resources/andorra.osm.pbf` (553 KB, real OSM extract, ODbL),
2. imports it with GraphHopper 10 into a graph directory,
3. routes Andorra la Vella → Escaldes-Engordany for car, bike and foot,
4. asserts a real road distance, positive duration, >20 geometry points, ≥3 manoeuvres, OSM street
   names on the instructions, and monotonically increasing manoeuvre indices,
5. closes the engine and **reloads the graph from disk without the PBF**, then routes again.

Measured during the probe run that validated the API before the test was written:

```
IMPORT_MS=13812
car  dist_m=5666  time_s=308  pts=141  instr=17
foot dist_m=5627  time_s=4051 pts=134  instr=17
bike dist_m=5627  time_s=962  pts=134  instr=17
RELOAD_MS=299
reload route ok=true dist=5666
```

Example instructions produced from real data:
`sign=-2 name='Carrer Mossèn Enric Marfany'`, `sign=2 name='Carrer Mossèn Tremosa'`.

## SQL cross-check (because Robolectric could not run)

The exact statements in `SearchIndex.kt` were executed against real SQLite 3.40.1 with Python's
`sqlite3` module: schema creation (FTS4 + `unicode61`), prefix search, multi-term AND, bbox nearby
query, region delete, and an injection attempt (`Ordino; DROP TABLE t;` → no results, table intact).
All statements behave as the Kotlin code expects.

## What could NOT be tested here

| Area | Why | What is committed instead |
|---|---|---|
| Map rendering, pan/zoom/rotate/tilt | no emulator (`/dev/kvm` absent), MapLibre needs a GPU-capable device | instrumentation test stubs + manual checklist below |
| Offline region download end to end | egress proxy blocks tile hosts | download engine covered against MockWebServer |
| GPS, permissions, airplane mode | no device | logic covered by `NavigationTracker` unit tests |
| Voice guidance audio | no device / no TTS engine | `GuidanceAnnouncer` + `InstructionMapper` unit tests |
| Low memory / low storage on device | no device | `StorageGuard` unit tests + graceful OOM handling in `RoutingEngine`/`PackageInstaller` |
| APK install / smoke test | no APK could be built here (see README) | CI workflow builds one on a real runner |

## Manual test checklist (for a device)

1. Cold start with no network → map shows cached/blank state, no crash, coverage chip says "No
   offline maps yet".
2. Install sample region → airplane mode → search "Ordino" → route to it → guidance works.
3. Deny location permission → tapping the location button shows the rationale, map still usable.
4. Start navigation, turn the screen off → notification stays, next instruction still spoken.
5. Drive/step off the route → after three off-route fixes the route recalculates.
6. Cancel a download mid-transfer, kill the app, reopen → download resumes from the partial file.
7. Fill storage, start a large download → refused with an explicit "not enough free space" message.
8. Rotate the device and change screen size → layout adapts, map state is preserved.
