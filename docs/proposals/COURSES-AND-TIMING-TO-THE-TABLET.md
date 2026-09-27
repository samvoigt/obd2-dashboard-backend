# Proposed contract change: courses and timing to the tablet

**Status: a proposal from the backend, for the tablet side to review.** Not
part of the contract until the tablet side answers and Sam agrees; it is
written here, in the backend's repo, and never into the app's
`docs/TELEMETRY-CONTRACT.md` from this side. If agreed, the tablet side adds it
to the contract as its next section (§22), in its own words, with any changes.

**From:** the backend, 2026-09-27. **Asked by Sam:** "ship maps and lap timing
back down to the tablet, so that the tablet can be a dumb terminal in that
sense … show the segments on the tablet side, as well as segment/lap info."

---

## Why

The backend is taking over lap timing (its plan, `docs/plans/RACE-LOGGING.md`,
milestones M12–M17):

- **Courses are drawn on a map on the website**: any course, not only the
  tracks the app ships. Each has layouts, a start/finish line, **sector
  lines**, and a pit lane. Every edit is a new version.
- **The server times laps and sector splits from the tablet's `gps.position`
  samples**, interpolating each line crossing between fixes on their `at`
  clock, as §16 describes the tablet doing today. Moving a line re-times past
  sessions.
- **Drivers are set on the website** (by the crew or the admin) per stint,
  since the car's power goes off at every driver change.

So the **results** come from the server. This proposal is how the **tablet
shows them**: the course and its sectors on its map, and the running lap,
splits and deltas, without timing anything itself. The tablet becomes the
display; the server does the work.

---

## Summary of the change

1. **Courses, down:** a new HTTPS endpoint, `GET /v1/courses`, and a live
   frame saying when they changed. The tablet caches them and draws them
   offline.
2. **Timing, down:** two new live frames, `timing` (the whole current
   state, after every `hello` and whenever it changes) and `crossing` (a
   sector or lap line just crossed).
3. **Opt-in:** the tablet announces what it understands in `hello`; the
   server sends the new frames only to a tablet that asked.
4. **Nothing existing changes.** Every addition is either a new frame type
   (which both sides already ignore when unknown, §5.2) or a new optional
   field. **The wire version stays `obd2-telemetry.v1`**, and the log format
   stays 3. (If Sam or the tablet side would rather call it v2, the content is
   the same.)

---

## 1. Opting in: `hello.features`

```json
{"t":"hello","v":3,"device":"…","app":"…","wall":1758719312000,"features":["courses.1","timing.1"]}
```

- **`features`** (new, optional): what this tablet build can use, each
  `name.version`. A server that doesn't know the field ignores it (§3.1).
- The server sends `courses` frames only to a tablet with `courses.1`, and
  `timing` and `crossing` only to one with `timing.1`. A tablet without them
  sees nothing new.
- A later incompatible change to a feature bumps its version (`timing.2`); the
  server speaks the highest both sides list.

---

## 2. Courses

### 2.1 `GET /v1/courses`

```
GET https://{host}/v1/courses
Authorization: Bearer {car token}
If-None-Match: "courses-41"
```

- **`200`** with every course, and `ETag: "courses-{n}"`, where `n` changes
  whenever any course does. **`304`** if nothing has changed.
- Any car's token may read every course: courses aren't secret (the site shows
  them), and a car may race at any of them.
- **The tablet caches the last answer** and uses it offline: a track often
  has no signal.

```json
{
  "courses": [
    {
      "id": "nhms",
      "version": 7,
      "name": "New Hampshire Motor Speedway",
      "updated": "2026-10-02T14:03:11Z",
      "geojson": { "type": "FeatureCollection", "features": [ … ] }
    }
  ]
}
```

### 2.2 The course's GeoJSON

**The app's own track format** (`nhms.geojson`), extended. Every coordinate
is `[lon, lat]`, WGS-84, as GeoJSON requires.

