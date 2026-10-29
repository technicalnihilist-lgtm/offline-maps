# Data sources, licences and attribution

| Dataset | Provider | Licence | Used for |
|---|---|---|---|
| OpenStreetMap vector tiles (OpenMapTiles schema) | OpenFreeMap (`tiles.openfreemap.org`) | OSM data ODbL 1.0; service free, no key | online map browsing, offline region caching |
| Terrarium elevation tiles | Mapzen / AWS Open Data (`elevation-tiles-prod`) | open data, attribution to Mapzen | terrain, hillshade, 3D relief |
| OSM extracts | Geofabrik (`download.geofabrik.de`) | ODbL 1.0 | routing graphs, places packs |
| Bundled Andorra extract | OpenStreetMap contributors | ODbL 1.0 | bundled sample region + routing integration test |

Required attribution (shown in Settings and in the map attribution control):
"© OpenStreetMap contributors · OpenFreeMap · Terrain: Mapzen/AWS Open Data".

## Not used

No Google Maps/Places/Earth data, no proprietary routing tiles, no paid API keys, no unofficial
endpoints. Satellite imagery is intentionally absent because no appropriately licensed global source
was integrated.

## Generating your own places pack

```bash
pip install osmium
python3 tools/build_places_pack.py region.osm.pbf region.jsonl.gz
```

The pack is gzip JSONL: `{"id","name","type","lat","lon","admin","street"}`. Types are OSM place
values (`city`, `town`, `village`, `hamlet`, ...) plus `street` and `poi`, which drive search
ranking (`SearchIndex.weightForType`).

## Pointing the app at your own tile server

Edit `MapConfig` in `core/map`:

```kotlin
MapConfig(
    vectorTileUrlTemplate = "https://your.server/tiles/{z}/{x}/{y}.pbf",
    terrainTileUrlTemplate = "https://your.server/dem/{z}/{x}/{y}.png",
    attribution = "© Your attribution",
)
```

For fully offline deployments, pre-generate a region server-side and ship MBTiles files through the
`MBTILES` package type instead of relying on MapLibre's tile fetching.
