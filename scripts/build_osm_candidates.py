"""Extract candidate walking topology; never infer building entrances or promote unreviewed data."""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
from pathlib import Path

BOUNDS = (20.33, 85.79, 20.38, 85.84)
WALKABLE = {'footway', 'pedestrian', 'steps', 'path', 'residential', 'living_street', 'service', 'unclassified'}
DENIED = {'no', 'private', 'customers', 'delivery', 'permit', 'destination'}


def access_reason(tags: dict) -> str | None:
    if any('conditional' in key for key in tags):
        return 'conditional_access_requires_review'
    if tags.get('area') == 'yes' or tags.get('indoor') == 'yes' or tags.get('highway') not in WALKABLE:
        return 'unsupported_way_type'
    if tags.get('foot') in DENIED:
        return 'foot_restricted'
    if tags.get('access') not in (None, 'yes', 'permissive') and tags.get('foot') not in ('yes', 'designated'):
        return 'access_restricted_or_unknown'
    if tags.get('foot') not in (None, 'yes', 'designated', 'permissive'):
        return 'foot_access_unknown'
    if tags.get('oneway:foot') not in (None, 'no', 'yes', '1', '-1'):
        return 'foot_direction_unknown'
    return None


def extract(source: dict) -> tuple[dict, dict]:
    if source.get('remark'):
        raise ValueError('Overpass reported an incomplete/error response')
    elements = source['elements']
    nodes = {item['id']: item for item in elements if item['type'] == 'node'}
    edges, used = [], set()
    rejected = collections.Counter()
    for way in sorted((e for e in elements if e['type'] == 'way'), key=lambda e: e['id']):
        tags = way.get('tags', {})
        reason = access_reason(tags)
        if reason:
            rejected[reason] += 1
            continue
        direction = tags.get('oneway:foot')
        for index, (start, end) in enumerate(zip(way['nodes'], way['nodes'][1:])):
            pair = [nodes.get(start), nodes.get(end)]
            if None in pair:
                rejected['missing_node_segment'] += 1
                continue
            if any(not (BOUNDS[0] < p['lat'] < BOUNDS[2] and BOUNDS[1] < p['lon'] < BOUNDS[3]) for p in pair):
                rejected['outside_segment'] += 1
                continue
            # A gate or access node cannot be crossed based on an unreviewed way alone.
            if any(p.get('tags', {}).get('barrier') or
                   p.get('tags', {}).get('access') not in (None, 'yes', 'permissive') or
                   p.get('tags', {}).get('foot') not in (None, 'yes', 'designated', 'permissive') or
                   any('conditional' in key for key in p.get('tags', {})) for p in pair):
                rejected['barrier_or_restricted_node_segment'] += 1
                continue
            if start == end or (pair[0]['lat'], pair[0]['lon']) == (pair[1]['lat'], pair[1]['lon']):
                rejected['zero_segment'] += 1
                continue
            directions = [(start, end)] if direction in ('yes', '1') else [(end, start)] if direction == '-1' else [(start, end), (end, start)]
            for a, b in directions:
                edges.append({'id': f"osm-way-{way['id']}-{index}-{a}-{b}", 'from': f'osm-node-{a}', 'to': f'osm-node-{b}',
                              'geometry': [{'latitude': nodes[n]['lat'], 'longitude': nodes[n]['lon']} for n in (a, b)],
                              'access': 'Unknown', 'reviewed': False, 'name': tags.get('name', ''), 'stairs': tags.get('highway') == 'steps',
                              'sourceWayId': way['id'], 'sourceTags': tags})
            used.update((start, end))
    result = {'sourceTimestamp': source.get('osm3s', {}).get('timestamp_osm_base'),
              'attribution': '© OpenStreetMap contributors', 'license': 'ODbL-1.0',
              'bounds': BOUNDS, 'nodes': [{'id': f'osm-node-{n}', 'point': {'latitude': nodes[n]['lat'], 'longitude': nodes[n]['lon']}} for n in sorted(used)],
              'candidateEdges': edges, 'entrances': [], 'fieldVerified': False}
    report = {'candidateNodeCount': len(used), 'candidateDirectedEdgeCount': len(edges),
              'excludedCounts': dict(sorted(rejected.items())), 'routableEntranceCount': 0,
              'navigationReady': False, 'reason': 'Candidates require access/topology review and source-supported destination entrances'}
    return result, report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path('data/osm/kiit-overpass.json'))
    parser.add_argument('--output', type=Path, default=Path('data/osm/kiit-candidates.json'))
    parser.add_argument('--report', type=Path, default=Path('data/reports/kiit-osm-candidates.json'))
    args = parser.parse_args()
    raw = args.source.read_bytes()
    candidates, report = extract(json.loads(raw))
    report['sourceSha256'] = hashlib.sha256(raw).hexdigest()
    for path, value in ((args.output, candidates), (args.report, report)):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
