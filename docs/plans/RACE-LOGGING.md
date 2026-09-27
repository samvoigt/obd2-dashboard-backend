# Race logging — the overall plan

**Sam, 2026-09-27:** "be able to have some race logging … group by driver, and
store sessions as belonging to a specific race … define a track with a
start/stop, and maybe segments too, so that we could track lap times and
segment splits."

The overall plan, agreed in outline before any code. It becomes milestones
M12–M16, and each is planned in detail and **each step validated against the
code before it's built**, as always. Nothing here is built yet.

---

## Settled with Sam, 2026-09-27

1. **Endurance racing, with driver changes.** The car's power is shut off at
   a change, so **the car's session ends at every stop** (and the OBD link may
   drop at other times too). A race is therefore **many sessions**, joined.
2. **Drivers are recorded on the website**, never on the tablet: the driver
   shouldn't have to touch it.
3. **The server times laps and splits from the GPS traces** (the "proposal
   A" of the first draft).
4. **A faster GPS receiver is planned.** Timing must take whatever rate comes
   (the contract already promises no fixed rate, §15).
5. **Courses are drawn on a map**: NHMS to start with, but **any course**, for
   testing (a loop near home) and for correcting a line.
6. **Practice and race.** Practice is sessions that stand alone; the race is
   sessions **tied together** into one.
7. **Public**, like the rest of the site. Editing stays behind sign-in.
8. **Only our cars** (the ones with tablets). No hand-entered lap times.

---

## What exists, and what it means (checked 2026-09-27)

- **Every GPS fix is a `gps.position` sample** with `lat`, `lon`, and the
  record's two clocks: **`at`**, the tablet's monotonic milliseconds (exact
  intervals, but only within one run of the app), and **`wall`**, the tablet's
  wall clock (the only one that compares across sessions, §3.1). There is **no
  time from the GPS receiver itself**. So:
  - **laps and splits within a session are timed on `at`**, which never jumps;
  - **a race is stitched across sessions on `wall`**, which is fine however
    wrong the clock is, as long as it's steady.
- **The tablet's clock is 10 h 58 min slow** (the first drive, M11). A slow
  but steady clock still times laps and still stitches a race; what it breaks
  is **placing sessions in real time**: which day, which race window. So
  sessions join races by the **server's own times** (when their data arrived),
  never the tablet's, and fixing the tablet's clock (automatic date and time)
  is **a prerequisite for a real race** anyway, for the times people read.
- **The tablet keeps streaming while the car is off** (the app's M40: a
  "tablet only" session, GPS and G), so **a pit stop is covered**: the car's
  session ends, a tablet session runs through the stop, the next car session
  begins. Together they give an unbroken trace.
- **The tablet already times laps** at NHMS (§16) with a start/finish marked
  as a guess. Its `lap` records stay, shown beside the server's as a check;
  the server's become the results.
- **Sessions are prepared once into a series** (decision 26), rebuilt when
  their version changes. Timing is the same kind of derived file: rebuilt when
  the timing rules change **or the course's lines move**.
- **The admin page** (Google sign-in, decision 25) and **the crew login** (a
  car's passcode, decision 22) are the two ways to edit today.

---

## The model

**Course.** What the app calls a track, but anything drawn:
- a name; one or more **layouts** (the line around, for the map);
- per layout, a **start/finish line** and **sector lines**, in order. A line
  is two points, and counts only when crossed in the layout's direction, so a
  car going the wrong way (or reversing over it in the pits) isn't timed;
- optionally a **pit lane** (entry and exit lines), to mark in-laps and
  out-laps and to time stops;
- **versions**: every edit is a new version, and every lap names the version
  it was timed on.

**Driver.** A name and a short code (for tables).

**Event**, with its **parts**:
- an event: a name, a course and layout, the cars entered;
- **practice** parts: each a time window; the sessions in it are timed **on
  their own**, lap numbers per session;
- **the race**: a start and end; its sessions are **one timeline**: laps
  numbered through the race, total time and distance, stops and their length,
  and **stints** (who drove, from when to when).

**Stint.** A driver in a car from a time to a time. **By default each car
session in the race is a stint**, since power goes off at each change; the
crew picks the driver per stint, and can split one where a change happened
without a stop.

**Lap.** Car, driver (from the stint), event and part, course version, number,
start and end, time, sector splits, and whether it's an in-lap, out-lap or
crossed a session break (see below).

---

## Timing

A pure module, `:timing`, like `:archive`: no Google, all tests.

- **Crossings.** For each pair of consecutive fixes, whether the segment
  between them crosses a line, in the right direction, and **where along it**;
  the crossing time is interpolated between the two fixes on `at`. At 1 fix a
  second that's good to about a tenth or two of a second a crossing; at 10 Hz,
  hundredths.
- **Laps** are start/finish to start/finish; **splits** are sector line to
  sector line; a lap missing a sector crossing (a gap, a shortcut) is kept but
  marked, and never a best.
- **In- and out-laps** by the pit lane's lines, as the tablet does (§18): never
  a best.
- **Across a session break** (the race's stitched trace): a lap that spans two
  sessions is timed on `wall` across the break, and marked; normally that's
  the in-lap to a stop, already not a best. A break with no GPS at all (no
  tablet session covering it) leaves the lap **untimed**, never guessed.
- **Which course a session was at** is found from its positions (near a
  layout), so practice sessions don't need tagging by hand.
- **Proven first on synthetic traces with known answers** (a circle with a
  line at a known place, fixes at 1 Hz and 10 Hz), then on real data: the
  first drive's 11-minute session against a loop drawn around its route.

Stored per session as `timing-v{N}-{course version}.json.gz` beside its
series; a race's stitched timing is built from its sessions' and stored with
the event.

---

## Pages

- **Events** (public): a list; an event's page with its practice parts (each
  session's laps, best by driver) and its race (the order of laps, stints by
  driver, stops and their length, best laps, sector splits, a **theoretical
  best** from the best of each sector, and a lap chart). Every lap links to its
  moment in the session's chart and map.
- **Drivers** (public): each driver's events, stints, best laps per course,
  and consistency.
- **A session's page** gains its course, laps and splits (the server's, with
  the tablet's beside them), its driver(s) and its event.
- **The car page, live** (M16): the running lap time, the last lap and its
  splits as they happen, the current driver, and during a race the lap count,
  the stint's time, and the time since the last stop.
- **Editing** (signed in):
  - **courses**, on the admin page: a map (OpenStreetMap, and satellite if a
    free source allows) where you click two points for a line, drag to move,
    and draw a layout; NHMS seeded from the app's file;
  - **events and drivers**, on the admin page;
  - **stints**, from the car page or the event page, by the **admin or the
    car's crew** (its passcode), so the pit wall can set the driver during the
    race without a Google sign-in.

---

## What it changes in what exists

- **New Firestore collections**: courses (with versions), drivers, events.
  A session's record gains its event part; stints live with the event.
- **The laps shown today** (the tablet's, on the session page and the car
  page) become the server's, with the tablet's as the check. Decision 26's
  summary keeps the tablet's; timing is its own file.
- **The session list** can show which event each session belongs to.
- **Contract: no change needed.** Everything is timed from `gps.position`,
  which the tablet sends. **Worth asking for in a future v2** (through Sam,
  never edited from here): the **receiver's own fix time** on each
  `gps.position` (the receiver knows it to the millisecond), which would make
  timing independent of the tablet's clock and of `at` restarting.
- **The M11 clock note** becomes important: a race needs the tablet's clock
  right, and the admin page says when it isn't.

---

## The milestones

**M12 — Courses and the timing engine.**
Courses and their versions; the map editor on the admin page; NHMS seeded;
`:timing` (crossings, laps, splits, in/out-laps, which course); a session's
laps and splits on its page, beside the tablet's; re-timing when a line moves.
*Proven on:* synthetic traces, then **a loop Sam draws around home and the
first drive re-timed** on it.

**M13 — Drivers and events.**
Drivers; events with practice parts and a race; sessions joining a part by
car and the server's time, or by hand; practice results.

**M14 — The race: stitching and stints.**
The race's sessions joined into one timeline on `wall`; stints (each car
session by default, splittable), set by the admin or the crew; laps numbered
through the race; stops and their length; race results, driver pages.

**M15 — Results worth reading.**
The lap chart, the theoretical best, sector comparisons between drivers,
consistency; every lap linked to its moment in the session.

**M16 — Live timing.**
The running lap and splits on the car page as the car drives; during a race,
the lap count, the stint and the time since the stop.

Each is deployed and proven live on its own, as every milestone has been.

---

## Risks, and what's done about them

- **The tablet's clock** (11 h slow today): races join by the server's time;
  the admin page flags it; **fix it on the tablet before race day**.
- **GPS at 1 Hz** makes short sectors rough: the timing takes any rate, and
  the faster receiver drops straight in. Sector times say how many fixes they
  rest on, so a rough one looks rough.
- **A GPS gap** (a tunnel, a dropped tablet session) mid-lap: that lap is
  marked, not guessed; the next one times normally.
- **Moving a line changes past results**: on purpose, and visibly, since every
  lap names the course version it was timed on.
- **Crew editing stints**: only their own car's, and every change is logged,
  as the admin page's are (decision 25).

---

## Still to decide (small; defaults proposed)

- **Sector lines per layout:** as many as drawn; three is typical. *Proposed:
  no limit.*
- **Satellite imagery** under the course editor: only if a free source's terms
  allow it; else OpenStreetMap. *Proposed: look at the options in M12.*
- **A race's start**: the first crossing of the start/finish after the race
  window opens, or a time the admin sets (a standing or rolling start).
  *Proposed: the admin sets it; the first crossing if not.*
