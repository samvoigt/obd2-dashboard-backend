# M8 — Dashboards

Dashboards configured on the site, per car, updating in real time: gauges,
big numbers, charts, the G-meter, the map, laps. **Not a mirror of the
tablet** (Sam, 2026-09-27): the site has its own layouts, so no contract
change is needed.

---

## Questions for Sam

1. **Who can edit a dashboard:** only admins (signed in with Google), or
   also anyone with the car's crew passcode? Viewing stays public.
2. **Units:** the tablet sends metric (km/h, °C, kPa, contract §4.1). Should
   the site offer US units (mph, °F, psi)? Proposed: a switch each viewer sets
   for themselves, kept in their browser, converting every widget.
3. **Widgets for the first version:** proposed below. Anything to add or drop?
4. **Where dashboards live:** proposed, the car's page shows its chosen
   dashboard, and today's page becomes a built-in one ("All signals"). Or keep
   the car's page as it is, and put dashboards on a page of their own.

---

## What exists, and what it means for this

- **The live stream is what a dashboard needs.** A car's SSE stream sends a
  snapshot (the latest reading of every signal, stopped signals, faults, and 5
  minutes of history), then every record as it comes, with freshness
  (decision 19). `live.ts` folds it into `LiveState`. A dashboard reads the same
  state; no new server stream is needed.
- **Whole sessions (M7):** the merge of the archive and the live lane gives
  laps and long charts. Laps come **only** in batches and in the archive,
  never in a snapshot (§16), so a lap widget reads the merge.
- **Widgets to reuse:** `Chart.svelte` (with zoom, bands, markers), the tile
  format (`format()`, by kind), `SessionMap.svelte` (Leaflet), the lap table
  (`lapRows`). The G-meter is the two `motion.acceleration.*` signals
  (§4.2), and GPS is `gps.*` (§4.3).
- **The contract gives units, not ranges.** A gauge needs a minimum, a
  maximum and warning zones, so those are part of each widget's settings,
  with defaults by unit (`rpm` 0–8,000, `km/h` 0–250, `°C` −40–150…).
- **Stores:** Firestore, as for cars and messages. A dashboard is a small
  document.
- **Performance:** batches arrive every 200 ms. The live page redraws its
  chart at most every 500 ms; a dashboard with a dozen widgets must do the
  same on a phone.

---

## Decided here, not asked (say if any is wrong)

- **Several named dashboards per car**, one marked as its default. Each is a
  Firestore document: `car`, `name`, `order`, and `widgets`.
- **A widget** has an id, a type, its place on a **12-column grid** (`x`,
  `y`, `w`, `h`), and its settings: the signal or signals, a label, and for
  number widgets a range and warning zones. Unknown settings are kept, so an
  older page doesn't lose what a newer one saved.
- **On a phone**, widgets stack in reading order (top to bottom, left to
  right), full width, whatever the grid says.
- **Proposed widgets** (question 3):
  - **Number:** a big reading with its unit, coloured by its warning zones.
  - **Gauge:** a dial with a needle, its range and a redline.
  - **Bar:** a horizontal or vertical fill (fuel, throttle, temperatures).
  - **Chart:** one to three signals over the last 1 or 5 minutes, or the whole
    session.
  - **G-meter:** the lateral and longitudinal acceleration as a dot in a
    circle, with a short trail and the session's peaks.
  - **Map:** the car's position now, with its trail, on OpenStreetMap.
  - **Laps:** the last lap, the best lap and a delta, and the lap table.
  - **Status:** a state or flag signal as a word or light (the MIL, the fuel
    system), and the current fault codes.
  - **Text:** a heading or a note, for arranging a page.
- **Every widget shows when its data is stale**: grey after its signal's
  usual interval five times over, and "stopped" if the tablet said so. Same
  rule as gaps (decision 26).
- **Edit mode**, for whoever question 1 allows: add a widget, choose its
  signal from the car's signals, set its range and zones, drag to move,
  drag a corner to resize, delete; then save. Nothing changes for viewers until
  saved. Last save wins, and the page says so if someone else saved first
  (a version number on the document).
- **Redrawing:** numbers, gauges and bars at most 10 times a second; charts,
  the map and the G-meter trail at most twice a second; all in one animation
  frame loop, paused while the page is hidden.
- **A car with no dashboards** shows a generated one: engine speed, road
  speed and coolant temperature as gauges, a chart, then every other signal
  as a number, much like today's page.

---

## The steps

### M8.1 — The dashboard model and its API

`:dashboards`, pure: the `Dashboard` and `Widget` types, validation (types,
grid bounds, at most 60 widgets, names, sizes), and a `DashboardStore` with
an in-memory fake. Firestore in `:archive-gcp`. Routes:
- `GET /api/cars/{slug}/dashboards`, `GET /api/dashboards/{id}` (public);
- create, save (with its version), reorder, set default, delete (editors
  only, per question 1).

**Done when:** tests for validation, versions (a stale save refused), editors
only, unknown settings kept; a live Firestore smoke; mutations checked.

### M8.2 — The dashboard page, read-only

The page (per question 4): the dashboard's widgets on the grid, stacked on a
phone, fed by the live stream through `LiveState`; a dashboard picker;
freshness for the whole car as today. Number, Gauge, Bar, Status and Text
widgets. Units per question 2.

**Done when:** Vitest for the pure parts (ranges, zones, staleness, unit
conversion, reading order); looked at in Chrome with a replay streaming;
phone width.

### M8.3 — Charts, the G-meter, the map, laps

The four widgets that need history: the chart (with the last-minutes and
whole-session windows, on M7's merge), the G-meter, the map, and laps (from
the merge).

**Done when:** Vitest; looked at in Chrome with the synthetic race streaming
and uploading.

### M8.4 — Editing

Edit mode: add, configure, move, resize, delete, save; rename, reorder, set
default, delete dashboards. Conflicts shown. Only for editors.

**Done when:** Vitest for grid placement (moves, resizes, collisions,
reading order); looked at in Chrome: every action, a conflicting save, a
viewer seeing the change on reload.

### M8.5 — Performance

A dashboard of 20 widgets, streaming, on a desktop and at phone size with the
CPU slowed. Measured: frames per second, time per frame, memory over 30
minutes.

**Done when:** it holds 30 fps on the slowed phone profile with no memory
growth, or what it costs is recorded and fixed.

### M8.6 — Deploy, and prove it live

Deploy; a throwaway car; build a dashboard on the deployed site (Sam signs in,
if question 1 makes editing admin-only); a replay streaming; phone width; then
clean up.

**Done when:** every step passes on the deployed site, with screenshots kept.

### M8.7 — Record it

Decisions, `COMPLETED.md`, `JOURNAL.md`, `PLAN.md`, README, `CLAUDE.md`. This
plan deleted, and pushed.

---

## Not in M8

- Mirroring the tablet's layout (Sam: not needed).
- Alerts that notify someone (sounds, push notifications). Warning zones only
  colour the widget.
- Comparing laps (a later milestone).
- Sharing a dashboard between cars. Each car has its own; copying one could
  come later.
