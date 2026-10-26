#!/usr/bin/env python3
"""Adds the Overture Maps places for a state to an offline points-of-interest index (built first by build_poi_index.py from OpenStreetMap).

Overture's places theme carries about ten times as many businesses in Odisha as OpenStreetMap (restaurants, shops, clinics, coaching centres,
salons ...). Places are cut to the state's boundary (Overture's own division polygon), closed or low-confidence ones are dropped, and any place
already in OpenStreetMap (same name within 60 m) is skipped, so the two sources never list a place twice.

    python3 scripts/build_overture_places.py --state IN-OD --name odisha --release 2026-10-21.0

Data: Overture Maps Foundation places theme, sources Meta and Microsoft (CDLA-Permissive-2.0), Foursquare (Apache-2.0), AllThePlaces (CC0-1.0).
"""
import argparse, datetime, hashlib, json, math, re, sqlite3, sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / 'app/src/main/assets/search'
BUCKET = 's3://overturemaps-us-west-2/release/{release}/theme={theme}/type={kind}/*'

# Overture taxonomy -> the app's category slugs (first match wins; the taxonomy hierarchy is joined into one string).
RULES = [
    (r'pharmac|drugstore', 'pharmacy'), (r'hospital|clinic|doctor|dentist|medical_center|health_care|diagnostic|nursing|laborator|physician|surgeon', 'health'),
    (r'gas_station|fuel', 'fuel'), (r'charging_station|ev_charging', 'ev'), (r'\batm\b|atm$', 'atm'), (r'(^|\W)bank|credit_union|financial_service', 'bank'),
    (r'restaurant|cafe|coffee|bakery|fast_food|food_court|ice_cream|dessert|snack|juice|tea_room|bar$|pub$|food_and_drink|catering|sweet', 'food'),
    (r'hotel|hostel|guest_house|resort|motel|lodging|bed_and_breakfast|dormitor', 'lodging'),
    (r'supermarket|grocery|convenience|general_store|department_store|greengrocer|butcher|fish_market|dairy', 'grocery'),
    (r'shopping_center|shopping_mall|\bmall\b|marketplace|market$', 'shopping'),
    (r'school|college|university|education|academy|institute|kindergarten|training|tutor|coaching|library', 'education'),
    (r'railway|train_station|bus_station|bus_stop|airport|metro|transit|taxi|ferry|travel_and_transportation', 'transit'),
    (r'temple|mosque|church|religious|place_of_worship|gurdwara|monastery|shrine', 'worship'),
    (r'park|garden|beach|playground|nature|national_park|zoo$', 'park'), (r'gym|fitness|yoga|sports_club|stadium|swimming|sports_and_recreation|sports_center', 'fitness'),
    (r'museum|monument|historic|attraction|tourist|landmark|viewpoint|cultural_and_historic|art_gallery|heritage', 'attraction'),
    (r'cinema|theat|nightclub|arts_and_entertainment|amusement|casino|event_venue', 'entertainment'),
    (r'police|post_office|fire_station|government|courthouse|town_hall|embassy|community_and_government', 'services'),
    (r'parking', 'parking'), (r'toilet|restroom', 'toilets'),
    (r'shopping|store|boutique|clothing|jewel|electronics|furniture|hardware|mobile_phone|cosmetic|retail', 'shopping'),
    (r'salon|spa$|barber|beauty|laundry|repair|lifestyle_services|real_estate|insurance|professional|advertising|automotive|car_|bike|mechanic|services_and_business', 'services'),
]
COMPILED = [(re.compile(p), c) for p, c in RULES]

def category(hierarchy, primary):
    text = '/'.join([*(hierarchy or []), primary or ''])
    for pattern, slug in COMPILED:
        if pattern.search(text): return slug
    return 'services'

def normal(text): return re.sub(r'[^a-z0-9]+', ' ', (text or '').lower()).strip()

def metres(lat1, lon1, lat2, lon2):
    return math.hypot((lat1 - lat2) * 111_320, (lon1 - lon2) * 111_320 * math.cos(math.radians(lat1)))

