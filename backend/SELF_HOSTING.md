# NavGate Zero-Billing Routing Setup

This setup gives you zero third-party API billing:

- Primary router: `Valhalla` (self-hosted)
- Fallback router: `OSRM` (self-hosted)
- Final fallback: in-code simplified route fallback (already in backend service)

## 1) Prerequisites

- Docker + Docker Compose plugin installed on your server
- At least 8 GB RAM recommended for India-scale routing data
- 80+ GB free disk recommended for full India extracts and build intermediates

## 2) Build route datasets (one-time)

From the `backend/deploy` directory:

```bash
./setup_osrm.sh
./setup_valhalla.sh
```

Notes:

- Default extract is `india-latest.osm.pbf`
- You can override with `PBF_URL=... ./setup_osrm.sh` and `PBF_URL=... ./setup_valhalla.sh`
- For faster builds and less disk, you can use regional extracts (for example Maharashtra + Odisha workflows)

## 3) Configure environment

```bash
cp .env.example .env
```

Important defaults in `.env`:

- `NAVGATE_ROUTER_ORDER=valhalla,osrm`
- `NAVGATE_VALHALLA_URL=http://valhalla:8002`
- `NAVGATE_OSRM_FOOT_URL=http://osrm-foot:5000`
- `NAVGATE_OSRM_CAR_URL=http://osrm-car:5000`

## 4) Start stack

```bash
docker compose -f docker-compose.selfhost.yml --env-file .env up -d --build
```

This runs:

- `navgate-backend` on port `8080`
- `valhalla` on port `8002`
- `osrm-foot` and `osrm-car` internally for backend failover

## 5) Validate

Health:

```bash
curl http://localhost:8080/health
```

Route sample:

```bash
curl -X POST http://localhost:8080/route \
  -H "Content-Type: application/json" \
  -d '{
    "origin": {"latitude": 19.0760, "longitude": 72.8777},
    "destination": {"latitude": 19.0926, "longitude": 72.8258},
    "profile": "walking",
    "cityHint": "Mumbai"
  }'
```

Look for `routeSource` in response:

- `valhalla` when primary succeeds
- `osrm` when fallback triggers
- `fallback` only when both providers fail

## 6) Android app wiring

Set Android backend URL to your server:

- `http://<server-ip>:8080`

For emulator, use:

- `http://10.0.2.2:8080` when backend runs on same machine

