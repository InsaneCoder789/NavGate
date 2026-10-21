#!/usr/bin/env python3
"""Builds the offline points-of-interest index (restaurants, fuel, hospitals, hotels, ATMs ...) for a region from OpenStreetMap.

Data comes from the public Overpass API, one request per category, so a build is polite and restartable. Output is an
Android-compatible SQLite file (FTS4 names, latitude index) plus a JSON manifest with provenance and the file hash.

    python3 scripts/build_poi_index.py                 # Mumbai metropolitan region (default)
    python3 scripts/build_poi_index.py --bbox 18.85 72.75 19.35 73.15 --name mumbai

Source data: (c) OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright).
"""
import argparse, datetime, hashlib, json, re, sqlite3, sys, time, urllib.parse, urllib.request
from pathlib import Path

ENDPOINTS = ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter', 'https://overpass.private.coffee/api/interpreter']
CACHE = Path(__file__).resolve().parent.parent / 'data/poi-cache'
AGENT = 'NavGate-index-build/1.0 (https://github.com/InsaneCoder789/NavGate)'
OUT = Path(__file__).resolve().parent.parent / 'app/src/main/assets/search'

# category -> (Overpass filters, readable label). Every filter is combined with the bounding box.
CATEGORIES = {
    'food': ('Restaurants & cafes', ['"amenity"~"^(restaurant|cafe|fast_food|food_court|ice_cream|bar|pub)$"', '"shop"~"^(bakery|confectionery)$"']),
    'fuel': ('Petrol stations', ['"amenity"="fuel"']),
    'ev': ('EV charging', ['"amenity"="charging_station"']),
    'health': ('Hospitals & clinics', ['"amenity"~"^(hospital|clinic|doctors|dentist)$"', '"healthcare"~"^(hospital|clinic|centre)$"']),
    'pharmacy': ('Pharmacies', ['"amenity"="pharmacy"', '"healthcare"="pharmacy"']),
    'lodging': ('Hotels', ['"tourism"~"^(hotel|hostel|guest_house|motel|apartment)$"']),
    'atm': ('ATMs', ['"amenity"="atm"']),
    'bank': ('Banks', ['"amenity"="bank"']),
    'grocery': ('Groceries', ['"shop"~"^(supermarket|convenience|grocery|greengrocer|department_store)$"']),
    'shopping': ('Shopping', ['"shop"="mall"', '"amenity"="marketplace"']),
    'education': ('Schools & colleges', ['"amenity"~"^(school|college|university|kindergarten)$"']),
    'transit': ('Stations', ['"railway"~"^(station|halt)$"', '"amenity"="bus_station"', '"public_transport"="station"']),
    'worship': ('Places of worship', ['"amenity"="place_of_worship"']),
    'park': ('Parks & beaches', ['"leisure"~"^(park|garden|playground|nature_reserve)$"', '"natural"="beach"']),
    'fitness': ('Gyms & sports', ['"leisure"~"^(fitness_centre|sports_centre|stadium|swimming_pool)$"']),
    'attraction': ('Things to do', ['"tourism"~"^(attraction|museum|gallery|viewpoint|zoo|theme_park)$"', '"historic"~"^(monument|memorial|fort|castle|ruins)$"']),
    'entertainment': ('Cinemas & theatres', ['"amenity"~"^(cinema|theatre|nightclub|arts_centre)$"']),
    'services': ('Services', ['"amenity"~"^(police|post_office|fire_station|townhall|courthouse)$"']),
    'toilets': ('Toilets', ['"amenity"="toilets"']),
    'parking': ('Parking', ['"amenity"="parking"']),
}

