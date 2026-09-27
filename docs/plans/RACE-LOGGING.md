# Race logging — the overall plan

**Sam, 2026-09-27:** "be able to have some race logging … group by driver, and
store sessions as belonging to a specific race … define a track with a
start/stop, and maybe segments too, so that we could track lap times and
segment splits."

The overall plan, agreed in outline before any code. It becomes milestones
M12–M17, and each is planned in detail and **each step validated against the
code before it's built**, as always. Nothing here is built yet.

**The contract change it needs** is proposed in
[`docs/proposals/COURSES-AND-TIMING-TO-THE-TABLET.md`](../proposals/COURSES-AND-TIMING-TO-THE-TABLET.md)
(revision 2, answering the tablet's §22), for the tablet side and Sam to agree.

---

## Settled with Sam, 2026-09-27

1. **Endurance racing, with driver changes.** The car's power is shut off at
   a change, so **the car's session ends at every stop** (and the OBD link may
   drop at other times too). A race is therefore **many sessions**, joined.
2. **Drivers are recorded on the website**, never on the tablet: the driver
   shouldn't have to touch it.
3. **The tablet times laps and sectors, and its numbers are the results**:
   *"the tablet's number wins, it is the source of truth for the location
   data."* (This replaced a first plan where the server timed; see the
   proposal's revision 2.) The server keeps the books: courses, drivers,
   events, results, and **re-timing from the tablet's own fixes** where a
   line has since moved or the tablet timed nothing.
4. **A faster GPS receiver is planned.** Timing takes whatever rate comes (the
   contract promises no fixed rate, §15).
5. **Courses are drawn on a map** on the website: NHMS to start with, but
   **any course**, for testing (a loop near home) and for correcting a line.
   **They live on the website only**, and go down to the tablet, which times
   on them.
6. **Practice and race.** Practice is sessions that stand alone; the race is
   sessions **tied together** into one.
7. **Public**, like the rest of the site. Editing stays behind sign-in.
8. **Only our cars** (the ones with tablets). No hand-entered lap times.
9. **The tablet shows what only the server knows**: the course and its
   sectors on its map, the driver and stint, the race's lap count, the
   event's bests. (Sam asked for the tablet as a "dumb terminal"; the tablet
   side's reply, §22, showed it has to keep the lap clock and live delta
   itself, and Sam agreed.)

---

## What exists, and what it means (checked 2026-09-27)

- **Every GPS fix is a `gps.position` sample** with `lat`, `lon`, and two
  clocks: **`at`**, the tablet's monotonic milliseconds, one clock for a whole
  **run of the app** across all its sessions, and **`wall`**, the tablet's wall
  clock, the only one that compares across runs (§3.1). **With the contract
  change, a fix also carries `fixAt`**: when the receiver took it, on `at`'s
  clock. Laps are timed on `fixAt`.
- **The tablet already times laps** (§16) at NHMS, from a shipped track file
  with a start/finish marked as a guess. With the change it times on courses
  from the website, and each `lap` names its course, version, layout and
  sectors.
- **The tablet's clock is 10 h 58 min slow** (the first drive, M11). That
  doesn't touch timing (on `fixAt`) or stitching a run (on `at`), but it breaks
  **placing sessions in real time**. So sessions join events by the **server's
  own times** (when their data arrived), and **fixing the tablet's clock is a
  prerequisite for race day** anyway, for the times people read.
