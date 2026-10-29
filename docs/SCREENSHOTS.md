# Screenshots

**There are no device screenshots in this repository.** The build environment had no emulator
(`/dev/kvm` is not available inside the sandbox) and the APK could not be built there, so capturing
a real screenshot was impossible.

The four images in `docs/screenshots/` are **UI design reference renders**: HTML/CSS mockups drawn
from the same layout definitions, colours, spacing and strings as the Compose UI
(`core/ui/Theme.kt`, `ui/AppScaffold.kt`, `ui/ExplorePanels.kt`, `ui/ListPanels.kt`). Each image
carries a caption band saying exactly that. They show intended composition, hierarchy and
typography — **not** a running application.

| File | Screen |
|---|---|
| `docs/screenshots/explore.png` | Explore: map, floating search bar, coverage/online chips, 3D toggle, zoom + location controls, bottom panel, bottom navigation |
| `docs/screenshots/route.png` | Destination selected: route preview with profile chips and Start/Save/Set-as-home actions |
| `docs/screenshots/navigate.png` | Active guidance: turn card with next manoeuvre, remaining distance/time/ETA, voice + repeat + end |
| `docs/screenshots/downloads.png` | Downloads: available regions with estimated size, an in-flight download with rate and ETA, a paused one, and installed regions |

The source of the renders is in `.render/` (HTML + CSS) so they can be regenerated:

```bash
chromium --headless --window-size=1080,2280 \
  --screenshot=docs/screenshots/explore.png .render/explore.html
```

## Producing real screenshots

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb exec-out screencap -p > docs/screenshots/explore-device.png
```

Worth capturing on a device: first launch, 3D tilt over a city, an offline region downloading,
offline search results, and the turn card during guidance.
