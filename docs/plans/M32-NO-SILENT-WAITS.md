# M32 — Nothing waits in silence

Pages show something at once and fill in as data arrives: event results are
worked out once and kept until something they're made of changes, a
session's laps come before its chart, every page says what it is in its
browser tab. Phase 3 of the UX review (`UX-REVIEW.md` §3.B, §4 phase 3).
**Sam, 2026-10-22**, approving the review's order: phones first, then
who's driving, then this.

> **Planned 2026-10-22**, checked against the code. Found while checking:
> the results hold's key ignores which cars the viewer may see (M32.1, a
> privacy fix, first).

---

## What exists, and what it means for this (checked 2026-10-22)

- **Event results** are worked out by `EventResultsService.eventResults`
  (`EventResults.kt`, M29's refactor) on every request that misses the hold:
  each session's laps (`retiming.sessionLaps`, a stored re-timing or one
  built now), the race as one timeline, practice bests. For the NHMS weekend
  that took 5.9 s, 8.3 s and 5.0 s on 2026-10-20 (Cloud Run logs,
  `GET /api/events/nhms-lemsons-2026`).
- **The hold** is `ResultsHold` (`EventResults.kt`, end of file): a map from
  `"${event.id}@${event.revision}"` to results, kept **10 s**
  (`forMs = 10_000`), emptied by `clear()`. `clear()` is called by `edited`
  (`Application.kt`: `{ timing.nudge(); resultsHold.clear() }`), which is
  passed as `onChanged` to course, event, driver and race routes. Test:
  `LiveResultsTest` "results are held 10 s, and emptied by an edit".
