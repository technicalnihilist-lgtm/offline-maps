# Architecture

## Modules

```
app ──► core:ui, core:data, core:map, core:offline, core:search, core:routing, core:location
                 │          │           │            │             │            │
                 └──────────┴───────────┴────────────┴─────────────┴────────────┴──► core:common
```

* `core:common` has no Android dependencies at all (except `System.currentTimeMillis`), so all
  geometry and tile maths is unit-testable on the JVM.
* `core:map`, `core:routing`, `core:location`, `core:search` each depend only on `core:common`
  (`core:search` also on `core:data` for nothing but types).
* `app` is the only module that knows about Android UI, services and DI.

## Key components

| Component | Responsibility |
|---|---|
| `MapStyleFactory` | generates the MapLibre style JSON: sources, light/dark palettes, road casings, `fill-extrusion` buildings, `raster-dem` terrain, hillshade, labels |
| `MapController` | camera moves, route/puck/destination GeoJSON layers, 3D building visibility |
| `OfflineRegionController` | MapLibre offline regions: create/download, pause/resume, delete, MBTiles merge, ambient cache |
| `DownloadManager` | resumable HTTP (Range + ETag), `.part` files, SHA-256/MD5 verification, storage and network guards, retry with backoff |
| `PackageInstaller` | routes a completed file to the right install step (routing graph / FTS index / MBTiles) |
| `RoutingEngine` | GraphHopper import/load, per-profile custom models, route → `RoutePlan` |
| `NavigationTracker` | projects GPS fixes onto route geometry, remaining distance/time, next manoeuvre, off-route detection |
| `GuidanceAnnouncer` | decides when to speak (threshold buckets, no repeats, arrival, reroute, GPS loss) |
| `NavigationManager` | owns the live loop, rerouting, speech; drives both UI and the notification |
| `SearchIndex` | SQLite FTS4 index over places packs, injection-safe prefix queries, nearby bbox queries |

## Threading

* Downloads and imports run on `Dispatchers.IO` inside an application-scoped `CoroutineScope`.
* The navigation loop runs on `Dispatchers.Default`; the foreground service only mirrors state.
* UI observes `StateFlow` from view models; no blocking calls on the main thread.

## Persistence

* Room: `saved_places`, `recent_searches`, `downloads`, `installed_regions`.
* SQLite (`offline_search.db`): `places` + `places_fts` (FTS4).
* MapLibre's own SQLite store: offline tile regions and merged MBTiles.
* Files under `getExternalFilesDir()/packages`: routing graph, downloaded packages.
* SharedPreferences: settings.

## Error and state model

Every user-visible failure has an explicit representation: `RouteResult.Failure(kind, message)`,
`DownloadFailureKind`, `SearchRepository.lastCoverageNote`, and status chips for online/offline,
GPS on/off, coverage and routing availability. The UI never silently degrades.
