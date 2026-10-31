# M37 — The event page: results first, each thing once, admin tools tucked away

Phase 5 of the UX review (`plans/UX-REVIEW.md` §3.E, §4 phase 5). The event
page reads top to bottom as a visitor wants it: the standings, the lap chart,
the stints with their stops, every lap, practice, then the sessions. Nothing
appears twice, no empty rows, and the flags form sits behind one "Edit the
race" button. **Sam, 2026-10-22:** the review's order and scope
("restructure freely, keep the look"); and who drove belongs to the session,
edited "Always the session page" (`UX-REVIEW.md` §7), so the event page's
stints are read-only and link to their sessions.

> **Planned 2026-10-22**, checked against the code. Builds after M31, whose
> table pattern (`.table-scroll`) the stints and laps tables use on phones;
> **after M32**, which removes hand-edited stints (stints are then worked
> out from sessions' drivers, and the stint editor and its route are gone);
> and **after M33**, whose results send a merged session's rows under the
> merge's id, and `merged` (member id → merged session id). Revised the
> same day for §7: no stint editor here.

## Validated against the code (2026-10-31, after M36)

Each step re-read against the code as it stands after M32–M36:

- **The stint editor is gone (M32):** `RaceSection.svelte` has only the
  flags form (`{#if anyone}`); the "as set by hand" / "Split at every stop"
  lines are already gone, replaced by "A stint is one driver's run, from who
  drove each session: set on the session's page." M37.3 drops that line too,
  since each stint now links to its session; M37.4 only hides the flags form.
- **`merged` is sent but not typed:** the server's `EventResults.merged`
  (`EventResults.kt:148`, member id → merged id, M33.6) isn't in the site's
  `EventResults` type. M37.3 adds it. Race laps' `session` is the **member's**
  id (the race is built from records, `EventResults.kt:356`), so
  `stintSession` maps through `merged`, as planned.
- **The Session column** shows today only for a race in sessions, as the
  part's name. It now shows always, as the session's start ("08:40", or
  "Sun 18 Oct, 08:40" when the race's sessions are on different days, the
  M36.9 format), from that session's row in `results.parts` (`heardFrom`;
  a merged row carries the merge's id and its first member's time). Passed
  to `RaceSection` as a `sessionTime(id)` function.
- **The M36 terms:** `TermHelp` already sits on Stint, Median, Spread,
  Theoretical best and Stops; Stops moves to the new Stop column's header.
- **No duration on a session row:** `SessionResult` has `heardFrom` and laps,
  not an end. The Sessions panel (M37.6) shows the time, the laps and the
  best instead of a duration.
- **`LapChart`'s `driverName`** is used only by the stints sentence M37.5
  removes, so the prop goes too. The idle legend's value functions are
  extracted to `lib/lapChart.ts` (`lapLegend`, `carLegend`) and tested.
- **Several practice parts:** each part's own bests are its own, not a
  repeat of "Practice, every part", so each part's panel keeps them; with one
  practice part only its panel shows.
- **"Edit the race"** goes after the chart (under the standings and their
  chart), so the button doesn't split the table from its chart.

---

## What exists, and what it means for this (checked 2026-10-22)

- **`EventPage.svelte`** renders, in order: title, Edit / Download, the facts
  line, **"Practice, every part"** (`results.practiceBests` etc.) whenever
  there's any practice part, then `RaceSection`, then **every part** in
  `results.parts` as its own panel. A practice part repeats its bests table;
  a race part says "The race as one timeline is above; its sessions are
  here." and lists its sessions. With one practice part, the practice table
  appears twice (seen on NHMS).
- **Session rows are per driver's stretch:** the server sends one
  `SessionResult` per stretch (`EventResults.kt`, `stretch` from M27,
  `Results.stretches`), keyed on the page by `${s.id}-${s.stretch}`. Bests,
  sector rows and consistency are computed **on the server** from those rows,
  so folding them into one row per session on the page changes only the
  display. Client-side grouping by `id` is enough. Since M33.6 a merged
  session's rows carry the merge's id, so they fold into one row too.