| `role` | Geometry | Properties | Meaning |
| --- | --- | --- | --- |
| `layout` | `LineString` | `id`, `name`, `default` | The line around, in the direction cars go. |
| `start_finish` | `LineString`, 2 points | `layout` (an `id`, or absent: every layout) | The timing line. |
| `sector` | `LineString`, 2 points | `layout`, `index` (1, 2, …) | Sector lines, in order around the lap. Sector 1 runs from the start/finish to line 1; the last sector ends at the start/finish. |
| `pit_lane` | `LineString` | `name` | The pit lane, in the direction cars go. |
| `pit_in`, `pit_out` | `LineString`, 2 points | | Where the pit lane begins and ends, for in- and out-laps (§18). |

- **Direction:** a line counts only when crossed the way the layout runs (so
  reversing over it in the pits never times a lap). The tablet needs this
  only if it ever times anything itself (see §5, question 1).
- **Unknown roles and properties are ignored**, so the server may add more.
- `attribution` (top-level, as today) says where the geometry came from.

### 2.3 Live: `courses`

```json
{"t":"courses","etag":"courses-42"}
```

- **Sent after every `hello`**, and whenever any course changes while the
  tablet is connected. If the tablet's cached `ETag` differs, it fetches
  `GET /v1/courses` again.

---

## 3. Timing

The server times on the fixes' own `at`, **the tablet's monotonic clock**. It
sends times back **on that same clock**, so the tablet runs a lap's clock
itself, exactly, without comparing wall clocks: the running time is simply
`now (at) − startAt`.

### 3.1 `timing`: the whole current state

Sent **after every `hello`** (once a session is running), and **whenever
anything in it changes** (a lap or sector completes, the driver changes, the
best improves). Like `messages` (§5.4), it is the **complete** state: the
tablet shows exactly what it says, and nothing it doesn't.

```json
{
  "t": "timing",
  "session": "5ace0000-1111-4111-8111-000000000035",
  "course": {"id": "nhms", "version": 7, "layout": "road"},
  "sectors": 3,
  "lap": {"number": 12, "startAt": 4410233, "sectors": [31.402]},
  "last": {"number": 11, "time": 94.871, "sectors": [31.512, 33.120, 30.239], "delta": 0.339},
  "best": {"number": 7, "time": 94.532, "sectors": [31.298, 32.990, 30.244]},
  "bestSectors": [31.298, 32.874, 30.101],
  "driver": {"name": "Sam", "code": "SAM", "stintStartAt": 3120500},
  "race": {"lap": 58, "sinceStopAt": 3120500}
}
```

| Field | Meaning |
| --- | --- |
| `session` | The session whose `at` the times are on. **A tablet ignores a `timing` for another session** (its `at` means nothing across app restarts). |
| `course` | Which course, version and layout the server is timing against; absent when the car isn't at a known course (then only `driver` and `race` may be present). |
| `sectors` | How many sectors the layout has. |
| `lap` | The lap under way: its number, when it began (`startAt`, on `at`), and the sector times done so far. Absent before the first crossing (an out-lap is not timed). |
| `last` | The last completed lap, its sectors, and `delta`: seconds against `best` (positive is slower). |
| `best` | The best lap on track **of this driver in this event**, or of this session if there's no event; never an in- or out-lap (§18). |
| `bestSectors` | The best of each sector, from any lap: the theoretical best is their sum. |
| `driver` | Who's driving, as the crew set it on the website, and since when (on `at`); absent if nobody's said. |
| `race` | During a race only: the lap count through the race, and when the car last left the pits (on `at`). |

Every field is optional; the tablet shows what's there.

### 3.2 `crossing`: a line just crossed

```json
{"t":"crossing","session":"5ace…0035","kind":"sector","lap":12,"sector":2,"at":4443355,"time":33.122,"delta":0.132}
{"t":"crossing","session":"5ace…0035","kind":"lap","lap":12,"at":4504765,"time":94.532,"delta":-0.339,"best":true}
```

