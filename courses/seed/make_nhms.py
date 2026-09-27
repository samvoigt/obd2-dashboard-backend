#!/usr/bin/env python3
"""NHMS as a course (M12.2), from the tablet app's track file.

    python3 courses/seed/make_nhms.py [path/to/nhms.geojson]

Reads the app's `app/src/main/assets/tracks/nhms.geojson` (only read: nothing
outside this repo is written) and writes `courses/seed/nhms.geojson`:
- each layout gets an `id` from its name;
- a `pit_line` is added where the tablet makes its pit gate today (its
  `core/laps` Venues.kt, pitGate): the point on the pit lane nearest the
  middle of the start/finish, a line square to the lane there, 8 m each side;
- everything else is kept as it is, the attribution and the start/finish's
  "guess" note included.
"""
import json
import math
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    HERE, "../../../obd2-dashboard/app/src/main/assets/tracks/nhms.geojson")
PIT_GATE_HALF_WIDTH = 8.0


def slug(name):
    s = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return s.replace("road-course", "road")


class Frame:
    """A flat frame in metres around a point: plenty for a few hundred metres."""

    def __init__(self, lon, lat):
        self.lon0, self.lat0 = lon, lat
        self.kx = math.cos(math.radians(lat)) * 111_320.0
        self.ky = 110_574.0

    def xy(self, p):
        return ((p[0] - self.lon0) * self.kx, (p[1] - self.lat0) * self.ky)

    def geo(self, q):
        return [round(self.lon0 + q[0] / self.kx, 7), round(self.lat0 + q[1] / self.ky, 7)]


def at(points, d):
    """The point d metres along an open polyline, clamped."""
    for a, b in zip(points, points[1:]):
        seg = math.dist(a, b)
        if d <= seg:
            t = 0.0 if seg == 0 else d / seg
            return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
        d -= seg
    return points[-1]


def project(points, p):
    """How far along the polyline the point nearest p is."""
    best, along, run = None, 0.0, 0.0
    for a, b in zip(points, points[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        seg2 = dx * dx + dy * dy
        t = 0.0 if seg2 == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / seg2))
        q = (a[0] + dx * t, a[1] + dy * t)
        dist = math.dist(p, q)
        if best is None or dist < best:
            best, along = dist, run + math.sqrt(seg2) * t
        run += math.sqrt(seg2)
    return along


def main():
    track = json.load(open(SOURCE))
    features = track["features"]
    sf = next(f for f in features if f["properties"]["role"] == "start_finish")
    lane = next(f for f in features if f["properties"]["role"] == "pit_lane")
    (a, b) = sf["geometry"]["coordinates"]
    frame = Frame((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    pts = [frame.xy(p) for p in lane["geometry"]["coordinates"]]
    along = project(pts, (0.0, 0.0))
    centre = at(pts, along)
    ahead = (at(pts, along + 1.0)[0] - at(pts, along - 1.0)[0], at(pts, along + 1.0)[1] - at(pts, along - 1.0)[1])
    n = math.hypot(*ahead)
    across = (-ahead[1] * PIT_GATE_HALF_WIDTH / n, ahead[0] * PIT_GATE_HALF_WIDTH / n)

    out = []
    for f in features:
        f = json.loads(json.dumps(f))
        if f["properties"]["role"] == "layout":
            props = {"role": "layout", "id": slug(f["properties"]["name"])}
            props.update({k: v for k, v in f["properties"].items() if k != "role"})
            f["properties"] = props
        out.append(f)
    # The tablet's own pit gate, to the coordinate (contract §22.5 asks the website to seed NHMS's from it),
    # so neither side relies on its fallback. Ours, worked out the same way, must agree within half a metre.
    tablets = [[-71.4616871, 43.3629732], [-71.4618739, 43.3630206]]
    ours = [frame.geo((centre[0] + across[0], centre[1] + across[1])), frame.geo((centre[0] - across[0], centre[1] - across[1]))]
    worst = max(math.dist(frame.xy(t), frame.xy(o)) for t, o in zip(tablets, ours))
    assert worst < 0.5, f"our pit line is {worst:.2f} m from the tablet's"
    out.append({
        "type": "Feature",
        "properties": {
            "role": "pit_line",
            "note": "The tablet's pit gate (contract §22.5): across the pit lane, level with the start/finish, 8 m each side.",
        },
        "geometry": {"type": "LineString", "coordinates": tablets},
    })
    course = {k: v for k, v in track.items() if k != "features"}
    course["features"] = out
    path = os.path.join(HERE, "nhms.geojson")
    with open(path, "w") as f:
        json.dump(course, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"Wrote {path}: layouts {[f['properties']['id'] for f in out if f['properties']['role'] == 'layout']}, "
          f"pit line {math.hypot(across[0], across[1]) * 2:.1f} m, {math.dist(centre, (0, 0)):.1f} m from the start/finish's middle")


main()