def overpass(query, attempts=9):
    body = urllib.parse.urlencode({'data': query}).encode()
    for attempt in range(attempts):
        endpoint = ENDPOINTS[attempt % len(ENDPOINTS)]  # a busy server is skipped, not hammered
        try:
            request = urllib.request.Request(endpoint, body, {'User-Agent': AGENT, 'Accept': 'application/json'})
            with urllib.request.urlopen(request, timeout=240) as response:
                return json.load(response)
        except Exception as error:
            wait = min(60, 10 * (attempt + 1))
            print(f'  request failed ({error}); retrying in {wait}s', file=sys.stderr, flush=True)
            time.sleep(wait)
    raise SystemExit('Overpass kept failing; run again later')

def tiles(south, west, north, east, rows=3, cols=3):
    for r in range(rows):
        for c in range(cols):
            yield (south + (north - south) * r / rows, west + (east - west) * c / cols, south + (north - south) * (r + 1) / rows, west + (east - west) * (c + 1) / cols)

def subtype(tags):
    for key in ('amenity', 'shop', 'tourism', 'leisure', 'railway', 'historic', 'healthcare', 'natural', 'public_transport'):
        if key in tags: return tags[key]
    return ''

def address(tags):
    street = ' '.join(x for x in (tags.get('addr:housenumber'), tags.get('addr:street')) if x)
    parts = [street, tags.get('addr:suburb') or tags.get('addr:neighbourhood'), tags.get('addr:city'), tags.get('addr:postcode')]
    return ', '.join(p for p in parts if p)

def pbf_elements(pbf, bbox):
    """Reads places straight from a Geofabrik extract: {category: [Overpass-shaped elements]} (no network rate limits)."""
    import osmium
    south, west, north, east = bbox
    rules = []
    for category, (_, filters) in CATEGORIES.items():
        for f in filters:
            m = re.match(r'"(\w+)"(=|~)"(.*)"$', f)
            key, op, value = m.groups()
            rules.append((category, key, re.compile(value) if op == '~' else re.compile('^' + re.escape(value) + '$')))
    found = {c: [] for c in CATEGORIES}

    class Handler(osmium.SimpleHandler):
        def take(self, kind, obj, lat, lon):
            if not (south <= lat <= north and west <= lon <= east): return
            tags = {t.k: t.v for t in obj.tags}
            if not tags: return
            for category, key, pattern in rules:
                if key in tags and pattern.match(tags[key]):
                    found[category].append({'type': kind, 'id': obj.id, 'lat': lat, 'lon': lon, 'tags': tags}); return
        def node(self, n):
            if n.location.valid(): self.take('node', n, n.location.lat, n.location.lon)
        def way(self, w):
            try:
                if not w.tags: return
                points = [(x.lat, x.lon) for x in w.nodes if x.location.valid()]
            except Exception: return
            if points: self.take('way', w, sum(a for a, _ in points) / len(points), sum(b for _, b in points) / len(points))

    Handler().apply_file(str(pbf), locations=True, idx='sparse_mem_array')
    return found

