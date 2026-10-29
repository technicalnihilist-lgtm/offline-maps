#!/usr/bin/env python3
"""Build an offline search pack from an OpenStreetMap extract.

The app never parses a PBF on the phone: this script turns a Geofabrik extract (or any
.osm.pbf / .osm file) into a gzip-compressed JSONL file that the on-device SQLite FTS index
imports directly.

    python3 tools/build_places_pack.py region.osm.pbf out.jsonl.gz

Output record shape (one JSON object per line):
    {"id":"n123","name":"Andorra la Vella","type":"town","lat":42.5,"lon":1.52,
     "admin":null,"street":null}

Data is (c) OpenStreetMap contributors, ODbL 1.0. Keep the attribution when you redistribute.
"""
import argparse
import gzip
import json
import sys

try:
    import osmium
except ImportError:
    sys.exit("pyosmium is required: pip install osmium")

PLACE_TYPES = {
    "country", "state", "region", "province", "county", "city", "town", "village",
    "hamlet", "suburb", "quarter", "neighbourhood", "locality", "island", "islet",
}

POI_KEYS = ("amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare")

ROAD_TYPES = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "living_street", "pedestrian", "service", "track", "footway",
    "path", "cycleway", "steps",
}


class Collector(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.records = []
        self.seen = set()

    def _add(self, rid, name, ptype, lat, lon, admin=None, street=None):
        if not name or rid in self.seen:
            return
        self.seen.add(rid)
        self.records.append({
            "id": rid,
            "name": name.strip()[:120],
            "type": ptype,
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "admin": admin,
            "street": street,
        })

    def node(self, n):
        tags = n.tags
        name = tags.get("name")
        if not name:
            return
        if "place" in tags and tags["place"] in PLACE_TYPES:
            self._add(f"n{n.id}", name, tags["place"], n.location.lat, n.location.lon)
            return
        for key in POI_KEYS:
            if key in tags:
                self._add(f"n{n.id}", name, "poi", n.location.lat, n.location.lon)
                return

    def way(self, w):
        tags = w.tags
        name = tags.get("name")
        if not name:
            return
        highway = tags.get("highway")
        if highway in ROAD_TYPES:
            try:
                coords = [(nd.lat, nd.lon) for nd in w.nodes]
            except Exception:
                return
            if not coords:
                return
            mid = coords[len(coords) // 2]
            self._add(f"w{w.id}", name, "street", mid[0], mid[1])
            return
        if tags.get("boundary") == "administrative" and tags.get("admin_level") in {"2", "4", "6"}:
            try:
                coords = [(nd.lat, nd.lon) for nd in w.nodes]
            except Exception:
                return
            if coords:
                mid = coords[len(coords) // 2]
                kind = "country" if tags.get("admin_level") == "2" else "state"
                self._add(f"w{w.id}", name, kind, mid[0], mid[1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("output")
    ap.add_argument("--limit", type=int, default=0, help="stop after N records (debugging)")
    args = ap.parse_args()

    collector = Collector()
    collector.apply_file(args.source)

    records = collector.records
    if args.limit:
        records = records[: args.limit]

    with gzip.open(args.output, "wt", encoding="utf-8", compresslevel=9) as out:
        for record in records:
            out.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
            out.write("\n")

    types = {}
    for r in records:
        types[r["type"]] = types.get(r["type"], 0) + 1
    print(f"wrote {len(records)} places to {args.output}")
    print("by type:", ", ".join(f"{k}={v}" for k, v in sorted(types.items(), key=lambda kv: -kv[1])))


if __name__ == "__main__":
    main()
