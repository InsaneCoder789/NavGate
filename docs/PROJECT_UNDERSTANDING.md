# NavGate: concept and implementation map

The review-stage architecture proposal dated 18 October 2026 is in [TECHNICAL_PROPOSAL.md](TECHNICAL_PROPOSAL.md), with five editable engineering diagrams and an [overview drawing](diagrams/architecture.svg). It proposes implementation phases; it does not record architecture approval or completed app changes.

## Updated user direction: 2026-10-17

The user explicitly wants native Kotlin rather than React Native, open-source mapping/routing components, and no paid services. A* or Dijkstra are acceptable algorithms. The current checkout is already Kotlin/Compose; a framework rewrite is unnecessary. Interpret "ma model" provisionally as map/routing technology rather than a requirement for a machine-learning model.

Proposed architecture under this constraint:

- Keep Kotlin/Compose and MapLibre. Use OpenStreetMap-derived data with required attribution and applicable data-license obligations.
- Start with a bounded KIIT walking graph stored on the device: verified entrances, junctions, paths, access restrictions, and explicit connections to destinations. Use Kotlin A* with a distance-based lower-bound heuristic for distance minimization; use Dijkstra as a reference implementation in algorithm validation. Travel-time routing needs a compatible time lower bound instead.
- Store place search and user data locally. Core campus navigation should not require a hosted backend or an API account.
- Use OpenFreeMap for online basemaps. Its current public service is free without API keys; offline downloads have published limits of 3,000 tiles or 50 MB per download and at most four parallel requests, with an identifying User-Agent. Offline map integration remains to be implemented and tested against this project's SDK versions.
- Evaluate BRouter as a broader offline routing candidate. It is an MIT-licensed Java/Android OSM router, not an already-validated drop-in NavGate dependency. Walking, maneuver output, graph footprint, and integration require a bounded prototype. Do not assume its driving profile meets road-navigation requirements.
- Retain the Go/Valhalla/OSRM code as existing optional infrastructure while evaluating this direction. Self-hosted software avoids provider bills but still consumes hardware, electricity, storage, and network resources. It is not a prerequisite for the proposed campus pilot.
- Keep map guidance and optional camera assistance separate from any claim of indoor or anchored AR precision. ARCore is not an open-source mapping engine; any requirement for an entirely open-source AR stack needs a separate decision.

Research sources checked live:

- [Public APIs](https://github.com/public-apis/public-apis): directory of services; includes Nominatim, OpenStreetMap, openrouteservice, and GraphHopper. Listing does not establish unlimited free hosting, an open-source service implementation, or a navigation SDK.
- [OpenFreeMap](https://openfreemap.org/): free public map rendering resources and explicit offline-download limits; no SLA guarantee.
- [MapLibre offline API](https://maplibre.org/maplibre-native/android/api/): native rendering/offline interfaces; rendering alone does not provide routing or offline place search.
- [BRouter](https://github.com/abrensch/brouter): MIT-licensed offline routing candidate.
- [Valhalla Thor](https://valhalla.github.io/valhalla/thor/): mature A* variants; useful if existing-hardware routing becomes appropriate.
- [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/): public service forbids autocomplete and caps aggregate application traffic at one request per second. Prefer local search for this proposal.
- [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/): standard public raster tile servers prohibit offline prefetch; do not use those servers to create offline map packs.
- [OSM copyright and licence](https://www.openstreetmap.org/copyright): data use carries attribution and ODbL obligations.

This records the budget constraint and a concrete proposal. It does not claim integration, performance, route correctness, or approved indoor scope. No application implementation was changed by this research.

Reviewed on 2026-10-17 against upstream `main` at `7ddd187`. This is a source and history review, not a device test, routing benchmark, or release-readiness audit. Interpretations of motivation are labeled as inference.

## Product concept

NavGate combines familiar map navigation with camera-based directional assistance. The intended journey is to discover a place, understand it, choose a start and destination, preview a walking or driving route, navigate, and switch between a map and AR assistance without losing trip progress.

The likely core problem is orientation: a map can show where a destination is while a person still struggles to identify the correct direction in the physical environment. This is an inference from the shared navigation engine, heading alignment interface, campus dataset, and historical beta copy.

The concept has three connected parts:

1. A broadly useful map-first navigator with search, place details, directions, spoken guidance, saved places, and recents.
2. Detailed campus discovery using institution-specific places that generic city search may miss.
3. A spatial guidance experience that helps users face and follow their route, with signal confidence and a map alternative.

The repository description emphasizes futuristic AR navigation. Later UI commits deliberately adopt Google Maps-style search, sheets, place profiles, route previews, and active guidance. The resulting direction is familiar map usability plus a distinctive AR assist experience.

## Intended audience and rollout

Historical commit `eb2dd17` contains explicit product copy:

- Mumbai is the daily map mode and a context for 2D route testing.
- KIIT students trial 3D/AR first and report bugs during a pilot.
- AR access is gated by KIIT mode plus a beta access preference.

This supports a campus pilot before broader AR expansion. It does not establish a launch, actual student adoption, or production deployment. The historical access switch is a local preference, not verified student identity or server-enforced entitlement.

Current `CityMode` still contains Mumbai and KIIT Beta, with Bhubaneswar as the latter's backend city. Bhubaneswar data also includes student-oriented places beyond campus. Treat broader city discovery and the KIIT pilot as related but distinct scopes.

## Intended journey and current wiring

| Stage | Intended experience | Current code |
| --- | --- | --- |
| Onboarding | Introduce discovery, routing, and AR | One-time screen persisted locally |
| Explore | Search or browse places in the selected city | Search box, quick category queries, collections, map |
| Place details | Inspect a destination and choose an action | Details sheet; directions, set start, bookmark |
| Plan | Choose start/destination and walking/driving | ViewModel selections and route requests |
| Preview | Review route, distance, duration, first instruction | Route preview sheet and rendered geometry |
| Navigate | Live position, next maneuver, ETA, voice | Shared engine, fused location, TTS, turn banner |
| Recover | Detect deviation and request a replacement route | ViewModel fetches a new route and preserves active navigation |
| AR assist | Face the route using camera guidance | CameraX preview, sensor heading, alignment ring and ribbon |
| Finish | Complete navigation and retain useful history | Engine arrival state and local route-history writes; no dedicated arrival surface in current home branching |

The latest home menu and profile callbacks are TODOs. City switching and beta-toggle functions remain in the ViewModel but are not connected to this home UI. The AR entry currently checks navigation and ARCore availability, rather than the historical KIIT beta rule. Layers and explore map-control buttons also have default empty callbacks; the location button requests permission rather than implementing a dedicated recenter action.

## Architecture and ownership

```text
Compose UI
  -> NavGateViewModel / StateFlow
       -> NavigationEngine -> shared NavigationSnapshot
       -> NavigationRepository -> routes and search
       -> SharedPreferences stores -> preferences, bookmarks, recents, history

Fused location -> ViewModel -> engine progress / rerouting
Device sensors -> AR alignment display
CameraX -> camera background
TextToSpeech -> maneuver announcements
MapLibre + OpenFreeMap -> map rendering

Go HTTP service -> embedded campus POIs
                -> Valhalla / OSRM provider chain -> simplified route fallback
```

Kotlin, Jetpack Compose, Material 3, coroutines, and StateFlow form the native Android app. Android API 26 is the minimum; compile/target SDK is 35. Java 17 is configured. Go 1.23 forms a small backend using the standard HTTP library and embedded JSON. History shows an earlier Expo project followed by an explicit reset to a native Android foundation at `c3ba2ee`.

The shared engine is a central design choice: map and AR are presentation modes of the same route, step, destination, arrival, and rerouting state. Switching presentation should not create a separate trip.

## Routing: intention versus actual request order

The documented backend design is configurable Valhalla first, OSRM second, then simplified geometry. Separate OSRM foot and car services are provided. Valhalla requests use pedestrian or auto costing. Provider results are normalized into route geometry, steps, mode, distance, duration, provenance, confidence labels, and warnings.

The Android implementation currently has a different outer order:

1. Public `router.project-osrm.org` request, using `foot` or `driving` in the URL.
2. The configured Go backend, whose own provider chain then applies.
3. `FakeCampusRepository`, which creates a midpoint-based illustrative route.

Thus self-hosted routing is implemented as backend infrastructure but is not the app's primary request path. A profile string in a public URL alone does not establish that a route was computed using an appropriate pedestrian graph; that behavior needs provider/device validation.

The backend's last-resort route is also midpoint-based geometry, not a path validated against roads, walls, walkways, gates, or access restrictions. Its response declares `routeSource=fallback` and a warning, but still sets medium confidence and AR support. The fake Android fallback inherits model defaults including `routeSource=backend`, which can obscure its synthetic origin. These are material distinctions for interpreting a route on screen.

Self-hosting aims to avoid per-request commercial routing bills. It still requires servers, routing extracts, preprocessing, storage, updates, and operation. The app also currently relies on public map tiles and geocoding/routing services. Bundled place data does not make the complete navigation experience offline.

## Search and place data

Backend search operates on the embedded campus seed, with query, city, campus, category, and limit parameters. Matching covers names, subtitles, categories, and tags. Place details and category-summary endpoints are also present; the Android repository interface currently uses search and route fetching rather than all backend endpoints.

Android searches the backend first. If empty or failed, it attempts bundled city data plus Nominatim search, then uses the small fake repository if necessary. An exception around the combined bundled/open-map fallback can discard bundled results and move directly to fake data.

Current checked-in data:

| Dataset | Records | Interpretation |
| --- | ---: | --- |
| Campus seed, duplicated identically in Android assets | 175 | Assigned campus entries imported from the workbook |
| Campus records without latitude or longitude | 84 | Present in the seed; not ready for dependable map placement |
| Reviewed unassigned campus entries admitted | 0 | No additional unassigned entries currently included |
| Mumbai popular places | 15 | Bundled city discovery entries |
| Bhubaneswar student places | 8 | Bundled student/city discovery entries |
| Bhubaneswar seed list in `data/` | 14 | Separate seed list, not the same as the 8-entry bundled asset |

The spreadsheet notes describe 326 original rows, including 151 unassigned. These historical workbook totals are documentation evidence; the original workbook was not reopened during this review. The importer normalizes categories/text, parses coordinate-bearing Maps URLs, preserves source row information, assigns IDs, and excludes unassigned rows unless allowlisted. It does not guarantee coordinates for admitted assigned rows. Kotlin reads coordinates using `getDouble`; Go's coordinate fields are numeric. Missing coordinates are therefore a contract concern, not merely an incomplete description.

The city builder uses Nominatim and Overpass for curated/geographic discovery. Search datasets describe destinations; they are not an indoor or campus routing graph.

## What AR currently means

Implemented pieces include ARCore availability detection, camera preview, device heading from rotation-vector or accelerometer/magnetometer fallback, gyro-adjusted heading smoothing, bearing-to-next-step alignment, colored feedback, and a switch back to the map.

Alignment thresholds are 12 degrees for aligned, 45 degrees for adjustment, and larger errors for recovery. The ribbon is a Compose Canvas line shifted by heading delta. Its distance argument currently does not alter the drawing.

No active ARCore session, world/geospatial anchors, tracked ground-plane path, floor graph, room-level positioning, or obstacle understanding was found. The current experience is a camera-backed directional HUD. The onboarding phrase about indoor high-precision navigation is an aspiration unsupported by the present implementation.

ARCore availability is stored in the Compose shell, but the engine's `setArAvailability` has no production caller in the inspected source. The AR screen uses sensor heading while engine confidence receives GPS bearing. These separate signal paths explain why visible alignment and guidance confidence can disagree. The manifest also declares AR hardware/Core required, despite the wider concept of map guidance when AR is unavailable.

## Navigation behavior and precision limits

The engine advances a step within 28 metres of its target, detects deviation more than 45 metres from the nearest stored route vertex, and classifies fix freshness at 3 and 8 seconds. Deviation uses vertices rather than distance to the route segments. ETA is recomputed from remaining step distances at fixed speeds: 1.35 m/s walking or 8 m/s driving. It is not live traffic prediction.

Location acceptance is restricted to within 120 km of the selected city anchor. Without a suitable live fix, route previews can start at Mumbai centre or the Patia campus default. These are preview conveniences, not evidence that the user is physically there.

Current confidence is heuristic: it considers fix age, presence of heading, deviation, and AR availability. It does not establish measured positioning accuracy, map matching quality, route safety, or world anchoring precision. Route provenance and sensor confidence are separate concepts and must remain separate in future work.

## Persistence, accounts, and backend scope

Bookmarks, recents, history, onboarding, city, and beta preferences are local SharedPreferences data. Saved places are capped at 40; recents and route history at 30. Route history replaces previous entries for the same destination, so it is a latest-per-destination history rather than a complete trip log.

Supabase has starter RLS SQL for profiles, saved places, recent places, and beta bug reports. There is no connected Supabase client/auth/sync flow or complete schema migration in this checkout. Account identity, cloud sync, and beta-report submission remain incomplete. Profile rating `4.8` and opening-hours copy are hardcoded UI content, not verified place metadata.

The Go backend exposes health, place search/details, categories, route, and reroute. Reroute reuses the route handler. POIs are embedded in the binary, not backed by a running database. Docker packaging, separate router services, India extract setup scripts, and environment examples are present; no running deployment was verified.

## Evidence and remaining decisions

Inspected: README and self-host guide, spreadsheet notes, history, Android shell/components, repository fallbacks, stores, navigation state/engine, location/sensor/camera code, backend HTTP/service/provider implementations, import scripts, JSON datasets, and existing test names. Existing tests cover engine progress/confidence, ViewModel transitions, live location/rerouting/arrival, alignment, GeoJSON, repository requests, backend HTTP, and provider failover. No tests, build, emulator, device, provider stack, or deployment were executed in this concept review.

Before expanding scope, clarify through the established product intent whether indoor room/floor navigation is a committed requirement or longer-term ambition; whether KIIT remains the exclusive AR pilot; and whether public routing remains acceptable before the self-hosted backend. These are unresolved product choices, not permission to redesign them automatically.

Key source entry points:

- [`NavGateApp.kt`](../app/src/main/java/com/rohanc/navgate/ui/NavGateApp.kt): current visible flows and callbacks.
- [`NavGateViewModel.kt`](../app/src/main/java/com/rohanc/navgate/ui/state/NavGateViewModel.kt): orchestration, city context, persistence, and recovery.
- [`NavigationEngine.kt`](../app/src/main/java/com/rohanc/navgate/navigation/NavigationEngine.kt): shared trip state and confidence heuristics.
- [`BackendNavigationRepository.kt`](../app/src/main/java/com/rohanc/navgate/data/BackendNavigationRepository.kt): actual client request and fallback order.
- [`AROverlay.kt`](../app/src/main/java/com/rohanc/navgate/ui/components/AROverlay.kt): screen-space guidance rendering.
- [`service.go`](../backend/internal/campus/service.go) and [`router.go`](../backend/internal/campus/router.go): POI service, provider normalization, and fallback geometry.
- [`KIIT_CAMPUS_SPREADSHEET_NOTES.md`](KIIT_CAMPUS_SPREADSHEET_NOTES.md): dataset origin and source quality.
- Historical `eb2dd17`: explicit campus pilot and Mumbai 2D product copy.
