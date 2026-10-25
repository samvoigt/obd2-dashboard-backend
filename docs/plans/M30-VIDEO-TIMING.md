# M30 — A session's GPS on the receiver's time, its measured latency, and its videos

Three changes so a session's data lines up with an in-car video, found while
putting the NHMS race weekend's telemetry over GoPro footage of Race
Session 2 (2026-10-18):
1. A fix's speed, heading and other readings are placed when the receiver
   took it, as its position already is (M28).
2. A session can carry its GPS receiver's measured latency, which moves
   where its positions, readings, laps and pit crossings go on its page.
   It never changes a lap's time.
3. A session can list its videos, each placed on the tablet's `wall`, so a
   tool or page can find the moment of a video that goes with a lap, and
   the other way round.

> **Proposed in a pull request from Hugh (henxing), 2026-10-20**: built and
> tested here. **Accepted by Sam, 2026-10-24** ("Merge now, ship it with
> M30"), so "Decided here" stands as decision 47; merged with the UX work
> (renumbered M31–M40 around it) and deployed with M31. The questions under
> "For Sam" are still open for later milestones.

---

## What exists, and what it means for this (checked 2026-10-20)

- **`SeriesBuilder` places positions on `wall − (at − fixAt)`** (M28,
  `FixFilter.placed`), and every other sample on its `wall`.
  - In the NHMS log, a fix's `gps.speed`, `gps.heading`, `gps.altitude` and
    `gps.satellites` follow its `gps.position` with the same `at`, in the
    same burst.
  - So the series still shows those readings bunched by Bluetooth: the
    positions moved in M28, their readings didn't.
- **`ThinSeries` keeps one position a second** and hands each 5 s bucket's
  samples on when the bucket closes. By then newer fixes have arrived. So
  the builder can't place a reading by "the last fix seen". It has to
  remember recent fixes by `at`, including the ones thinning left out.
- **Laps are placed on `wall` by `RunTiming.wallOffsets`** (`Retiming.retime`).
  Everything that puts a lap or a pit crossing on a page reads that map:
  `Race.lapsOf`, `Race.crossingsOf`, `Standings.wall`, `standingLaps`.
  - A latency taken out there moves all of them together.
  - The laps themselves, and their check against the tablet's
    (`AGREE_MS`), are timed on `fixAt` before that map is used, so neither
    changes (decision 33).
- **`Retimer` stores a run's timing beside its first session** and reads it
  back while the run is unchanged. Rebuilding has to happen when a latency
  changes. `Retimer.timing` already reads each session's record when it
  builds, so it can compare the stored timing's latencies with the records'.
- **`CarTimings` holds a car's complete runs in memory** while it is live,
  keyed by course, version and sessions. A latency belongs in that key too.
- **The series is stored under a versioned key** (`seriesKey`,
  `thinSeriesKey`), and `prepare` deletes every other series file of the
  session. A latency in the key builds it again, and the old one goes.
- **`SessionRecord` already carries what people set on a session**: its
  name, driver and driver changes. Each has a `set…` on both indexes and
  two routes in `DriverRoutes.kt`, one for the admin and one for the crew,
  which call `onChanged` so held results are emptied. The new fields follow
  that pattern exactly.
- **Live is untouched.** `Provisional` re-times live sessions, and
  `LiveTimings.laps` and `LiveSeries` serve them, all as heard
  (decision 45).

## Decided here (proposed)

- **A latency is a measurement someone sets, never a guess the server
  makes.** It's milliseconds from 1 to 5,000, and null (or 0) clears it.
  - It is set per session, since a different receiver or mount is a
    different session.
  - The server still knows nothing about the car: the value is about the
    session's GPS, set by its crew or an editor. It isn't a vehicle fact in
    code or types (the rule in `CLAUDE.md`).
- **What a latency moves:** a complete session's positions and `gps.*`
  readings in its series, its laps and its pit crossings, all by the same
  amount, earlier.
  - It never moves a lap's time, its sectors or the tablet check.
  - It never moves anything live.
- **A video** is `{ file, startWall, durationMs }`. `file` is just a name,
  wherever the file is kept. `startWall` is the tablet's `wall` at its
  first frame.
  - A camera's chapters are a video each, so the camera clock's drift never
    adds up over more than about 20 minutes.
  - Up to 200 videos per session. The whole list is set at once, sorted by
    start.
- **Not in this milestone:** anything on the website to set or show either
  value, and any change to the contract or the tablet.

## The steps

### M30.1 — A fix's readings with its position ✅

`SeriesBuilder`:
- `fix(record)` notes each `gps.position`'s `at` and `at − fixAt` (the last
  64, about five seconds at 10 Hz).
