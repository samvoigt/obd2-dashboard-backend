# M15 — The race: one timeline, stints and stops

The fourth milestone of race logging (`RACE-LOGGING.md`): **an endurance
race's sessions made into one race**. Laps numbered through the race, from
the start to the flag; **stops** timed through the pit lane; **stints**, who
drove from when to when, set by the admin or the crew; **race results**; and
**driver pages**. Practice (M14) is untouched.

Nothing here changes the contract or touches the tablet (that's M17).

---

## What exists, and what it means (checked 2026-09-27)

- **The tablet outlives the car's power** (the app's `HARDWARE.md`: it has
  its own battery). At a driver change the car's session ends, a tablet-only
  session begins (§20), and the car's next session starts after; **the app
  keeps running**, so it's all **one run** (§22.8): `at` one clock, and the
  tablet's timing carries across the stop. A **restart of the app** (a crash,
  the battery) starts a new run, and the tablet begins again with an out-lap.
- **Re-timing is per run** (decision 33): `RunTiming` has the laps as they
  stand (the tablet's where current, re-timed where not), each session's
  `wall − at`, and the sessions. So **within a run the race's laps already
  exist**, in order, on `at`'s clock.
- **Across runs** only `wall` joins them. The tablet's wall clock can be hours
  out (the first drive: 11 h slow), but **a constant offset cancels in a
  difference**: the time between two runs, measured on `wall`, is right as
  long as nobody set the clock in between. The **server's time** (`created`,
  `updated`) orders runs and places them in the race's window (M14).
- **The lap rule knows only the pit line** (M13.1): a lap ending there is an
  in-lap, the next an out-lap. **Nothing times a stop**: `pit_in` and
  `pit_out` (§22.5: "only time stops") aren't read by anything yet, and
  **NHMS has neither drawn**, only its pit lane and pit line.
- **Events** (M14) have at most one race part, its window, and its sessions
  (by the server's time, or by hand); **who drove** is per session, set by the
  admin or the crew.

---

## Settled with Sam, 2026-09-27

- **The race's start and finish are annotations, not a cut-off** (Sam: "we
  will enter the start/stop ourselves, but once the session starts, we can
  start counting laps, i.e. the race start/finish is an annotation not a data
  cut off"). The race part gains an optional **start** (green flag) and
  **finish** (chequered flag), entered by the admin or the car's crew, shown on
  the race's timeline and results (which lap the green flag and the flag fell
  in). **Laps are counted from the first lap of the race's sessions**, formation
  laps and all, and none is dropped for falling before the start or after the
  finish. The part's **window** still only decides which sessions are the
  race's (the server's time, or by hand, M14), so it can be as broad as the
  race day.
- **Stints split at every pit stop** by default (below).
- **Stops are timed in the pit lane, from its ends** where `pit_in` and
  `pit_out` aren't drawn (below).

## Decided here (say if any is wrong)

- **Laps are numbered through the race**, from 1, every car's own, from the
  first lap its race sessions timed. Within a run they're the laps as they
  stand (decision 33). **Across a restart of the
  app** the gap between the last crossing before and the first after is **one
  lap, marked "across a restart"**, timed on `wall`; an out-lap if the last
  crossing was the pit line. Never a lap longer than the gap.
- **A stop is the car's time in the pit lane**: from crossing `pit_in` to
  crossing `pit_out`, drawn on the course, **else lines made square to the pit
  lane at its first and last points** (8 m each side, as the tablet's pit gate
  is made), so NHMS times stops as it is. Measured by re-timing from the fixes
  (a new rule version, so stored re-timings rebuild).
- **Stints split at every stop by default**, each stint's driver defaulting to
  the driver set on the session it began in (M14.4), if any. The admin or **the
  car's crew** can set each stint's driver, **merge** two (a stop without a
  driver change, a fuel stop), or **split** one at any lap (a change without a
  stop). Once edited, a car's stints are stored whole and replace the default.
- **Race results** (public): per car, laps completed, the time from the first
  lap's start to the last lap's end, where the green flag and the flag fell (if
  entered), the best lap on track, **stints** (driver, laps from–to, time, best
  lap), **stops** (after which lap, time in the pit lane); every lap listed
  with its stint's driver and whether it's the tablet's, re-timed, or across a
  restart. Several of our cars are classified by laps, then by time.
- **Driver pages** (public), `/drivers` and `/drivers/{id}`: each driver's
  events, their race stints (laps, time, best), and their best practice lap per
  course.
- **A race session's own driver** (M14.4) only seeds the stints; the race's
  results go by stints.

---

## The steps

Each validated against the code just before it's built, the validation
written here.

### M15.1 — Stops, from the fixes (`:timing`)

The pit lane's entry and exit lines (drawn `pit_in`, `pit_out`, else made at
the lane's ends); each forward crossing's moment, interpolated as a lap line's
is; stored in `RunTiming` as the run's pit crossings; `RULE_VERSION` 2.

