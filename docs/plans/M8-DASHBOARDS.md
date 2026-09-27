# M8 — The dashboard

A car's page becomes a dashboard: **one fixed layout for every car**, updating
in real time. Gauges, big numbers and bars, the G-meter, the map, laps, status
lights and fault codes, then every other signal as a number, as today.

**Not configurable** (Sam, 2026-09-27): no dashboard storage, no editor, no
per-car settings. **Not a mirror of the tablet** either, so no contract change.
Sam picks which signals fill the layout's slots, at a checkpoint in M8.3.
**The whole site takes the Bad News Bears look** from the team's logo (M8.1).

---

## Settled with Sam, 2026-09-27

1. **One fixed layout**, the same for every car, designed in code. Per-car
   choices (which signal in each gauge, ranges and redlines) could come later
   as a small form on the admin page, if the generic ranges bother him.
2. **Sam chooses the signals for the slots** when the time comes: a checkpoint
   in M8.3, starting from the proposal below.
3. **Units: a switch each viewer sets**, metric or US (mph, °F, psi…), kept in
   their browser, converting every reading.
4. **No chart widget.** The page keeps today's chart, with its "Whole session"
   view, below the dashboard.
5. **The site's colours come from the Bad News Bears logo**, the app's
   `docs/branding/bnb-logo.pdf` (Sam, 2026-09-27). **Only looked at, never
   changed**: nothing outside this repo is modified.

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

- **The logo** (read only): hot pink, sky blue, mint and a light pink,
  on white. The tablet app already reads the same four from the PDF's own
  fills: pink `#FF0099`, blue `#01B7F9`, mint `#67EFE6`, light pink `#FFA9DE`.
  The site uses the same values, so a colour means the same thing on the
  tablet and on the web. The app's rule (its decision 100) is followed too:
  the logo's colours for the accent, "in range", caution and critical, **no
  amber or red**, and the dark neutrals kept, since a dash is dark.
- **The site's colours today** are all in `web/src/app.css`'s variables
  (`--live` green, `--stale` amber, `--idle` blue, `--danger` red…), except
  for a few written directly in components (the chart's series, the map's
  speed scale, the markers). So the look is mostly one file, plus those few.

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

**Proposed slots** (Sam decides at M8.3):
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
- **Warning zones**, generic by unit, in the look's caution (light pink) and
  critical (hot pink): engine speed, temperatures and voltage (for example
  coolant over 105 °C caution, over 115 °C critical; voltage under 12.0 V
  caution). Only colour; nothing notifies anyone.
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

### M8.1 — The Bad News Bears look

The whole site, before the widgets, so they're built in it:
- **Roles, not colours**, in `app.css`, as the app has them:
  - accent (links, buttons, the chosen tab): sky blue;
  - in range, and "live": mint;
  - caution, and "behind": light pink;
  - critical, errors, trouble codes: hot pink;
  - "connected, no session": the accent;
  - offline and no data: grey;
  - background, panels, lines, text: the dark neutrals, as today.
- **Every colour written directly in a component** moves to a role: the
  chart's series (mint, blue, pink), its markers and shading, the map's speed
  scale (blue for slow, through mint, to pink for fast), the admin page's
  buttons.
- **The logo on the landing page**, and a small bear as the browser tab's icon,
  both made from the PDF **into this repo** (`web/public/`), by a script kept
  here so they can be made again. The PDF itself is only read.
- **Legibility checked**, as the app does: a Vitest test fails if text,
  caution, critical or in range reads under 3:1 on the background or a
  panel, or if caution and critical look alike (closer than 25 in CIE Lab).

**Done when:** that test; no colour left outside `app.css` (a test searches
the components); looked at in Chrome: the landing page, a car's page streaming
(live, behind, offline), a past session with its map, the admin page, phone
width.

