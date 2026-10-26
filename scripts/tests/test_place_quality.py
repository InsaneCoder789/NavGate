import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('pq', Path(__file__).parents[1] / 'place_quality.py')
pq = importlib.util.module_from_spec(spec); spec.loader.exec_module(pq)
MUMBAI = {'box': (18.85, 72.75, 19.35, 73.15), 'postcodes': ('40', '41', '42'), 'regions': {'mh', 'maharashtra', 'mumbai'}}


class NameTest(unittest.TestCase):
    def test_good_names_survive_and_shouting_is_toned_down(self):
        self.assertEqual(('Leopold Cafe', None), pq.clean_name('  Leopold Cafe '))
        self.assertEqual(('Desi Aahar', None), pq.clean_name('DESI AAHAR'))
        self.assertEqual(('Yes Bank', None), pq.clean_name('YES BANK'))
        self.assertEqual(('SBI ATM', None), pq.clean_name('SBI ATM'))
        self.assertEqual(('कैफे लियोपोल्ड', None), pq.clean_name('कैफे लियोपोल्ड'))
    def test_names_that_only_look_wrong_are_kept(self):
        for raw in ['Super Hotel O Kesora Square Formerly OYO', 'DealsKaro.com', 'Anand Sports.in', 'Cafe 9', 'Hotel 99', 'Cafe कलेवा', 'Sector 13, Rourkela', 'Unit 4 Market', 'Shop No Frills Mart']:
            self.assertIsNone(pq.clean_name(raw)[1], raw)
        self.assertEqual('no_name', pq.clean_name('')[1])
    def test_each_kind_of_wrong_name_has_a_reason(self):
        for raw, reason in [(None, 'no_name'), ('A', 'name_too_short'), ('12345', 'name_has_no_letters'), ('+91 98765 43210', 'name_has_no_letters'), ('www.shop.com', 'name_is_a_link_or_email'),
                            ('Kumar Medical (Permanently Closed)', 'closed_in_name'), ('Shop No 12', 'name_is_an_address'), ('Flat 4B', 'name_is_an_address'), ('Plot 45/B', 'name_is_an_address'), ('restaurant', 'generic_name'),
                            ('NULL', 'generic_name'), ('19.0760, 72.8777', 'name_has_no_letters')]:
            self.assertEqual(reason, pq.clean_name(raw)[1], raw)


class CategoryTest(unittest.TestCase):
    def test_the_name_overrules_a_wrong_source_category(self):
        self.assertEqual(('worship', True), pq.fix_category('Hazrat Bale Shah Peer Dargah', 'attraction'))
        self.assertEqual('dargah', pq.worship_kind('Hazrat Bale Shah Peer Dargah', 'christian place of worship'))
        self.assertEqual('hindu temple', pq.worship_kind('Jai Mata Di Temple', 'christian place of worship'))
        self.assertEqual('church', pq.worship_kind('St Andrews Church'))
        self.assertEqual(('food', True), pq.fix_category('Ocean Star Restro', 'lodging'))
        self.assertEqual(('pharmacy', True), pq.fix_category('Shree Medicals', 'health'))
    def test_a_name_that_merely_contains_a_word_is_left_alone(self):
        self.assertEqual(('education', False), pq.fix_category('Mandir Marg School', 'education'))
        self.assertEqual(('transit', False), pq.fix_category('Lingaraj Temple Road', 'transit'))
        self.assertEqual(('transit', False), pq.fix_category('Durga Mandir Bus Stop', 'transit'))
        self.assertEqual(('worship', True), pq.fix_category('Lord Lingaraj Temple', 'attraction'))
        for name, category in [('Durga Diagnostic', 'health'), ('Jay Durga Hydraulics', 'business'), ('Ganesh Computer', 'shopping'), ('Saraswati Shishu Vidya Mandir', 'education'), ('OYO 19323 Shiv Sai Guest House', 'lodging'), ('Ganesh Cement Products', 'business')]:
            self.assertEqual((category, False), pq.fix_category(name, category), name)
        self.assertEqual(('pharmacy', True), pq.fix_category('Mishra Medical Store', 'health'))
        self.assertEqual(('lodging', True), pq.fix_category('OYO 4685 Baikuntha Palace', 'services'))
        self.assertEqual(('food', False), pq.fix_category('Temple Road Cafe', 'food'))


