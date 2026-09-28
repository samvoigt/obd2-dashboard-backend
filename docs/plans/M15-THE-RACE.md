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

## Decided here (say if any is wrong)

- **The race runs from the first start/finish crossing after its part's start
  to the first crossing after its part's end** (the flag lap is counted). Lap 1
  begins at that first crossing, whatever the start was (rolling or standing);
  the admin sets the part's start just before the green flag.
- **Laps are numbered through the race**, from 1, every car's own. Within a
  run they're the laps as they stand (decision 33). **Across a restart of the
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
- **Race results** (public): per car, laps completed, the race time (start to
  flag), the best lap on track, **stints** (driver, laps from–to, time, best
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

### M15.2 — The race, one timeline (pure)

From the race window, each run's timing (laps on `at`, `wall − at`, pit
crossings) and the stint edits: the race's laps numbered from the start
crossing to the flag, laps across a restart, stops, default stints, stints as
edited, and each car's results.

**Done when:** tests: a race of one run across two driver changes (car sessions
ending, tablet-only sessions between) numbered straight through; the start at
the first crossing after the window opens, the flag lap counted; a restart
bridged by one lap, an out-lap after the pit line; stints split at each stop,
drivers from their sessions; merged, split and named by hand; two cars
classified.

### M15.3 — Stints stored, and edited by the admin or the crew

The race part gains each car's stints (boundaries and drivers) in Firestore;
`PUT /api/admin/events/{id}/stints/{car}` and
`PUT /api/cars/{slug}/events/{id}/stints` (the crew's cookie path, as who
drove is); logged; `import-event` keeps them.

**Done when:** tests (the admin, the car's crew, another car's crew refused,
boundaries outside the race refused, a stale save refused); the mapping and
the real Firestore.

### M15.4 — Race results, and the stint editor

`GET /api/events/{id}` gains the race's results; the event page's race section
(classification, each car's stints, stops and laps), and for the admin or the
car's crew, the stints editor (driver per stint, merge, split at a lap).

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
