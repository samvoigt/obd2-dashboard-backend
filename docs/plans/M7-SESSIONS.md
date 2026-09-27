# M7 — Past sessions

Every session a car has uploaded, on the site:
- a list per car, grouped into drives;
- a session page with full-length charts, laps, where it went, and what
  happened (faults, signals that stopped, gaps);
- a download of the log.

A session still being driven is shown whole, with the archive and the live lane
merged (contract §7).

---

## Questions for Sam

1. **Downloads.** A session's log holds the car's VIN, which the site never
   shows (decision 16).
   - **Proposed:** anyone can download a session with the VIN removed from its
     first line; the exact original, VIN included, is on the admin page only.
   - Or: downloads only from the admin page.
2. **The map.** Positions are public already (§12.8).
   - **Proposed:** a real map (OpenStreetMap tiles, with Leaflet) under the
     GPS trace, colored by speed. Viewers' browsers fetch the tiles from
     OpenStreetMap.
   - Or: the trace alone, drawn on a blank background, with nothing fetched
     from anyone else.
3. **The live page.** Its chart shows the last 5 minutes (decision 19).
   - **Proposed:** a "whole session" switch there, using the same archive and
     live merge as the session page, so the crew can see the whole race so far.
   - Or: leave the live page as it is, and link to the session page.
4. **Laps.** Proposed for M7: a lap table (the best lap not counting pit laps,
   as §18 asks), and choosing a lap zooms the charts to it.
   - **Comparing laps**, overlaid by distance or by time, is a bigger piece.
     Proposed for a later milestone. Or do you want it now?

---

## What exists, and what it means for this

- **The archive is the record** (decisions 17, 19): a completed session is one
  `sessions/{id}/session.jsonl.gz` in Cloud Storage. One still uploading is
  segments, listed in its Firestore document. Live history is 5 minutes in
  memory, and nothing else of the live lane is kept.
- **Sessions are big, and v3 ones will be bigger.** The test logs (OBD only)
  run about 1,400 lines a minute: 8.6 MB an hour raw, 0.8 MB gzipped. Adding
  the G-meter (2 signals at 10 Hz) and GPS (6 signals at about 1 Hz) comes to
  about **3,000 lines a minute**: a 3-hour race is roughly 540,000 lines, 55 MB
  raw, 5 MB gzipped. So **the page must not download and parse the raw log**.
  The server prepares each session once, as columns per signal (M7.2).
- **Cloud Run gives the server 512 MiB.** Preparing a session streams its lines
  and keeps only primitive arrays: about 9 MB for a 3-hour race. If a
  measurement says otherwise, raising the memory is a deploy setting.
- **Records** (§3): `sample` has five kinds (`number`, `state`, `flag`,
  `flags`, `position`). Also `signals`, `stopped`, `fault`, `gap`, `lap`
  (§16, with `pitIn` and `pitOut` since §18), and **unknown types, kept and
  ignored**. **Time is `wall`**, always present in v3, which is all the archive
  takes.
- **The session index** (`FirestoreSessionIndex`) has the header,
  `ackedThrough`, segments, `complete`, and `created`/`updated`, but no end time
  or summary. The admin page (M6) already lists sessions from it.
- **The chart** (`Chart.svelte`, uPlot) draws two chosen signals over 5
  minutes. uPlot handles hundreds of thousands of points, so a full-length
  chart is the same component with zoom.
- **The site's routes are explicit** (`WebRoutes`), and each page gets its own.
- **The contract has a §18 from the tablet** that this side hasn't confirmed.
  Nothing in it changes the server, and its `pitIn`/`pitOut` matter here. The
  confirmation is written as §19 when M7.1 starts, as §17 was.

---

## Decided here, not asked (say if any is wrong)

- **A session's summary is kept in its Firestore document**, built once from
  its lines:
  - started, ended, duration, lines;
  - its signals;
  - its track and lap count, and best lap (never a pit lap);
  - its fault codes.

  It's built **after `complete` is acknowledged**, never before it, so the
  tablet never waits. It's also built on first view if missing (older sessions,
  or a failed build). It carries a version number, so a better summary later
  rebuilds itself.
- **A prepared session is one more file**, `sessions/{id}/series.json.gz`,
  beside the log, built in the same pass:
  - per `number` signal: its times and values, as columns;
  - `flag` signals as 0 and 1, charted as steps;
  - `state` and `flags` as a list of changes;
  - positions as times, latitudes and longitudes;
  - events: `stopped`, `fault`, `gap`, `lap`.

  It is **derived, never the record**: it can always be rebuilt from the log,
  and the log is never changed.