> **Validated against the code, 2026-09-27, before building.**
> - **The variables are renamed to roles**, as the app names them: `--live` →
>   `--in-range` (mint), `--stale` → `--caution` (light pink), `--idle` →
>   `--accent` (blue), `--danger` → `--critical` (hot pink), `--offline` →
>   `--no-data` (grey). `--bg`, `--panel`, `--line`, `--text` and `--muted`
>   stay, set to the app's neutrals (`#0A0B0D`, `#16181C`, `#F2F4F7`,
>   `#9AA3AE`), which are nearly the site's already.
> - **About 20 colours are written in components**: the chart's lines, axes
>   and grid; its markers and shading; the map's dot, background and speed
>   scale. The chart (uPlot) and the map (Leaflet) draw on canvas and need
>   real colour strings, not `var(--…)`, so **`theme.ts` reads the variables at
>   run time** (and makes a translucent version for shading). `speedColor`
>   takes its colour stops as an argument, so it stays pure: blue for slow,
>   through mint and light pink, to hot pink for fast.
> - **The chart's lines:** mint, then blue. Markers: faults critical, gaps
>   caution, stopped signals muted. Shading: caution, translucent.
> - **Images:** the server serves only `/assets` (and the pages), so the logo
>   and the tab's bear go in `web/src/assets/`, imported so Vite puts them
>   there with hashed names. `web/scripts/make_images.sh` makes them with
>   macOS's `sips` (to render the PDF) and ImageMagick, as the app's own icon
>   script does. It **reads** the PDF where it lies and writes only here.
> - **The tests read `app.css` itself**, so the colours have one source: the
>   legibility test (WCAG contrast, and CIE Lab distance between caution,
>   critical, in range and the accent), and a scan of `src/` for any colour
>   written outside it.

> **✅ Done, 2026-09-27.**
> - **New:** `app.css`'s roles, `theme.ts`, `speedColor` on colour stops,
>   `web/scripts/make_images.sh` (reads the PDF, writes only here),
>   `src/assets/logo.webp` (118 KB) and `bear.png`, the logo on the landing
>   page, the bear as the tab's icon.
> - **Tests:** 17 new (77 in all), including the legibility checks and the
>   scan, which found nothing left outside `app.css`. Each guard was shown to
>   fail when it should: a colour written into a component, `rgb()` in one, a
>   critical made to look like caution, unreadable text, a blue that isn't the
>   logo's.
> - **Looked at in Chrome** against the dev server: the landing page with the
>   logo; a car's page streaming (live mint, behind light pink, offline grey,
>   read from the page's own classes); a past session (the trace blue through
>   mint to pink, the fault code hot pink, the markers); the admin page (blue
>   buttons, pink Remove and Delete, mint "Live now"); 390 px wide.
> - **Found by looking:**
>   - **The logo came out muted** (`#EA3396` for `#FF0099`): `sips` renders
>     into Display P3 and tags the image with that profile, and stripping the
>     profile made browsers read it as sRGB. The script converts to sRGB first;
>     the pink is now `#FE0098`.
>   - **Mint and blue, as two thin chart lines, were hard to tell apart**,
>     though far enough apart as solid colours. The second line is light pink.
> - **Found by the type check:** the test's use of Node's file functions isn't
>   in the site's types. It reads through Vite instead (`?raw`,
>   `import.meta.glob`), and Vitest is told to hand `app.css` over rather than
>   blank it, as it does every CSS file by default.

### M8.2 — The widgets

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

> **Validated against the code, 2026-09-27, before building.**
> - **The contract's units** (§4.1): °C, kPa, km/h, km, g/s, L/h, m (GPS),
>   m/s² (the G-meter), %, V, mA, s, rpm, °. US counterparts: °F, psi, mph,
>   mi, gal/h, ft. **No litres or gallons**: the catalogue has no volume (fuel
>   is a percentage), so that conversion is dropped. g/s stays as sent. The
>   G-meter shows g whatever the switch says.
> - **Warning zones are per signal, not per unit**: "over 105 °C" is right for
>   coolant and wrong for outside air, which is also °C. They're generic
>   engine knowledge (coolant, oil, voltage, engine speed), never one car's, so
>   the app's decision 33 holds. **Ranges** default by unit, and a signal can
>   have its own (outside air −20 to 50 °C, voltage 10 to 16 V).
> - **Out of date:** each signal's usual interval comes from the page's 5
>   minutes of history (its median gap), on the server's clock (`LiveState`'s
>   offset), as the whole car's freshness is. Stale once the last reading is 5
>   times that old (never under 2 s); "stopped" if the tablet said so; "—" if it
>   never came.
> - **The live map** reuses `SessionMap`'s drawing but follows the car (the
>   latest position, a 5-minute trail) unless the viewer has dragged or zoomed
>   it; so `SessionMap` gains a `follow` option rather than a second map.
> - **Laps** come from M7's merge (the archive's series, then the live
>   history), as "Whole session" does, re-checked each minute: the last lap is
>   the highest-numbered, and the difference is last minus best.
> - **The scratch page is dev-only**: a `preview` route in `routes.ts` that
>   exists only when `import.meta.env.DEV`, so Vite drops it from the build,
>   and the server never serves `/dev/…` anyway.