- A sample whose signal starts `gps.` and whose `at` is a noted fix's is
  placed `at − fixAt` earlier. Anything else is placed as before.
- `ThinSeries` calls `fix` on every sample, so a thinned-out position still
  places its readings.
- Series version 4, so every stored series is built again.

Tests:
- `SeriesBuilderTest`: two fixes heard together, their speed and heading
  placed when taken; `engine.rpm` and a reading with no fix as heard.
- `ThinSeriesTest`: a bucket's minimum and maximum speed placed by fixes
  thinned out and since passed.

### M30.2 — A session's measured GPS latency ✅

- `SessionRecord.gpsLatencyMs`, set by `setGpsLatency` on both indexes
  (Firestore `gpsLatencyMs`).
- `PUT /api/admin/sessions/{id}/gps-latency` and
  `PUT /api/cars/{slug}/sessions/{id}/gps-latency`, body
  `{"latencyMs": 650}`, logged as the name is. Returned on
  `/api/sessions/{id}`.
- `SeriesBuilder(gpsLatencyMs)` places positions and `gps.*` readings that
  much earlier. `seriesKey` and `thinSeriesKey` gain `-gps{ms}` when it's
  set.
- `Retiming.retime(course, run, gpsLatencies)` takes each latency out of
  `wallOffsets` and records them in `RunTiming.gpsLatencies`.
- `Retimer.timing` builds again when the records' latencies differ from the
  stored ones. `CarTimings` keys its held runs on them.

Tests:
- `SeriesBuilderTest`: positions and `gps.speed` 400 ms earlier, `engine.rpm`
  not.
- `ArchiveServiceTest`: the series key with the latency, the old series
  deleted, and back when it's cleared.
- `RetimingTest`: offsets moved, laps and pit crossings identical.
- `RetimerTest`: rebuilt when set, read back while unchanged, rebuilt when
  cleared.
- `DriverRoutesTest`: crew and admin set it; 0 clears it; over 5,000,
  negative, signed out or another car's crew is refused.

### M30.3 — A session's videos ✅

- `SessionRecord.videos` (`SessionVideo`), set by `setVideos` on both
  indexes (Firestore `videos`, a list of maps, as driver changes are).
- `PUT /api/admin/sessions/{id}/videos` and
  `PUT /api/cars/{slug}/sessions/{id}/videos`, body
  `{"videos": [{"file", "startWall", "durationMs"}]}`. They are trimmed,
  sorted and checked by `sessionVideos`, and returned on
  `/api/sessions/{id}`.

Tests (`DriverRoutesTest`): two chapters set out of order are returned in
order and trimmed; an empty file, two lines, no start, no length or over a
day is refused; signed out is refused; an empty list clears them.

## For Sam

1. **Is a measured latency worth carrying at all?** Lap times don't need it.
   Only placing laps against something else does (a video, another car's
   timing loop).
   - The alternative is to keep it in the tool that needs it, as the
     overlay does now.
   - The race's transponder timing agrees with it (JOURNAL 2026-10-20,
     M30): official crossings come a median 0.55 s before ours.
   - M30.1 stands on its own either way.
2. **Should a latency go on the car's page too**, and should the car page be
   able to set it? This milestone only adds the API.
3. **Videos:** is a file name enough, or should a video be a link (YouTube,
   Drive) the session's page could open at a lap?
