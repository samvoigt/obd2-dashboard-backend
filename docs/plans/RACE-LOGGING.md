# Race logging — the high-level plan, for Sam's review

**Sam, 2026-09-27:** "be able to have some race logging … group by driver, and
store sessions as belonging to a specific race … define a track with a
start/stop, and maybe segments too, so that we could track lap times and
segment splits."

This is the outline, to agree on before anything is split into milestones and
validated step by step. Nothing here is built. The questions at the end
change the shape, so they come first in the review.

---

## What exists today

- **The tablet times laps itself** (the app's M36, contract §16 and §18): at
  tracks it ships with (only New Hampshire Motor Speedway, as a GeoJSON file
  of layouts, a start/finish line and the pit lane), it sends each completed
  lap as a `lap` record: track, layout, lap number, time, and whether it was an
  in-lap or out-lap. **No splits, no driver.** Its start/finish was placed from
  a map and is still marked as a guess.
- **The server keeps and shows those laps**: each session's summary has its
  laps and best lap; the session page has a lap table; the car page shows the
  last and best lap live.
- **GPS comes at about 1 fix a second** from the tablet (§15); an external
  receiver could give 10 or more.
- **A session belongs to a car**, and sessions group into "drives" by time.
  There's no notion of a race, a driver, or a track on the server.
- **Sessions are prepared once into a series** (every signal as columns,
  positions included) that a page draws directly (decision 26). That's what a
  timing engine would read.

---

## The central choice: who does the timing

**A. The server times laps and splits from GPS** (proposed).
- It reads a session's positions and finds every crossing of the start/finish
  and of each sector line, interpolated between fixes as the tablet does.
- **Moving a line re-times every session** at that track, past ones included.
  That matters: the start/finish is still a guess, and sectors will be
  adjusted as you learn the track.
- Works with any tablet build, and for any track you define, with no app
  change.
- The tablet's own laps stay, shown beside the server's, as a check.

**B. The tablet times everything**, the server stores it. Splits and new
tracks would need an app change and a new contract version for each; past
sessions could never be re-timed.

**Precision either way:** at 1 fix a second, a crossing is interpolated
between two fixes up to ~30–60 m apart at speed, so a lap is good to
roughly ±0.1–0.3 s, and a short sector proportionally worse. Good for
trends and comparing drivers; not for splitting hairs. A 10 Hz receiver on
the tablet is the upgrade if it matters.

---

## What it adds

### Tracks
- A track: a name, one or more **layouts** (the racing line, for the map),
  a **start/finish line**, **sector lines** (in order, per layout), and the
  **pit lane** (to mark in- and out-laps, as the tablet does).
- **Defined on the admin page, on a map**: click two points for a line,
  drag to adjust. Stored as GeoJSON, like the app's.
- **Seeded from the app's NHMS file** (read from the app, copied into this
  repo with its OpenStreetMap attribution, as the logo was).
- Each change is a new **version** of the track; timing names the version it
  used, so results can say "timed on the line as of …" and re-time when it
  moves.

### The timing engine
- A pure module (like `:archive`), tested on synthetic laps with known
  answers: from a session's positions and a track version, the laps (start,
  end, time, in/out), each lap's **sector splits**, and which track the
  session was at at all (by where its positions are).
- Run when a session is prepared, and again for every session at a track when
  its lines change. Stored beside the series as a derived file, rebuilt as
  the series is.

### Drivers
- A driver: a name (and maybe a short code for tables).
- **Who drove when**: a session, or part of one, belongs to a driver.
  Endurance-style **driver changes mid-session** (stints) are the harder,
  likelier case; see the questions.
- Laps take the driver whose stint they fall in.

### Races
- A race (or event): a name, a track and layout, a date and time window,
  and the cars in it.
- **Sessions join a race** automatically when they're of an entered car and
  at the race's track inside its window; they can also be added or removed by
  hand.
- **Practice and qualifying** could be separate races, or parts of one event;
  see the questions.

### Pages
- **Races:** a list, and a page per race: every lap by driver and car, best
  laps, sector splits, a **theoretical best** (best of each sector), and a lap
  chart. Each lap links to its place in the session's chart and map.
- **Drivers:** a page each: their races, bests at each track, consistency.
- **A session's page** gains the server's laps and splits beside the
  tablet's, and its driver(s) and race.
- **Live (a later step):** the car page's current lap running, last lap and
  splits as they happen, and a race's live order across its cars.
- **Admin:** tracks (the map editor), drivers, races, and assigning drivers
  and sessions.

---

## What it changes in what exists

- **New Firestore collections** (tracks, drivers, races), and a session
  record gains its race and driver stints. Nothing existing is removed.
- **Lap display** moves from the tablet's laps alone to the server's, with the
  tablet's as the check (decision 26's summary and the lap panels).
- **The session list** can group by race as well as by drive.
- **The car page's lap panel** (M8) takes the server's live laps once live
  timing is built.
- **No contract change for the first milestones**: the server times from
  `gps.position`, which the tablet already sends. **Later, optionally, v2**
  (agreed through Sam, never edited from here): the tablet choosing the driver
  at a driver change, and the server's track definitions sent to the tablet
  so both time on the same lines.

---

## A possible order

1. **Tracks and the timing engine**: tracks with start/finish and sectors,
   edited on a map, NHMS seeded; the server's laps and splits on a session's
   page, against the tablet's. Proven on the synthetic race, then on a real
   session at a track.
2. **Drivers**: drivers, and who drove when; per-driver laps.
3. **Races**: races, sessions joining them, results pages.
4. **Live timing**: the running lap and splits on the car page, and a race's
   live order.
5. **(Optional) The tablet's half**, contract v2: driver changes on the
   tablet, tracks synced to it.

Each is a milestone of its own, planned and validated step by step as usual.

---

## Questions for Sam

1. **What kind of racing?** Endurance with driver changes during a session
   (stints), or one driver per session? How many cars and drivers at once?
2. **Who records the driver?** The crew on the website (during or after), or
   the driver on the tablet (needs an app change)? If it's the website, is
   the crew's passcode enough, or the admin sign-in only?
3. **Timing on the server** (proposal A) rather than the tablet: agreed?
4. **Is ±0.1–0.3 s good enough**, or is a faster GPS receiver on the cards?
5. **Tracks beyond NHMS?** And sectors: lines you draw on a map, a few per
   layout (3 is typical): right?
6. **Events:** is a "race" one session window, or an event with practice,
   qualifying and race parts?
7. **Public or not:** race results and driver pages public like the rest of
   the site, or crew-only?
8. **Other teams' cars:** only your own cars (the ones with tablets), or also
   entering others' lap times by hand?
