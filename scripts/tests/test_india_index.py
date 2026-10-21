import importlib.util
from pathlib import Path
import sqlite3
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('india', Path(__file__).parents[1] / 'build_india_index.py')
india = importlib.util.module_from_spec(spec)
spec.loader.exec_module(india)

class IndiaIndexTest(unittest.TestCase):
    def test_alias_coordinates_and_atomic_rejection(self):
        with tempfile.TemporaryDirectory() as folder:
            archive, output = Path(folder)/'IN.zip', Path(folder)/'india.sqlite'
            fields = ['1275339','Mumbai','Mumbai','Bombay,मुंबई','19.07283','72.88261','P','PPLA','IN','','16','','','','12691836','','','Asia/Kolkata','2026-01-01']
            with zipfile.ZipFile(archive,'w') as z:
                z.writestr('IN.txt', '\t'.join(fields)+'\n'+'\t'.join(fields[:4]+['nan']+fields[5:])+'\n')
            report = india.build(archive,output)
            self.assertEqual('1', report['records'])
            self.assertEqual(1,report['rejected'])
            with sqlite3.connect(output) as db:
                for query in ['Bombay*','मुंबई*','Mumbai*']:
                    self.assertEqual([(1275339,)],db.execute('SELECT docid FROM names WHERE names MATCH ?', (query,)).fetchall())
                self.assertEqual((19.07283,72.88261),db.execute('SELECT latitude,longitude FROM places').fetchone())
                self.assertEqual(1,db.execute('SELECT count(*) FROM coordinates WHERE min_lat<20 AND max_lat>19').fetchone()[0])
            before = output.read_bytes()
            with zipfile.ZipFile(archive,'w') as z:
                z.writestr('IN.txt','malformed\n')
            with self.assertRaises(ValueError): india.build(archive,output)
            self.assertEqual(before,output.read_bytes())

if __name__ == '__main__': unittest.main()
