# Building

## Requirements

* JDK 17 (tested with Temurin 17.0.21)
* Android SDK: platform 35, build-tools 34.0.0, platform-tools
* Gradle 8.9 — use the wrapper (`./gradlew`) or an equivalent local install
* ~1.5 GB of dependency downloads on a clean machine

`local.properties` (not committed):

```
sdk.dir=/absolute/path/to/Android/Sdk
```

## Commands

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew test                      # all JVM unit tests
./gradlew :core:routing:testDebugUnitTest   # just the routing suite (includes GraphHopper import)
./gradlew :app:assembleRelease      # minified release APK
./gradlew lint
```

Outputs: `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release.apk`.

## Release signing

Signing material is read from the environment, never from the repository:

```bash
export OFFLINEMAPS_KEYSTORE=/secure/release.jks
export OFFLINEMAPS_KEYSTORE_PASSWORD=...
export OFFLINEMAPS_KEY_ALIAS=...
export OFFLINEMAPS_KEY_PASSWORD=...
```

If `OFFLINEMAPS_KEYSTORE` is unset the release build is signed with the debug key, which is fine for
local testing and unacceptable for distribution.

## Test-environment knobs

The root build script forwards these to the test JVM when present:

| Variable | Purpose |
|---|---|
| `HTTPS_PROXY` | HTTP proxy for tests (parsed into `https.proxyHost`/`Port`/`User`/`Password`) |
| `OFFLINEMAPS_TRUSTSTORE` / `OFFLINEMAPS_TRUSTSTORE_PASSWORD` | custom CA bundle for TLS interception |
| `ROBOLECTRIC_DEPS` | directory with a pre-fetched `android-all-instrumented` jar (air-gapped CI) |
| `TEST_TMPDIR` | relocates `java.io.tmpdir` when `/tmp` is a small tmpfs |

Robolectric also needs `robolectric.graphicsMode=LEGACY` on containerised runners; the build sets
this already.

## Known build pitfalls

* **D8 dexing is the slow step.** GraphHopper pulls in jackson, janco, jts and osmosis; on a 2-core
  2 GB machine `mergeDebugGlobalSynthetics` can take several minutes. Use `--max-workers=1` and a
  machine with ≥4 GB RAM.
* **`robolectric.graphicsMode`** must stay `LEGACY` for headless CI, otherwise the test JVM aborts
  in the native graphics loader.
* **No `nativeruntime-dist-compat` pin.** One was added to the search and data modules and then
  removed: Robolectric 4.13 brings its own, and a mismatched pin aborts the test JVM with exit code
  134 (`unable to find class BitmapRegionDecoderNatives`). Do not add one back.
* **`./gradlew lint` is not part of CI.** It runs locally; wiring it into the workflow is J-08 in
  `OPTIMIZATION.md`.
* If Gradle cannot resolve dependencies, check the `repositories` block in `settings.gradle.kts`
  and your proxy configuration; a mirror can be added next to `mavenCentral()`.