- **`RaceSection.svelte`**, in order: a sentence about flags and counting;
  the standings table; `LapChart`; **the flags form** (`{#if anyone}`, line
  136, datetime inputs, Now, Save the flags); per car: the stints table
  (with a Session column for a race in sessions), "Theoretical best", "Stints
  as set by hand." / "Split at every stop…", **"Stops: lap 22, 1139.1 s in
  the pit lane · …"** (a sentence), the stint editor or its open buttons
  ("Edit Race Session 1's stints", line 205), and `<details>` "Every lap".
- **Empty stints come from edited marks:** `Race.kt:139–146` makes one stint
  per mark; a lap belongs to the last mark that began before it ended
  (line 141), so two marks with no lap end between them leave the first with
  no laps (`laps = 0`, `firstLap = null`). NHMS stints 6, 8 and 13 are these.
  They're real stored marks today; M32 turns them into sessions' driver
  changes and clears them. After it, a stint with no laps can still happen
  (a session's start driver in a session with no laps, a tablet-only
  minute), so the reading table still hides them. `stintsIn` (`lib/race.ts:93`) already skips them, which is
  why their Session cell is blank.
- **Stops:** `CarRace.stops` (`RaceStop(lap, entry, exit?, seconds?)`), the
  stop on `lap` = the in-lap. A stint ends at the stop whose `lap` is its
  `lastLap`, when it ends at one.
- **`LapChart.svelte`:** uPlot with `legend: { show: true }`; series 0's
  `value` returns `'--'` for null (line 59), so the idle legend reads
  "Lap: -- Tha Bearmobile: --". Under the chart, a sentence lists every stint
  ("Stints, shaded in turn: 1 Hugh Enxing (laps 1–60) · …", lines 93–96),
  repeating the table. Shading alternates two tints, not per driver.
- **Tests:** `lib/eventResults.test.ts`, `lib/race.test.ts`,
  `lib/lapChart.test.ts` exist.

## Decided here

- **Order:** title and buttons → facts → **The race** (standings, chart,
  then per car: stints table, theoretical best, every lap collapsed) →
  **Practice** → **Sessions**.
- **Practice once:** one practice part, only its panel (bests, analysis,
  sessions); several, "Practice, every part" first and then each part's
  panel without repeating its sessions' bests.
- **Sessions:** one panel, grouped by part (practice parts and race
  sessions alike), one row per session; its stretches as one line inside it
  ("Hugh Enxing 60 laps · Drew Blouch 4 laps"). The "timeline is above"
  sentence goes.
- **Stints table:** columns Stint, (Session), Driver, Laps, Time, Best,
  Median, Spread, **Stop** (the pit time after it, as `m:ss`, "ended in the
  pits", or blank). Stints with no laps hidden; under the table, "2 stints
  with no laps not shown" when any are hidden. **Each stint names its
  session and links to it**: the Session column reads "Sat 12:00" (the
  stint's first lap's session, or the merged session it's in) as a link,
  with the title "Edit drivers on this session". No driver is edited here.
  The "Stops: …" sentence and the chart's stints sentence go; the chart
  keeps its shading, with one caption "Shaded bands are stints; dashed lines
  are stops." (Not the review's driver → shade legend: the shading
  alternates two tints, not one per driver.)
- **"Edit the race"** (shown to whoever may set the race, as `anyone` now):
  one button under the standings; pressed, it shows the flags form (the
  stint editor is gone with M32). The flag/counting sentence moves into that
  panel, except one line kept public: "Laps count before the green flag and
  after the flag."
- **The chart's idle legend** shows "Hover a lap" for the lap and nothing
  for the cars.

## The steps

### M37.1 — Sessions folded per session (pure, tested)

`lib/eventResults.ts`: `sessionRows(rows: SessionResult[])` →
`{ id, car, name, heardFrom, live, otherLayout, stretches: {driver, laps, best}[] }[]`,
in first-heard order. Tests: a session without changes (one stretch), one
with three stretches, a merged session's rows (the merge's id, stretches
across members) as one row, live and other-layout flags carried.

