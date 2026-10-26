"""Rules that decide which places are fit to ship: wrong names, closed places, wrong locations and duplicates.

Every rule returns a short machine-readable reason, so the datasheet can say exactly why each rejected place was left out.
The pipeline (build_places.py) calls these; nothing here touches the network.
"""
import difflib, math, re
from collections import Counter, defaultdict

# ---------- names -------------------------------------------------------------------------------------------------------------------------
GENERIC = {'restaurant', 'restaurants', 'hotel', 'shop', 'store', 'stores', 'clinic', 'hospital', 'bank', 'atm', 'school', 'college', 'temple', 'mandir', 'masjid', 'mosque', 'church',
           'park', 'garden', 'office', 'cafe', 'bar', 'pub', 'gym', 'salon', 'pharmacy', 'medical', 'medicals', 'building', 'house', 'home', 'society', 'complex', 'market', 'stall',
           'shop no', 'plot', 'flat', 'room', 'name', 'unnamed', 'noname', 'null', 'none', 'nil', 'na', 'n/a', 'test', 'unknown', 'untitled', 'xxx', 'abc', 'asdf', 'tbd', 'my shop', 'my home'}
# "Formerly OYO ..." is a rebrand, so the place is open and is not matched here.
CLOSED_IN_NAME = re.compile(r'(permanently|temporarily)?\s*closed(\s+down)?\b|\bshut\s*down\b|\bdefunct\b|\bout of business\b|\bnot in (use|operation)\b|\bno longer\b|\bdemolished\b|\bclosing down\b', re.I)
ADDRESS_LIKE = re.compile(r'^(shop|plot|flat|house|room|gala|office|door)\.?\s*(no\.?|number|#)?\s*[\w\-/,]+$', re.I)
# Only real links and e-mail addresses: "DealsKaro.com" and "Anand Sports.in" are business names.
URLISH = re.compile(r'https?://|www\.|@[a-z0-9]', re.I)
KEEP_UPPER = {'atm', 'iit', 'nit', 'kiit', 'kims', 'kiss', 'sbi', 'hdfc', 'icici', 'lic', 'bsnl', 'cbse', 'icse', 'mba', 'bms', 'ev', 'kfc', 'tcs', 'dmart', 'bpcl', 'hpcl', 'iocl', 'pnb', 'uco', 'rto', 'cng', 'lpg', 'ongc'}

def clean_name(raw):
    """(cleaned name or None, reason or None)."""
    if raw is None: return None, 'no_name'
    name = re.sub(r'[​-‏‪-‮﻿\x00-\x1f]', '', str(raw))
    name = re.sub(r'\s+', ' ', name).strip().strip('"\'`|;:,-_*~ ').strip()
    if not name: return None, 'no_name'
    if len(name) < 2: return None, 'name_too_short'
    if not re.search(r'[^\W\d_]', name): return None, 'name_has_no_letters'              # digits and punctuation only
    digits = sum(c.isdigit() for c in name)
    if digits >= 7 and digits / max(1, len(name.replace(' ', ''))) > 0.6: return None, 'name_is_a_phone_number'
    if URLISH.search(name): return None, 'name_is_a_link_or_email'
    if re.search(r'-?\d{1,2}\.\d{4,}\s*,\s*-?\d{2,3}\.\d{4,}', name): return None, 'name_is_coordinates'
    if CLOSED_IN_NAME.search(name): return None, 'closed_in_name'
    if ADDRESS_LIKE.match(name) and any(c.isdigit() for c in name): return None, 'name_is_an_address'
    if name.lower() in GENERIC or (name.isascii() and not any(c.isdigit() for c in name) and re.sub(r'[^a-z ]', '', name.lower()).strip() in GENERIC): return None, 'generic_name'
    letters = [c for c in name if c.isalpha()]
    if len(letters) > 5 and all(c.isupper() for c in letters if c.isascii()) and any(c.isascii() for c in letters):
        words = [w if w.lower() in KEEP_UPPER else w.capitalize() for w in name.split(' ')]
        name = ' '.join(words)
    return name, None