def fetch(release, state, minimum_confidence):
    import duckdb
    con = duckdb.connect()
    con.execute("INSTALL httpfs; LOAD httpfs; INSTALL spatial; LOAD spatial; SET s3_region='us-west-2';")
    area = BUCKET.format(release=release, theme='divisions', kind='division_area')
    places = BUCKET.format(release=release, theme='places', kind='place')
    con.execute(f"CREATE TEMP TABLE boundary AS SELECT geometry AS g, bbox.xmin AS x0, bbox.ymin AS y0, bbox.xmax AS x1, bbox.ymax AS y1, names.primary AS name FROM read_parquet('{area}', hive_partitioning=1) WHERE country='IN' AND subtype='region' AND region=? AND class='land'", [state])
    row = con.execute('SELECT name, x0, y0, x1, y1 FROM boundary').fetchone()
    if not row: raise SystemExit(f'No division area found for {state}')
    row = (None, row[1], row[2], row[3], row[4], row[0])
    xmin, ymin, xmax, ymax = row[1:5]
    rows = con.execute(f"""SELECT id, names.primary, names.common['en'], taxonomy.primary, taxonomy.hierarchy, confidence, phones[1], websites[1], addresses[1].freeform, addresses[1].locality,
        addresses[1].postcode, brand.names.primary, sources[1].dataset, ST_Y(geometry), ST_X(geometry), operating_status
        FROM read_parquet('{places}', hive_partitioning=1), boundary
        WHERE bbox.xmin BETWEEN {xmin} AND {xmax} AND bbox.ymin BETWEEN {ymin} AND {ymax} AND ST_Within(geometry, boundary.g)
        AND confidence >= {minimum_confidence} AND coalesce(operating_status, 'open') = 'open' AND names.primary IS NOT NULL AND names.primary <> ''""").fetchall()
    return row[5], (ymin, xmin, ymax, xmax), rows

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--state', default='IN-OD'); parser.add_argument('--name', default='odisha'); parser.add_argument('--release', default='2026-10-21.0')
    parser.add_argument('--min-confidence', type=float, default=0.4); parser.add_argument('--label', default=None)
    args = parser.parse_args()
    target = OUT / f'{args.name}_pois.sqlite'
    if not target.exists(): raise SystemExit(f'Build the OpenStreetMap index first: {target}')
    state_name, bbox, rows = fetch(args.release, args.state, args.min_confidence)
    print(f'{state_name}: {len(rows)} Overture places inside the state boundary (confidence >= {args.min_confidence})', flush=True)
    db = sqlite3.connect(target)
    db.execute("DELETE FROM names WHERE docid IN (SELECT rowid FROM pois WHERE id LIKE 'o%')"); db.execute("DELETE FROM pois WHERE id LIKE 'o%'")
    # OpenStreetMap places, bucketed on a 0.001 degree grid (about 100 m), so a duplicate check only looks at 9 buckets.
    grid = {}
    for name, lat, lon in db.execute("SELECT name, latitude, longitude FROM pois WHERE id NOT LIKE 'o%'"):
        grid.setdefault((round(lat * 1000), round(lon * 1000)), []).append((normal(name), lat, lon))
    def duplicate(title, lat, lon):
        key = normal(title); cx, cy = round(lat * 1000), round(lon * 1000)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for other, la, lo in grid.get((cx + dx, cy + dy), ()):
                    if (other == key or (key and other and (key in other or other in key) and min(len(key), len(other)) >= 5)) and metres(lat, lon, la, lo) <= 60: return True
        return False
    counts, skipped, kept = {}, 0, 0
    for oid, name, english, tax, hier, conf, phone, web, addr, loc, pc, brand, dataset, lat, lon, status in rows:
        if duplicate(name, lat, lon): skipped += 1; continue
        slug = category(hier, tax)
        subtype = (tax or slug).replace('_', ' ')
        address = ', '.join(p for p in (addr, loc, pc) if p)
        title = name.strip()
        alternate = (english or '').strip()
        key = 'o' + oid.replace('-', '')[:16]
        db.execute('INSERT OR IGNORE INTO pois VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)', (key, title, slug, subtype, (brand or '').strip(), lat, lon, address, (phone or '').strip(), (web or '').strip(), '', '', ''))
        db.execute('INSERT INTO names(docid,name,brand,subtype) VALUES((SELECT rowid FROM pois WHERE id=?),?,?,?)', (key, title + (' ' + alternate if alternate and alternate != title else ''), brand or '', subtype))
        counts[slug] = counts.get(slug, 0) + 1; kept += 1
    db.commit()
    total = db.execute('SELECT count(*) FROM pois').fetchone()[0]
    meta = {r[0]: r[1] for r in db.execute('SELECT key, value FROM metadata')}
    meta['source'] = 'OpenStreetMap (Geofabrik) + Overture Maps places ' + args.release
    meta['license'] = 'OpenStreetMap: ODbL 1.0. Overture places: CDLA-Permissive-2.0 (Meta, Microsoft), Apache-2.0 (Foursquare), CC0-1.0 (AllThePlaces)'
    meta['attribution'] = '(c) OpenStreetMap contributors; Overture Maps Foundation'
    meta['overture_release'] = args.release; meta['overture_places_added'] = str(kept); meta['overture_duplicates_skipped'] = str(skipped)
    meta['bbox'] = ','.join(map(str, bbox)); meta['retrieved_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    db.executemany('INSERT OR REPLACE INTO metadata VALUES(?,?)', meta.items()); db.commit()
    db.execute("INSERT INTO names(names) VALUES('optimize')"); db.commit(); db.execute('VACUUM')
    if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok': raise SystemExit('Integrity check failed')
    all_counts = dict(db.execute('SELECT category, count(*) FROM pois GROUP BY category').fetchall())
    db.close()
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    manifest = {**meta, 'counts': all_counts, 'places': total, 'bytes': target.stat().st_size, 'index_sha256': digest}
    (OUT / f'{args.name}_pois.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Done: {total} places ({kept} from Overture, {skipped} duplicates of OpenStreetMap skipped), {target.stat().st_size // 1024} KB, sha256 {digest[:12]}')
    print(sorted(all_counts.items(), key=lambda x: -x[1]))

if __name__ == '__main__': main()
