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

> **Validated against the code, 2026-09-27, before building.**
> - **The summary** (`SessionReader`, one pass over the log) gains, as
>   version 3: the session record's `device`; the first and last `at` of the
>   session's records (a run is `at` rising from one session to the next,
>   §22.8); and the **bounds** of its `gps.position` fixes, to find which
>   sessions a course touches without reading every log. Its Firestore mapping
>   gains the fields. Older summaries rebuild on first view, as in M7 and M11.
> - **The fix reader** lives in `:timing`, which now also depends on
>   `:archive` for `Records.parseObject` and `LineSplitter` (a log is read a
>   line at a time, never whole): each `gps.position`'s `lat`, `lon` and
>   `fixAt` (else `at`), skipping any that goes back in time (§22.3 says the
>   tablet never sends one); each `lap` record with §22.6's fields.
> - **Runs** from summaries: one car, one `device`, sessions back to back
>   in time, each first `at` above the last one's last `at`; a device-less
>   session (older logs) is a run of its own; so is anything more than 12 hours
>   from the last.

**Done when:** tests for the reader (old logs without `fixAt`, new ones with
it, a `fixAt` that goes backwards skipped as §22.3 promises it never does);
for runs (one device back to back; an app restart, `at` falling, starting a
new run; another device; a gap of hours); the summary rebuilt on first view,
as before.

> **✅ Done, 2026-09-27.** The summary is version 3 (`device`, `firstAt`,
> `lastAt`, `Bounds`), in Firestore as plain fields and a list of four;
> `:timing`'s `SessionTrace` (fixes and `TabletLap`s from a log, streamed)
> and `runs()`.
> - **Tests: 5** (the summary's new fields and bounds' overlap; their
>   Firestore round trip; fixes on `fixAt`, else `at`, a backward one left
>   out; old and new `lap` records; runs joined and split each way).
> - **Mutations: 13, all killed**, one only after a fix to the test: the
>   device-less sessions had no `at` either, so they split for the wrong
>   reason; they now have rising `at` and split only for having no device.

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

> **Validated against the code, 2026-09-27, before building.**
> - **Reading a run:** `ArchiveService.read(record)` streams a session's
>   lines; each session gives a `SessionTrace`, and the run's fixes go through
>   **one** `LapRule` in session order, so a lap across an OBD drop is one lap.
>   A lap belongs to the session it **ends** in (where the tablet writes its
>   `lap` record); a server lap to the session of the fix that completed it.
> - **Storing:** the archive's `SegmentStore` is private to `ArchiveService`,
>   so it gains a small derived-file API beside the series (read, write, list,
>   delete under `sessions/{id}/`), deleted with the session as the series is.
>   The file names the run's sessions, and is **read back only if they still
>   match** (a run grows when a later session joins it); a new version deletes
>   the older files of that course.
> - **The course:** `CourseStore.get(id)` for the current version,
>   `CourseRules.check` for its `CourseShape`. **The layout** is the one the
>   run's tablet laps name (by `id`, or by name in older logs), else the
>   default.
> - **What stands**, lap by lap: every tablet lap on this course, current
>   version and layout; and every re-timed lap that doesn't overlap one of
>   those in time. **The check:** a tablet lap agrees if a re-timed lap starts
>   and ends within 2 ms of it; else it's flagged, with what re-timing found
>   there (or that it found no such lap). A current-version lap without
>   `startAt` and `endAt` (not expected, §22.6) is placed from its record's `at`
>   and `time`, and marked unchecked.
> - **The file** is JSON through kotlinx serialization (`:timing` gains the
>   plugin, as the server has); `RULE_VERSION` 1 is in its name, so a rule
>   change rebuilds it.

