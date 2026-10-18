import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('catalog', Path(__file__).parents[1] / 'build_region_catalog.py')
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class CatalogTest(unittest.TestCase):
    def test_missing_coordinates_are_preserved_as_information_only(self):
        pack, report = catalog.build_catalog([{'id': 'a', 'name': 'Place A', 'latitude': None, 'longitude': None}], 'v1', '2026-10-18')
        self.assertIsNone(pack['places'][0]['point'])
        self.assertEqual([], pack['edges'])
        self.assertEqual([], pack['entrances'])
        self.assertEqual(1, report['missingCoordinateCount'])
        self.assertFalse(report['navigationReady'])

    def test_invalid_coordinate_is_not_silently_replaced(self):
        with self.assertRaises(ValueError):
            catalog.build_catalog([{'id': 'a', 'name': 'A', 'latitude': float('nan'), 'longitude': 85}], 'v1', '2026-10-18')

    def test_duplicate_identity_is_rejected(self):
        with self.assertRaises(ValueError):
            catalog.build_catalog([{'id': 'a', 'name': 'A'}] * 2, 'v1', '2026-10-18')


if __name__ == '__main__':
    unittest.main()
