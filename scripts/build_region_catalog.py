"""Build an information-only campus pack. Never infer entrances or edges from POIs."""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
from pathlib import Path


def build_catalog(rows: list[dict], version: str, generated_at: str) -> tuple[dict, dict]:
    places = []
    seen = set()
    missing = []
    invalid = []
    for row in rows:
        identity = row['id'].strip()
        if not identity or identity in seen:
            raise ValueError(f'Duplicate or blank place ID: {identity!r}')
        seen.add(identity)
        latitude, longitude = row.get('latitude'), row.get('longitude')
        point = None
        if latitude is None or longitude is None:
            missing.append(identity)
        elif (isinstance(latitude, (int, float)) and not isinstance(latitude, bool)
              and isinstance(longitude, (int, float)) and not isinstance(longitude, bool)
              and math.isfinite(latitude) and math.isfinite(longitude)
              and -90 <= latitude <= 90 and -180 <= longitude <= 180):
            point = {'latitude': latitude, 'longitude': longitude}
        else:
            invalid.append(identity)
        places.append({
            'id': identity, 'title': row['name'].strip(), 'subtitle': row.get('subtitle', '').strip(),
            'category': row.get('categoryNormalized', ''), 'city': row.get('city', 'Bhubaneswar'),
            'campus': row.get('campusLabel'), 'point': point,
            'aliases': [value for value in row.get('searchTags', []) if value],
            'reviewed': False, 'sourceRow': row.get('sourceRow'), 'sourceUrl': row.get('googleMapsUrl'),
        })
    if invalid:
        raise ValueError(f'Invalid coordinates require source review: {invalid}')
    pack = {
        'schemaVersion': 1, 'regionId': 'kiit', 'version': version, 'city': 'Bhubaneswar',
        # Explicit pilot boundary. This is a coverage declaration, not a claim of mapped paths.
        'coverage': {'boundary': [
            {'latitude': 20.33, 'longitude': 85.79}, {'latitude': 20.33, 'longitude': 85.84},
            {'latitude': 20.38, 'longitude': 85.84}, {'latitude': 20.38, 'longitude': 85.79},
        ]},
        'source': 'Existing NavGate KIIT workbook import; coordinates and access not field verified',
        'attribution': 'NavGate campus source workbook; original Maps links retained per place for review',
        'license': 'Source workbook redistribution and coordinate provenance require review before public data release',
        'generatedAt': generated_at, 'reviewedAt': None,
        'nodes': [], 'edges': [], 'entrances': [], 'places': sorted(places, key=lambda item: item['id']),
    }
    report = {'regionId': 'kiit', 'version': version, 'placeCount': len(places),
              'missingCoordinateIds': sorted(missing), 'missingCoordinateCount': len(missing),
              'reviewedEdgeCount': 0, 'reviewedEntranceCount': 0, 'routablePlaceCount': 0,
              'navigationReady': False, 'reason': 'No reviewed walking graph or entrances have been supplied'}
    return pack, report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path('backend/internal/campus/seed/custom_pois.json'))
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/regions/kiit-catalog.json'))
    parser.add_argument('--report', type=Path, default=Path('data/reports/kiit-catalog.json'))
    parser.add_argument('--date', default='2026-10-18')
    parser.add_argument('--version', default='catalog-2026-10-18')
    args = parser.parse_args()
    dt.date.fromisoformat(args.date)
    pack, report = build_catalog(json.loads(args.source.read_text()), args.version, args.date)
    payload = (json.dumps(pack, indent=2, ensure_ascii=False, allow_nan=False) + '\n').encode('utf-8')
    report['sha256'] = hashlib.sha256(payload).hexdigest()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(payload)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(f"Imported {len(pack['places'])} information-only places; {report['missingCoordinateCount']} lack coordinates; zero invented routes")


if __name__ == '__main__':
    main()