# ---------- category fixes by name -------------------------------------------------------------------------------------------------------
# (category, words that point to it, words that cancel the rule). Words such as "Durga", "Ganesh" or "Shiv" name shops as often as temples, so they are not used.
NAME_RULES = [
    ('worship', re.compile(r'\b(temple|mandir|mandirs|masjid|mosque|dargah|gurudwara|gurdwara|church|cathedral|basilica|synagogue|monastery|derasar)\b', re.I),
     re.compile(r'\b(school|college|hospital|clinic|bank|hotel|restaurant|restro|cafe|store|shop|sweets|medical|pharmacy|institute|stationers|general|garage|travels|tours|catering|bakery|mart|electronics|jewel|studio|'
                r'salon|parlou?r|gym|road|rd|marg|street|st|lane|chowk|square|circle|junction|crossing|stand|stop|station|colony|nagar|vihar|enclave|sector|parking|gate|bus|ghat|bridge|park|market|hall|'
                r'trust|society|samiti|vidya|vidyalaya|vidyalay|vidyapith|shishu|sishu|public school|guest ?house|lodge|hotel|oyo|computers?|tiles|cement|steels?|hydraulics|diagnostic|creations|jali|decor|'
                r'designer|furniture|gifts?|handicrafts?|stationers|prints?|printers?|arts?|industries|enterprises|traders|agency)\b', re.I)),
    ('pharmacy', re.compile(r'\b(pharmacy|chemist|medical store|medicals|drug store|medico)\b', re.I), None),
    ('fuel', re.compile(r'\b(petrol pump|petrol bunk|filling station|fuel station|cng station|hp petrol|indian oil|bharat petroleum|shell (petrol|fuel|station|pump))\b', re.I),
     re.compile(r'\b(road|rd|marg|street|lane|colony|nagar|chowk|circle|junction|society)\b', re.I)),
    ('atm', re.compile(r'\batm\b', re.I), re.compile(r'\b(bank|branch|hospital|hotel)\b', re.I)),
    ('food', re.compile(r'\b(restaurant|restro|resto|dhaba|cafe|caf[eé]|biryani|bakery|sweets?|juice|pizza|burger|momos?|tiffin|canteen|mess|tea (stall|shop)|chai|snacks?|eatery|food court|ice cream|'
                        r'bar & restaurant|thali|bhojanalay|bhojnalay|fast food|chinese|tandoor|kebab|rolls?)\b', re.I),
     re.compile(r'\b(school|college|hospital|clinic|bank|temple|mandir|masjid|church|academy|institute|hostel|pg|rooms?)\b', re.I)),
    ('lodging', re.compile(r'\b(hostel|guest ?house|lodge|resort|homestay|home stay|oyo|inn|dormitory|pg for|paying guest)\b', re.I),
     re.compile(r'\b(restaurant|restro|cafe|bar|bakery|sweets|pharmacy|temple|school|college|food|kitchen|dhaba|biryani|pizza|burger|momos?|juice|tea|coffee)\b', re.I)),
    ('education', re.compile(r'\b(school|college|university|vidyalaya|vidyalay|vidyapeeth|academy|institute|coaching|classes|tuition|tutorial|iit|nit|kiit)\b', re.I),
     re.compile(r'\b(hostel|restaurant|restro|cafe|canteen|bank|atm|hospital|temple|mandir|stationers|store|shop|bus|stop|station|parking|salon|hair|beauty|parlou?r|spa|makeup|yoga|gym|fitness|cricket|'
                r'badminton|chess|swimming|sports|football|tennis|karate|dance)\b', re.I)),
    ('health', re.compile(r'\b(hospital|nursing home|clinic|polyclinic|diagnostic|pathology|lab(oratory)?s?|dental|dentist|eye care|physio|orthopaedic|orthopedic|maternity|ayush|homeopath|ayurved|'
                          r'health ?(centre|center))\b', re.I),
     re.compile(r'\b(school|college|hotel|restaurant|restro|cafe|bank|atm|pharmacy|chemist|medical store|medicals|stationers|parking|bus|stop|hostel|law|legal|advocate|court|tax|yoga|fitness|gym|'
                r'weight|slimming|beauty|salon)\b', re.I)),
]
# Something filed as a shop, a business or a school is never moved into worship by a word in its name ("Mandir Jali" is a shop, "Vidya Mandir" a school).
COMMERCIAL = {'business', 'shopping', 'food', 'lodging', 'health', 'pharmacy', 'bank', 'atm', 'fuel', 'education', 'grocery', 'entertainment'}

