import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('builder', ROOT / 'scripts/update_catalog.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class CatalogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lines = {line['id']: line for line in json.loads((ROOT / 'app/src/main/assets/transit_catalog.json').read_text())['lines']}

    def test_gtfs_after_midnight(self):
        self.assertEqual(builder.seconds('25:12:00'), 90720)
        with self.assertRaises(ValueError): builder.seconds('12:65:00')

    def test_complete_lines_and_scoped_ids(self):
        self.assertEqual([s['id'] for s in self.lines['RENFE_C5']['stations']], builder.C5)
        self.assertEqual([s['id'] for s in self.lines['METRO_L3']['stations']], builder.L3)
        self.assertTrue(all(r.startswith('10') for r in self.lines['RENFE_C5']['routeIds']))

    def test_valid_renfe_trips_can_be_partial_and_have_both_directions(self):
        directions = set()
        for trip in self.lines['RENFE_C5']['trips']:
            self.assertTrue(trip['dates'])
            self.assertEqual(len(trip['stopIds']), len(trip['times']))
            self.assertTrue(all(a < b for a, b in zip(trip['times'], trip['times'][1:])))
            directions.add(builder.C5.index(trip['stopIds'][-1]) > builder.C5.index(trip['stopIds'][0]))
        self.assertEqual(directions, {True, False})

    def test_metro_does_not_invent_valid_trips_from_expired_source(self):
        metro = self.lines['METRO_L3']
        self.assertFalse(metro['trips'])
        self.assertIn('provisional', metro['notice'])
        self.assertEqual(metro['calendarEnd'], '20260527')

    def test_coordinates_and_durations(self):
        for line in self.lines.values():
            self.assertEqual(len(line['segmentSeconds']), len(line['stations']) - 1)
            self.assertTrue(all(30 <= seconds <= 1800 for seconds in line['segmentSeconds']))
            self.assertTrue(all(40.0 < s['lat'] < 40.9 and -4.4 < s['lon'] < -3.1 for s in line['stations']))

    def test_new_network_contains_the_line_four_and_mostoles_interchange(self):
        alfonso = next(s for s in self.lines['METRO_L4']['stations'] if s['id'] == 'par_4_66')
        self.assertEqual(alfonso['placeName'], 'Alfonso XIII')
        metro = next(s for s in self.lines['METRO_L12']['stations'] if s['id'] == 'par_4_214')
        rail = next(s for s in self.lines['RENFE_C5']['stations'] if s['id'] == '35606')
        self.assertEqual(metro['placeId'], rail['placeId'])
        self.assertEqual(rail['placeName'], 'Móstoles Central')


if __name__ == '__main__': unittest.main()