**Done when:** tests on the box course with a pit lane: a stop timed to its
true length at 1 Hz; the made lines matching drawn ones; a crossing only in the
lane's direction; a car passing the lane's end on track never stopping; a stop
across two sessions of one run.

> **Validated against the code and NHMS's course, 2026-09-27, before
> building.**
> - **The lane's very ends are on the track.** Measured on NHMS's seed: the
>   pit lane starts and ends on the track's line (0 m), is 6 m from it 15 m in,
>   and near the exit runs within 3 m of it 40 m from the end. A line made at
>   either end would be crossed by cars that never pit. **So the made lines go
>   where the lane first comes clear of every layout by 16 m** (8 m of line
>   each side, and 8 m for the track's width and GPS error): on NHMS, **70 m
>   in from the entry and 76 m before the exit** (the pit line is at 325 m,
>   between). A stop is then the time in the pit lane **from where it leaves the
>   track**, missing about 140 m of lane, some 8 s at a 60 km/h limit, the same
>   every stop. Drawn `pit_in` and `pit_out` win wherever they're drawn.
> - **The lap rule stays the tablet's, line for line** (M13.1); stops are a
>   second, separate reader of the same fixes (`PitLane`), sharing its frame,
>   `crossing` and the pit lane's direction (`forwardSign`, made `internal`).
> - **`RunTiming` gains the run's pit crossings** (session, in or out, the
>   moment on `at`), and `RULE_VERSION` goes to 2: every stored re-timing is
>   rebuilt on its next use, as decision 33 says.

> **✅ Done, 2026-09-27.** `:timing`'s `PitLane` and `PitCrossing`;
> `RunTiming.pitCrossings`; `RULE_VERSION` 2 (stored files are now
> `timing-v2-…`); `LapRule.forwardSign` and `at` shared (`internal`).
> - **Tests: 6** (a stop timed to its true length, 5.6 s in and 125.3 s out
>   on the box's lane; drawn lines winning; only the lane's direction, never a
>   car on track, nothing without a lane or with one line; a stop across two
>   sessions; NHMS timing stops as it is; a re-timing keeping the crossings).
> - **Mutations: 10, 9 killed**, one after a test (a single line can't time a
>   stop); **one as good as equivalent**: sorting the crossings within one move,
>   which only matters if a move crosses both lines, and on a sane lane the
>   entry comes first.
> - The timing tests now read the NHMS seed, so `:timing`'s test task declares
>   it as an input (JOURNAL: M12).

### M15.2 — The race, one timeline (pure)

From each run's timing (laps on `at`, `wall − at`, pit crossings), the
entered start and finish, and the stint edits: the race's laps numbered from
the first, laps across a restart, stops, default stints, stints as edited, the
laps the green flag and the flag fell in, and each car's results.

**Done when:** tests: a race of one run across two driver changes (car sessions
ending, tablet-only sessions between) numbered straight through; laps before
the entered start and after the finish counted, the start's and the flag's
laps marked, and none marked with no times entered; a restart
bridged by one lap, an out-lap after the pit line; stints split at each stop,
drivers from their sessions; merged, split and named by hand; two cars
classified.

> **Validated against the code, 2026-09-27, before building.**
> - **Pure, in `:timing`**, beside `RunTiming`, which it reads: a car's race
>   is its race sessions' runs (in the server's order), each run's laps and pit
>   crossings **whose session is one of the race's** (a run can begin in
>   practice: runs join across 12 hours), placed on `wall` by their session's
>   `wall − at`.
> - **Everything stays on the tablet's clock**, which may be hours out but is
>   one clock: laps, stops, stints and their boundaries, where a constant offset
>   cancels in every difference. Stored stint boundaries are on it too.
> - **The start and finish Sam enters are real times**, so placing them needs
>   the tablet's offset. The live lane measures it but keeps it in memory. A
>   session the live lane announced is **created by the server within seconds
>   of its first record**, so the smallest `created − started` across the
>   race's sessions is the offset to within seconds, which is plenty to say
>   which lap the flag fell in. (A tablet-only session, created when it
>   uploads, only ever makes the difference larger, so the smallest is right.)
> - **Across a restart**, the lap from the last crossing of one run to the
>   first of the next is an **out-lap** if the first run ended on the pit line
>   (its last lap an in-lap), and an **in-lap** if the next begins on it (its
>   first lap an out-lap), whichever side of the pit line the car stopped.
> - **A stop** pairs each lane entry with the next exit (an entry with none,
>   the race ending in the pits, is a stop with no end); it's **on the lap the
>   car entered the lane in**, its in-lap (the lane's entry comes before the pit
>   line; found by the tests, which first counted the laps completed at the
>   entry and so named the lap before). **A stint** holds the laps that **end** in it:
>   default boundaries are each stop's exit (its entry if it has none), so an
>   in-lap is the outgoing driver's and the out-lap the incoming one's.
> - **Two cars** are classified by laps, then by the time from their first lap's
>   start to their last lap's end.

