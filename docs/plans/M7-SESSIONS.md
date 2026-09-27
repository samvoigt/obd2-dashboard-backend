# M7 — Past sessions

Every session a car has uploaded, on the site:
- a list per car, grouped into drives;
- a session page with full-length charts, laps, where it went on a map, and
  what happened (faults, signals that stopped, gaps);
- the exact log, downloadable from the admin page.

A session still being driven is shown whole, with the archive and the live lane
merged (contract §7).

---

## Settled with Sam, 2026-09-26

1. **Downloads are on the admin page only**, as the exact original log (VIN
   included). The public site has no download.
2. **A real map:** OpenStreetMap tiles with Leaflet, and the GPS trace on top,
   colored by speed. Viewers' browsers fetch the tiles from OpenStreetMap,
   with its attribution shown, as its tile policy asks.
3. **The live page gets a "whole session" switch**, using the same merge of
   the archive and the live lane as the session page.
4. **Laps: a table now, comparison later.** The best lap never counts a pit
   lap (§18); choosing a lap zooms the charts to it. Overlaying laps is a later
   milestone.

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
  a VIN. Session ids are UUIDs. Only the admin page deletes, and downloads.
- **Routes:**
  - pages: `/cars/{slug}/sessions` and `/cars/{slug}/sessions/{id}`;
  - API: `GET /api/cars/{slug}/sessions` (drives and summaries),
    `GET /api/sessions/{id}` (summary and signals),
    and `GET /api/sessions/{id}/series` (the prepared file, sent gzipped as it
    is);
  - admin: `GET /api/admin/sessions/{id}/download`, the exact log (the
    completed file, or its segments joined), as `{id}.jsonl.gz`.
- **Links:** the car page links "Past sessions"; the admin page's session rows
  link to their pages, and have Download.
- **The map is Leaflet** (bundled from npm, as uPlot is), with OpenStreetMap's
  standard tiles and attribution. The trace is colored by `gps.speed` where
  there is one, else by the car's speed.

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

> **Validated against the code, 2026-09-26, before building.**
> - **Stores only read whole objects** (`SegmentStore.read` gunzips into one
>   array). A 3-hour race would be one 55 MB array. `SegmentStore` gains
>   `readStream(key) { InputStream -> … }`, gunzipping as it goes in Cloud
>   Storage, and `ArchiveService.lines(id)` walks a session's lines: the
>   completed file, or its segments in index order up to `ackedThrough`.
> - **The reader is pure, in `:archive`** (`SessionReader`), fed one line at a
>   time, so M7.2 can extend the same pass. It parses with kotlinx, as
>   `Records` does, and never fails a session on a line it doesn't understand:
>   an unparsable line is counted and skipped.
> - **`SessionSummary`** (version, started, ended, lines, signals, track and
>   layout, laps, best lap, fault codes, gaps) goes on `SessionRecord` as
>   `summary`, with **its own conditional write**, `setSummary(id, summary)`.
>   That only sets the field, so it can't collide with `append` or `complete`,
>   which never touch it.
> - **Started** is `wall` of the first record that has one (the header's
>   `started` if none does). **Ended** is the latest `wall`. Only v3 is
>   archived, and v3 always has `wall` (§3.1).
> - **After `complete`**, the route answers first and then summarises in the
>   application's scope. A failure is logged and left for "on first view".

> **✅ Done, 2026-09-26.**
> - **`SessionReader`** and **`LineSplitter`** in `:archive`;
>   **`SessionSummary`**, **`SignalInfo`**, **`LapInfo`**;
>   **`SegmentStore.readStream`** (Cloud Storage gunzips as it goes);
>   **`ArchiveService.read`** and **`summary`**;
>   **`SessionIndex.setSummary`**.
> - **The summary is part of the Firestore record's mapping.** The index
>   rewrites the whole document on every conditional write, so a field outside
>   the mapping would have been erased by the next one (found while validating).
> - **The complete route summarises after answering.**
> - **Tests:** 10 for the reader, 5 in `ArchiveService`, 1 mapping, 1 route.
>   The live archive smoke gained 4 checks (streamed segments, the summary built,
>   kept in Firestore, and kept by a later write), all passing against the real
>   bucket.
> - **The app's real logs** (v1, so no `wall` or signal lists) read cleanly.
>   One was cut mid-line by unplugging the adapter, and its last line is
>   counted as unreadable, as designed; the archive refuses such a line anyway.
> - **Mutations:** 18, 17 killed. The equivalent one was a check for segments
>   past `ackedThrough`, which the index can't hold, so the check was removed
>   rather than kept.
> - **§19 (confirming §18)** is appended to the app's contract, uncommitted,
>   for Sam or the tablet side.

### M7.2 — The prepared series

The same pass builds `series.json.gz`, with the gap rule. Complete sessions are
prepared once. Uploading ones are prepared from segments, and kept per
`ackedThrough`. Measured on the biggest test log, and on a synthetic 3-hour
race (time and memory).

**Done when:** tests for each kind, gaps (the 5× rule, `gap` records,
`stopped`), and rebuilding after a version change; measured sizes and times;
mutations are checked.

### M7.3 — The sessions API

The three public routes, and the admin download. The list groups drives.
`series` streams the stored file with `Content-Encoding: gzip`. The download
is the exact log, VIN and all, and is admin-only.

**Done when:** each route is tested (an unknown session, another car's slug, a
session still uploading); the VIN appears in no public response (a raw-bytes
test, as decision 19's); the download is byte for byte the log and `401`
without a sign-in; mutations are checked.

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
- the lap table, where choosing a lap zooms to it (the best never a pit lap);
- the map, with the trace colored by speed, and the chart's cursor shown on it;
- events on the chart's time line (faults, stopped signals, gaps).

The admin page's sessions get a link to this page, and Download.

**Done when:** Vitest for the pure parts; looked at in Chrome with a real log,
a synthetic race with laps and GPS, and a session with gaps; phone width.

### M7.6 — A session being driven

The merge: the session page of a live session shows the archive, then the live
tail, then live updates, with archive rows replacing provisional ones as
chunks arrive. The live page gets its "whole session" switch, on the same
merge.

**Done when:** tests for the merge by `seq` (overlap, archive ahead, live
ahead, a reconnect); looked at in Chrome with a replay streaming live and
uploading, through at least two chunk uploads.

### M7.7 — Deploy, and prove it live

Deploy; replay the test logs into a throwaway car (archive, and one live); the
list, pages and map on the deployed site, and the download through the API; the summary built after
`complete`, and on first view for a session made before M7. Then clean up.

**Done when:** every step passes on the deployed site, with screenshots kept.

### M7.8 — Record it

Decisions (the summary and series, the gap rule, drives, the merge);
`COMPLETED.md`, `JOURNAL.md`, `PLAN.md`, README, `CLAUDE.md`. This plan deleted,
and pushed.

---

## Not in M7

- Comparing laps (Sam: a later milestone).
- Naming or annotating sessions.
- Dashboards (M8).
- Showing crew messages beside a session (they're kept for it, decision 21).