class LocationTest(unittest.TestCase):
    def test_reasons(self):
        self.assertIsNone(pq.location_problem(19.07, 72.87, MUMBAI, '400053', 'MH'))
        self.assertEqual('null_island', pq.location_problem(0.0, 0.0, MUMBAI))
        self.assertEqual('outside_region', pq.location_problem(28.6, 77.2, MUMBAI))
        self.assertEqual('postcode_in_another_region', pq.location_problem(19.07, 72.87, MUMBAI, '941070', 'MH'))
        self.assertEqual('address_in_another_state', pq.location_problem(19.07, 72.87, MUMBAI, None, 'Gujarat', 0.5))
        self.assertIsNone(pq.location_problem(19.07, 72.87, MUMBAI, '400053', 'Gujarat', 0.5))      # a valid local postcode outweighs an odd state spelling
        self.assertIsNone(pq.location_problem(19.07, 72.87, MUMBAI, None, 'Gujarat', 0.9))
    def test_placeholder_pins_are_found_but_a_mall_is_not(self):
        pins = [{'key': f'p{i}', 'lat': 18.94018, 'lon': 72.83484, 'postcode': f'4000{i % 9:02d}', 'locality': 'Mumbai'} for i in range(30)]
        mall = [{'key': f'm{i}', 'lat': 19.06, 'lon': 72.86, 'postcode': '400070', 'locality': 'Kurla'} for i in range(40)]
        self.assertEqual({f'p{i}' for i in range(30)}, pq.pile_ups(pins + mall))


class DuplicateTest(unittest.TestCase):
    def rec(self, name, lat, lon, source, **kw):
        return {'key': f'{source}{name}', 'name': name, 'lat': lat, 'lon': lon, 'category': kw.pop('category', 'food'), 'source': source, **kw}
    def test_the_same_place_in_two_sources_becomes_one_with_everything_known_about_it(self):
        osm = self.rec('Leopold Cafe', 18.9229, 72.8316, 'osm', hours='Mo-Su 08:00-23:30', photo='https://x/photo.jpg')
        ov = self.rec('Leopold Café', 18.92292, 72.83163, 'overture', phone='+91 22 2282 8185', website='https://leopoldcafe.com')
        kept, log = pq.merge_duplicates([osm, ov])
        self.assertEqual(1, len(kept)); k = kept[0]
        self.assertEqual(('Leopold Cafe', 'Mo-Su 08:00-23:30', '+91 22 2282 8185', 'https://leopoldcafe.com'), (k['name'], k['hours'], k['phone'], k['website']))
        self.assertEqual({'osm+overture': 1}, dict(log))
    def test_neighbours_with_different_names_and_far_copies_stay_separate(self):
        a = self.rec('Cafe Moondust', 19.0, 72.8, 'osm'); b = self.rec('Cafe Sunrise', 19.00005, 72.80005, 'overture'); c = self.rec('Cafe Moondust', 19.01, 72.81, 'overture')
        self.assertEqual(3, len(pq.merge_duplicates([a, b, c])[0]))
    def test_a_bank_and_its_atm_are_not_the_same_place(self):
        bank = self.rec('ICICI Bank', 19.0, 72.8, 'osm', category='bank'); atm = self.rec('ICICI Bank ATM', 19.00002, 72.80002, 'overture', category='atm')
        self.assertEqual(2, len(pq.merge_duplicates([bank, atm])[0]))


class LandmarkTest(unittest.TestCase):
    def rec(self, key, name, lat, lon, category='attraction'): return {'key': key, 'name': name, 'lat': lat, 'lon': lon, 'category': category}
    def test_copies_of_a_mapped_landmark_in_the_wrong_place_are_found(self):
        osm = [self.rec('osm', 'Gateway of India', 18.9220, 72.8346)]
        others = [self.rec('near', 'Gateway of India', 18.92215, 72.83465), self.rec('far1', 'Gateway Of India', 19.0348, 72.8437), self.rec('far2', 'Gateway of India, Mumbai', 19.1, 72.9),
                  self.rec('hotel', 'Taj Hotel, Gateway of India', 19.13, 72.86, 'lodging')]
        self.assertEqual({'far1', 'far2'}, pq.landmark_conflicts(osm, others))
    def test_chains_and_common_names_are_not_landmarks(self):
        osm = [self.rec('a', 'Hindustan Petroleum', 19.0, 72.8, 'transit'), self.rec('b', 'Hindustan Petroleum', 19.1, 72.9, 'transit')]
        self.assertEqual(set(), pq.landmark_conflicts(osm, [self.rec('c', 'Hindustan Petroleum', 19.2, 72.85, 'transit')]))
        self.assertEqual(set(), pq.landmark_conflicts([self.rec('x', 'City Pizza', 19.0, 72.8, 'food')], [self.rec('y', 'City Pizza', 19.3, 72.9, 'food')]))


if __name__ == '__main__':
    unittest.main()
