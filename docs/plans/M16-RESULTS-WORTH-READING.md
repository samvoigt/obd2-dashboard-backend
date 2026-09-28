# M16 — Results worth reading

The fifth milestone of race logging (`RACE-LOGGING.md`): what the laps
**mean**, not just what they were. **Every lap links to its moment** in its
session; the **theoretical best**, **sector comparisons** and **consistency**
for practice and the race; the race's **lap-time chart**; and **any two laps
compared** side by side, trace against trace.

Nothing here changes the contract, the tablet, or how anything is timed.

---

## Settled with Sam, 2026-09-28

- **The lap chart is lap times over the race** (not positions): a line per
  car, each lap's time by lap number, stints shaded by driver, stops and the
  flags marked, the lap across a restart flagged. Useful with one car, our
  usual case.
- **Comparing two laps is in M16**: any two laps (two drivers, or a driver's
  best against the event's), speed and the other signals overlaid by distance
  round the lap, the running time gained or lost, and both lines on the map.

---

## What exists, and what it means (checked 2026-09-28)

- **Laps everywhere carry their moment on the tablet's wall clock** (start,
  end): a session's laps as they stand (M13.5), practice results (M14.5),
  the race's laps (M15.2). **The session page works on the same `wall`**:
  its chart and map, and its lap table already zoom the chart to a lap. It
  **can't be linked to one**: nothing in its address picks a moment.
- **A session's prepared series** (M7.2) has every numeric signal and the
  positions, each on `wall`; the page fetches it whole (`fetchSeries`).
- **Courses** give each layout's path (the line cars take, closed), so a
  position can be **projected onto the layout**: its distance round the lap,
  the same for every car and driver whatever their line. That's what lines two
  laps up by distance.
- **Results** (`Results`, `Race`) already have each driver's best lap and the
  best of each sector (§22.6's in- and out-lap rule); nothing adds them up,
  compares drivers sector by sector, or says how consistent anyone was.
- **The chart** (`Chart.svelte`, uPlot) takes series against time, a zoom
  range, markers and shaded bands.

---

## Decided here (say if any is wrong)

- **A lap's link** is its session's page with the lap's moment in the address
  (`?from=…&to=…`, on `wall`, milliseconds): the chart zoomed to it, the map
  showing that stretch. Every lap in every table links so: practice, race,
  driver pages, bests.
- **The theoretical best** is the sum of the best of each sector (§22.6's
  rule): over the event's practice, per driver in practice, and per car and
  per stint in the race. Shown only when every sector has a best.
- **Sector comparisons**: per practice part and over all practice, a row per
  driver of their best in each sector and the gap to the best of all, and
  their theoretical best beside their best lap.
- **Consistency**, per driver in practice and per stint in the race, over
  **laps on track only** (no in- or out-laps, no lap across a restart): the
  laps counted, the best, the median, the spread (standard deviation), and how
  many were within 1% of the best.
- **The lap-time chart** (the race's section): by lap number; a lap slower
  than 130% of the car's best (the out-laps, the restart's) drawn at the top
  edge, marked, so the racing laps keep the scale; stints shaded by driver.
- **Comparing two laps**, at `/compare` (public, both laps in its address):
  - both laps' positions **projected onto the layout**, so distance is along
    the course's own line; each lap's signals resampled every metre;
  - charts by distance, one per signal both laps have (speed first), the two
    laps overlaid; **the running delta**: at each metre, how far ahead or
    behind the second lap is in time;
  - the map with both laps' lines, and the cursor's metre on both;
  - done in the browser from the two sessions' series (no new server work
    beyond the address), and pure where it can be (`lib/compare.ts`).
  - **Entry points**: "compare" on any lap in results (against the event's
    best, or pick a second lap), and from a driver's page.

---

## The steps

Each validated against the code just before it's built, the validation
written here.

### M16.1 — Every lap linked to its moment

The session page reads `?from=&to=` (the chart zoomed, the map drawing that
stretch); a lap link helper; links from every lap in practice results, the
race's laps, stints' best laps, driver pages and the session's own lap table
(so a zoomed view can be shared).

**Done when:** tests for the link and the address read back; looked at in
Chrome: a race lap's link opening its session zoomed to it.

### M16.2 — Theoretical best, sector comparisons, consistency

Pure additions to `Results` (practice) and `Race` (stints, cars): the
theoretical best, sector rows per driver with gaps, consistency; in
`GET /api/events/{id}`; tables on the event page, and the driver page's.

**Done when:** tests (a theoretical best only with every sector, never an in-
lap's last sector; gaps to the best; consistency over laps on track only,
median and spread to the millisecond); looked at in Chrome.

### M16.3 — The lap-time chart

A chart in the race's section: lap times by lap number, per car; slow laps at
the top edge, marked; stints shaded; stops, the flags and the restart lap
marked.

**Done when:** tests for the chart's data (the cap, the marks, the shading);
looked at in Chrome on the generated race (M15's).

### M16.4 — Two laps compared

`lib/compare.ts` (projection onto the layout, distance, resampling each metre,
the running delta), the `/compare` page (charts by distance, the delta, the
map), and "compare" on laps in results and on driver pages.

**Done when:** tests on the box course with known answers (a lap projected to
its true distances; two laps at 40 and 38 m/s: the delta grows as it should
and ends at the lap-time difference; a lap on another line projected to the
same distances); looked at in Chrome: two drivers' laps compared.

### M16.5 — Deploy, and prove it

Deployed with a stream across it (a second throwaway car's, under
`caffeinate -i`). **Proof in production:** a throwaway car, a test course, a
test event with practice for two test drivers and a race; the tables, the lap
chart, a lap's link, and two laps compared on the live site. All removed after.

### M16.6 — Record it

A decision for what results show; `COMPLETED`, `JOURNAL`, `PLAN`, `README`;
this plan deleted.

---

## Not in M16

- Anything live, or to the tablet (M17).
- Positions per lap (Sam chose lap times); other teams' cars.
- Comparing more than two laps at once.
