#!/usr/bin/env python3
"""Imports EVERY row of the KIIT campus workbook (campuses, hostels, cafeterias, sports, KIMS, KISS, guest houses and the unassigned places).

The older importer kept only rows assigned to a campus (175 of 326) and lost the coordinates of rows whose name cell carried the link.
This one keeps all rows, takes the coordinate from the link in either cell, cleans the name, and never stores the link itself.
Rows with no coordinate can optionally be matched by name against the OpenStreetMap place index (strong matches only, marked as such).

    python3 scripts/import_kiit_sheet.py [workbook.xlsx] [--osm-index app/src/main/assets/search/odisha_pois.sqlite]
"""
import argparse, difflib, importlib.util, json, math, re, sqlite3, sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('kiit_import_base', ROOT / 'backend/scripts/import_kiit_locations.py')
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)

URL = re.compile(r'https?://\S+')
KIIT_BOX = (20.30, 85.77, 20.40, 85.88)          # south, west, north, east: the campuses all lie inside this

def coordinates(text):
    for pattern in (r'[?&]q=(-?\d+\.\d+),(-?\d+\.\d+)', r'@(-?\d+\.\d+),(-?\d+\.\d+)', r'(-?\d{1,2}\.\d{3,}),\s*(-?\d{2,3}\.\d{3,})'):
        m = re.search(pattern, text or '')
        if m: return float(m.group(1)), float(m.group(2))
    return None, None

def clean_name(value):
    value = URL.sub(' ', value or '')
    value = re.sub(r'\s+', ' ', value).strip(' -,:;|')
    return value

def tokens(text):
    return set(re.findall(r'[a-z0-9]+', text.lower())) - {'the', 'of', 'and', 'kiit', 'campus', 'block', 'building'}

CITIES = {'Bhubaneswar': (20.2961, 85.8245, 14_000), 'Puri': (19.8135, 85.8312, 9_000), 'Cuttack': (20.4625, 85.8828, 9_000), 'Konark': (19.8876, 86.0945, 6_000)}

def city_of(lat, lon):
    """The nearest known city a coordinate belongs to; places elsewhere in the state are just 'Odisha'."""
    if lat is None: return 'Bhubaneswar'
    for city, (la, lo, radius) in CITIES.items():
        if math.hypot((lat - la) * 111_320, (lon - lo) * 111_320 * math.cos(math.radians(la))) <= radius: return city
    return 'Odisha'

