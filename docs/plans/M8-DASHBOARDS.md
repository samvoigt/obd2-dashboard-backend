# M8 — The dashboard

A car's page becomes a dashboard: **one fixed layout for every car**, updating
in real time. Gauges, big numbers and bars, the G-meter, the map, laps, status
lights and fault codes, then every other signal as a number, as today.

**Not configurable** (Sam, 2026-09-27): no dashboard storage, no editor, no
per-car settings. **Not a mirror of the tablet** either, so no contract change.
Sam picks which signals fill the layout's slots, at a checkpoint in M8.2.

---

## Settled with Sam, 2026-09-27

1. **One fixed layout**, the same for every car, designed in code. Per-car
   choices (which signal in each gauge, ranges and redlines) could come later
   as a small form on the admin page, if the generic ranges bother him.
2. **Sam chooses the signals for the slots** when the time comes: a checkpoint
   in M8.2, starting from the proposal below.
3. **Units: a switch each viewer sets**, metric or US (mph, °F, psi…), kept in
   their browser, converting every reading.
4. **No chart widget.** The page keeps today's chart, with its "Whole session"
   view, below the dashboard.

---

## What exists, and what it means for this

- **The live stream is what the dashboard needs.** A car's SSE stream sends a
  snapshot (the latest reading of every signal, stopped signals, faults, and 5
  minutes of history), then every record as it comes, with freshness
  (decision 19). `live.ts` folds it into `LiveState`. The dashboard reads the
  same state, so **the server doesn't change**.
- **Laps are never in a snapshot** (§16): they come in batches, and in the
  archive. So the lap panel reads M7's merge (the archive's series plus the
  live records after it), as the "Whole session" chart does.
- **To reuse:** the tile format (`format()`, by kind), `SessionMap.svelte`
  (Leaflet), the lap table (`lapRows`), the merge (`merge.ts`). The G-meter is
  the two `motion.acceleration.*` signals (§4.2); position is `gps.*` (§4.3).
- **The contract gives units, not ranges**, and the server never holds facts
  about a particular vehicle in its code (the app's decision 33). So a gauge's
  range and redline come **from its unit**, generically (`rpm` 0–8,000, `km/h`
  0–250, `°C` −40–150…), never from which car it is.
- **A signal a car doesn't send** leaves its slot showing "—" (never sent) or
  "stopped" (the tablet said so), so the layout doesn't jump around between
  cars.
- **Performance:** batches arrive every 200 ms. The live page redraws its
  chart at most every 500 ms; a dashboard of a dozen widgets must keep up on a
  phone.

---

## The layout

On a wide screen, top to bottom; on a phone, the same order, one column:

1. **The freshness banner** and session line, as today.
2. **Gauges**, a row of up to four dials, each with its reading as a number
   underneath.
3. **Numbers**, a row of up to six big readings.
4. **Bars**, up to four fill bars (fuel, throttle and the like).
5. **The G-meter** beside **the map** (each hidden if the car sends no
   `motion.*` or `gps.*`).
6. **Laps**: the last lap, the best lap (never a pit lap) and the difference,
   then the lap table (hidden with no laps).
7. **Status**: lights for flag and state signals (the MIL, the fuel system),
   and the current fault codes.
8. **The crew message panel**, as today.
9. **Today's chart**, with "Last 5 minutes | Whole session".
10. **Every other signal** as a tile, as today.

**Proposed slots** (Sam decides at M8.2):
- gauges: `engine.rpm`, `vehicle.speed`, `engine.coolant_temperature`,
  `engine.oil_temperature`;
- numbers: `control_module.voltage`, `intake.air_temperature`,
  `engine.load`, `fuel.rate`, `ambient.air_temperature`, `gps.speed`;
- bars: `fuel.tank_level`, `accelerator.relative_pedal_position`,
  `engine.throttle_position`;
- status: `diagnostics.mil`, `fuel.system_1_status`, and the fault codes.

Every name is in the contract's catalogue (checked 2026-09-27).

---

## Decided here, not asked (say if any is wrong)

- **The slots are a list in one file** (`dashboard.ts`), with each gauge's
  range and redline by unit. Changing a slot later is a one-line change and a
  deploy.
- **A signal in a slot isn't repeated** in the tiles at the bottom.
- **Warning colours**, generic by unit: amber and red zones for engine speed,
  temperatures and voltage (for example coolant over 105 °C amber, over 115 °C
  red; voltage under 12.0 V amber). Only colour; nothing notifies anyone.
- **Every reading shows when it's out of date**: grey once overdue by five
  times its usual interval, "stopped" if the tablet said so. The same rule as
  gaps (decision 26).
- **The G-meter:** a dot in a circle (1.5 g full scale, in g whatever the
  units), a 5-second trail, and the session's peaks in each direction since
  the page opened.
- **The map:** the car's position now and its trail over the last 5 minutes,
  following the car unless the viewer has moved the map.
- **Units:** conversions for each unit in the catalogue with a US counterpart
  (km/h→mph, °C→°F, kPa→psi, km→mi, L→gal, L/h→gal/h, m→ft); anything else is
  shown as sent. Ranges and warning zones convert with them.
- **Redrawing:** numbers, gauges and bars at most 10 times a second; the map
  and the G-meter trail at most twice a second; in one animation-frame loop,
  paused while the page is hidden.

---

## The steps

### M8.1 — The widgets

`web/`: Gauge, Number, Bar, Status light, Faults, G-meter, Live map, Laps
panel, and the units switch. Pure logic in `dashboard.ts` and `units.ts`, with
Vitest:
- the needle's angle and a range's zones;
- conversions (to and from, for ranges);
- staleness;
- the G-meter's scale, trail and peaks;
- the lap panel's last, best and difference.

**Done when:** Vitest and a type check, and every widget looked at in Chrome on
a scratch page, with a replay streaming.

### M8.2 — The page

**Checkpoint first: Sam picks the slots' signals**, from the proposal and the
signals his car actually sends. Then the car's page is rebuilt in the layout's
order, fed by the live stream, with the tiles below skipping what the
dashboard shows.

**Done when:** looked at in Chrome with a real log and the synthetic race
streaming: every section; a car that sends no GPS or G-meter (sections hidden);
a signal stopping; US units; phone width.

### M8.3 — Performance

The page streaming the synthetic race (every section busy), on a desktop and at
phone size with the CPU slowed 4×, measured over 30 minutes: frames per
second, time per frame, memory.

**Done when:** it holds 30 fps on the slowed profile with no memory growth, or
what it costs is found and fixed.

### M8.4 — Deploy, and prove it live

Deploy, with a drive streaming across it; a throwaway car; the synthetic race
streaming and uploading; the page in Chrome on badnewsbears.live, US units,
phone width; then clean up.

**Done when:** every step passes on the deployed site, with screenshots kept.

### M8.5 — Record it

A decision (the fixed layout, its slots and generic ranges), `COMPLETED.md`,
`JOURNAL.md`, `PLAN.md`, README, `CLAUDE.md`. This plan deleted, and pushed.

---

## Not in M8

- **Configuring the layout** (Sam: fixed for now). A per-car form for the
  slots, ranges and redlines is the natural next step if wanted.
- **A chart widget** (the page keeps today's chart).
- **Alerts that notify someone** (sounds, push notifications).
- Comparing laps (a later milestone).
