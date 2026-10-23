# M34 — The event page: results first, each thing once, admin tools tucked away

Phase 5 of the UX review (`plans/UX-REVIEW.md` §3.E, §4 phase 5). The event
page reads top to bottom as a visitor wants it: the standings, the lap chart,
the stints with their stops, every lap, practice, then the sessions. Nothing
appears twice, no empty rows, and the flags form and stint editor sit behind
one "Edit the race" button. **Sam, 2026-10-22:** the review's order and scope
("restructure freely, keep the look").

> **Planned 2026-10-22**, checked against the code. Builds after M30, whose
> table pattern (`.table-scroll`) the stints and laps tables use on phones.

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
  display. Client-side grouping by `id` is enough.
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
  They're real stored marks: the editor must still show them; the reading
  table needn't. `stintsIn` (`lib/race.ts:93`) already skips them, which is
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
  pits", or blank). Stints with no laps hidden; under the table, "2 stint
  marks have no laps" when any are hidden, linking to the editor. The "Stops:
  …" sentence and the chart's stints sentence go; the chart keeps its
  shading, with one caption "Shaded bands are stints; dashed lines are
  stops."
- **"Edit the race"** (shown to whoever may set the race, as `anyone` now):
  one button under the standings; pressed, it shows the flags form and each
  car's "Edit … stints" buttons. The flag/counting sentence moves into that
  panel, except one line kept public: "Laps count before the green flag and
  after the flag."
- **The chart's idle legend** shows "Hover a lap" for the lap and nothing
  for the cars.

## The steps

### M34.1 — Sessions folded per session (pure, tested)

`lib/eventResults.ts`: `sessionRows(rows: SessionResult[])` →
`{ id, car, name, heardFrom, live, otherLayout, stretches: {driver, laps, best}[] }[]`,
in first-heard order. Tests: a session without changes (one stretch), one
with three stretches, live and other-layout flags carried.

### M34.2 — Stops per stint (pure, tested)

`lib/race.ts`: `stopAfter(car, stint)` → `RaceStop | null` (the stop whose
`lap` is the stint's `lastLap`), and `pitTime(seconds)` → `"5:57"`. Tests:
stints ending at a stop, at the race's end in the pits, and with no stop;
an empty stint (null `lastLap`).

### M34.3 — The stints table

`RaceSection`: the Stop column from M34.2; empty stints filtered
(`laps > 0`) with the count line; the stops sentence removed. Checked on the
dev server with edited stints that include a mark with no laps.

### M34.4 — "Edit the race"

`RaceSection`: an `editingRace` toggle; the flags form and the per-car
"Edit stints" buttons render only while it's on; the stint editor opens from
there as now. Checked: signed out, no form control above "Every lap";
signed in as the dev master, the button shows and both tools work.

### M34.5 — The chart's words

`LapChart`: series values return `''`/"Hover a lap" when null; the stints
sentence replaced by the one caption. A `lapChart.test.ts` case for the
legend's value function if it's extracted (it's a one-liner; else checked by
eye).

### M34.6 — The page's order, practice once, one sessions panel

`EventPage`: reorder; render "Practice, every part" only when there are two
or more practice parts; part panels show bests/analysis only (practice) and
nothing for race parts; a new "Sessions" panel renders `sessionRows` per
part. Checked on the dev server's `m29-check` event (one practice, two race
sessions) and against a copy of NHMS's shape: practice once, each session
once, nothing else lost (count the laps and sessions before and after).

## Acceptance

On the NHMS event, signed out: the standings and chart are the first panel;
the practice table appears once; each session appears once; the stints table
has no empty rows and shows each stop's time; there's no form control on the
page; signed in, "Edit the race" shows the flags and stints tools. Deployed
with a live stream across it.
