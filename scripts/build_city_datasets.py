import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

USER_AGENT = 'NavGate dataset builder/1.0'
DATA_DIR = Path('data')
DATA_DIR.mkdir(exist_ok=True)

MUMBAI_POPULAR = [
    'Gateway of India, Mumbai', 'Marine Drive, Mumbai', 'Chhatrapati Shivaji Maharaj Terminus, Mumbai',
    'Bandra Worli Sea Link, Mumbai', 'Juhu Beach, Mumbai', 'Siddhivinayak Temple, Mumbai',
    'Powai Lake, Mumbai', 'Bandra Fort, Mumbai', 'Nehru Planetarium, Mumbai', 'Carter Road, Mumbai',
    'Versova Beach, Mumbai', 'Bandra Kurla Complex, Mumbai', 'High Street Phoenix, Mumbai',
    'Colaba Causeway, Mumbai', 'Haji Ali Dargah, Mumbai', 'NESCO Exhibition Centre, Mumbai',
    'Phoenix Palladium, Mumbai', 'Elephanta Caves Ferry Terminal, Mumbai', 'Lokhandwala Market, Mumbai',
    'Mumbai University Kalina Campus, Mumbai'
]

BHUBANESWAR_STUDENT = [
    'KIIT Campus 6, Bhubaneswar', 'KIIT Square, Bhubaneswar', 'Patia Square, Bhubaneswar',
    'Infocity Avenue, Bhubaneswar', 'Infocity Square, Bhubaneswar', 'Nandankanan Road, Bhubaneswar',
    'DN Regalia Mall, Bhubaneswar', 'Nexus Esplanade, Bhubaneswar', 'Utkal Kanika Galleria, Bhubaneswar',
    'Master Canteen, Bhubaneswar', 'Rasulgarh Square, Bhubaneswar', 'Jaydev Vihar, Bhubaneswar',
    'Chandrasekharpur, Bhubaneswar', 'XIM University, Bhubaneswar', 'KIIMS Hospital, Bhubaneswar',
    'Kalinga Stadium, Bhubaneswar', 'BMC Bhawani Mall, Bhubaneswar', 'Forum Mart, Bhubaneswar',
    'Pathani Samanta Planetarium, Bhubaneswar', 'Ekamra Haat, Bhubaneswar'
]

OVERPASS_QUERY = '''
[out:json][timeout:60];
(
  node["amenity"~"cafe|restaurant|fast_food|bar|pub|food_court|ice_cream|cinema|college|university|library|hospital"]({south},{west},{north},{east});
  node["shop"~"mall|supermarket|clothes|coffee"]({south},{west},{north},{east});
  node["tourism"~"attraction|museum"]({south},{west},{north},{east});
  way["amenity"~"cafe|restaurant|fast_food|bar|pub|food_court|ice_cream|cinema|college|university|library|hospital"]({south},{west},{north},{east});
  way["shop"~"mall|supermarket|clothes|coffee"]({south},{west},{north},{east});
  way["tourism"~"attraction|museum"]({south},{west},{north},{east});
);
out center tags;
'''

AREAS = {
    'patia_infocity': {'south': 20.3230, 'west': 85.7870, 'north': 20.3815, 'east': 85.8455},
    'bbsr_core': {'south': 20.2550, 'west': 85.7700, 'north': 20.3300, 'east': 85.8600},
}


def fetch_json(url, method='GET', body=None):
    req = urllib.request.Request(url, data=body, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def geocode_many(names, city):
    out = []
    for name in names:
        q = urllib.parse.quote(name)
        url = f'https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q={q}'
        data = fetch_json(url)
        if data:
            item = data[0]
            out.append({
                'id': f"{city.lower()}-" + name.lower().replace(',', '').replace(' ', '-'),
                'name': name.split(',')[0],
                'subtitle': item.get('display_name', ''),
                'city': city,
                'latitude': float(item['lat']),
                'longitude': float(item['lon']),
                'source': 'nominatim'
            })
        time.sleep(1)
    return out


def fetch_overpass_hotspots():
    merged = []
    seen = set()
    for area_name, bbox in AREAS.items():
        query = OVERPASS_QUERY.format(**bbox)
        body = urllib.parse.urlencode({'data': query}).encode()
        payload = fetch_json('https://overpass-api.de/api/interpreter', method='POST', body=body)
        for el in payload.get('elements', []):
            tags = el.get('tags', {})
            name = tags.get('name')
            if not name:
                continue
            lat = el.get('lat') or el.get('center', {}).get('lat')
            lon = el.get('lon') or el.get('center', {}).get('lon')
            if lat is None or lon is None:
                continue
            key = (name, round(lat, 5), round(lon, 5))
            if key in seen:
                continue
            seen.add(key)
            merged.append({
                'id': f"bbsr-{name.lower().replace(' ', '-').replace('/', '-')[:50]}",
                'name': name,
                'subtitle': ', '.join(filter(None, [tags.get('amenity'), tags.get('shop'), tags.get('tourism'), area_name])).replace('_', ' '),
                'city': 'Bhubaneswar',
                'latitude': lat,
                'longitude': lon,
                'category': tags.get('amenity') or tags.get('shop') or tags.get('tourism') or 'hotspot',
                'source': 'overpass',
                'area': area_name,
            })
    merged.sort(key=lambda x: (x['area'], x['name']))
    return merged

if __name__ == '__main__':
    mumbai = geocode_many(MUMBAI_POPULAR, 'Mumbai')
    bbsr_curated = geocode_many(BHUBANESWAR_STUDENT, 'Bhubaneswar')
    bbsr_hotspots = fetch_overpass_hotspots()
    (DATA_DIR / 'mumbai_popular_places.json').write_text(json.dumps(mumbai, indent=2))
    (DATA_DIR / 'bhubaneswar_student_places.json').write_text(json.dumps(bbsr_curated, indent=2))
    (DATA_DIR / 'bhubaneswar_student_hotspots.json').write_text(json.dumps(bbsr_hotspots, indent=2))
    print('mumbai', len(mumbai))
    print('bhubaneswar_curated', len(bbsr_curated))
    print('bhubaneswar_hotspots', len(bbsr_hotspots))
