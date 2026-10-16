# NavGate

NavGate is a native Android maps and navigation prototype with a Compose UI, shared 2D + AR navigation flow, and self-hosted routing providers. Self-hosting avoids per-request provider billing, not infrastructure or routing-data costs.

## What we have built

- Native Android app (`Kotlin + Jetpack Compose`)
- Premium dark navigation UI with map-first experience
- Full state flow for:
  - `Explore`
  - `Place details`
  - `Route preview`
  - `Active navigation`
  - `AR assist mode`
- Search and place selection with city mode handling
- Route preview and start navigation flow
- Turn banner + trip summary in active navigation
- GPS/sample hardening for city-context route stability
- Backend fallback logic in app search (backend -> bundled/open-map fallback)

## Architecture

### Android app

- `Kotlin`, `Jetpack Compose`, `Material 3`
- `MapLibre` map rendering
- Shared navigation state engine for map and AR modes
- Route/search repositories with fallback handling

### Backend (`Go`)

- Endpoints:
  - `GET /health`
  - `GET /places`
  - `GET /places/{id}`
  - `GET /categories`
  - `POST /route`
  - `POST /reroute`
- KIIT seed dataset support for custom POIs
- Routing provider chain:
  1. `Valhalla` (primary, self-hosted)
  2. `OSRM` (fallback, self-hosted)
  3. In-code simplified fallback route

## Zero API billing plan

This repo now supports a zero external API billing stack by self-hosting routing providers.

- Primary: `Valhalla`
- Fallback: `OSRM` (foot + car profiles)
- Final fallback: backend in-code route fallback

Deployment files added:

- [`backend/Dockerfile`](backend/Dockerfile)
- [`backend/deploy/docker-compose.selfhost.yml`](backend/deploy/docker-compose.selfhost.yml)
- [`backend/deploy/.env.example`](backend/deploy/.env.example)
- [`backend/deploy/setup_osrm.sh`](backend/deploy/setup_osrm.sh)
- [`backend/deploy/setup_valhalla.sh`](backend/deploy/setup_valhalla.sh)
- [`backend/SELF_HOSTING.md`](backend/SELF_HOSTING.md)

## Run Android app

1. Clone the repository and open its root directory in Android Studio.
2. Sync Gradle.
3. Run on emulator or device.
4. For local backend from emulator, backend URL should be `http://10.0.2.2:8080`.

## Run backend locally (quick)

### Docker

Run these commands from the repository root. Docker must be installed and running.

1. Build:
```bash
docker build -t navgate-backend:local ./backend
```
2. Run:
```bash
docker run -d --name navgate-backend-local -p 8080:8080 navgate-backend:local
```
3. Verify:
```bash
curl http://localhost:8080/health
```

### Full self-host routing stack

Follow:
- [`backend/SELF_HOSTING.md`](backend/SELF_HOSTING.md)

## Routing Evidence And Fallbacks

Inspect `routeSource` and `warnings` in each backend route response:

- `valhalla` or `osrm` identifies the routing provider that produced the route; this is not a measured navigation-accuracy guarantee.
- `fallback` identifies a simplified in-code route when providers are unavailable. It is not equivalent to a road-network-validated route and must not be presented as provider-quality guidance.
- AR guidance depends separately on device pose, GPS and confidence. A route response alone does not prove safe world anchoring; unavailable or low-confidence AR guidance is not successful AR navigation.

Backend regression tests can be run from a clean checkout with Go 1.23+:

```sh
cd backend
go test ./...
```

These tests do not start self-hosted routing engines or establish installed-device AR acceptance. The full Docker stack requires the routing datasets described in the self-hosting guide; clean-machine/device verification remains a separate gate.

## Current known limits

- Some city routes can still be imperfect depending on local OSM routing graph coverage.
- AR mode is assistive and still being hardened for production-grade world-anchored precision.
- 16 KB native library compatibility warnings can still appear on some emulator/device setups.

## Next priority work

1. Finish Supabase auth + user-linked saved/recents sync.
2. Improve route quality with tuned self-hosted Valhalla/OSRM data pipelines.
3. Continue AR path anchoring quality and confidence fallback refinement.