- **A session still uploading is prepared on request, from its segments**, and
  kept until `ackedThrough` moves, so reloading it costs nothing.
- **Gaps are drawn as gaps.** A signal's line breaks where two of its samples
  are more than 5 times its own usual interval apart, at every `gap` record,
  and after its `stopped`. Never interpolated.
- **Drives:** a car's sessions less than 10 minutes apart (one's end to the
  next's start) are one drive, since an adapter reconnect starts a new session
  (§3.2). The list shows drives, newest first, each with its sessions.
- **The merge (contract §7):** for a session being driven, the page takes the
  archive up to its last `seq`, then the hub's live history after it, then live
  updates as they come. **Archive rows win** wherever both have a `seq`. Live
  rows show as provisional until the archive covers them.
- **Everything is public, like the live pages** (decision 11), and never shows
  a VIN. Session ids are UUIDs. Only the admin page deletes.
- **Routes:**
  - pages: `/cars/{slug}/sessions` and `/cars/{slug}/sessions/{id}`;
  - API: `GET /api/cars/{slug}/sessions` (drives and summaries),
    `GET /api/sessions/{id}` (summary and signals),
    `GET /api/sessions/{id}/series` (the prepared file, sent gzipped as it
    is), and `GET /api/sessions/{id}/download`.
- **Links:** the car page links "Past sessions"; the admin page's session rows
  link to their pages.

---

## The steps

### M7.1 — Reading a session, and its summary

A pure reader in `:archive`: it streams a session's lines (the log, or its
segments in order) and builds the summary. It tolerates everything §3 allows:
unknown types and fields, absent GPS fields, a `signals` record mid-session.

The summary goes into the Firestore document, built after `complete` and on
first view. Also: the §19 confirmation in the app's contract.

**Done when:** tests with every record type, unknown ones, laps with and
without `pitIn`/`pitOut`, and a mid-session `signals`; the real test logs
summarised; the Firestore mapping round-trips; mutations are checked.

### M7.2 — The prepared series

The same pass builds `series.json.gz`, with the gap rule. Complete sessions are
prepared once. Uploading ones are prepared from segments, and kept per
`ackedThrough`. Measured on the biggest test log, and on a synthetic 3-hour
race (time and memory).

**Done when:** tests for each kind, gaps (the 5× rule, `gap` records,
`stopped`), and rebuilding after a version change; measured sizes and times;
mutations are checked.

### M7.3 — The sessions API

The four routes. The list groups drives. `series` streams the stored file with
`Content-Encoding: gzip`. `download` follows question 1.

**Done when:** each route is tested (an unknown session, another car's slug, a
session still uploading); the VIN appears in no response (a raw-bytes test, as
decision 19's); mutations are checked.

### M7.4 — The sessions list

`/cars/{slug}/sessions`: drives newest first, each with its sessions: when,
how long, track and best lap if any, faults if any, and "uploading" or "live"
where true. Linked from the car page.

**Done when:** Vitest for the grouping and wording; looked at in Chrome with
several replayed sessions; phone width.

### M7.5 — The session page

`/cars/{slug}/sessions/{id}`:
- the summary;
- the full-length chart: signals chosen as on the live page, drag to zoom,
  double-click to reset, gaps as gaps;
- the lap table (question 4), where choosing a lap zooms to it;
- the map or trace (question 2), with the chart's cursor shown on it;
- events on the chart's time line (faults, stopped signals, gaps);
- the download.

**Done when:** Vitest for the pure parts; looked at in Chrome with a real log,
a synthetic race with laps and GPS, and a session with gaps; phone width.

### M7.6 — A session being driven

The merge: the session page of a live session shows the archive, then the live
tail, then live updates, with archive rows replacing provisional ones as
chunks arrive. The live page's "whole session" switch follows question 3.

**Done when:** tests for the merge by `seq` (overlap, archive ahead, live
ahead, a reconnect); looked at in Chrome with a replay streaming live and
uploading, through at least two chunk uploads.

### M7.7 — Deploy, and prove it live

Deploy; replay the test logs into a throwaway car (archive, and one live); the
list, pages, map and download on the deployed site; the summary built after
`complete`, and on first view for a session made before M7. Then clean up.

**Done when:** every step passes on the deployed site, with screenshots kept.

### M7.8 — Record it

Decisions (the summary and series, the gap rule, drives, the merge);
`COMPLETED.md`, `JOURNAL.md`, `PLAN.md`, README, `CLAUDE.md`. This plan deleted,
and pushed.

---

## Not in M7

- Comparing laps (question 4 may move it in).
- Naming or annotating sessions.
- Dashboards (M8).
- Showing crew messages beside a session (they're kept for it, decision 21).
