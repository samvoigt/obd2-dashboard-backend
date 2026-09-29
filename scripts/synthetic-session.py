#!/usr/bin/env python3
"""
A synthetic session at the tablet's real rate (M19.1), for measuring long
sessions: any length, the record mix and rates of the drive of 2026-09-28
(37.4 lines a second: accelerations at ~10 Hz, engine speed and road speed at
~5.5 Hz, GPS at ~0.9 Hz, diagnostics every ~4 s), driven round a 1000 x 400 m
box near NHMS at 40 m/s (a 70 s lap), with the tablet's own `lap` records.

Nothing here is any car's: the signals are the contract's generic names, the
values invented.

    scripts/synthetic-session.py --hours 8 --id 5ace0000-...-0001 --out long.jsonl [--course box.geojson]
"""
import argparse
import json
import math
import time
import uuid

LON0, LAT0 = -71.4611, 43.3626
PER_LON = 111_195 * math.cos(math.radians(LAT0))
SPEED = 40.0  # m/s
LAP_M = 2800.0

SIGNALS = [
    ("diagnostics.engine_type", "", "state"), ("diagnostics.mil", "", "flag"),
    ("diagnostics.monitors_available", "", "flags"), ("diagnostics.monitors_complete", "", "flags"),
    ("diagnostics.trouble_code_count", "", "number"), ("engine.coolant_temperature", "°C", "number"),
    ("engine.rpm", "rpm", "number"), ("gps.accuracy", "m", "number"), ("gps.altitude", "m", "number"),
    ("gps.heading", "°", "number"), ("gps.position", "", "position"), ("gps.satellites", "", "number"),
    ("gps.speed", "km/h", "number"), ("motion.acceleration.lateral", "m/s²", "number"),
    ("motion.acceleration.longitudinal", "m/s²", "number"), ("vehicle.speed", "km/h", "number"),
    ("vehicle.system_voltage", "V", "number"),
]
# Milliseconds between readings, as measured.
EVERY = {
    "motion.acceleration.lateral": 102, "motion.acceleration.longitudinal": 102,
    "engine.rpm": 183, "vehicle.speed": 183,
    "gps.position": 1124, "gps.speed": 1124, "gps.altitude": 1124, "gps.accuracy": 1124, "gps.satellites": 1124, "gps.heading": 1408,
    "diagnostics.mil": 4167, "diagnostics.trouble_code_count": 4167, "diagnostics.engine_type": 4167,
    "diagnostics.monitors_available": 4167, "diagnostics.monitors_complete": 4167, "engine.coolant_temperature": 4167,
    "vehicle.system_voltage": 5000,
}
MONITORS = ["Catalyst", "Components", "EGR and/or VVT System", "Evaporative System", "Fuel System", "Misfire", "Oxygen Sensor"]


def place(d):
    """Where the car is d metres round the box from its bottom-left corner."""
    d %= LAP_M
    if d < 1000: return d, 0.0
    if d < 1400: return 1000.0, d - 1000
    if d < 2400: return 1000 - (d - 1400), 400.0
    return 0.0, 400 - (d - 2400)


def lonlat(x, y):
    return LON0 + x / PER_LON, LAT0 + y / 111_195


def course(path):
    def feat(props, *pts):
        return {"type": "Feature", "properties": props, "geometry": {"type": "LineString", "coordinates": [list(lonlat(x, y)) for x, y in pts]}}
    fc = {"type": "FeatureCollection", "features": [
        feat({"role": "layout", "id": "box", "name": "Box", "default": True}, (0, 0), (1000, 0), (1000, 400), (0, 400), (0, 0)),
        feat({"role": "start_finish"}, (500, -15), (500, 15)),
        feat({"role": "sector", "layout": "box", "index": 1}, (985, 200), (1015, 200)),
        feat({"role": "sector", "layout": "box", "index": 2}, (500, 415), (500, 385)),
        feat({"role": "sector", "layout": "box", "index": 3}, (15, 200), (-15, 200)),
    ]}
    with open(path, "w") as f:
        json.dump(fc, f)


