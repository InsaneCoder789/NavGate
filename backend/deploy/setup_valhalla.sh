#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="${ROOT_DIR}/valhalla-data"
PBF_URL="${PBF_URL:-https://download.geofabrik.de/asia/india-latest.osm.pbf}"

mkdir -p "${DATA_DIR}"
mkdir -p "${DATA_DIR}/valhalla_tiles"

if [ ! -f "${DATA_DIR}/india-latest.osm.pbf" ]; then
  echo "Downloading India OSM extract..."
  curl -L "${PBF_URL}" -o "${DATA_DIR}/india-latest.osm.pbf"
fi

if [ ! -f "${DATA_DIR}/valhalla.json" ]; then
  echo "Creating Valhalla config..."
  docker run --rm -v "${DATA_DIR}:/custom_files" ghcr.io/gis-ops/docker-valhalla/valhalla:latest \
    bash -lc "valhalla_build_config --mjolnir-tile-dir /custom_files/valhalla_tiles --mjolnir-tile-extract /custom_files/valhalla_tiles.tar > /custom_files/valhalla.json"
fi

echo "Building Valhalla tiles (this can take a long time)..."
docker run --rm -v "${DATA_DIR}:/custom_files" ghcr.io/gis-ops/docker-valhalla/valhalla:latest \
  bash -lc "valhalla_build_tiles -c /custom_files/valhalla.json /custom_files/india-latest.osm.pbf"

echo "Valhalla tiles ready in ${DATA_DIR}/valhalla_tiles"