def fix_category(name, category, subtype=''):
    """(category, changed): the category the name itself points to, when it clearly contradicts the source's category (a 'Medical Store' filed as a clinic, an 'OYO' filed as services)."""
    for slug, positive, negative in NAME_RULES:
        if positive.search(name) and not (negative and negative.search(name)):
            if slug == 'worship' and category in COMMERCIAL: return category, False
            return slug if slug != category else category, (slug != category)
    return category, False

WORSHIP_KINDS = [(re.compile(r'dargah|peer|baba|sufi|mazar', re.I), 'dargah'), (re.compile(r'masjid|mosque|jama|idgah', re.I), 'mosque'), (re.compile(r'church|cathedral|basilica|chapel', re.I), 'church'),
                 (re.compile(r'gurudwara|gurdwara', re.I), 'gurudwara'), (re.compile(r'jain|derasar|basadi', re.I), 'jain temple'), (re.compile(r'buddh|vihar|stupa|monastery', re.I), 'buddhist temple'),
                 (re.compile(r'temple|mandir|devi|shiv|hanuman|ganesh|krishna|ram |jagannath|lingaraj|math\b|ashram', re.I), 'hindu temple')]

def worship_kind(name, current=''):
    """The kind of place of worship the name says it is ('dargah', 'mosque', 'church' ...); sources often get this wrong, so the name wins."""
    for pattern, kind in WORSHIP_KINDS:
        if pattern.search(name): return kind
    return current

# ---------- location ------------------------------------------------------------------------------------------------------------------------
def location_problem(lat, lon, region, postcode=None, address_region=None, confidence=0.0):
    """A reason the location can't be trusted, or None. region = {'box': (s,w,n,e), 'postcodes': ('40','41',..), 'regions': {...}}"""
    if lat is None or lon is None or not (math.isfinite(lat) and math.isfinite(lon)): return 'no_coordinates'
    if abs(lat) < 0.5 and abs(lon) < 0.5: return 'null_island'
    s, w, n, e = region['box']
    if not (s <= lat <= n and w <= lon <= e): return 'outside_region'
    pc = re.sub(r'\D', '', postcode or '')
    if len(pc) == 6 and region.get('postcodes') and not pc.startswith(tuple(region['postcodes'])): return 'postcode_in_another_region'
    # A chain's feed may spell the state oddly while the point and postcode are right: only a low-confidence place with a foreign state and no local postcode is rejected.
    if address_region and region.get('regions') and confidence < 0.8 and not (len(pc) == 6):
        key = re.sub(r'[^a-z]', '', address_region.lower())
        if key and key not in region['regions'] and not any(key.startswith(r) or r.startswith(key) for r in region['regions'] if len(key) > 2): return 'address_in_another_state'
    return None

def pile_ups(records, minimum=10, spread=4):
    """Coordinates shared by many places whose addresses are spread over the city: placeholder pins, not real positions. Returns the set of record keys to reject."""
    groups = defaultdict(list)
    for r in records: groups[(round(r['lat'], 5), round(r['lon'], 5))].append(r)
    bad = set()
    for members in groups.values():
        if len(members) < minimum: continue
        places = {re.sub(r'\D', '', m.get('postcode') or '')[:6] for m in members} - {''}
        localities = {(m.get('locality') or '').lower() for m in members} - {''}
        if len(places) >= spread or len(localities) >= spread: bad.update(m['key'] for m in members)
    return bad

# ---------- duplicates -------------------------------------------------------------------------------------------------------------------
STOP = {'the', 'and', 'of', 'pvt', 'ltd', 'private', 'limited', 'co', 'company', 'shop', 'store'}

