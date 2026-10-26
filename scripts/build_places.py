#!/usr/bin/env python3
"""Builds a region's offline places index from OpenStreetMap (Geofabrik extract) and Overture Maps, cleaned and de-duplicated, plus the datasheets.

    python3 scripts/build_places.py --region mumbai
    python3 scripts/build_places.py --region odisha

Steps: read OpenStreetMap places from the extract; read Overture places inside the region's state boundary; reject wrong names, closed places and
wrong locations (scripts/place_quality.py gives every rejection a reason); fold the same place seen twice into one record that keeps everything known
about it; write the SQLite index the app opens, a JSON report, and CSV datasheets of every kept and every rejected place.

Data: OpenStreetMap contributors (ODbL 1.0). Overture Maps Foundation places: CDLA-Permissive-2.0 (Meta, Microsoft), Apache-2.0 (Foursquare), CC0-1.0.
"""
import argparse, csv, datetime, hashlib, importlib.util, json, re, sqlite3, sys, urllib.parse
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / f'{name}.py'); module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module
pq, osm, overture = load('place_quality'), load('build_poi_index'), load('build_overture_places')

REGIONS = {
    'mumbai': {'label': 'Mumbai', 'box': (18.85, 72.75, 19.35, 73.15), 'state': 'IN-MH', 'postcodes': ('40', '41', '42', '43', '44'), 'regions': {'mh', 'maharashtra', 'mumbai'},
               'pbf': ROOT / 'data/osm-extract/western-zone.osm.pbf', 'min_confidence': 0.6},
    'odisha': {'label': 'Odisha', 'box': (17.78, 81.37, 22.57, 87.53), 'state': 'IN-OD', 'postcodes': ('75', '76', '77'), 'regions': {'od', 'or', 'odisha', 'orissa'},
               'pbf': ROOT / 'data/osm-extract/eastern-zone.osm.pbf', 'min_confidence': 0.5},
}
OUT = ROOT / 'app/src/main/assets/search'
SHEETS = Path('/Users/rohanc/Documents/Projects/NavGate/output/places')
EXTRA_TAGS = ('operator', 'wikidata', 'brand:wikidata', 'internet_access', 'takeaway', 'delivery', 'outdoor_seating', 'air_conditioning', 'payment:upi', 'payment:cards', 'diet:vegetarian',
              'diet:vegan', 'diet:halal', 'smoking', 'capacity', 'beds', 'emergency', 'healthcare:speciality', 'religion', 'denomination', 'fee', 'toilets', 'start_date', 'description',
              'stars', 'rooms', 'reservation', 'drive_through', 'level', 'ref', 'old_name', 'alt_name', 'short_name', 'name:hi', 'name:or')
UNNAMED_OK = ('atm', 'toilets', 'parking', 'fuel', 'ev')
AUDIT = []          # (source, name, category before, category after) for every category the name changed

def bare(link):
    """A link without its scheme and www: the app adds https:// when it opens one (saves a few megabytes over a hundred thousand places)."""
    return re.sub(r'^https?://(www\.)?', '', (link or '').strip()).rstrip('/') if link else ''

def compact_address(freeform, locality, postcode):
    """Street address, then the locality and postcode only if the street line does not already say them."""
    parts = [freeform] if freeform else []
    for extra in (locality, postcode):
        if extra and not any(extra.lower() in p.lower() for p in parts): parts.append(extra)
    return ', '.join(parts)

CACHE = ROOT / 'data/poi-cache/wikidata-photos.json'