def osm_match(name, db):
    """The OpenStreetMap/Overture place inside the KIIT box that [name] means, or None unless the match is strong and unambiguous.

    A name that says KIIT only matches a place that also says KIIT (so "KIIT Guest House" never becomes some other guest house), and when two
    different places match equally well the answer is "unknown" rather than a guess."""
    s, w, n, e = KIIT_BOX
    want = tokens(name)
    if not want: return None
    says_kiit = 'kiit' in name.lower()
    scored = []
    for pid, title, lat, lon, cat in db.execute('SELECT id,name,latitude,longitude,category FROM pois WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?', (s, n, w, e)):
        got = tokens(title)
        if not got: continue
        if says_kiit and 'kiit' not in title.lower() and 'kalinga' not in title.lower(): continue
        score = len(want & got) / len(want | got)
        score = max(score, difflib.SequenceMatcher(None, name.lower(), title.lower()).ratio() * 0.95)
        # "Sculpture park" is the place called "Sculpture Park, KIIT University": every word of the sheet's name appears, and the place says KIIT.
        if len(want) >= 2 and want <= got and 'kiit' in title.lower(): score = max(score, 0.9)
        scored.append((score, pid, title, lat, lon))
    scored.sort(reverse=True)
    if not scored or scored[0][0] < 0.85: return None
    best = scored[0]
    for other in scored[1:]:
        if other[0] < best[0] - 0.02: break
        if math.hypot(other[3] - best[3], other[4] - best[4]) * 111_320 > 100: return None      # two different places fit equally well
    return best

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('workbook', nargs='?', default='/Users/rohanc/Downloads/KIIT_Complete_Campus_Wise_Locations.xlsx')
    parser.add_argument('--osm-index')
    parser.add_argument('--output', type=Path, default=ROOT / 'backend/internal/campus/seed/custom_pois.json')
    parser.add_argument('--also', type=Path, nargs='*', default=[ROOT / 'app/src/main/assets/custom_pois.json'])
    arguments = parser.parse_args()
    rows = base.load_all_locations(Path(arguments.workbook))
    headers = rows[0]
    if [headers.get(c) for c in 'ABCD'] != ['Campus', 'Category', 'Location Name', 'Google Maps URL']: raise SystemExit('Unexpected All_Locations headers')
    db = sqlite3.connect(arguments.osm_index) if arguments.osm_index else None
    pois, seen_ids, seen_places, stats = [], set(), set(), Counter()
    for index, row in enumerate(rows[1:], start=2):
        campus = base.normalize_text(row.get('A', '')); category_raw = base.normalize_text(row.get('B', ''))
        name_cell = base.normalize_text(row.get('C', '')); url_cell = base.normalize_text(row.get('D', ''))
        lat, lon = coordinates(url_cell); source = 'workbook link' if lat is not None else None
        if lat is None: lat, lon = coordinates(name_cell); source = 'workbook link (in the name cell)' if lat is not None else None
        name = clean_name(name_cell)
        if not name: stats['blank name'] += 1; continue
        confidence = None
        if lat is None and db is not None:
            match = osm_match(name, db)
            if match: confidence, _, matched, lat, lon = round(match[0], 2), match[1], match[2], match[3], match[4]; source = f'OpenStreetMap name match ({matched})'
        unassigned = campus.lower() == 'unassigned' or not campus
        key = (campus.lower(), name.lower(), lat, lon)
        if key in seen_places: stats['exact duplicate rows dropped'] += 1; continue
        seen_places.add(key)
        place_type, category = base.CATEGORY_MAP.get(category_raw.lower(), ('Landmark', category_raw.title() or 'Other'))
        number = int(campus.split()[-1]) if campus.lower().startswith('campus ') else None
        poi_id = f"{base.slug(campus if not unassigned else 'unassigned')}-{base.slug(name)}"; base_id = poi_id; n = 2
        while poi_id in seen_ids: poi_id = f'{base_id}-{n}'; n += 1
        seen_ids.add(poi_id)
        subtitle = ' • '.join(p for p in ([campus] if not unassigned else []) + [category])
        pois.append({'id': poi_id, 'name': name, 'normalizedName': name.lower(), 'subtitle': subtitle, 'city': city_of(lat, lon), 'region': 'Odisha',
            'campusLabel': None if unassigned else campus, 'campusNumber': number, 'categoryRaw': category_raw, 'categoryNormalized': category, 'placeType': place_type,
            'latitude': lat, 'longitude': lon, 'coordinateSource': source, 'coordinateConfidence': confidence, 'isReviewedUnassigned': False, 'isActive': True,
            'searchTags': [t for t in (base.slug(category).replace('-', ' '), base.slug(campus).replace('-', ' ') if not unassigned else '', 'kiit', 'bhubaneswar', 'odisha') if t], 'sourceRow': index})
        stats['with coordinates' if lat is not None else 'without coordinates'] += 1
        stats['campus ' + (campus if not unassigned else 'Unassigned')] += 0
    # The same place can be on the sheet twice (under its campus and again as 'Unassigned', or once more without a position): keep the best row, and give it the campus the other one knew.
    def norm(poi): return re.sub(r'[^a-z0-9]+', ' ', poi['name'].lower()).strip()
    def source_rank(poi): return 2 if (poi['coordinateSource'] or '').startswith('workbook') else 1 if poi['coordinateSource'] else 0
    def apart(a, b): return math.hypot((a['latitude'] - b['latitude']) * 111_320, (a['longitude'] - b['longitude']) * 111_320 * math.cos(math.radians(a['latitude'])))
    kept = []
    for poi in sorted(pois, key=lambda p: (-source_rank(p), p['campusLabel'] is None, p['sourceRow'])):
        twin = next((k for k in kept if norm(k) == norm(poi) and (poi['latitude'] is None or k['latitude'] is None or apart(k, poi) <= 60)), None)
        if twin is None: kept.append(poi); continue
        stats['repeat rows dropped (same name, same spot)'] += 1
        if twin['campusLabel'] is None and poi['campusLabel'] is not None:
            for field in ('campusLabel', 'campusNumber', 'subtitle', 'searchTags', 'categoryRaw', 'categoryNormalized', 'placeType'): twin[field] = poi[field]
    pois = sorted(kept, key=lambda p: p['sourceRow'])
    for target in [arguments.output, *arguments.also]:
        target.write_text(json.dumps(pois, indent=2, ensure_ascii=False) + '\n')
    by_source = Counter((p['coordinateSource'] or 'none').split(' (')[0] for p in pois)
    print(f'{len(pois)} KIIT places written ({dict(by_source)}); categories: {dict(Counter(p["categoryNormalized"] for p in pois))}; campuses: {len({p["campusLabel"] for p in pois if p["campusLabel"]})}; {dict(stats)}')

if __name__ == '__main__': main()