def main():
    a = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    a.add_argument("--hours", type=float, required=True)
    a.add_argument("--id", default=str(uuid.uuid4()))
    a.add_argument("--out", required=True)
    a.add_argument("--course", help="also write the box's GeoJSON here")
    a.add_argument("--course-id", default="box", help="the course id the lap records name")
    a.add_argument("--wall", type=int, default=int(time.time() * 1000), help="the tablet's wall at the start, epoch ms")
    args = a.parse_args()
    if args.course:
        course(args.course)

    end = int(args.hours * 3_600_000)
    at0 = 40_000_000  # the app's uptime at the start, as a real run's
    nxt = {s: 0 for s in EVERY}
    seq = 0
    lap_n = 0
    # The start/finish is 500 m round; the first crossing at 12.5 s, then every 70 s.
    lap_ms = int(LAP_M / SPEED * 1000)
    first_cross = int(500 / SPEED * 1000)
    next_lap = first_cross + lap_ms
    with open(args.out, "w") as out:
        def w(rec, t):
            nonlocal seq
            rec["seq"] = seq
            rec["at"] = at0 + t
            rec["wall"] = args.wall + t
            out.write(json.dumps(rec, separators=(",", ":"), ensure_ascii=False) + "\n")
            seq += 1

        w({"type": "session", "v": 3, "id": args.id, "device": "synthetic-long", "app": "synthetic",
           "started": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(args.wall / 1000)),
           "signals": [{"name": n, "unit": u, "kind": k} for n, u, k in SIGNALS]}, 0)
        t = 0
        while t <= end:
            # Everything due at or before t, in the order it falls due.
            due = sorted((nxt[s], s) for s in nxt)
            t = due[0][0]
            if t > end:
                break
            if next_lap <= t:
                start = next_lap - lap_ms
                lap_n += 1
                w({"type": "lap", "course": args.course_id, "courseVersion": 1, "layout": "box", "lap": lap_n,
                   "time": lap_ms / 1000, "sectors": [17.5, 17.5, 17.5, 17.5],
                   "startAt": at0 + start, "endAt": at0 + next_lap}, next_lap)
                next_lap += lap_ms
                continue
            for due_t, s in due:
                if due_t != t:
                    break
                nxt[s] = t + EVERY[s]
                d = SPEED * t / 1000
                if s == "gps.position":
                    lon, lat = lonlat(*place(d))
                    w({"type": "sample", "signal": s, "lat": round(lat, 8), "lon": round(lon, 8), "fixAt": at0 + t - 40}, t)
                elif s.startswith("motion.acceleration"):
                    w({"type": "sample", "signal": s, "value": round(3 * math.sin(t / 3000.0 + (1 if s.endswith("lateral") else 0)), 3)}, t)
                elif s in ("engine.rpm",):
                    w({"type": "sample", "signal": s, "value": round(4500 + 2500 * math.sin(t / 7000.0), 0)}, t)
                elif s in ("vehicle.speed", "gps.speed"):
                    w({"type": "sample", "signal": s, "value": round(SPEED * 3.6 + 10 * math.sin(t / 9000.0), 1)}, t)
                elif s == "diagnostics.mil":
                    w({"type": "sample", "signal": s, "flag": False}, t)
                elif s == "diagnostics.engine_type":
                    w({"type": "sample", "signal": s, "text": "Spark ignition"}, t)
                elif s.startswith("diagnostics.monitors"):
                    w({"type": "sample", "signal": s, "flags": MONITORS}, t)
                elif s == "diagnostics.trouble_code_count":
                    w({"type": "sample", "signal": s, "value": 0}, t)
                elif s == "engine.coolant_temperature":
                    w({"type": "sample", "signal": s, "value": 92}, t)
                elif s == "vehicle.system_voltage":
                    w({"type": "sample", "signal": s, "value": 14.1}, t)
                elif s == "gps.altitude":
                    w({"type": "sample", "signal": s, "value": 120.0}, t)
                elif s == "gps.accuracy":
                    w({"type": "sample", "signal": s, "value": 3.9}, t)
                elif s == "gps.satellites":
                    w({"type": "sample", "signal": s, "value": 11}, t)
                elif s == "gps.heading":
                    w({"type": "sample", "signal": s, "value": round((d % LAP_M) / LAP_M * 360, 1)}, t)
    print(f"{args.out}: session {args.id}, {seq} lines, {args.hours} h, {lap_n} laps")


if __name__ == "__main__":
    main()
