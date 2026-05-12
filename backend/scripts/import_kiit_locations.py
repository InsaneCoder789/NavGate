from __future__ import annotations

import json
import re
import sys
import unicodedata
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

NS = {
    'a': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
    'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
}

CATEGORY_MAP = {
    'boys hostel': ('Residential', 'Boys Hostel'),
    'girls hostels': ('Residential', 'Girls Hostel'),
    'cafeteria': ('Food', 'Cafeteria'),
    'kafe': ('Food', 'Cafe'),
    'guest house': ('Hospitality', 'Guest House'),
    'kiit': ('Academic', 'KIIT'),
    'kims': ('Medical', 'KIMS'),
    'kiss': ('Academic', 'KISS'),
    'sports': ('Sports', 'Sports'),
    'commercial': ('Commercial', 'Commercial'),
    'other': ('Landmark', 'Other'),
}


def normalize_text(value: str) -> str:
    value = unicodedata.normalize('NFKC', value or '')
    value = re.sub(r'\s+', ' ', value).strip()
    return value


def slug(value: str) -> str:
    value = normalize_text(value).lower()
    value = re.sub(r'[^a-z0-9]+', '-', value).strip('-')
    return value or 'item'


def parse_maps_url(url: str):
    match = re.search(r'q=([-0-9.]+),([-0-9.]+)', url or '')
    if not match:
        return None, None
    return float(match.group(1)), float(match.group(2))


def load_all_locations(path: Path):
    with ZipFile(path) as zf:
        shared = []
        if 'xl/sharedStrings.xml' in zf.namelist():
            root = ET.fromstring(zf.read('xl/sharedStrings.xml'))
            for si in root.findall('a:si', NS):
                shared.append(''.join(t.text or '' for t in si.iterfind('.//a:t', NS)))

        workbook = ET.fromstring(zf.read('xl/workbook.xml'))
        rels = ET.fromstring(zf.read('xl/_rels/workbook.xml.rels'))
        relmap = {rel.attrib['Id']: rel.attrib['Target'] for rel in rels}
        all_locations_sheet = None
        for sheet in workbook.find('a:sheets', NS):
            if sheet.attrib['name'] == 'All_Locations':
                all_locations_sheet = sheet
                break
        if all_locations_sheet is None:
            raise SystemExit('All_Locations sheet not found')

        rel_id = all_locations_sheet.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']
        target = 'xl/' + relmap[rel_id]
        root = ET.fromstring(zf.read(target))

        rows = []
        for row in root.findall('.//a:sheetData/a:row', NS):
            row_map = {}
            for c in row.findall('a:c', NS):
                ref = c.attrib.get('r', '')
                col = re.match(r'([A-Z]+)', ref).group(1)
                cell_type = c.attrib.get('t')
                value_node = c.find('a:v', NS)
                if value_node is None:
                    value = ''
                elif cell_type == 's':
                    value = shared[int(value_node.text)]
                else:
                    value = value_node.text or ''
                row_map[col] = value
            rows.append(row_map)
        return rows


def main():
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('/Users/rohanc/Downloads/KIIT_Complete_Campus_Wise_Locations.xlsx')
    allowlist_path = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('backend/internal/campus/seed/reviewed_unassigned_allowlist.json')
    output_path = Path(sys.argv[3]) if len(sys.argv) > 3 else Path('backend/internal/campus/seed/custom_pois.json')

    rows = load_all_locations(source)
    headers = rows[0]
    if [headers.get('A'), headers.get('B'), headers.get('C'), headers.get('D')] != ['Campus', 'Category', 'Location Name', 'Google Maps URL']:
        raise SystemExit('Unexpected All_Locations headers')

    allowlist = set()
    if allowlist_path.exists():
        allow_items = json.loads(allowlist_path.read_text())
        for item in allow_items:
            allowlist.add((normalize_text(item['category']), normalize_text(item['locationName']), normalize_text(item.get('googleMapsUrl', ''))))

    pois = []
    seen_ids = set()
    for idx, row in enumerate(rows[1:], start=2):
        campus = normalize_text(row.get('A', ''))
        category_raw = normalize_text(row.get('B', ''))
        name = normalize_text(row.get('C', ''))
        maps_url = normalize_text(row.get('D', ''))
        is_unassigned = campus.lower() == 'unassigned'
        if is_unassigned and (category_raw, name, maps_url) not in allowlist:
            continue
        lat, lng = parse_maps_url(maps_url)
        norm_key = category_raw.lower()
        place_type, category_norm = CATEGORY_MAP.get(norm_key, ('Landmark', category_raw.title() or 'Other'))
        campus_number = None
        campus_label = campus
        if campus.lower().startswith('campus '):
            campus_number = int(campus.split()[-1])
        poi_id = f"{slug(campus or 'reviewed-unassigned')}-{slug(name)}"
        suffix = 2
        base_id = poi_id
        while poi_id in seen_ids:
            poi_id = f"{base_id}-{suffix}"
            suffix += 1
        seen_ids.add(poi_id)
        subtitle_parts = []
        if campus and campus.lower() != 'unassigned':
            subtitle_parts.append(campus)
        subtitle_parts.append(category_norm)
        pois.append({
            'id': poi_id,
            'name': name,
            'normalizedName': normalize_text(name).lower(),
            'subtitle': ' • '.join(subtitle_parts),
            'city': 'Bhubaneswar',
            'region': 'Odisha',
            'campusLabel': campus_label or None,
            'campusNumber': campus_number,
            'categoryRaw': category_raw,
            'categoryNormalized': category_norm,
            'placeType': place_type,
            'latitude': lat,
            'longitude': lng,
            'googleMapsUrl': maps_url or None,
            'isReviewedUnassigned': is_unassigned,
            'isActive': True,
            'searchTags': [slug(category_norm).replace('-', ' '), slug(campus_label).replace('-', ' ') if campus_label else '', 'kiit', 'bhubaneswar'],
            'sourceRow': idx,
        })

    output_path.write_text(json.dumps(pois, indent=2))
    print(f'wrote {len(pois)} POIs to {output_path}')


if __name__ == '__main__':
    main()
