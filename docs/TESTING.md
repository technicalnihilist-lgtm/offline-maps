# Test results

Executed on **GitHub Actions** (Ubuntu runner, JDK 17 Temurin, Android SDK platform 35,
build-tools 34.0.0, Gradle 8.9 wrapper).

Run: <https://github.com/technicalnihilist-lgtm/offline-maps/actions/runs/43581385354> - **success**.
(The counts below are that run's; the badge on the README links the latest run.)

## Automated tests — 662 tests, 0 failures

| Module | Tests |
|---|---|
| `app` | 154 |
| `core/common` | 37 |
| `core/map` | 129 |
| `core/offline` | 122 |
| `core/routing` | 115 |
| `core/search` | 58 |
| `core/location` | 33 |
| `core/data` | 14 |
| `core/ui` | 0 |
| **total** | **662** |

Counts are read from the Gradle HTML report artifacts of the run (`testDebugUnitTest`), and each
number is the number of `@Test` methods in that module: `DocsConsistencyTest` counts them in the
tree and fails if either this table's total or the README's disagrees with it. `./gradlew test` runs
`testDebugUnitTest` and `testReleaseUnitTest`, so every count above is executed twice — 1,324 test
executions — and both variants must be green. The CI run also checks that: `tools/verify_test_results.sh`
fails the job when a module that carries test sources reported no results (OPTIMIZATION.md J-07).

`core/ui` has no unit tests of its own: it is Compose design-system code (theme, panels, chips,
progress rows) with no logic a JVM test can reach without a device. Everything else is covered,
including the static audits in `app`: startup I/O, unused imports, the logging convention, the CI
workflow, and the numbers on this page.

Build: `./gradlew :app:assembleDebug` → **success** on the same run, artifact `app-debug-apk`. Its
size and digest change with every commit, so they are not quoted here; download the artifact from
the run, or the APK from the latest release, and check it yourself.

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

The v1.0.0 crash after installing a region, the diagnosis, and the architectural fix are written up in
[`CRASH-FIX-v1.0.1.md`](CRASH-FIX-v1.0.1.md). Short version: no modern GraphHopper can import OSM on
Android (its reader needs `javax.xml.stream`), so the app now loads a **pre-built graph** shipped in
`app/src/main/assets/routing_andorra.zip`.

`RoutingEngineIntegrationTest` unpacks that exact asset, calls `engine.load()`, routes car/bike/foot
across Andorra, and asserts real distances, manoeuvres, OSM street names and a missing-graph failure.
`RoutingEngineAndroidTest` repeats it under Robolectric's real android-all runtime.

## Offline map tiles — what is and is not covered

A tile download is executed by MapLibre's native offline manager, so it can only be exercised on a
device. What is tested here is the input it depends on: `StyleFileWriterTest` proves the generated
style is written to disk, returns a well-formed `file://` URL, contains version 8 with the vector
source, extruded buildings and terrain, honours the terrain toggle, and separates light/dark files.
A wrong style file or a malformed URL is the failure mode that makes `createOfflineRegion` silently
download nothing, which is what "download does not work" looks like in practice.

Still device-only: the actual tile fetch, progress callbacks, pause/resume of a MapLibre region, and
whether the map renders from cache in airplane mode.

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