- **The tablet keeps streaming while the car is off** (the app's M40), so **a
  pit stop is covered**: the car's session ends, a tablet session runs through
  the stop, the next car session begins; all one run of the app, one `at`.
- **The live lane coalesces samples** (§5.2): at 10 Hz the server sees about
  half the fixes live. It doesn't need them: `lap` records go whole in the
  next batch, and the archive has every fix for re-timing.
- **Sessions are prepared once into a series** (decision 26), rebuilt when
  their version changes. Re-timing is the same kind of derived file.
- **The admin page** (Google sign-in, decision 25) and **the crew login** (a
  car's passcode, decision 22) are the two ways to edit today.

---

## The model

**Course.** What the app calls a track, but anything drawn:
- a name; one or more **layouts** (the line around, in the direction cars go),
  each with an `id`;
- per layout, a **start/finish line** and **sector lines**, in order. A line is
  two points, and counts only when crossed in the layout's direction;
- optionally the **pit lane**: `pit_in` and `pit_out` (a lap crossing them is
  an in- or out-lap, and a stop runs from one to the other) and a **`pit_line`**
  across the lane, where an in-lap ends and the out-lap begins;
- **versions**: every edit is a new version; every lap names the version it
  was timed on.

**Driver.** A name and a short code (for tables).

**Event**, with its **parts**:
- an event: a name, a course and layout, the cars entered;
- **practice** parts: each a time window; the sessions in it stand alone, lap
  numbers per session;
- **the race**: a start and end; its sessions are **one timeline**: laps
  numbered through the race, total time and distance, stops and their length,
  and **stints** (who drove, from when to when).

**Stint.** A driver in a car from a time to a time. **By default each car
session in the race is a stint**, since power goes off at each change; the
crew picks the driver per stint, and can split one where a change happened
without a stop.

**Lap.** The tablet's `lap` record, or the server's re-timing of the same
fixes: car, driver (from the stint), event and part, course version, number,
start and end, time, sector splits, in- or out-lap, and **which it is**:
timed by the tablet, or re-timed by the server on version N.

---

## Timing: the tablet's, and the server's re-timing

**Live and official, a lap is the tablet's.** The server stores `lap` records
as sent, shows them everywhere, and **never overrides** one timed on the
course version that is current.

**The server re-times** (a pure module, `:timing`, like `:archive`: no
Google, all tests) only:
- **after a line moves**, the laps timed on older versions, on the new one;
- **sessions with no `lap` records**: logs from before the change, or a
  tablet that didn't have the course.

It re-times **the tablet's own fixes, on `fixAt`** (else `at`), by the tablet's
rule (§16): each segment between two fixes that crosses a line in the right
direction gives a crossing, interpolated along it. In- and out-laps by
`pit_in`, `pit_out` and `pit_line`. A lap with a missing sector (a gap, a
shortcut) is kept but marked, never a best. **A GPS gap with no fixes leaves a
lap untimed**, never guessed.

**Where both exist for the same version, they must agree** (same fixes, same
clock, same rule): the server checks and **flags** a lap that doesn't, as a bug
for one side to find, never replacing the tablet's number.

**Across sessions:** within one run of the app, sessions join on `at` (the
tablet's §22.5), so a lap across an OBD drop is timed exactly; across runs
(an app restart), on `wall`.

**Which course a session was at** comes from its `lap` records, or, for one
without, from where its positions are.

**Proven first** on synthetic traces with known answers (a circle with lines
at known places, fixes at 1 Hz and 10 Hz, `fixAt` behind `at`), then on the
first drive's real session re-timed on a loop Sam draws around its route.

---

## Pages

- **Events** (public): a list; an event's page with its practice parts (each
  session's laps, best by driver) and its race (the order of laps, stints by
  driver, stops and their length, best laps, sector splits, a **theoretical
  best** from the best of each sector, and a lap chart). Every lap links to its
  moment in the session's chart and map, and says whether it's the tablet's
  or re-timed.
- **Drivers** (public): each driver's events, stints, best laps per course,
  and consistency.
- **A session's page** gains its course, laps and splits, its driver(s) and its
  event.
- **The car page, live**: each lap and its splits as the tablet sends them, the
  current driver, and during a race the lap count, the stint's time and the
  time since the last stop.
- **The tablet, live**: the course and its sectors on its map, and from
  `timing`, the driver and stint, the race's lap count, the event's bests. The
  lap clock, splits and live delta are the tablet's own.
- **Editing** (signed in):
  - **courses**, on the admin page: a map (OpenStreetMap, and satellite if a
    free source allows) where you click two points for a line, drag to move,
    and draw a layout; NHMS seeded from the app's file, with its `pit_line`
    where the tablet draws one today;
  - **events and drivers**, on the admin page;
  - **stints**, from the car page or the event page, by the **admin or the
    car's crew** (its passcode), so the pit wall can set the driver during the
    race without a Google sign-in.

---

## What it changes in what exists

- **New Firestore collections**: courses (with versions), drivers, events.
  A session's record gains its event part; stints live with the event.
- **Laps** stay the tablet's, as today, now with course, version and sectors;
  re-timed laps are a derived file beside the series.
- **The session list** can show which event each session belongs to.
- **Contract:** the proposal (revision 2): `GET /v1/courses` and a `courses`
  frame; `fixAt` on `gps.position`; `lap` records with course, version, layout
  `id` and sectors; a `timing` frame of what only the server knows; all opted
  into by `hello.features`; wire v1, log format 3, nothing existing changed.
- **The M11 clock note** becomes important: a race needs the tablet's clock
  right, and the admin page says when it isn't.

---

## The milestones

**M12 — Courses, and down to the tablet.**
Courses and their versions; the map editor on the admin page; NHMS seeded
(with its `pit_line`); `GET /v1/courses` and the `courses` frame, so the
tablet times on the website's courses; the tablet's new `lap` records (course,
version, layout `id`, sectors) read, and a session's laps and splits on its
page. *Needs:* the contract agreed. *Proven on:* a replay, then the tablet
timing a loop Sam draws near home.

**M13 — Re-timing.**
`:timing`: crossings on `fixAt`, laps, sectors, in/out-laps, which course;
re-timing laps on older versions when a line moves, and sessions without
`lap` records; the agreement check. *Proven on:* synthetic traces with known
answers, then the first drive re-timed on the drawn loop.

**M14 — Drivers and events.**
Drivers; events with practice parts and a race; sessions joining a part by
car and the server's time, or by hand; practice results.

**M15 — The race: stitching and stints.**
The race's sessions joined into one timeline (on `at` within a run, `wall`
across runs); stints (each car session by default, splittable), set by the
admin or the crew; laps numbered through the race; stops from the pit lines;
race results, driver pages.

**M16 — Results worth reading.**
The lap chart, the theoretical best, sector comparisons between drivers,
consistency; every lap linked to its moment in the session.

**M17 — Live: the car page and the tablet.**
The car page's live laps and splits (the tablet's `lap` records as they
arrive), driver, stint and race lap count; the `timing` frame to a tablet that
lists `timing.1`: driver, stint, race, and the event's bests.