> **✅ Done, 2026-09-27.** `:timing`'s `Retiming.retime` (pure: a run's
> traces and a course to a `RunTiming`, the laps that stand and every re-timed
> one) and `Retimer` (stored beside the run's first session, read back while
> the run and version match); `ArchiveService`'s derived files, `timing-*`
> only.
> - **Changed while building:** a tablet lap displaces a re-timed one only if
>   they **share more than half the shorter lap**, not on any overlap. Laps are
>   back to back, so a tablet lap 1 ms early overlapped the re-timed lap before
>   it by that millisecond and knocked it out (the tests found it).
> - **Tests: 10**: the tablet's laps on the current version standing and
>   agreeing; an older version's re-timed on the new (the line 100 m on,
>   2.5 s later); no laps of its own, and older laps naming the layout by
>   name; another course's laps not standing; a lap across an OBD drop as one
>   lap in the session it ended in, and two runs starting afresh; 3 ms flagged
>   (with what re-timing found), 1 ms agreeing; a lap without crossings placed
>   from its record, unchecked; the file read back, rebuilt on a new version
>   (the old one deleted) and for a changed run; no such course.
> - **Mutations: 14, all killed**, two only after tests were added (a layout
>   named by name when it isn't the default; laps from another course).
> - **Not tested here:** an app restart *from the pits* starting with an
>   out-lap: that's the rule's (M13.1 tests the out-lap from the pit line),
>   and runs are split by M13.2.

### M13.4 — When it runs

After a session is prepared; and for every session a course touches (its
laps name the course, or its bounds cross the course's) when the course is
saved, in the background, one at a time. The admin page's course save says
"re-timing N sessions", and the course's page on the admin site says when
they're done.

**Done when:** tests for which sessions a save picks, and for the background
job (one at a time, a failure logged and the rest carried on); looked at on
the admin page against the dev server.

> **Validated against the code, 2026-09-27, before building.**
> - **After a session completes**, `ArchiveRoutes` already launches
>   `archive.prepare(id)` after answering, failures only logged; re-timing
>   follows it in the same coroutine. It finds the car's sessions' summaries
>   (`archive.summary`, which rebuilds an older version on the way, once), the
>   **run** the session is in, and every **course that run touches**, and
>   re-times each.
> - **Touches:** a session's summary names the course as its `track` (the
>   tablet's laps), or its fix `bounds` overlap the course's (every layout's
>   path and the pit lane, with 50 m round them). Picking is pure, in
>   `:timing`, and tested there.
> - **A course save** (`PUT /api/admin/courses/{id}`, which already calls
>   `onChange` for the tablets) also starts a **background job**: every car's
>   complete sessions, grouped into runs, those touching the course re-timed
>   **one run at a time** (one lock for all re-timing, the instance being one,
>   decision 20), a failure logged and the rest carried on. A newer save of the
>   same course cancels an older one's job. A flagged lap is logged as a
>   warning.
> - **The admin page:** the job's progress, in memory,
>   `GET /api/admin/courses/{id}/retiming` (the version, runs and sessions
>   picked, done, failed, finished); the editor shows it after a save and
>   while a job runs. In memory is enough: a restart loses only the progress
>   display, and a session's re-timing is rebuilt on view if it's missing
>   (M13.5).

> **✅ Done, 2026-09-27.** `:timing`'s `courseBounds`, `touches`, `runsAt`,
> `runOf`; the server's `RetimingJobs` (after prepare, on a save, one lock),
> `GET /api/admin/courses/{id}/retiming`, and the editor following it
> (`retimingText`).
> - **Found while building:** the summary's `track` read only a lap's
>   `track`, but a §22.6 lap names its `course` (`track` is for older
>   readers); it now reads `course`, else `track`, as `SessionTrace` does.
> - **The box course and its logs** moved to `:timing`'s test fixtures,
>   shared with the server's tests (with `wall` and a device, so runs order
>   as real ones do).
> - **Tests: 11 Kotlin** (bounds with the margin and the pit lane; touching
>   by laps or by fixes; a save picking whole runs, other cars' too; a
>   session's run and its courses; the job re-timing three runs with one
>   failing and counted; a newer save cancelling the older job; a prepared
>   session re-timed on the course it touches and not a far one; a session
>   uploaded and completed over HTTP, then re-timed; the progress route, 204
>   before, admin only) and **2 Vitest** (the wording).
> - **Mutations: 14, all killed**, one only after a test was added (the pit
>   lane in a course's bounds: the box had none).
> - **Looked at on the admin page** against the dev server: a synthetic drive
>   round a box at NHMS replayed in; NHMS saved as version 2; the page said
>   "Version 2: re-timed 1 session.", and the log "re-timed 1 runs at nhms
>   v2, 0 failed".
> - **Noticed, not changed:** the editor's Save stays disabled after changing
>   only the course's name (M12's `dirty` is set by drawing only).
> - **Flaky:** `:replay`'s coalescing test failed once in a loaded full run,
>   and passed three times alone. For the JOURNAL.

### M13.5 — On the session page

`GET /api/sessions/{id}/laps`: the laps as they stand, each saying whether
it's the tablet's or re-timed on version N, with flags. The session page's
lap table reads it (sectors, bests, as M12.7), marks re-timed laps, and shows
a flag's detail.

**Done when:** tests for the API; looked at in Chrome: a session whose laps
were re-timed after a line moved, a session timed with no laps of its own,
and a flagged lap.

> **Validated against the code, 2026-09-27, before building.**
> - **The page is on `wall`**: the series places everything, laps included,
>   by `wall` (a lap row ends at its record's `wall` and starts its time
>   before). Re-timed laps are on `at`'s clock, so **the stored re-timing
>   gains each session's `wall − at`** (the median over its fixes, since
>   the wall clock can be corrected mid-session, M11), and the API gives each
>   lap's start and end on `wall` from the session it ended in.
> - **`GET /api/sessions/{id}/laps`**, public as `/series` is: for a
>   complete session, its run re-timed (stored, or built now under the same
>   lock as the jobs) on **the course its laps name**, else the first course
>   its fixes touch; **204** for a session still uploading, or at no course,
>   and the page keeps the series' laps, as today.
> - **Each lap:** numbered through the run (as the tablet numbers them, a run
>   being one run of its app), its time, sectors, in/out, start and end,
>   `tablet` or `retimed`, the version, and a flag's detail. Only the laps
>   that **ended in this session**.
> - **The page** (`SessionPage.svelte`, `lapRows`): rows from the API when
>   it answers, else from the series; the best and the sector bests by the
>   same rules (§18, §22.6); a re-timed lap says "re-timed on version N"; a
>   flagged lap says what re-timing found.

