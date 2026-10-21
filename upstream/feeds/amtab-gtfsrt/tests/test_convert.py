import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import amtab_gtfsrt as m  # noqa: E402

FIX = pathlib.Path(__file__).parent / "fixtures"


class ConvertTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tu = json.loads((FIX / "trip_updates.json").read_text())
        cls.vp = json.loads((FIX / "vehicle_positions.json").read_text())

    def test_trip_updates_roundtrip(self):
        msg = m.convert_trip_updates(self.tu, self.vp)
        self.assertEqual(len(msg.entity), len(self.tu["Entities"]))
        parsed = m.rt.FeedMessage.FromString(msg.SerializeToString())
        e = parsed.entity[0].trip_update
        src = self.tu["Entities"][0]["TripUpdate"]
        self.assertEqual(e.trip.trip_id, src["Trip"]["TripId"])
        self.assertEqual(e.stop_time_update[0].stop_id, src["StopTimeUpdates"][0]["StopId"])
        self.assertEqual(e.stop_time_update[0].stop_sequence, src["StopTimeUpdates"][0]["StopSequence"])
        self.assertRegex(e.trip.start_date, r"^\d{8}$")

    def test_vehicle_positions(self):
        msg = m.convert_vehicle_positions(self.vp)
        self.assertGreater(len(msg.entity), 0)
        v = msg.entity[0].vehicle
        self.assertTrue(40.9 < v.position.latitude < 41.3)
        self.assertLess(v.position.speed, 40)  # m/s

    def test_missing_time_keeps_delay(self):
        src = {"Header": {"Timestamp": 1791320028}, "Entities": [{"Id": "x", "TripUpdate": {
            "Trip": {"TripId": "1", "RouteId": "01"},
            "StopTimeUpdates": [{"StopSequence": 3, "StopId": "s", "Arrival": {"Delay": 60}, "Departure": None}]}}]}
        msg = m.convert_trip_updates(src)
        stu = msg.entity[0].trip_update.stop_time_update[0]
        self.assertEqual(stu.arrival.delay, 60)
        self.assertFalse(stu.HasField("departure"))


class CacheTests(unittest.TestCase):
    def setUp(self):
        self.tu = json.loads((FIX / "trip_updates.json").read_text())
        self.vp = json.loads((FIX / "vehicle_positions.json").read_text())
        self.calls = []

    def fetch(self, path):
        self.calls.append(path)
        if getattr(self, "down", False):
            raise OSError("upstream down")
        return self.tu if path == "TripUpdates" else self.vp

    def test_keeps_encoded_snapshot(self):
        c = m.Cache(self.fetch).get(now=1000)
        self.assertEqual(c.trip_updates_bytes, c.trip_updates.SerializeToString())
        self.assertEqual(sorted(self.calls), ["TripUpdates", "VechiclePosition"])
        c.get(now=1005)
        self.assertEqual(len(self.calls), 2, "fresh snapshot is reused")

    def test_waits_before_retrying_a_failed_upstream(self):
        c = m.Cache(self.fetch).get(now=1000)
        good = c.trip_updates_bytes
        self.down = True
        c.get(now=1000 + m.CACHE_SECONDS + 1)
        calls = len(self.calls)
        c.get(now=1000 + m.CACHE_SECONDS + 2)
        self.assertEqual(len(self.calls), calls, "no retry inside the back-off window")
        self.assertEqual(c.trip_updates_bytes, good, "last good feed is still served")
        self.assertIsNotNone(c.error)
        self.down = False
        c.get(now=1000 + m.CACHE_SECONDS + 2 + m.Cache.RETRY_SECONDS)
        self.assertIsNone(c.error)


if __name__ == "__main__":
    unittest.main()
