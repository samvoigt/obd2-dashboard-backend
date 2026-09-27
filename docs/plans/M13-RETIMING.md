# M13 — Re-timing

The second milestone of race logging (`RACE-LOGGING.md`): **the server times
laps and sectors from the tablet's own fixes, by the tablet's own rule**, where
the tablet's laps aren't current (contract §22.1, decision 31):

- **after a line moves on the website**: laps the tablet timed on an older
  version of a course, re-timed on the new one;
- **sessions with no `lap` records**: logs from before courses came from the
  website, or a tablet that didn't have the course;
- and, where both exist on the same version, **a check** that the two agree,
  **flagging** a lap that doesn't, never replacing the tablet's number.

The tablet's `lap` records stay the results wherever they're current. Nothing
here goes to the tablet (that's `timing`, M17), and nothing changes the
contract.

---

## What exists, and what it means (checked 2026-09-27)

- **The tablet's rule** (the app's `core/laps`, `LapTimer`, read only; contract
  §16, §22.5, §22.6):
  - fixes in a flat local frame (metres east and north); **each move from one
    fix to the next** is tested against every line; a crossing counts **only
    in the line's direction** (the sign of the move's cross product with the
    line, against the layout's own at that line), and its moment is
    **interpolated along the move** (`t` between 0 and 1, touching an end
    counts); several crossings in one move count **in order of `t`**;
  - **the first start/finish crossing starts timing**; each later one ends a
    lap, but only once **armed**: the car must have been
    `min(150 m, the layout's length / 4)` from the start/finish's middle since
    the last crossing (a car sat on the line makes no laps);
  - **the pit line** (the course's `pit_line`, else one made across the pit
    lane level with the start/finish, 8 m each side, only with a pit lane)
    ends a lap as an **in-lap**, and the next starts as an **out-lap**; it
    counts in the pit lane's direction;
  - **sectors only if every sector line crosses the layout exactly once**;
    **only the next sector line counts**, a missed one stops that lap's sectors
    there; the last sector ends at the start/finish (or the pit line) and is
    recorded only if every line before it was crossed;
  - **a start/finish that misses the layout times nothing**, never an error.
  - **Where a crossing falls along a move doesn't depend on the projection**
    (an affine map keeps it), so the server's frame needn't be the tablet's to
    get the same moment; the arming distance moves by millimetres at most.
- **Fix times:** `gps.position` carries `fixAt` (the receiver's time on
  `at`'s clock, whole milliseconds; §22.3), else only `at`. The tablet times
  on the receiver's nanoseconds, so the two sides can differ by **about a
  millisecond** of rounding: the agreement check allows 2 ms.
- **The tablet's laps** carry `course`, `courseVersion`, `layout` (an `id`),
  `sectors`, `startAt` and `endAt` (§22.6), so the check compares crossings
  directly, never lap numbers.
- **The prepared series has positions on `wall` only**, not `fixAt`, so
  re-timing reads the **session's log** itself, streamed a line at a time, as
  the summary is built (`SessionReader`).
- **Across sessions** (§22.8): the tablet's timing carries across the
  sessions of **one run of the app** (a lap under way when the OBD link drops
  is finished in the next session). A run is the car's sessions from one
  `device`, back to back, each session record's `at` above the last. **The
  summary doesn't keep `at` or the device** today, so it gains them (and the
  sessions' position bounds, to find which sessions a course touches).
- **Courses** are versioned (decision 32): a save makes version N+1 and keeps
  the old ones, so every lap names what it was timed on.
- **The session page** shows the tablet's laps from the series (M12.7); it
  will show the laps as they stand (the tablet's where current, re-timed where
  not), marked.

---

## Decided here (say if any is wrong)

- **Re-timing is per run**, not per session, so a lap across an OBD drop is
  timed as the tablet timed it. Runs are found from the summary (device, `at`
  rising, sessions back to back); a run is at most one day of one car.
- **The laps that stand**, per lap: the tablet's, if timed on the course's
  current version; else the server's re-timing on the current version. Both
  kept; the page shows the one that stands and says which.
- **Agreement:** a lap both sides timed on the same version agrees when its
  start and end crossings are within **2 ms**; otherwise it's **flagged** on the
  session page and in the service's log (a bug for one side), and the tablet's
  number still stands.
- **When:** a session is re-timed when it's prepared (after `complete`, as
  its series), and again for every session at a course whenever that course is
  saved. A course save starts it in the background; the admin page says how
  many sessions were re-timed.
- **Stored** as a derived file per run and course version,
  `sessions/{first session}/timing-v{rule version}-{course}-{course version}.json.gz`,
  rebuilt when the rule or the course version changes, as the series is.

---

## The steps

Each validated against the code just before it's built, the validation
written here.

### M13.1 — The rule (`:timing`)

A pure module: the tablet's rule as above, from fixes (point and time) and a
course layout (from `:courses`' `CourseShape`, with the pit-line fallback),
to laps (start, end, time, in/out, sectors). No Google, all tests.

**Done when:** tests mirroring the app's own (`LapTimerTest`,
`SectorsTest`): true lap times at 1 Hz and 10 Hz and at a varying pace; a car
sat on the line makes no laps; a lap counts once it has left the line; driven
the wrong way nothing is timed; fixes dropped across the line still make the
lap; the 150 m (or a quarter) arming; sectors to their true times and adding
up to the lap; a move across a sector line and the start/finish counting
both; a missed sector line stopping that lap's sectors; a sector line that
misses the layout leaving it without sectors; the in-lap ending at the pit
line, with the fallback pit line when the course has none. And **the same
moments whatever the frame's origin**.

