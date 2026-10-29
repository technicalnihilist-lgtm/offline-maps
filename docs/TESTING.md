# Test results

Executed on **GitHub Actions** (Ubuntu runner, JDK 17 Temurin, Android SDK platform 35,
build-tools 34.0.0, Gradle 8.9 wrapper).

Run: <https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/40901142334> — **success**

## Automated tests — 99 tests, 0 failures

| Module | Suite | Tests |
|---|---|---|
| `core/common` | `GeoMathTest` | 13 |
| `core/common` | `TileMathTest` | 7 |
| `core/common` | `FormattersTest` | 8 |
| `core/offline` | `DownloadManagerTest` | 11 |
| `core/offline` | `StorageAndVerificationTest` | 10 |
| `core/routing` | `NavigationTrackerTest` | 10 |
| `core/routing` | `GuidanceAnnouncerTest` | 6 |
| `core/routing` | `InstructionMapperTest` | 6 |
| `core/routing` | `RoutingEngineIntegrationTest` | 6 |
| `core/search` | `SearchIndexTest` | 12 |
| `core/data` | `RepositoryTest` | 10 |
| | **total** | **99** |

Counts are read from the Gradle HTML report artifacts of that run (`testDebugUnitTest`).
`core/map`, `core/location` and `core/ui` currently have no unit tests of their own — their logic is
either Android-view glue or covered indirectly.

Build: `./gradlew :app:assembleDebug` → **success**. Artifact `app-debug-apk` = 38,594,710 bytes
zipped, 67,483,772 bytes installed: 22 dex files, 393 entries, native libraries for
`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, APK Signature Scheme v2/v3 block present,
SHA-256 `6b6cf1d2f41a74f214ff9599175cc49315db663496909299b3b8d1bfdcea1fb2`.

## Bugs the tests caught (all fixed)

Found while running the suites in the build sandbox:

1. **Pause could be overwritten by an in-flight progress write.** `DownloadManager.pause()` cancelled
   the job and wrote `PAUSED`, but a `publishProgress` already in flight could write `RUNNING` back.
   Fixed with a `pausedKeys` set that suppresses late writes.
2. **Turn announcements used the loosest threshold.** `GuidanceAnnouncer` took the *first* threshold
   the distance was under (always the 600 m bucket), so a manoeuvre was announced once and never
   again as you approached. Fixed by taking the tightest matching bucket.
3. **FTS4 prefix search returned nothing.** Queries were built as `"term"*`, which FTS4 treats as a
   complete-token match, so typing `Andor` matched nothing. Fixed to `term*`.

Found only on the CI runner (the sandbox could not run Robolectric or the full Android build):

4. **`TextStyle` positional arguments did not compile.** The constructor order is
   `(color, fontSize, fontWeight, ...)`, not `(fontFamily, fontWeight, fontSize, ...)`. Fixed with
   named arguments.
5. **Multi-word search returned nothing on Android.** `toFtsTerms` joined terms with `" AND "`.
   Android's bundled SQLite is built *without* FTS3/4 enhanced query syntax, so a literal `AND` is
   parsed as a search term — desktop SQLite accepts it, which is why my local check passed. Terms
   are now space-separated (implicit AND everywhere).
6. **Robolectric aborted with exit code 134** because a pinned
   `nativeruntime-dist-compat:1.0.19` did not match the version Robolectric 4.13 expects
   (`unable to find class BitmapRegionDecoderNatives`). The pin was removed.
7. **CI itself was broken** by `android-actions/setup-android@v3`, whose `sdkmanager` fails trying to
   install the removed `tools` package. Replaced with an explicit package assertion against the
   runner's preinstalled SDK.

Wrong test expectations I corrected along the way (the code was right): tile count for Andorra at
zooms 6–14 is 412; `StorageGuard` reserves 200 MB headroom; `describeZoom(16)` is "Full street
detail"; at the route origin the next manoeuvre is the first turn, not the departure instruction.

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

Instructions produced from real data: `sign=-2 name='Carrer Mossèn Enric Marfany'`,
`sign=2 name='Carrer Mossèn Tremosa'`.

## What is still NOT tested

| Area | Why | Status |
|---|---|---|
| Map rendering, pan/zoom/rotate/tilt | needs a GPU-capable device; no emulator here (`/dev/kvm` absent) | implemented, unverified on device |
| Live tile/region download | sandbox egress proxy blocks `tiles.openfreemap.org` and `download.geofabrik.de` | engine covered against MockWebServer instead |
| GPS, permission prompts, airplane mode | no device | tracker logic unit-tested |
| Voice playback | no device, no TTS engine | announcer + text mapping unit-tested |
| APK install / smoke test | no device or emulator | APK built and integrity-checked only |

## Manual test checklist (for a phone)

1. Cold start with no network → map shows its state, no crash, coverage chip says "No offline maps yet".
2. Install sample region → airplane mode → search "Ordino" → route to it → guidance works.
3. Deny location permission → tapping the location button shows the rationale, map still usable.
4. Start navigation, turn the screen off → notification stays, next instruction still spoken.
5. Step off the route → after three off-route fixes the route recalculates.
6. Cancel a download mid-transfer, kill the app, reopen → download resumes from the partial file.
7. Fill storage, start a large download → refused with an explicit "not enough free space" message.
8. Rotate the device and change screen size → layout adapts, map state preserved.