> **✅ Done, 2026-09-27.** `:timing`'s `Race` (`car`, `classify`,
> `tabletOffset`), `CarRace`, `RaceLap`, `RaceStop`, `RaceStint`, `StintMark`.
> - **Tests: 7** (one run through two driver changes: laps 1–9 straight
>   through, stops on the in-laps, stints split at the exits, drivers from
>   their sessions, the out-lap the incoming driver's; a restart bridged on
>   `wall`, an out-lap after the pit line or an in-lap before it, never the
>   best, no bridge without a gap; only the race's sessions and their stops;
>   the flags marked, nothing cut; stints edited, merged and split; an entry
>   with no exit; two cars classified, and the tablet's offset).
> - **Mutations: 16, all killed**, three after tests were added (a practice
>   stop in the same run; a bridge on track that would be the quickest; a
>   stint's quick in-lap).

### M15.3 — Stints and the race's start and finish, stored and edited

The race part gains its start and finish (optional instants) and each car's
stints (boundaries and drivers) in Firestore, both set by the admin or the
car's crew;
`PUT /api/admin/events/{id}/stints/{car}` and
`PUT /api/cars/{slug}/events/{id}/stints` (the crew's cookie path, as who
drove is); logged; `import-event` keeps them.

**Done when:** tests (the admin, the car's crew, another car's crew refused,
a stale save refused); the mapping and the real Firestore.

> **Validated against the code, 2026-09-27, before building.**
> - **The race part gains** `green` and `flag` (real instants, entered) and
>   `stints` (by car: each stint's start **on the tablet's clock** and its
>   driver) in `:events`, and in Firestore as a map of lists of maps (no list
>   inside a list).
> - **Every other save keeps them.** The event editor and `import-event` both
>   rebuild the parts from what they're sent, which never carries these; so
>   they're kept from the stored part of the same id, as the hand-made session
>   lists are (M14).
> - **What can be checked:** a car's stints have distinct starts, and name
>   drivers who exist; the car is entered; the event has a race; the flag
>   comes after the green flag. **Not** whether a boundary falls inside the
>   race: boundaries are on the tablet's clock, the window on the server's.
> - **Routes**, one rule each, as who drove (M14.4): the admin's
>   `PUT /api/admin/events/{id}/race` (the flags) and
>   `…/race/stints/{car}`; the crew's `PUT /api/cars/{slug}/events/{id}/race`
>   and `…/race/stints`, for a car entered in the event. Both take the
>   event's `expected` revision, so an edit on a stale page is refused, and log
>   who. Stints `null` (or empty) goes back to the default.

> **✅ Done, 2026-09-27.** `Part.green`, `flag`, `stints` (`Stint`),
> `Event.keepingRaceEdits`; their rules; Firestore; `RaceRoutes`
> (`SaveFlags`, `SaveStints`, `RaceSaved`); the editor's save and
> `import-event` keeping them.
> - **Tests: 6** (the rules; a save keeping them; the admin's flags, stale and
>   backwards refused; a crew's stints and back to the default, nobody else's,
>   not a car outside the event; the admin's stints and the editor keeping
>   everything) and the mapping, the tool and the real Firestore extended (a
>   race with flags and stints read back exactly).
> - **Mutations: 13, all killed.**
> - **Changed while building:** a car not entered is refused before the rules
>   see it (404, as for a crew), so the rule's own wording only shows for stored
>   data.

### M15.4 — Race results, and the stint editor

`GET /api/events/{id}` gains the race's results; the event page's race section
(classification, each car's stints, stops and laps, the green flag and the
flag marked), and for the admin or the car's crew, the race's start and finish
(a time, or "now" at the flag) and the stints editor (driver per stint, merge,
split at a lap).

**Done when:** API tests; looked at in Chrome: a replayed race of three
sessions and a restart, stints edited as the crew.

### M15.5 — Driver pages

`GET /api/drivers/{id}` and `/drivers`, `/drivers/{id}`: events, stints,
best practice laps per course; drivers linked from results.

**Done when:** API tests; looked at in Chrome.

### M15.6 — Deploy, and prove it

Deployed with a stream across it (a second throwaway car's, under
`caffeinate -i`, JOURNAL: M13, M14). **Proof in production:** a throwaway car,
the box course with a pit lane, two test drivers, a test event whose race
catches a replayed race (driver changes in the pit lane, one app restart);
stints set through the crew's passcode; the public race results and driver
pages. All removed after.

### M15.7 — Record it

A decision for the race; `COMPLETED`, `JOURNAL`, `PLAN`, `README`,
`CLAUDE.md`; this plan deleted.

---

## Not in M15

- The lap chart, theoretical best, sector comparisons, consistency (M16).
- Anything live, or to the tablet: the race's lap count and stint on the car
  page and in `timing` (M17).
- Other teams' cars, or laps typed in by hand (only our cars, as settled).
