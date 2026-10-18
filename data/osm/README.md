# KIIT OSM source snapshot

`kiit-overpass.json` was downloaded through the read-only Overpass API with this bounded query:

```overpass
[out:json][timeout:40];
(way[highway](20.33,85.79,20.38,85.84);node[entrance](20.33,85.79,20.38,85.84););
(._;>;);
out body;
```

Endpoint: https://overpass-api.de/api/interpreter
User-Agent: NavGate-campus-development/1.0 (https://github.com/InsaneCoder789/NavGate)

The snapshot contains 2,298 ways and 13,374 nodes. Its source database timestamp is `2026-10-18T14:10:21Z`. There are only five `highway=footway` ways and one standalone entrance node in this bounding box; another residential way has an entrance tag and private access. Neither establishes a connection from the existing workbook destinations to a public walking entrance. Do not snap building centroids onto nearby roads and label those points as entrances.

A shared OSM node ID establishes topology. Geographic proximity alone does not establish a junction, gate, crossing, or building entrance. Access, foot restrictions, conditional access, barriers, construction, and disconnected ways must be accounted for before allowing an edge. OSM source/tag validation is distinct from on-foot verification and live closure information.

This is development ingestion evidence, not a live per-user dependency. Cache snapshots and avoid repeated public endpoint calls. Public servers have limited capacity and do not provide an app availability guarantee. See [Overpass policy](https://wiki.openstreetmap.org/wiki/Overpass_API#Public_Overpass_API_instances) and [foot access semantics](https://wiki.openstreetmap.org/wiki/Key:foot).

OSM data: © OpenStreetMap contributors, [ODbL](https://www.openstreetmap.org/copyright). Preserve attribution and comply with database redistribution/share-alike requirements for derived graph data. The separate workbook catalogue has its own unresolved provenance; ODbL does not grant rights to the workbook.

Regenerate candidates with `python3 scripts/build_osm_candidates.py`; verify importer rules with `python3 -m unittest discover -s scripts/tests -v`. Generated candidates are excluded from Git and APK assets. The versioned report records the raw-source SHA-256, excluded access/barrier counts, and zero established entrances. Candidate edges remain `Unknown` and unreviewed, so this tooling cannot silently enable production guidance.
