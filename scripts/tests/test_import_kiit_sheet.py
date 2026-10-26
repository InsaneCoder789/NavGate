import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('kiit_sheet', Path(__file__).parents[1] / 'import_kiit_sheet.py')
kiit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kiit)


class ImportKiitSheetTest(unittest.TestCase):
    def test_coordinates_come_from_the_link_in_either_cell(self):
        self.assertEqual((20.346226, 85.823535), kiit.coordinates('https://maps.google.com/?q=20.346226,85.823535'))
        self.assertEqual((20.35, 85.82), kiit.coordinates('https://maps.google.com/maps/@20.35,85.82,17z'))
        self.assertEqual((None, None), kiit.coordinates(''))
        self.assertEqual((None, None), kiit.coordinates('Adidas store'))

    def test_names_lose_their_links_but_keep_everything_else(self):
        self.assertEqual('Admission Office', kiit.clean_name('Admission Office https://maps.google.com/?q=20.346226,85.823535'))
        self.assertEqual('KP-7 Sports Ground (Futsal & Tennis)', kiit.clean_name('KP-7 Sports Ground (Futsal & Tennis)'))
        self.assertEqual('', kiit.clean_name('https://maps.google.com/?q=20.3,85.8'))

    def test_the_generated_directory_keeps_every_row_and_stores_no_map_links(self):
        import json
        data = json.loads((Path(__file__).parents[2] / 'backend/internal/campus/seed/custom_pois.json').read_text())
        self.assertGreaterEqual(len(data), 300)
        titles = [p['name'].lower() for p in data]; self.assertEqual(len(titles), len(set(titles)), 'a place listed twice')
        self.assertEqual(len({p['id'] for p in data}), len(data))
        self.assertFalse([p for p in data if 'http' in json.dumps(p) and 'google' in json.dumps(p).lower()])
        self.assertTrue(any(p['campusLabel'] is None for p in data), 'unassigned places must be kept')
        for p in data:
            if p['latitude'] is not None:
                self.assertTrue(17.7 <= p['latitude'] <= 22.6 and 81.3 <= p['longitude'] <= 87.6, p['name'])        # inside Odisha
                self.assertEqual(kiit.city_of(p['latitude'], p['longitude']), p['city'], p['name'])
        self.assertEqual('Puri', next(p for p in data if p['name'] == 'Puri Guest house')['city'])
        self.assertTrue(sum(1 for p in data if p['latitude'] is not None) >= 240)


if __name__ == '__main__':
    unittest.main()