def wikidata_photos(records):
    """Fills in a photo for notable places (temples, stations, hospitals, colleges, landmarks): OpenStreetMap lists their Wikidata entry, and Wikidata names a Commons picture.
    Answers are cached, so a rebuild asks again only for places not asked about before."""
    import urllib.request, time
    cache = json.loads(CACHE.read_text()) if CACHE.exists() else {}
    wanted = sorted({r['extra']['wikidata'] for r in records if r.get('extra', {}).get('wikidata') and not r['photo'] and r['extra']['wikidata'] not in cache})
    for i in range(0, len(wanted), 50):
        batch = wanted[i:i + 50]
        data = None
        for attempt in range(6):                                     # Wikidata asks callers to slow down when it answers 429
            try:
                request = urllib.request.Request('https://www.wikidata.org/w/api.php?action=wbgetentities&props=claims&format=json&ids=' + '|'.join(batch), headers={'User-Agent': 'NavGate/1.0 (https://github.com/InsaneCoder789/NavGate; place photos)'})
                data = json.load(urllib.request.urlopen(request, timeout=40)); break
            except Exception as error:
                time.sleep(8 * (attempt + 1))
        if data is None: print('  wikidata kept refusing; the remaining places get no photo this time', file=sys.stderr); break
        for q in batch:
            claims = data.get('entities', {}).get(q, {}).get('claims', {}).get('P18', [])
            cache[q] = claims[0]['mainsnak']['datavalue']['value'] if claims else ''
        CACHE.parent.mkdir(parents=True, exist_ok=True); CACHE.write_text(json.dumps(cache))
        time.sleep(1.5)
    CACHE.parent.mkdir(parents=True, exist_ok=True); CACHE.write_text(json.dumps(cache))
    added = 0
    for r in records:
        q = r.get('extra', {}).get('wikidata')
        if q and not r['photo'] and cache.get(q):
            r['photo'] = 'https://commons.wikimedia.org/wiki/Special:FilePath/' + urllib.parse.quote(cache[q].replace(' ', '_')) + '?width=800'; added += 1
    return added

def first(*values):
    for v in values:
        if v and str(v).strip(): return str(v).split(';')[0].strip()
    return ''

def photo_url(tags):
    image = tags.get('image') or tags.get('contact:image') or ''
    commons = tags.get('wikimedia_commons') or ''
    for value in (commons, image):
        if value.lower().startswith('file:'): return 'https://commons.wikimedia.org/wiki/Special:FilePath/' + urllib.parse.quote(value[5:].strip().replace(' ', '_')) + '?width=800'
    if image.startswith(('http://', 'https://')) and re.search(r'\.(jpe?g|png|webp)(\?|$)|commons\.wikimedia|upload\.wikimedia|mapillary|flickr', image, re.I): return image.replace('http://', 'https://')
    return ''

def social_url(tags):
    for key, base in (('contact:facebook', 'https://www.facebook.com/'), ('facebook', 'https://www.facebook.com/'), ('contact:instagram', 'https://www.instagram.com/'), ('instagram', 'https://www.instagram.com/')):
        v = (tags.get(key) or '').strip()
        if v: return v if v.startswith('http') else base + v.lstrip('@/')
    return ''

def osm_records(region, rejects):
    """Places from the OpenStreetMap extract as records, with the quality rules applied."""
    found = osm.pbf_elements(region['pbf'], region['box'])
    records = []
    for category, elements in found.items():
        label = osm.CATEGORIES[category][0]
        for e in elements:
            tags = e['tags']
            if any(k.startswith(('disused', 'abandoned', 'demolished', 'razed', 'was:', 'removed:')) for k in tags) or tags.get('opening_hours', '').strip().lower() == 'off':
                rejects.append(('osm', f"{e['type'][0]}{e['id']}", tags.get('name', ''), e['lat'], e['lon'], 'closed_or_disused', '')); continue
            raw = tags.get('name:en') or tags.get('name') or tags.get('brand') or tags.get('operator') or ''
            if not raw.strip() and category in UNNAMED_OK: name, reason = (label.rstrip('s') if category == 'atm' else label), None
            else: name, reason = pq.clean_name(raw)
            lat, lon = e['lat'], e['lon']
            if reason is None: reason = pq.location_problem(lat, lon, region, tags.get('addr:postcode'), None)
            if reason:
                rejects.append(('osm', f"{e['type'][0]}{e['id']}", raw, lat, lon, reason, '')); continue
            subtype = osm.subtype(tags)
            fixed, changed = pq.fix_category(name, category, subtype)
            if changed and category not in UNNAMED_OK: AUDIT.append(('osm', name, category, fixed)); category_final, subtype = fixed, (fixed if fixed != 'worship' else subtype)
            else: category_final = category
            if category_final == 'worship': subtype = pq.worship_kind(name, subtype)
            alt = sorted({tags[k].strip() for k in ('name:hi', 'name:or', 'alt_name', 'old_name', 'short_name', 'name') if tags.get(k) and tags[k].strip() != name})
            records.append({'key': f"{e['type'][0]}{e['id']}", 'source': 'osm', 'name': name, 'category': category_final, 'subtype': subtype, 'lat': lat, 'lon': lon,
                'brand': (tags.get('brand') or tags.get('operator') or '').strip(), 'address': osm.address(tags), 'phone': first(tags.get('phone'), tags.get('contact:phone'), tags.get('contact:mobile')),
                'website': bare(first(tags.get('website'), tags.get('contact:website'), tags.get('url'))), 'hours': (tags.get('opening_hours') or '').strip(), 'wheelchair': tags.get('wheelchair', ''),
                'cuisine': tags.get('cuisine', ''), 'email': first(tags.get('email'), tags.get('contact:email')), 'photo': photo_url(tags), 'social': bare(social_url(tags)), 'confidence': 1.0,
                'postcode': tags.get('addr:postcode', ''), 'locality': tags.get('addr:city') or tags.get('addr:suburb') or '', 'alt': alt[:4],
                'extra': {k: tags[k] for k in EXTRA_TAGS if tags.get(k)}})
    return records