### M37.2 — Stops per stint (pure, tested)

`lib/race.ts`: `stopAfter(car, stint)` → `RaceStop | null` (the stop whose
`lap` is the stint's `lastLap`), and `pitTime(seconds)` → `"5:57"`. Tests:
stints ending at a stop, at the race's end in the pits, and with no stop;
an empty stint (null `lastLap`).

### M37.3 — The stints table

`RaceSection`: the Stop column from M37.2; the Session column as a link to
the stint's session: its first lap's `session` (`c.laps` by number), mapped
through `results.merged` to the merged session when it's a member, so the
link needs no redirect (the car's slug from `c.car`); a pure
`stintSession(car, stint, merged)` in `lib/race.ts`, tested (plain, merged
member, empty stint → null); empty stints filtered (`laps > 0`) with the
count line; the stops sentence and "Split at every stop; merge a fuel stop…"
/ "Stints as set by hand." lines removed (M32 leaves one kind of stint).
Checked on the dev server: each Session link opens that session's page, and
a session with no laps gives a hidden empty stint and the count line; with
two of the race's sessions merged (M33.8), their stints link to the merged
page.

### M37.4 — "Edit the race"

`RaceSection`: an `editingRace` toggle; the flags form renders only while
it's on. Checked: signed out, no form control on the page; signed in as the
dev master, the button shows and the flags save.

### M37.5 — The chart's words

`LapChart`: series values return `''`/"Hover a lap" when null; the stints
sentence replaced by the one caption. A `lapChart.test.ts` case for the
legend's value function if it's extracted (it's a one-liner; else checked by
eye).

### M37.6 — The page's order, practice once, one sessions panel

`EventPage`: reorder; render "Practice, every part" only when there are two
or more practice parts; part panels show bests/analysis only (practice) and
nothing for race parts; a new "Sessions" panel renders `sessionRows` per
part as a list (not a table) whose rows wrap at phone width: the time and
duration, then the stretches line. Checked on the dev server's `m29-check`
event (one practice, two race sessions) and against a copy of NHMS's shape:
practice once, each session once (a merged one once), nothing else lost
(count the laps and sessions before and after); the phone check (M31)
passes for the event.

## Built (2026-10-31)

- **M37.1–M37.6 built** as validated above. Site tests 307 → 314
  (`sessionRows`, `stopAfter`, `pitTime`, `stintSession`, the legend).
- **Checked on production's data, signed out** (the new site through a
  local Vite pointed at the public API, read only): the race first, the
  practice table once (6 tables → 3), 3 session rows for 3 sessions (12
  before), 8 stints, each with its stop ("5:58", "ended in the pits" where a
  day ended in the pit lane) and its session's link; laps 460 before and
  after; no form control; the legend reads "Hover a lap".
- **On the dev server:** a race of four sessions, two of them merged: the
  merged session is one row and its stints link to the merged page; signed
  in, "Edit the race" opens the flags and they save; signed out, no button.
- **A stint with no laps no longer happens:** since M32/M34, a driver
  change snaps to a lap end, a second change at the same end replaces the
  first, a change after the last lap makes no stint, and a session with no
  laps makes no run. The filter and its count line stay as a cheap guard
  and are never seen.
- **Fixed in passing:** the facts line and a named session's link lost the
  space before "·" (Svelte trims a block's leading text), now `{' · '}`.
- **Process:** two Vite servers sharing `node_modules/.vite` leave the
  second's dependency cache stale ("504 Outdated Optimize Dep"), and the
  course editor drew nothing until the dev Vite was restarted.

## Acceptance

On the NHMS event, signed out: the standings and chart are the first panel;
the practice table appears once; each session appears once; the stints table
has no empty rows and shows each stop's time; there's no form control on the
page; each stint links to its session; signed in, "Edit the race" shows the
flags. Deployed
with a live stream across it.