Each is deployed and proven live on its own, as every milestone has been.

---

## Risks, and what's done about them

- **The contract change** is the app's work too: M12 waits for it to be agreed,
  and each server step is proven against a replay before the tablet's half
  exists.
- **The tablet's clock** (11 h slow today): races join by the server's time;
  the admin page flags it; **fix it on the tablet before race day**.
- **GPS at 1 Hz** makes short sectors rough: `fixAt` removes the delivery
  delay, and the faster receiver drops straight in.
- **A GPS gap** mid-lap: the lap is marked, not guessed.
- **Moving a line changes past results**: on purpose, and visibly, since every
  lap names the course version it was timed on and whether it's re-timed.
- **The tablet and the server disagreeing** on a lap: flagged, never silently
  replaced.
- **Crew editing stints**: only their own car's, and every change is logged,
  as the admin page's are (decision 25).

---

## Still to decide (small; defaults proposed)

- **Sector lines per layout:** as many as drawn; three is typical. *Proposed:
  no limit* (the tablet side agreed, §22.6).
- **Satellite imagery** under the course editor: only if a free source's terms
  allow it; else OpenStreetMap. *Proposed: look at the options in M12.*
- **A race's start**: a time the admin sets (a standing or rolling start), or
  the first start/finish crossing after the window opens. *Proposed: the admin
  sets it; the first crossing if not.*