- **The hold's key ignores what the viewer may see.** `results(event,
  hidden)` holds `seen(event, hidden)` (hidden cars removed), but the key is
  only the event's id and revision, the same for a signed-in viewer (who
  sees hidden cars) and a signed-out one. So for 10 s a signed-out visitor
  can be sent results computed for someone signed in, a hidden car's laps
  included, contradicting M25 and the comment on `seen` ("Results are held
  per what's seen, so no one is sent another's"). A longer hold makes this
  worse, so it's fixed first.
- **What else changes results but doesn't clear the hold:** a session
  completing (`archiveRoutes`' `completed` callback in `Application.kt`
  calls `liveTimings.completed` and `liveSeries.completed` only); a session's
  re-timing stored (`RetimingJobs.sessionPrepared` → `retime`, and a course
  save's job in `courseSaved`); a session deleted (`CarAdmin.deleteSession`).
  With a 10 s hold these don't matter; with a long one they must clear it.
- **Live parts:** while a session in the event isn't complete, its laps are
  provisional (`LiveTimings`), changing every lap. The event page re-asks
  every 30 s while a part is on (`partOn`, `REFRESH_MS` in
  `lib/eventResults.ts`).
- **The driver page** (`GET /api/drivers/{id}`) calls `service.results` for
  every event, so it waits on all of them; held results make it fast too.
- **The session page** (`SessionPage.svelte`, `onMount`, line 202): fetches
  `/api/sessions/{id}`, then (not awaited) drivers and messages, then
  **awaits the series** (thinned first for sessions over an hour, M19.6),
  and only then `fetchLaps(id)` (`/api/sessions/{id}/laps`, a few kB). The
  laps table and lap speeds are derived from `standing` or the series
  (`laps` at line 166), so the laps could show first, without speeds, and
  gain them when the series arrives.
- **"Loading…"** is a grey line in 12 places (`grep Loading web/src/*.svelte`);
  no page shows its layout before its data.
- **Titles:** `web/index.html` has `<title>OBD2 Live</title>`; nothing sets
  `document.title`. `App.svelte` routes by `lib/routes.ts`; each page knows
  its name once loaded.

## Decided here

- **Results are held until something they're made of changes**, keyed by
  the event's id and revision **and the cars the viewer sees** (sorted,
  joined). An entry for an event with a session still live is held 10 s, as
  now; the page's 30 s refresh then sees new laps.
- **What clears the hold:** everything that calls `edited` today, plus a
  session completing, a session's re-timing stored, a course's re-timing job
  finishing, and a session deleted. One `resultsHold.clear()` each; no
  finer invalidation (events are few; recomputing one on the next visit is
  fine).
- **The 202-with-progress idea is deferred.** Measure first: with the hold,
  only the first visit after a change pays the 5–8 s. If that first visit is
  still a problem after M32.1–M32.2 (measured on NHMS, below), a later
  milestone adds it; this one doesn't.
- **Skeletons, not spinners:** each page renders its panels with their
  headings and a placeholder block of the panel's usual height
  (`.skeleton` in `app.css`: the panel colour, a soft pulse, no new colour
  role).
- **Titles** are "<what> · Bad News Bears": "Tha Bearmobile · live",
  "Sessions · Tha Bearmobile", "Sat 17 Oct, 12:00 · Tha Bearmobile",
  "NHMS Lemons 2026", "Drew Blouch", "New Hampshire Motor Speedway", and the
  list pages' names.

## The steps

### M32.1 — The hold keyed by what's seen (privacy, first)

`ResultsHold.get`'s key becomes `"${event.id}@${event.revision}:${event.cars.sorted().joinToString(",")}"`
(`seen` already removes hidden cars, so the cars are what's seen).

Test (`LiveResultsTest` or `HiddenTest`): an event with a hidden car; ask
signed in, then signed out within 10 s; the second answer doesn't name the
hidden car or its sessions. Fails before the change, passes after.

Deploy on its own (with a replay streaming), since it's a leak today.

### M32.2 — Held until changed

`ResultsHold` gains `get(event, live: Boolean, compute)`: `live` entries
expire after 10 s, others never; `clear()` as now. `eventResults` already
knows which sessions are live (`liveIds`); pass `live = liveIds.isNotEmpty()`
back with the results (or compute it before) so the service can choose.

Clear the hold also in `Application.kt`: in the `completed` callback; from
`RetimingJobs` (a new `onRetimed: () -> Unit` constructor parameter, called
after `retime` stores and after a course job finishes); in
`CarAdmin.deleteSession`'s caller in `adminCarRoutes` (after a delete).

Tests: a held entry outlives 10 s when nothing is live (fixed clock); a live
one doesn't; each new clearing path empties it (`LiveResultsTest`,
`RetimingJobsTest` with the callback counted).

Measure (JOURNAL): on production after deploy, the NHMS event page twice in
a row and after an edit, from the logs' `GET /api/events/… in Nms`; the
driver page the same.

### M32.3 — The session page: laps first

In `SessionPage.svelte`'s `onMount`, start `fetchLaps(id)` right after the
detail arrives (for a complete session), not after the series; render the
laps panel as soon as `standing` is set, speeds blank until the series
comes (`speedOf` already returns nothing without one). The chart and map
panels show skeletons until the series arrives.

Test (`sessionPage.test.ts`, a fake fetcher recording order): for a complete
session, `/laps` is asked before `/series`. Check on the dev server with
the M29 synthetic 3 h session: the laps table appears before the chart.

### M32.4 — Skeletons

`.skeleton` in `app.css`; replace each "Loading…" with the page's real
headings and skeleton blocks: event (title, standings, chart, stints), car
(banner, gauges, map), session (facts, laps, chart, map), driver, course,
lists. One page per commit; `npm run check` and the colour-roles test pass.

Check: at 390 px and desktop, each page's layout appears before its data
(throttle the network in the browser to see it); no layout jump larger than
a line when the data lands.

### M32.5 — Page titles

`lib/title.ts`: `setTitle(...parts)` → `document.title = [...parts, 'Bad
News Bears'].join(' · ')`. Each page calls it once its name is known (and
with the list name at once). Test: the function's output; a check in the
phone script (M30) that each route's title isn't the default.

### M32.6 — Deployed, and measured

With a replay streaming across the deploy. JOURNAL: the event page's and
driver page's times before (2026-10-20's logs) and after (M32.2's
measurement); the session page's time to laps on the 3 h 17 min Saturday
12:00 session, before and after (browser, signed out). Decide there
whether the first-visit progress line is still wanted.

## Open questions

None.