| Field | Meaning |
| --- | --- |
| `kind` | `sector` or `lap` (the start/finish, which ends the lap and its last sector). |
| `lap`, `sector` | Which. |
| `at` | When the line was crossed, interpolated, on the tablet's `at`. |
| `time` | The sector's or the lap's time, seconds. |
| `delta` | Against the best sector or best lap (positive is slower). |
| `best` | Present and `true` when this is a new best. |
| `pitIn`, `pitOut` | As §18, on a lap that ended in, or began from, the pits. |

- **For flashing the moment**: the tablet may show a split for a few seconds,
  in its colours for faster and slower. The next `timing` carries the same
  numbers as settled state, so a missed `crossing` loses nothing but the
  flash.
- **How soon:** the server knows a line was crossed once the next fix after it
  arrives, then sends at once: at 1 fix a second, within about 1–1.5 s of the
  car crossing; much sooner with a faster receiver. The **lap clock itself is
  never late**, because it runs on the tablet from `startAt`.

### 3.3 When the link drops

- The tablet **keeps the lap clock running** from the last `startAt`: it
  needs nothing from the server to count.
- **No crossings arrive** while the link is down (the live lane never replays,
  §5.3). The tablet should say the timing is **paused**, not show a stale split
  as current.
- **On reconnect**, the `timing` after `hello` brings everything up to date,
  as far as the server knows. Laps completed in the dead zone reach the server
  through the archive lane later, and appear in the results then.

---

## 4. What the tablet shows (suggestions, the tablet side's call)

- **The map widget** draws the course from `/v1/courses`: the layout, the
  start/finish, **the sector lines**, the pit lane; the current sector
  highlighted from `timing.lap.sectors`.
- **A lap widget:** the running lap (from `startAt`), the last lap and its
  delta, the best.
- **A splits widget:** each sector of the lap under way against the best
  sector, coloured in the team's roles (faster, slower, a new best).
- **The driver and stint**, and during a race the lap count and time since the
  stop.
- **"Timing paused"** while the link is down.

---

## 5. Questions for the tablet side

1. **Offline timing.** A dumb terminal shows nothing new in a dead zone. Should
   the tablet also time laps **itself, on the server's course lines**, as a
   fallback while the link is down, marked provisional and replaced by the
   server's when it's back? That's today's §16 timing, pointed at downloaded
   courses instead of shipped ones. The backend is fine either way; it's a
   question of how dumb Sam wants the terminal. **Suggested: yes, fallback
   only.**
2. **The tablet's own `lap` records (§16).** Keep logging them (the backend
   shows them beside its own as a check while its timing is new), or stop once
   `timing.1` is on? **Suggested: keep them for now**, marked as the tablet's.
3. **Shipped tracks.** Once courses come from the server, does the app still
   ship `nhms.geojson`, as a first-run default before any download?
4. **`startAt` on `at`.** Is the bus's `at` the same clock the tablet can read
   on screen for a running timer? (§3.1 says monotonic since app start, which
   is what's wanted.) Anything that would make `at` jump within a session?
5. **Frame sizes.** `timing` and `crossing` are small; courses go over HTTPS
   because a detailed course could pass the 64 KB live frame limit. Fine?
6. **Sector count.** The server allows as many sector lines as are drawn.
   Any limit the widgets need?

---

## What the backend commits to, if agreed

- `GET /v1/courses` with `ETag` and `304`, and the `courses` frame after every
  `hello` and on any change, to tablets that list `courses.1`.
- `timing` after every `hello` (once a session is running) and on every change,
  and `crossing` on every line crossed, to tablets that list `timing.1`, with
  every time on the session's own `at`.
- **Never sending the new frames to a tablet that didn't ask**, and never
  changing an existing frame or record.