> **Validated against the code, 2026-09-27, before building.**
> - **The tablet's code, line by line** (read only): `LapTimer.offer` (each
>   move, every line's crossing in order of `t`, the direction sign against the
>   line's own at the layout, the arming), `passSector` (only the next),
>   `endLap` (the last sector only after all the others), `forwardSign`,
>   `crossing` (touching an end counts), `Polyline.crossings` (a sector line
>   must cut the layout's segments exactly once), `Track.startFinish(layout)`
>   (its own, else the shared one), `Track.pitGate(layout)` (the fallback:
>   the start/finish's middle projected onto the **open** pit lane, square to
>   it at ±1 m along, 8 m each side), `LocalFrame` (equirectangular about the
>   **default layout's first point**, 111,195 m a degree).
> - **In `:courses`' terms:** `CourseShape` has each `Layout`'s `path`,
>   `startFinish` and ordered `sectors`, and the course's `pitLane`,
>   `pitLine`; so `:timing` depends only on `:courses`.
> - **Times are milliseconds as doubles** on `at`'s clock (`fixAt` whole
>   milliseconds, interpolated between), laps and sectors in seconds as the
>   `lap` record has them.
> - **Exactness:** on a straight at a steady speed, interpolating along the
>   move is exact, so those tests expect the true moment to a microsecond; on a
>   circle the chord cuts corners, so those expect the time within what a
>   chord's error can be at that rate.

> **✅ Done, 2026-09-27.** `:timing`'s `LapRule` (and `Fix`, `TimedLap`),
> the tablet's rule line by line, on `:courses`' `CourseShape`.
> - **Tests: 14**, on a 1000 × 400 m box with every line mid-straight (so
>   true moments are exact): laps at 1 Hz to a microsecond; sectors to their
>   true times and adding up; 10 Hz at a varying pace within 0.2 ms; a car sat
>   on the line; arming at 150 m or a quarter of a short layout; the wrong way;
>   fixes lost across the line; a sector line and the start/finish in one
>   move; a missed sector line; a sector line off the track or cutting it
>   twice; a start/finish missing the layout; the in-lap ending at the pit
>   line and the out-lap starting there; the made pit line matching a drawn
>   one; the same moments whatever the frame's origin.
> - **Mutations: 9, all killed**, two only after tests were added: a sector
>   line cutting the track twice (only one missing it had been tested) and the
>   fallback pit line's width (the test car drove the pit lane's exact line;
>   it now runs 5 m off it, as a GPS trace does).
> - **Found by the tests:** my own first pit test sent the car into the pits
>   on its first lap, which the rule rightly timed as an in-lap.


### M13.2 — A session's fixes and laps, and runs

A reader streaming a session's log for its `gps.position` fixes (`fixAt`,
else `at`) and its `lap` records; the summary (version 3) gains the device,
the first and last `at`, and the positions' bounds; runs found from
summaries.

**Done when:** tests for the reader (old logs without `fixAt`, new ones with
it, a `fixAt` that goes backwards skipped as §22.3 promises it never does);
for runs (one device back to back; an app restart, `at` falling, starting a
new run; another device; a gap of hours); the summary rebuilt on first view,
as before.

### M13.3 — Re-timing a run, stored, and checked

For a run and a course: re-time on the current version; take the tablet's
laps on the current version; the laps that stand; flags where both exist and
disagree. Stored per run and course version; rebuilt when either changes.

**Done when:** tests on synthetic runs: the tablet's laps on the current
version standing untouched; laps on an older version re-timed on the new;
a session with no laps timed; a lap across two sessions of one run timed as
one; an app restart starting afresh with an out-lap; a disagreement of 3 ms
flagged and one of 1 ms not; the file rebuilt when the course moves on, and
read back when it hasn't.

### M13.4 — When it runs

After a session is prepared; and for every session a course touches (its
laps name the course, or its bounds cross the course's) when the course is
saved, in the background, one at a time. The admin page's course save says
"re-timing N sessions", and the course's page on the admin site says when
they're done.

**Done when:** tests for which sessions a save picks, and for the background
job (one at a time, a failure logged and the rest carried on); looked at on
the admin page against the dev server.

### M13.5 — On the session page

`GET /api/sessions/{id}/laps`: the laps as they stand, each saying whether
it's the tablet's or re-timed on version N, with flags. The session page's
lap table reads it (sectors, bests, as M12.7), marks re-timed laps, and shows
a flag's detail.

**Done when:** tests for the API; looked at in Chrome: a session whose laps
were re-timed after a line moved, a session timed with no laps of its own,
and a flagged lap.

### M13.6 — Deploy, and prove it

Deployed with a drive streaming. **The first drive, re-timed:** a course
drawn around its route (a loop from its own GPS trace, made into a course
file and imported, or drawn by Sam on the website), and the drive's session
then shows laps it never had. A line moved, and its laps re-timed. The test
course removed afterwards.

### M13.7 — Record it

A decision for re-timing (per run, what stands, the 2 ms check);
`COMPLETED`, `JOURNAL`, `PLAN`, `PROTOCOL`; this plan deleted.

---

## Not in M13

- Drivers, events, stints (M14, M15).
- `timing` to the tablet (M17).
- Re-timing live, while a session is being driven: the tablet's laps are the
  live ones; the server re-times once the session is prepared.