# Overture taxonomy that is business-to-business or office noise rather than somewhere a person goes.
BUSINESS = re.compile(r'real_estate|developer|event_planning|party_and|professional_service|b2b|manufactur|industrial|contractor|consult|advertis|printing|logistic|wholesale|engineering|interior_design|architect|'
                      r'legal|lawyer|accountant|staffing|recruit|marketing|software|it_service|security_service|shipping|freight|packaging|trading|supplier|distribut|exporter|importer|agency')

def overture_records(region, minimum_confidence, rejects):
    import duckdb
    con = duckdb.connect(); con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';")
    release = overture.RELEASE if hasattr(overture, 'RELEASE') else '2026-10-21.0'
    area = overture.BUCKET.format(release=release, theme='divisions', kind='division_area'); places = overture.BUCKET.format(release=release, theme='places', kind='place')
    con.execute(f"CREATE TEMP TABLE boundary AS SELECT geometry AS g FROM read_parquet('{area}', hive_partitioning=1) WHERE country='IN' AND subtype='region' AND region=? AND class='land'", [region['state']])
    s, w, n, e = region['box']
    rows = con.execute(f"""SELECT id, names.primary, names.common['en'], names.common['hi'], names.common['or'], taxonomy.primary, taxonomy.hierarchy, confidence, operating_status,
        phones, websites, emails, socials, addresses[1].freeform, addresses[1].locality, addresses[1].postcode, addresses[1].region, brand.names.primary, sources[1].dataset,
        ST_Y(geometry), ST_X(geometry), ST_Within(geometry, boundary.g)
        FROM read_parquet('{places}', hive_partitioning=1), boundary WHERE bbox.xmin BETWEEN {w} AND {e} AND bbox.ymin BETWEEN {s} AND {n}""").fetchall()
    records = []
    for (oid, raw, en, hi, od, tax, hier, conf, status, phones, webs, mails, socials, addr, loc, pc, reg, brand, dataset, lat, lon, inside) in rows:
        key = 'o' + oid.replace('-', '')[:16]
        reason = None
        if status in ('permanently_closed', 'temporarily_closed'): reason = 'closed_status'
        elif conf is None or conf < (minimum_confidence if dataset == 'meta' else min(minimum_confidence, 0.4)): reason = 'low_confidence'
        else:
            name, reason = pq.clean_name(raw)
        if reason is None and not inside: reason = 'outside_state_land'
        if reason is None: reason = pq.location_problem(lat, lon, region, pc, reg, conf or 0.0)
        if reason:
            rejects.append(('overture', key, raw or '', lat, lon, reason, round(conf or 0, 2))); continue
        category = overture.category(hier, tax)
        text = '/'.join([*(hier or []), tax or ''])
        if BUSINESS.search(text) or category == 'services' and re.search(r'real_estate|event|professional|b2b|manufactur|industrial', text): category = 'business'
        fixed, changed = pq.fix_category(name, category, tax or '')
        if changed: AUDIT.append(('overture', name, category, fixed)); category = fixed
        subtype = (tax or category).replace('_', ' ')
        if category == 'worship': subtype = pq.worship_kind(name, subtype)
        alt = [x for x in dict.fromkeys(a.strip() for a in (en, hi, od) if a and a.strip() and a.strip() != name)]
        phone = (phones or [''])[0] if phones else ''
        extra = {}
        if phones and len(phones) > 1: extra['more_phones'] = '; '.join(phones[1:3])
        if socials and len(socials) > 1: extra['more_links'] = '; '.join(socials[1:3])
        records.append({'key': key, 'source': 'overture', 'name': name, 'category': category, 'subtype': subtype, 'lat': lat, 'lon': lon, 'brand': (brand or '').strip(),
            'address': compact_address(addr, loc, pc), 'phone': phone or '', 'website': bare((webs or [''])[0] if webs else ''), 'hours': '', 'wheelchair': '', 'cuisine': '',
            'email': (mails or [''])[0] if mails else '', 'photo': '', 'social': bare((socials or [''])[0] if socials else ''), 'confidence': round(conf, 2), 'postcode': pc or '', 'locality': loc or '',
            'alt': alt[:4], 'extra': extra, 'dataset': dataset})
    return records

