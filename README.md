# NavGate

NavGate is a native Kotlin/Compose navigation prototype. Its application backend runs on the phone: local place search, versioned Room data, A* walking routing, a shared navigation session, location quality/progress/rerouting, and recoverable trips. It requires no paid routing API, cloud account, or hosted server.

**Current data limitation:** the bundled KIIT catalogue contains 175 destinations, 84 without coordinates, and no reviewed walking graph or entrances. Search works locally. Route planning returns an explicit `MissingGraph` result until a reviewed pack is installed. Real OSM geometry has been acquired as development candidates; those candidates are not silently enabled for navigation.

## Start here

- [Implemented backend and Stitch integration contract](docs/BACKEND_INTEGRATION.md): APIs, ownership, engineering diagrams, persistence, complexity, update flow and limitations.
- [Technical proposal](docs/TECHNICAL_PROPOSAL.md): architecture rationale and longer-term phases.
- [Implementation status and evidence](docs/IMPLEMENTATION_STATUS.md): commit ledger, executed checks and acceptance gaps.
- [Initial repository understanding](docs/PROJECT_UNDERSTANDING.md): historical audit at the pre-migration baseline.
- [OSM source and import policy](data/osm/README.md): bounded source snapshot, attribution and candidate regeneration.

The user will create the frontend in Stitch. Existing Compose screens are an integration shell; this backend work does not finalize the visual design.

## Run Android

Open the repository root in Android Studio, sync Gradle, and run `app`. Install Android SDK 35 and a compatible JDK. The verified host uses Java 22 with Java 17 bytecode targets. Keep `local.properties` machine-local; do not commit SDK paths.

Production services are wired through `NavGateServices` and `NavGateViewModel`. MapLibre renders the map, framework LocationManager supplies foreground location, Room stores structured data, DataStore stores settings, and optional Android offline TTS speaks instructions. Map and camera presentation share one trip. Camera assistance is a compass overlay; world-anchored AR and an ML localization model are not implemented.

Routing data and basemap resources are separate. Offline basemap download is not implemented. Online map rendering still needs connectivity and provider availability. The new local repository does not fall back to public OSRM or fabricated geometry.

## Verify

With an API 34 device/emulator connected:

```sh
./gradlew :navigation-core:test :app:testDebugUnitTest :app:assembleDebug :app:connectedDebugAndroidTest :app:lintDebug --console=plain
python3 -m unittest discover -s scripts/tests -v
```

JVM tests compare A* costs against an independent Dijkstra oracle and exercise route/session/update failures. Instrumentation uses isolated databases/preferences and tests durable activation, migration, retention, recovery, document access and the real bundled catalogue. These checks do not establish outdoor walking, GNSS, physical compass, audio, or production acceptance.

## Campus data and updates

Regenerate the information-only catalogue with `python3 scripts/build_region_catalog.py`. Extract source-backed graph candidates with `python3 scripts/build_osm_candidates.py`. Neither command invents walking entrances or approves access from coordinate proximity.

Pack updates validate bounded input, schema, topology, signature and selected region, then atomically switch active versions. Active trips remain pinned to their old graph. Manual document imports are exposed for future Stitch screens, but signed updates are disabled until the publisher bundles its trusted public key. See the integration contract for the exact trust-root path and release signing command. Private signing keys must remain outside Git.

The OSM snapshot is © OpenStreetMap contributors under ODbL; workbook redistribution/provenance remains a separate review item.

## Optional historical Go service

The existing `backend/` source is preserved as a development/reference service. It exposes `/health`, `/places`, `/places/{id}`, `/categories`, `/route`, and `/reroute`, with a Valhalla → OSRM → simplified geometry chain. Its final simplified fallback is not a validated walking route. None of this chain is a production fallback for the local Kotlin engine.

With Go 1.23+ installed:

```sh
cd backend
go test ./...
```

To run it with Docker from the repository root:

```sh
docker build -t navgate-backend:local ./backend
docker run -d --name navgate-backend-local -p 8080:8080 navgate-backend:local
curl http://localhost:8080/health
```

The full provider deployment is documented in [SELF_HOSTING.md](backend/SELF_HOSTING.md). Self-hosting consumes machine/storage/network resources even without per-request API billing. It is optional; Android campus engine testing does not require this stack. Supabase starter work is historical and is not required for local search, bookmarks or navigation.

## Remaining release work

Review source-supported walking connectivity and building entrances, resolve workbook data rights, perform real campus/device walks and failure recovery checks, and decide whether to add permitted offline basemap resources. Broader city routing, indoor connectivity and world-anchored AR require separate implementation and acceptance. No production navigation readiness is claimed.
