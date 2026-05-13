#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="${ROOT_DIR}/osrm-data"
PBF_URL="${PBF_URL:-https://download.geofabrik.de/asia/india-latest.osm.pbf}"

mkdir -p "${DATA_DIR}"

if [ ! -f "${DATA_DIR}/india-latest.osm.pbf" ]; then
  echo "Downloading India OSM extract..."
  curl -L "${PBF_URL}" -o "${DATA_DIR}/india-latest.osm.pbf"
fi

echo "Preparing OSRM car graph..."
docker run --rm -t -v "${DATA_DIR}:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-extract -p /opt/car.lua /data/india-latest.osm.pbf -o /data/india-car.osrm
docker run --rm -t -v "${DATA_DIR}:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-partition /data/india-car.osrm
docker run --rm -t -v "${DATA_DIR}:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-customize /data/india-car.osrm

echo "Preparing OSRM foot graph..."
docker run --rm -t -v "${DATA_DIR}:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-extract -p /opt/foot.lua /data/india-latest.osm.pbf -o /data/india-foot.osrm
docker run --rm -t -v "${DATA_DIR}:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-partition /data/india-foot.osrm
docker run --rm -t -v "${DATA_DIR}:/data" ghcr.io/project-osrm/osrm-backend:latest \
  osrm-customize /data/india-foot.osrm

echo "OSRM datasets ready in ${DATA_DIR}."