def normal(text): return re.sub(r'[^a-z0-9ऀ-ॿ଀-୿ ]+', ' ', (text or '').lower()).strip()
def tokens(text): return [t for t in normal(text).split() if t not in STOP]

def same_place_name(a, b, same_category):
    na, nb = ' '.join(tokens(a)), ' '.join(tokens(b))
    if not na or not nb: return False
    if na == nb: return True
    if not same_category: return False
    ta, tb = set(na.split()), set(nb.split())
    if len(ta & tb) / len(ta | tb) >= 0.75: return True
    if min(len(na), len(nb)) >= 6 and (na in nb or nb in na) and min(len(na), len(nb)) / max(len(na), len(nb)) >= 0.6: return True
    return difflib.SequenceMatcher(None, na, nb).ratio() >= 0.88

def metres(lat1, lon1, lat2, lon2): return math.hypot((lat1 - lat2) * 111_320, (lon1 - lon2) * 111_320 * math.cos(math.radians(lat1)))

FILL = ('phone', 'website', 'hours', 'email', 'photo', 'social', 'address', 'brand', 'cuisine', 'wheelchair')

def merge_duplicates(records, radius=60.0, cell=0.0006):
    """Folds records describing one place into one. Records come best first (OpenStreetMap, then Overture by confidence). The first keeps its name and
    position and takes any phone, website, hours, photo, e-mail ... it lacked from the later ones. Returns (kept, log) where log counts merges per pair of sources."""
    grid, kept, log = defaultdict(list), [], Counter()
    for r in records:
        cx, cy = math.floor(r['lat'] / cell), math.floor(r['lon'] / cell)
        target = None
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for k in grid.get((cx + dx, cy + dy), ()):
                    limit = radius if k['category'] == r['category'] else radius / 2
                    if metres(r['lat'], r['lon'], k['lat'], k['lon']) <= limit and same_place_name(r['name'], k['name'], k['category'] == r['category']):
                        target = k; break
                if target: break
            if target: break
        if target is None:
            grid[(cx, cy)].append(r); kept.append(r); r['merged'] = 0; continue
        for f in FILL:
            if not target.get(f) and r.get(f): target[f] = r[f]
        for key, value in (r.get('extra') or {}).items(): target.setdefault('extra', {}).setdefault(key, value)
        if r.get('alt'): target['alt'] = sorted(set(target.get('alt', [])) | set(r['alt']))
        target['merged'] += 1; target['sources'] = sorted(set(target.get('sources', [target['source']])) | {r['source']})
        log[f"{target['source']}+{r['source']}"] += 1
    return kept, log

# ---------- landmarks listed again in the wrong place -------------------------------------------------------------------------------------
LANDMARK_CATEGORIES = {'attraction', 'transit', 'park', 'worship', 'education', 'health', 'entertainment'}
CITY_WORDS = {'mumbai', 'thane', 'navi', 'bhubaneswar', 'cuttack', 'puri', 'odisha', 'orissa', 'maharashtra', 'city', 'india'}

def landmark_conflicts(trusted, others, gap=150.0):
    """Keys of [others] that repeat the name of a landmark mapped exactly once in [trusted] but sit more than [gap] metres away from it.
    A large crowd-sourced source sometimes pins a famous name ("Gateway of India") at several wrong places across the city; the one mapped by hand wins.
    A name mapped more than once (a chain, a common name) is never treated as a landmark."""
    def key(name):
        words = tokens(name)
        core = [w for w in words if w not in CITY_WORDS] or words
        return ' '.join(sorted(core))
    by_name = defaultdict(list)
    for r in trusted:
        if r['category'] in LANDMARK_CATEGORIES: by_name[key(r['name'])].append(r)
    bad = set()
    for r in others:
        anchors = by_name.get(key(r['name']))
        if not anchors: continue
        # One landmark may be mapped as a node and an outline; a name spread over the city is a chain, not a landmark.
        if any(metres(a['lat'], a['lon'], b['lat'], b['lon']) > 500 for a in anchors for b in anchors): continue
        if min(metres(r['lat'], r['lon'], a['lat'], a['lon']) for a in anchors) > gap: bad.add(r['key'])
    return bad
