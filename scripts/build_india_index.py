"""Build an atomic, Android-compatible FTS4 gazetteer from the full GeoNames IN archive.
No API key or Python dependencies. Coordinates are source WGS84 points, not entrances.
"""
import argparse
import datetime
import hashlib
import io
import json
import math
import os
from pathlib import Path
import sqlite3
import urllib.request
import zipfile

URL = 'https://download.geonames.org/export/dump/IN.zip'
LICENSE = 'https://creativecommons.org/licenses/by/4.0/'


def sha256(path):
    with open(path, 'rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def build(archive, output):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.building')
    temporary.unlink(missing_ok=True)
    db = sqlite3.connect(temporary)
    count = 0
    rejected = 0
    try:
        db.executescript('''
          CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
          CREATE TABLE places(id INTEGER PRIMARY KEY,name TEXT NOT NULL,ascii_name TEXT NOT NULL,
            aliases TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,
            feature TEXT NOT NULL,admin1 TEXT NOT NULL,admin2 TEXT NOT NULL,
            population INTEGER NOT NULL,modified TEXT NOT NULL);
          CREATE VIRTUAL TABLE names USING fts4(name,aliases,tokenize=unicode61);
          CREATE VIRTUAL TABLE coordinates USING rtree(id,min_lat,max_lat,min_lon,max_lon);
          PRAGMA user_version=1;
        ''')
        with zipfile.ZipFile(archive) as zipped:
            info = zipped.getinfo('IN.txt')
            if info.file_size > 1024 * 1024 * 1024:
                raise ValueError('Archive expansion exceeds 1 GiB')
            with zipped.open(info) as raw:
                for line in io.TextIOWrapper(raw, encoding='utf-8'):
                    fields = line.rstrip('\n').split('\t')
                    try:
                        if len(fields) != 19 or fields[8] != 'IN':
                            raise ValueError('Not an IN feature')
                        gid, lat, lon = int(fields[0]), float(fields[4]), float(fields[5])
                        if gid <= 0 or not fields[1].strip() or not all(map(math.isfinite, (lat, lon))) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
                            raise ValueError('Invalid feature')
                        population = max(0, int(fields[14] or '0'))
                        datetime.date.fromisoformat(fields[18])
                    except (ValueError, IndexError):
                        rejected += 1
                        continue
                    aliases = ','.join(dict.fromkeys([fields[2], *fields[3].split(',')]))
                    db.execute('INSERT INTO places VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                               (gid, fields[1], fields[2], aliases, lat, lon, fields[7], fields[10], fields[11], population, fields[18]))
                    db.execute('INSERT INTO names(docid,name,aliases) VALUES(?,?,?)', (gid, fields[1], aliases))
                    db.execute('INSERT INTO coordinates VALUES(?,?,?,?,?)', (gid, lat, lat, lon, lon))
                    count += 1
        if not count:
            raise ValueError('No valid India records; retaining previous index')
        metadata = {'schema': '1', 'source': URL, 'license': LICENSE,
                    'attribution': 'GeoNames, CC BY 4.0', 'country': 'IN', 'records': str(count),
                    'retrieved_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    'source_sha256': sha256(archive)}
        db.executemany('INSERT INTO metadata VALUES(?,?)', metadata.items())
        db.commit()
        if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('Index integrity failed')
        db.execute('INSERT INTO names(names) VALUES(\'optimize\')')
        db.commit()
        db.close()
        os.replace(temporary, output)
        report = {**metadata, 'rejected': rejected, 'bytes': output.stat().st_size,
                  'index_sha256': sha256(output)}
        output.with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)
        return report
    except BaseException:
        db.close()
        temporary.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, default=Path('data/india/raw/IN.zip'))
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/search/india.sqlite'))
    parser.add_argument('--download', action='store_true')
    args = parser.parse_args()
    if args.download:
        args.archive.parent.mkdir(parents=True, exist_ok=True)
        partial = args.archive.with_suffix('.part')
        print('Downloading full India GeoNames archive', flush=True)
        try:
            request = urllib.request.Request(URL, headers={'User-Agent': 'NavGate/1.0 (India offline gazetteer)'})
            with urllib.request.urlopen(request, timeout=60) as response, partial.open('wb') as target:
                size = 0
                while block := response.read(1024 * 1024):
                    size += len(block)
                    if size > 128 * 1024 * 1024:
                        raise ValueError('Download exceeds 128 MiB limit')
                    target.write(block)
                print(f'Downloaded {size} bytes; building FTS4 index', flush=True)
            os.replace(partial, args.archive)
        finally:
            partial.unlink(missing_ok=True)
    build(args.archive, args.output)

if __name__ == '__main__':
    main()
