#!/usr/bin/env python3
"""Build the single-file HTML demo of the Offline Maps app.

Embeds the same places pack and region catalogue the Android app ships, so the search and the
download-size figures in the demo are computed from real data rather than invented.
"""
import gzip, json, pathlib, html

ROOT = pathlib.Path(__file__).resolve().parent.parent  # repository root
OUT = ROOT / "docs" / "web-demo.html"

places = [json.loads(line) for line in gzip.open(ROOT / "app/src/main/assets/places_andorra.jsonl.gz", "rt")]
catalogue = json.loads((ROOT / "app/src/main/assets/regions.json").read_text())

places_js = json.dumps(
    [{"n": p["name"], "t": p["type"], "y": round(p["lat"], 6), "x": round(p["lon"], 6)} for p in places],
    separators=(",", ":"),
)
regions_js = json.dumps(
    [
        {
            "id": r["id"], "name": r["name"], "country": r["country"],
            "s": r["south"], "w": r["west"], "n": r["north"], "e": r["east"],
            "z0": r["min_zoom"], "z1": r["max_zoom"],
        }
        for r in catalogue["regions"]
    ],
    separators=(",", ":"),
)

TEMPLATE = (ROOT / "tools" / "web-demo-template.html").read_text()
out = TEMPLATE.replace("__PLACES__", places_js).replace("__REGIONS__", regions_js)
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(out, encoding="utf-8")
print(f"wrote {OUT} ({OUT.stat().st_size/1024:.0f} KB) · {len(places)} places · {len(catalogue['regions'])} regions")