SCHEMA = '''
CREATE TABLE pois(id TEXT PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL, subtype TEXT NOT NULL, brand TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
    address TEXT NOT NULL, phone TEXT NOT NULL, website TEXT NOT NULL, hours TEXT NOT NULL, wheelchair TEXT NOT NULL, cuisine TEXT NOT NULL,
    email TEXT NOT NULL DEFAULT '', photo TEXT NOT NULL DEFAULT '', social TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '', confidence REAL NOT NULL DEFAULT 1,
    extra TEXT NOT NULL DEFAULT '', altnames TEXT NOT NULL DEFAULT '');
CREATE VIRTUAL TABLE names USING fts4(name, brand, subtype, tokenize=unicode61, content='');
CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
'''

def write_index(target, kept, meta):
    temporary = target.with_suffix('.building'); temporary.unlink(missing_ok=True)
    db = sqlite3.connect(temporary); db.executescript(SCHEMA + 'PRAGMA user_version=2;')
    for r in kept:
        db.execute('INSERT INTO pois VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)', (r['key'], r['name'], r['category'], r['subtype'], r['brand'], r['lat'], r['lon'], r['address'], r['phone'], r['website'], r['hours'],
            r['wheelchair'], r['cuisine'], r['email'], r['photo'], r['social'], '+'.join(r.get('sources') or [r['source']]) + (':' + r['dataset'].lower() if r.get('dataset') else ''), r['confidence'], json.dumps(r['extra'], ensure_ascii=False) if r['extra'] else '', ' | '.join(r.get('alt', []))))
        db.execute('INSERT INTO names(docid,name,brand,subtype) VALUES((SELECT rowid FROM pois WHERE id=?),?,?,?)', (r['key'], r['name'] + (' ' + ' '.join(r['alt']) if r.get('alt') else ''), r['brand'], r['subtype']))
    db.execute('CREATE INDEX pois_category_latitude ON pois(category, latitude)'); db.execute('CREATE INDEX pois_latitude ON pois(latitude)')
    db.executemany('INSERT INTO metadata VALUES(?,?)', meta.items()); db.commit()
    # A contentless full-text table cannot validate itself against content it does not keep, so that one message is expected; anything else is a real problem.
    problems = [r[0] for r in db.execute('PRAGMA integrity_check').fetchall() if r[0] != 'ok' and 'inverted index for FTS4 table main.names' not in r[0]]
    if problems or db.execute("SELECT count(*) FROM names WHERE names MATCH 'a*' OR names MATCH 'bank*'").fetchone()[0] == 0: raise SystemExit(f'Integrity check failed: {problems}')
    db.execute("INSERT INTO names(names) VALUES('optimize')"); db.commit(); db.execute('VACUUM'); db.close()
    temporary.replace(target)

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--region', required=True, choices=REGIONS); parser.add_argument('--min-confidence', type=float, default=None, help='Overture/Meta places below this are left out (default: per region, 0.6 Mumbai, 0.5 Odisha)')
    parser.add_argument('--release', default='2026-10-21.0'); parser.add_argument('--no-sheets', action='store_true')
    args = parser.parse_args(); region = REGIONS[args.region]; overture.RELEASE = args.release
    if args.min_confidence is None: args.min_confidence = region['min_confidence']
    rejects = []
    print('reading OpenStreetMap ...', flush=True); osm_list = osm_records(region, rejects); print(f'  {len(osm_list)} kept, {len(rejects)} rejected', flush=True)
    before = len(rejects)
    print('reading Overture ...', flush=True); ov_list = overture_records(region, args.min_confidence, rejects); print(f'  {len(ov_list)} kept, {len(rejects) - before} rejected', flush=True)
    wrong = pq.landmark_conflicts(osm_list, ov_list)
    for r in ov_list:
        if r['key'] in wrong: rejects.append(('overture', r['key'], r['name'], r['lat'], r['lon'], 'name_of_a_landmark_mapped_elsewhere', r['confidence']))
    ov_list = [r for r in ov_list if r['key'] not in wrong]; print(f'  {len(wrong)} places repeat a landmark name far from where it is mapped', flush=True)
    piles = pq.pile_ups(ov_list + osm_list)
    for r in ov_list:
        if r['key'] in piles: rejects.append(('overture', r['key'], r['name'], r['lat'], r['lon'], 'placeholder_pin_shared_by_many', r['confidence']))
    ov_list = [r for r in ov_list if r['key'] not in piles]; print(f'  {len(piles)} places on placeholder pins dropped', flush=True)
    ordered = osm_list + sorted(ov_list, key=lambda r: -r['confidence'])
    kept, log = pq.merge_duplicates(ordered); merged = sum(log.values())
    print(f'duplicates folded: {merged} ({dict(log)}); final {len(kept)} places', flush=True)
    print(f"photos added from Wikidata: {wikidata_photos(kept)}", flush=True)
    counts = Counter(r['category'] for r in kept)
    meta = {'schema': '2', 'region': args.region, 'bbox': ','.join(map(str, region['box'])), 'source': f'OpenStreetMap (Geofabrik) + Overture Maps places {args.release}', 'min_confidence_overture_meta': str(args.min_confidence), 'license': 'OpenStreetMap: ODbL 1.0. Overture places: CDLA-Permissive-2.0 (Meta, Microsoft), Apache-2.0 (Foursquare), CC0-1.0 (AllThePlaces)',
            'attribution': '(c) OpenStreetMap contributors; Overture Maps Foundation', 'city_label': region['label'], 'retrieved_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'counts': json.dumps(dict(counts))}
    OUT.mkdir(parents=True, exist_ok=True); target = OUT / f'{args.region}_pois.sqlite'; write_index(target, kept, meta)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    reasons = Counter((src, reason) for src, _, _, _, _, reason, _ in rejects)
    report = {**meta, 'counts': dict(counts), 'places': len(kept), 'bytes': target.stat().st_size, 'index_sha256': digest, 'min_confidence_meta': args.min_confidence,
              'kept_by_source': dict(Counter('+'.join(r.get('sources') or [r['source']]) for r in kept)), 'duplicates_folded': merged, 'duplicate_pairs': dict(log),
              'rejected': {f'{s}: {why}': n for (s, why), n in sorted(reasons.items(), key=lambda x: -x[1])},
              'category_fixed_by_name': dict(Counter(f'{a} -> {b}' for _, _, a, b in AUDIT).most_common(25)), 'category_fix_examples': [list(x) for x in AUDIT[:: max(1, len(AUDIT) // 60)][:60]],
              'with_phone': sum(1 for r in kept if r['phone']), 'with_website': sum(1 for r in kept if r['website']), 'with_hours': sum(1 for r in kept if r['hours']),
              'with_email': sum(1 for r in kept if r['email']), 'with_photo': sum(1 for r in kept if r['photo']), 'with_address': sum(1 for r in kept if r['address'])}
    (OUT / f'{args.region}_pois.json').write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    if not args.no_sheets:
        folder = SHEETS / args.region; folder.mkdir(parents=True, exist_ok=True)
        with open(folder / 'places.csv', 'w', newline='') as f:
            w = csv.writer(f); w.writerow(['id', 'name', 'category', 'type', 'brand', 'latitude', 'longitude', 'address', 'phone', 'website', 'email', 'hours', 'photo', 'social', 'wheelchair', 'cuisine', 'sources', 'confidence', 'duplicates merged', 'other names'])
            for r in sorted(kept, key=lambda r: (r['category'], r['name'])): w.writerow([r['key'], r['name'], r['category'], r['subtype'], r['brand'], r['lat'], r['lon'], r['address'], r['phone'], r['website'], r['email'], r['hours'], r['photo'], r['social'], r['wheelchair'], r['cuisine'], '+'.join(r.get('sources') or [r['source']]), r['confidence'], r.get('merged', 0), ' | '.join(r.get('alt', []))])
        with open(folder / 'rejected.csv', 'w', newline='') as f:
            w = csv.writer(f); w.writerow(['source', 'id', 'name as given', 'latitude', 'longitude', 'why it was left out', 'confidence']); w.writerows(rejects)
    print(json.dumps({k: report[k] for k in ('places', 'bytes', 'kept_by_source', 'duplicates_folded', 'rejected', 'with_phone', 'with_website', 'with_hours', 'with_email', 'with_photo')}, indent=1, ensure_ascii=False))

if __name__ == '__main__': main()