> **✅ Done, 2026-09-27.** `GET /api/sessions/{id}/laps` (`SessionLaps`,
> `StandingLap`, `LapFlag`), from `RetimingJobs.sessionLaps`; `SessionTrace`'s
> `wallOffset` and `RunTiming.wallOffsets`; the page's `standingRows`,
> `lapNote`, `fetchLaps`.
> - **Found by looking** (the dev server, a box course at NHMS, two drives
>   replayed in; version 1, then version 2 with the line 100 m on):
>   - **A disagreement was logged on every page view**, the stored re-timing
>     being reported each time it was read back. The `Retimer` now reports a
>     re-timing only when it's built; a test says it's told once.
>   - **The best-sector highlight was patchy**: re-timed sectors differ in
>     the 7th decimal (a receiver's centimetres), so equal-looking sectors
>     weren't equal. The API gives times to the millisecond, as a `lap`
>     record has them; the test drive now has centimetre positions.
>   - **The note wrapped** a word a line in the table; it's kept on one line,
>     the table a little wider.
> - **Seen:** a drive with no laps of its own showing four laps re-timed on
>   version 1; the tablet's laps standing with lap 2 flagged "Re-timing found
>   1:10.000"; after version 2, every lap re-timed, S1 2.5 s shorter and the
>   last sector 2.5 s longer, as the line moved.
> - **Tests: 5 Kotlin, 2 Vitest.** **Mutations: 12**, 11 killed, four only
>   after the test was tightened (rounding needs noisy positions; the run's
>   numbering needs a tablet number that differs; the course the laps name
>   needs a second course there). **One equivalent:** without the route's
>   "still uploading" guard, an uploading session is still left out of runs,
>   so it still gets 204; the guard only saves the work.

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
