import copy
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_osm_candidates import extract


def fixture(tags=None):
    return {'elements': [
        {'type': 'node', 'id': 1, 'lat': 20.35, 'lon': 85.81},
        {'type': 'node', 'id': 2, 'lat': 20.351, 'lon': 85.81},
        {'type': 'node', 'id': 3, 'lat': 20.351, 'lon': 85.810001},
        {'type': 'way', 'id': 10, 'nodes': [1, 2], 'tags': tags or {'highway': 'footway'}}]}


class CandidateTests(unittest.TestCase):
    def test_real_topology_is_not_promoted_to_reviewed_access(self):
        data, report = extract(fixture())
        self.assertEqual(2, len(data['candidateEdges']))
        self.assertFalse(report['navigationReady'])
        self.assertEqual([], data['entrances'])
        self.assertTrue(all(not e['reviewed'] and e['access'] == 'Unknown' for e in data['candidateEdges']))
        self.assertNotIn('osm-node-3', [n['id'] for n in data['nodes']])

    def test_restrictions_and_conditionals_do_not_become_walkable(self):
        for tags in [{'highway': 'footway', 'foot': 'no'}, {'highway': 'service', 'access': 'private'},
                     {'highway': 'path', 'foot:conditional': 'yes @ (Mo-Fr)'}, {'highway': 'construction'}]:
            with self.subTest(tags=tags):
                self.assertEqual([], extract(fixture(tags))[0]['candidateEdges'])
        data = fixture(); data['elements'][1]['tags'] = {'barrier': 'gate'}
        self.assertEqual([], extract(data)[0]['candidateEdges'])

    def test_foot_direction_not_motor_direction_controls_edges(self):
        data, _ = extract(fixture({'highway': 'footway', 'oneway': 'yes'}))
        self.assertEqual(2, len(data['candidateEdges']))
        data, _ = extract(fixture({'highway': 'footway', 'oneway:foot': '-1'}))
        self.assertEqual([('osm-node-2', 'osm-node-1')], [(e['from'], e['to']) for e in data['candidateEdges']])

    def test_incomplete_response_and_outside_nodes_fail_closed(self):
        data = fixture(); data['remark'] = 'runtime error'
        with self.assertRaises(ValueError): extract(data)
        data = fixture(); data['elements'][1]['lat'] = 20.5
        self.assertEqual([], extract(data)[0]['candidateEdges'])
        data = fixture(); del data['elements'][1]
        self.assertEqual([], extract(data)[0]['candidateEdges'])