> **✅ Done, 2026-09-27.**
> - **New:** `units.ts`, `unitsState.svelte.ts`, `readout.ts`, `dashboard.ts`
>   (slots, profiles, zones, `timings`, freshness, the G-meter, the lap
>   summary); the widgets `Gauge`, `Readout`, `Bar`, `Status`, `Faults`,
>   `GMeter`, `LapsPanel`, `UnitsSwitch`; `SessionMap`'s `follow`; the dev-only
>   `/dev/widgets/{slug}`.
> - **Tests:** 17 new (94 in all). Mutations: 17, 16 killed once a test for
>   off-scale zones was added. The equivalent one is decimals by the sent unit
>   rather than the shown one, which today are the same for every converted
>   pair.
> - **Looked at in Chrome**, with a synthetic race (both G axes, GPS, laps, a
>   fault, the MIL) streaming and uploading:
>   - gauges with their zones; coolant near caution;
>   - numbers, the fuel bar;
>   - the G-meter's dot, trail and peaks;
>   - the map following the car;
>   - laps (last, best never a pit lap, the difference in light pink);
>   - the MIL on in hot pink, the fuel system's words, P0301;
>   - US units (mph, °F, the dial's scale converted, the choice remembered);
>   - the stream stopped: every reading stale within 4 s, "never" for signals
>     it didn't send.
> - **Found by looking**, all fixed:
>   - **The page hung the browser.** First, the peaks effect wrote what it
>     read, so it reran itself (now `untrack`ed). Then, even without that, 15
>     widgets each scanned the 5 minutes of history (about 10,000 records)
>     every 100 ms, through the deep proxies Svelte puts on `$state`: millions
>     of proxied reads a second. Now **`timings()`** works out every signal's
>     last reading and usual gap **in one pass per batch**, and the live state
>     is **`$state.raw`** (every update replaces it whole, so it needs no
>     proxies). **The car's page must do both** (M8.3).
>   - **The map drew nothing** in follow mode: Leaflet can't place a line on a
>     map without a view, so the view is now set before drawing.
>   - **The preview reached the production bundle** through a static import;
>     it's now loaded lazily behind `import.meta.env.DEV`, and the build is 18
>     KB smaller without it.
>   - The first synthetic race had no longitudinal G, so the G-meter (which
>     needs both axes, as the tablet sends) said "No readings"; the race was
>     regenerated with both.
>
>   A hung tab also stuck Chrome's automation until Sam closed the tabs.

### M8.3 — The page

**Checkpoint first: Sam picks the slots' signals**, from the proposal and the
signals his car actually sends. Then the car's page is rebuilt in the layout's
order, fed by the live stream, with the tiles below skipping what the
dashboard shows.

**Done when:** looked at in Chrome with a real log and the synthetic race
streaming: every section; a car that sends no GPS or G-meter (sections hidden);
a signal stopping; US units; phone width.

### M8.4 — Performance

The page streaming the synthetic race (every section busy), on a desktop and at
phone size with the CPU slowed 4×, measured over 30 minutes: frames per
second, time per frame, memory.

**Done when:** it holds 30 fps on the slowed profile with no memory growth, or
what it costs is found and fixed.

### M8.5 — Deploy, and prove it live

Deploy, with a drive streaming across it; a throwaway car; the synthetic race
streaming and uploading; the page in Chrome on badnewsbears.live (the new
look throughout), US units, phone width; then clean up.

**Done when:** every step passes on the deployed site, with screenshots kept.

### M8.6 — Record it

Decisions (the fixed layout, its slots and generic ranges; the Bad News Bears
look), `COMPLETED.md`,
`JOURNAL.md`, `PLAN.md`, README, `CLAUDE.md`. This plan deleted, and pushed.

---

## Not in M8

- **Configuring the layout** (Sam: fixed for now). A per-car form for the
  slots, ranges and redlines is the natural next step if wanted.
- **A chart widget** (the page keeps today's chart).
- **Alerts that notify someone** (sounds, push notifications).
- Comparing laps (a later milestone).