def build(bbox, name, pbf=None):
    south, west, north, east = bbox
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / f'{name}_pois.sqlite'
    temporary = target.with_suffix('.building')
    temporary.unlink(missing_ok=True)
    db = sqlite3.connect(temporary)
    db.executescript('''
        CREATE TABLE pois(id TEXT PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL, subtype TEXT NOT NULL, brand TEXT NOT NULL,
            latitude REAL NOT NULL, longitude REAL NOT NULL, address TEXT NOT NULL, phone TEXT NOT NULL, website TEXT NOT NULL, hours TEXT NOT NULL,
            wheelchair TEXT NOT NULL, cuisine TEXT NOT NULL);
        CREATE VIRTUAL TABLE names USING fts4(name, brand, subtype, tokenize=unicode61);
        CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        PRAGMA user_version=1;
    ''')
    counts = {}
    seen = set()
    from_pbf = pbf_elements(pbf, bbox) if pbf else None
    for category, (label, filters) in CATEGORIES.items():
        print(f'{category}: fetching {label} ...', flush=True)
        elements = []
        if from_pbf is not None: filters = []
        # Small tiles keep each request cheap enough for the public servers; nodes and ways cover almost every place.
        for tile_south, tile_west, tile_north, tile_east in ([] if from_pbf is not None else tiles(south, west, north, east)):
            clauses = ''.join(f'{kind}[{f}]({tile_south},{tile_west},{tile_north},{tile_east});' for f in filters for kind in ('node', 'way'))
            cached = CACHE / f'{name}-{category}-{tile_south:.3f}-{tile_west:.3f}.json'
            if cached.exists():
                data = json.loads(cached.read_text())
            else:
                data = overpass(f'[out:json][timeout:120];({clauses});out center tags;')
                CACHE.mkdir(parents=True, exist_ok=True); cached.write_text(json.dumps(data)); time.sleep(2)
            elements.extend(data.get('elements', []))
        if from_pbf is not None: elements = from_pbf[category]
        added = 0
        for element in elements:
            tags = element.get('tags', {})
            lat = element.get('lat', element.get('center', {}).get('lat'))
            lon = element.get('lon', element.get('center', {}).get('lon'))
            if lat is None or lon is None: continue
            key = f"{element['type'][0]}{element['id']}"
            if key in seen: continue
            title = (tags.get('name:en') or tags.get('name') or tags.get('brand') or tags.get('operator') or '').strip()
            if not title:
                # Unnamed ATMs, toilets, parking and fuel points are still useful; others are noise.
                if category not in ('atm', 'toilets', 'parking', 'fuel', 'ev'): continue
                title = label.rstrip('s') if category in ('atm',) else label
            seen.add(key)
            db.execute('INSERT INTO pois VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)', (key, title, category, subtype(tags), (tags.get('brand') or tags.get('operator') or '').strip(), lat, lon,
                address(tags), (tags.get('phone') or tags.get('contact:phone') or '').strip(), (tags.get('website') or tags.get('contact:website') or '').strip(),
                (tags.get('opening_hours') or '').strip(), tags.get('wheelchair', ''), tags.get('cuisine', '')))
            db.execute('INSERT INTO names(docid,name,brand,subtype) VALUES((SELECT rowid FROM pois WHERE id=?),?,?,?)', (key, title, tags.get('brand', ''), subtype(tags)))
            added += 1
        counts[category] = added
        print(f'  {added} places', flush=True)
        db.commit()
    db.execute('CREATE INDEX pois_category_latitude ON pois(category, latitude)')
    db.execute('CREATE INDEX pois_latitude ON pois(latitude)')
    metadata = {'schema': '1', 'region': name, 'bbox': ','.join(map(str, bbox)), 'source': 'OpenStreetMap via Geofabrik extract' if pbf else 'OpenStreetMap via Overpass API', 'license': 'ODbL 1.0',
                'attribution': '(c) OpenStreetMap contributors', 'retrieved_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'counts': json.dumps(counts)}
    db.executemany('INSERT INTO metadata VALUES(?,?)', metadata.items())
    db.commit()
    if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok': raise SystemExit('Integrity check failed')
    db.execute("INSERT INTO names(names) VALUES('optimize')"); db.commit(); db.execute('VACUUM'); db.close()
    temporary.replace(target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    manifest = {**metadata, 'counts': counts, 'places': sum(counts.values()), 'bytes': target.stat().st_size, 'index_sha256': digest}
    (OUT / f'{name}_pois.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f"Done: {manifest['places']} places, {manifest['bytes'] // 1024} KB, sha256 {digest[:12]}")

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--bbox', nargs=4, type=float, default=[18.85, 72.75, 19.35, 73.15], metavar=('SOUTH', 'WEST', 'NORTH', 'EAST'))
    parser.add_argument('--name', default='mumbai')
    parser.add_argument('--pbf', help='Geofabrik .osm.pbf to read instead of calling Overpass')
    arguments = parser.parse_args()
    build(arguments.bbox, arguments.name, arguments.pbf)
