# NavGate

NavGate is a native Android maps and navigation app with a premium Compose UI, shared 2D + AR navigation flow, and a self-hosted zero-API-billing backend strategy.

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

- [`backend/Dockerfile`](/Users/rohanc/NavGate/backend/Dockerfile)
- [`backend/deploy/docker-compose.selfhost.yml`](/Users/rohanc/NavGate/backend/deploy/docker-compose.selfhost.yml)
- [`backend/deploy/.env.example`](/Users/rohanc/NavGate/backend/deploy/.env.example)
- [`backend/deploy/setup_osrm.sh`](/Users/rohanc/NavGate/backend/deploy/setup_osrm.sh)
- [`backend/deploy/setup_valhalla.sh`](/Users/rohanc/NavGate/backend/deploy/setup_valhalla.sh)
- [`backend/SELF_HOSTING.md`](/Users/rohanc/NavGate/backend/SELF_HOSTING.md)

## Run Android app

1. Open `/Users/rohanc/NavGate` in Android Studio.
2. Sync Gradle.
3. Run on emulator or device.
4. For local backend from emulator, backend URL should be `http://10.0.2.2:8080`.

## Run backend locally (quick)

### Docker

1. Build:
```bash
docker build -t navgate-backend:local /Users/rohanc/NavGate/backend
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
- [`backend/SELF_HOSTING.md`](/Users/rohanc/NavGate/backend/SELF_HOSTING.md)

## Current known limits

- Some city routes can still be imperfect depending on local OSM routing graph coverage.
- AR mode is assistive and still being hardened for production-grade world-anchored precision.
- 16 KB native library compatibility warnings can still appear on some emulator/device setups.

## Next priority work

1. Finish Supabase auth + user-linked saved/recents sync.
2. Improve route quality with tuned self-hosted Valhalla/OSRM data pipelines.
3. Continue AR path anchoring quality and confidence fallback refinement.
