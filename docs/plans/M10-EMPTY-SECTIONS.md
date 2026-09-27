# M10 — The G-meter and map shown before their data

On a car's page, the **G-meter and the map are always there**: the G-meter's
rings saying "No readings", and a map of the world saying "Waiting for GPS",
until the first reading, when each comes alive as now. **Sam, 2026-09-27**,
after the tablet's first real connections sent neither: an empty box says
"nothing has come", where a hidden one says nothing at all.

This **amends decision 28**, which hid a section until its data came.

---

## What exists, and what it means for this (checked 2026-09-27)

- **The page hides both** (`CarPage.svelte`): the section appears only if a
  `motion.acceleration.*` reading is in the 5-minute history (`hasG`) or a
  `gps.position` with numeric `lat` and `lon` is (`positions`), and each
  widget only with its own.
- **The G-meter already has an empty state.** With an empty trail it draws
  its rings, no dot, and "No readings" (`GMeter.svelte`); the page already
  passes `stale` when the trail is empty. So it only needs showing always.
  When readings stop, the trail empties (it's the last 5 seconds) and it goes
  back to "No readings", faded: right for a stream that has stopped.
- **The map has no empty state.** `SessionMap` sets its view only from
  positions; with none it returns before setting one, and Leaflet draws
  nothing but a grey box (found in M8.2: no view, no drawing). It needs:
  - **a default view when following and empty**: the whole world, zoomed
    out. Not a place: the site holds no facts about a car or where it lives
    (the app's decision 33, followed here). *Not chosen:* the car's last
    known position from its latest session, which would need a new API; the
    Outback has no sessions yet, so it gains nothing today;
  - **that view marked as the page's own move.** The map calls any move it
    didn't mark the viewer's (M8.4's fix sets the mark around each call), and
    an unmarked default view would switch following off before the first
    fix. With the mark, the first position still sets the view on the car at
    street zoom, as now (`fitted` stays false until then);
  - **a "Waiting for GPS" label** over it while there are no positions.
- **The session page** uses the same map without `follow`, and only when a
  session has positions. It doesn't change: the default view applies only
  when following.
- **Laps stay hidden without laps**: they mean something only at a track.
- **No new pure logic**, so no new unit tests: the change is which widgets
  the page draws, and one view in the map. The proof is looking at it, and
  the existing 99 tests still passing. Mutation-checking applies to the one
  condition added in the map (default view only when following and empty),
  by looking: removed, the session page would open on the world.

---

## The steps

### M10.1 — Build

- `CarPage.svelte`: the G-meter and map section always drawn; the G-meter
  always; the map always, given the positions it has (possibly none).
- `SessionMap.svelte`: when following with no positions, the world view
  (set as the page's own move) and "Waiting for GPS" over it.
- `WidgetsPreview.svelte` (dev only): the same, so the preview matches.

**Done when:** type check and tests; looked at in Chrome against the dev
server:
- a car with no stream: G-meter "No readings", the world map "Waiting for
  GPS", following not switched off;
- the Outback's real evening drive replayed (it has no GPS or G): both empty
  beside live gauges;
- the synthetic race: the map jumps to the car on the first fix and follows;
  the G-meter comes alive; drag, then "Follow the car", still works;
- the race stopped: the G-meter back to "No readings", faded;
- a past session's page: its map opens on the route, not the world;
- 390 px wide.

### M10.2 — Deploy

Deployed as usual, and looked at on https://badnewsbears.live/cars/outback-2018
(both empty unless the tablet is streaming them).

### M10.3 — Record

Decision 28 amended (sections that show before their data: the G-meter and
map; laps still wait); `COMPLETED.md`, `PLAN.md`. This plan deleted, and
pushed.

---

## Not in M10

- The map opening on the car's last known position (a later step, if wanted).
- Why the tablet sent no GPS or G: that's the tablet's side, to be read off
  the live stream when it next streams.
