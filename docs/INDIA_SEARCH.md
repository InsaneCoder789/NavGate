# India search implementation

The national gazetteer is independent of the selected offline routing region. It preserves WGS84 latitude/longitude and GeoNames feature IDs, alternate names, modification dates and source attribution. These points identify features; they do not establish an entrance or accessible route. Live GPS comes from Android LocationManager and is a separate timestamped observation.

Generate the complete available India country archive (not a city sample):

```
python3 scripts/build_india_index.py --download
```

This creates a local APK asset `app/src/main/assets/search/india.sqlite` and a JSON provenance report. Generated bulk files are ignored by Git. A fresh checkout must run this before building an APK with India offline coverage. The SQLite database uses Android-compatible FTS4 unicode tokens, prefix matching, stable IDs, population ordering, a spatial R-tree and retained source metadata. Build replacement is atomic; invalid/empty sources leave the previous database intact. On Android the shipped index is copied to internal storage, integrity checked and opened read-only; app upgrades compare its content hash. Room user places and reviewed routing graphs are unchanged.

Queries combine national matches, installed regional matches and Photon worldwide matches. Online failure retains offline India matches with attribution. National search does not depend on selecting KIIT or Mumbai. No user GPS is transmitted by this search layer. No paid API key is required.

Source: https://download.geonames.org/export/dump/IN.zip
Schema/license: https://download.geonames.org/export/dump/readme.txt
Attribution: GeoNames, Creative Commons Attribution 4.0.

Coverage is the publisher's available India features, not a guarantee of every business or address. The retrieval timestamp is not a claim that a place was verified at that time. OpenStreetMap POIs through Photon remain available online. Full OSM and Overture offline ingestion, national road routing, and automatic dataset refresh are subsequent integration work and are not implemented by this gazetteer.

Acceptance must cover national place names/aliases and real source coordinates, offline recovery, Android SQLite compatibility, map selection, and simulated fresh/stale GPS on the existing Pixel_9_Pro API 37 emulator. Emulator locations do not prove physical GNSS accuracy. No new AVD is required.
