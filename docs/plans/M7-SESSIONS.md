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
  are more than 5 times its own median interval apart (never under a second),
  and where a `gap` record's `seq` falls between theirs. After `stopped` no
  samples come. Never interpolated. (By `seq`, since M7.2: see there.)
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

> **Validated against the code, 2026-09-26, before building.**
> - **Gaps must be explicit nulls.** The chart (uPlot) joins every signal onto
>   one time axis, and the holes that joining makes are bridged; only a `null`
>   in the data breaks a line. So the file puts a `null` value at each break,
>   and the page only draws it. A break comes where two samples of a signal
>   are more than 5× its median interval apart, or where a `gap` record falls
>   between them. After `stopped` no samples come, so nothing is drawn anyway.
> - **Its shape** (JSON, gzipped): `version`, `t0` (the summary's `started`),
>   `signals`, and times as **milliseconds after `t0`**, which are short
>   integers:
>   - `numbers`: per `number` or `flag` signal (a flag is 0 or 1), `t` and `v`;
>   - `states`: per `state` signal, its changes (`t`, `code`, `text`);
>   - `sets`: per `flags` signal, its changes (`t`, `flags`);
>   - `positions`: `t`, `lat`, `lon`;
>   - `events`: `stopped`, `fault`, `gap`, `lap`.
> - **The version is in the name**, since a stored object can't be checked
>   without reading it: `sessions/{id}/series-v1.json.gz`. A session still
>   uploading gets `series-v1-{ackedThrough}.json.gz`, and older partial files
>   are deleted when a newer one is written. `delete` already removes
>   everything under the session's prefix.
> - **Memory:** `SeriesBuilder`, beside `SessionReader`, keeps growable arrays
>   of plain numbers per signal, and writes its JSON straight into the store's
>   gzip stream, never as one string.
> - **Built with the summary**, in the same background task after `complete`,
>   and on first view if missing.
> - **Serving it without re-compressing** (the stored gzip as the response's
>   `Content-Encoding: gzip`) needs a raw read from the store; that's M7.3's.
>
> **✅ Done, 2026-09-26.** `SeriesBuilder` (fed by `SessionReader`, one parse
> per line), `ArchiveService.prepare` and `seriesKey`; `complete` now prepares,
> building the summary and the series in one pass.
> - **Measured**, with the heap capped at Cloud Run's real default (128 MiB),
>   on a synthetic race streamed from disk:
>   - **3 hours:** 530,514 lines (65 MB raw, 8.4 MB gzipped) became a 14 MB
>     file (5.9 MB gzipped) in 1.5 s, the heap peaking at 62 MiB;
>   - **6 hours:** 1.06 million lines in 2.9 s, peaking at 69 MiB.
>
>   The peak is parsing garbage, not data (6 h peaks where 3 h does). Still,
>   **the JVM had only 128 MiB of the container's 512**, by default, so the
>   Dockerfile now gives it 75% (384 MiB), for room beside the live lane.
> - **Tests:** 15 for the builder, 4 more for `prepare`. Mutations: 24, all
>   killed in the end. Two survivors led to changes:
>   - **Gaps placed by `seq`, not `wall`.** A gap record sharing a millisecond
>     with a sample was put on the wrong side of it. `seq` places it exactly
>     (§3.5: missed from its `seq` on), at 8 more bytes a sample.
>   - **Numbers JSON can't hold** (`1e999` parses as infinity) are written as
>     a break, now tested.
>
>   A JSON-writing slip (an unclosed object) was caught by the tests, which
>   parse every output as JSON.

### M7.3 — The sessions API

The three public routes, and the admin download. The list groups drives.
`series` streams the stored file with `Content-Encoding: gzip`. The download
is the exact log, VIN and all, and is admin-only.

**Done when:** each route is tested (an unknown session, another car's slug, a
session still uploading); the VIN appears in no public response (a raw-bytes
test, as decision 19's); the download is byte for byte the log and `401`
without a sign-in; mutations are checked.

> **Validated against the code, 2026-09-26, before building.**
> - **Stores only return unzipped bytes**, and the series is gzip at rest.
>   `SegmentStore` gains `readRaw(key) { InputStream -> … }`: the object as
>   stored. `series` sends it with `Content-Encoding: gzip`, never unzipping
>   and re-zipping, and the admin download sends a completed log the same way
>   (as `application/gzip`, a file to save). A session still uploading is
>   downloaded as its segments, zipped as they stream.
> - **The session states** (live, uploading, complete, incomplete) are worked
>   out in `AdminRoutes` today; the public list needs the same rule, so it moves
>   to one function both use.
> - **The prepared file's key is its version**, so it's the `ETag`: a repeat
>   visit gets `304` rather than megabytes.
> - **Ids are checked as UUIDs** (`SessionIds`, which the archive lane uses),
>   so a bad one is a `404` before any store is asked.
> - **An old complete session with no summary** gets one built on first view
>   (M7.1's rule). The list does this for any that lack one; there are few, and
>   each is built once.
> - **Drives are grouped by a pure function**, tested on its own, with the
>   10-minute rule.

> **✅ Done, 2026-09-26.**
> - **New:** `SessionRoutes` (the list as drives, one session, the series), the
>   admin download (`downloadSession`), `SegmentStore.readRaw`, and
>   `SessionStates` (the state rule, now shared with the admin page).
> - **Tests:** 9 for the API, including raw-bytes checks for the VIN and the
>   drive rule. The live smoke's `readRaw` check passes against the real bucket.
> - **Found by the tests:** on first view, the list built a missing summary but
>   took the start from the record read before it, so every session fell back
>   to its header's date. It now uses the summary it just built.
> - **Found by a mutation:** a session the live lane announced has no archived
>   lines for its first minutes, and was hidden then. It's now listed while
>   live. Its page before any lines are stored is M7.6's.
> - **Mutations:** 17 killed. One is equivalent: downloading a completed
>   session through its segments' path gives the same bytes, only re-zipped.

### M7.4 — The sessions list

`/cars/{slug}/sessions`: drives newest first, each with its sessions: when,
how long, track and best lap if any, faults if any, and "uploading" or "live"
where true. Linked from the car page.

**Done when:** Vitest for the grouping and wording; looked at in Chrome with
several replayed sessions; phone width.

> **Validated against the code, 2026-09-26, before building.**
> - `routes.ts` gains `sessions` (`/cars/{slug}/sessions`) and `session`
>   (`/cars/{slug}/sessions/{id}`); `WebRoutes` serves both, explicitly, as
>   every page is.
> - **The drives come grouped from the server** (M7.3), so the page only words
>   them: dates, durations, the best lap, faults, and badges for live and
>   uploading. That wording is pure, in `sessions.ts`, with Vitest (the grouping
>   is already tested on the server).
> - **Links:** the car page gets "Past sessions", and each session row links to
>   its page. The page itself is M7.5, so until then it's a placeholder.
> - **To look at it**, the dev server gets sessions from `replay.sh`, which
>   upgrades the app's v1 logs to v3 (with `wall`), several logs from different
>   days making several drives.

> **✅ Done, 2026-09-26.** `SessionsPage.svelte`, `sessions.ts` (7 Vitest
> tests), the `sessions` and `session` routes on both sides (with a placeholder
> session page), "Past sessions →" on the car page, and each admin session row
> linked to its page. Looked at in Chrome with four real logs, a synthetic
> 20-minute race (laps, a pit lap, a fault, a gap, GPS) and a live replay:
> - drives by day, newest first; the two sessions on the morning of 23 Sept, 5
>   minutes apart, as one drive;
> - the race's track and best lap (1:34.000, lap 11 of 12: the quicker pit lap
>   isn't counted), and its fault code in red;
> - the live session badged "Live now";
> - 390 px wide with no sideways scroll.
>
> **Found by looking:**
> - **Every session lasted "0 s".** The validation note above was wrong: the
>   replay's upgrade gave only line 0 a `wall`. Real v3 has `wall` on every
>   record (§3.1), so the replay now adds it to each upgraded record (the start,
>   plus the time since the first `at`), inserted before the closing brace so
>   the rest of each line is untouched. Tested.
> - **A live session ended at its start**, so "0 s". A live session now ends
>   "now" (tested), and a line count of 0 isn't shown.
>
> Mutations: 11 in the page's logic and routes, all killed once two boundary
> tests were added (exactly an hour; a span a stepped-back clock made
> negative).

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

> **Validated against the code, 2026-09-26, before building.**
> - **`Chart.svelte` is the live page's**: drag is off, and it has no range,
>   cursor or markers. It gains four **optional** props, so the live page is
>   unchanged: `zoom` (drag to zoom, double-click to reset, as uPlot does),
>   `range` (set from outside: a lap), `onCursor` (the time under the cursor,
>   for the map), and `markers` (thin lines at faults, gaps and stopped
>   signals, drawn in uPlot's `draw` hook).
> - **Joining signals:** each has its own times, so the page joins them with
>   `uPlot.join`. It keeps the file's explicit `null`s (gaps) and leaves the
>   holes joining makes as `undefined`, which uPlot bridges, as M7.2 said.
>   Times are the file's `t0 + t`, in seconds, as uPlot's time scale wants.
> - **Default signals:** `engine.rpm` and `vehicle.speed` when present, as the
>   live page's `defaultChart` does; otherwise the first two numbers.
> - **Leaflet isn't installed.** It's added pinned (`leaflet`, and
>   `@types/leaflet` for the type check), as uPlot is. The map uses the canvas
>   renderer, since a 3-hour trace at 1 Hz is about 10,000 coloured segments.
>   Each segment's colour is the nearest `gps.speed` (else `vehicle.speed`) in
>   time. With no positions, there's no map.
> - **Pure parts in `sessionPage.ts`**, with Vitest: the lap table (the best,
>   never a pit lap), a lap's time span (it ends at its `wall`, and lasts its
>   `time`), joining, the speed colour, and the position nearest a time.

> **✅ Done, 2026-09-26.**
> - **New:** `SessionPage.svelte`, `SessionMap.svelte` (Leaflet 1.9.4 on
>   OpenStreetMap), `sessionPage.ts` (8 Vitest tests; 53 in all), and
>   `Chart.svelte`'s four optional props. The live page is unchanged except
>   that its legend's time now matches its axis.
> - **Looked at in Chrome**, on the synthetic race and a real 29-minute log:
>   - the full-length chart, with the fault (red) and the gap (amber) marked;
>   - the map at the track, coloured by speed;
>   - the lap table, lap 11 marked best and the quicker in-lap not counted;
>     choosing a lap zooms the chart to its 94 seconds;
>   - the dot on the map following the chart's cursor;
>   - the events listed;
>   - the gap breaking every signal just after the last sample before it;
>   - a session with no GPS or laps has neither section;
>   - 390 px wide with no sideways scroll.
> - **Found by looking:** the legend wrote the time as "11:16am" beside a
>   24-hour axis; it now matches. Faint seams between map tiles appear in this
>   browser (a fractional pixel ratio). Leaflet's usual fix didn't clear them,
>   so it was left out; noted as cosmetic.
> - **The upgraded test logs have no units** unless replayed with
>   `--units-from`, so engine and road speed share an axis there. Real v3 logs
>   carry units, and different units get two axes.
> - **Mutations:** 13, 12 killed. The equivalent one is an infinite speed,
>   which the server never sends: it writes a break instead.

### M7.6 — A session being driven

The merge: the session page of a live session shows the archive, then the live
tail, then live updates, with archive rows replacing provisional ones as
chunks arrive. The live page gets its "whole session" switch, on the same
merge.

**Done when:** tests for the merge by `seq` (overlap, archive ahead, live
ahead, a reconnect); looked at in Chrome with a replay streaming live and
uploading, through at least two chunk uploads.

> **Validated against the code, 2026-09-26, before building.**
> - **The live stream carries the raw records**, `seq` and `wall` included:
>   the snapshot's `history` (`{atMs, record}`) and each `records` event, with
>   the session's header (its `id`) in the snapshot and in `session` events.
>   So the page can merge by `seq`, as §7 says.
> - **The cut-off must come with the data it cuts.** A separate "archived up
>   to" could be from a different moment than the file fetched. So the
>   prepared file records the last `seq` it covers (`lastSeq`). That's a new
>   field, so **`SeriesBuilder.VERSION` becomes 2**, and every stored file
>   rebuilds itself on next view (the version is in its name, M7.2).
> - **A live session with no archived lines yet** (the live lane announces it
>   before the first chunk) gets its page from the live stream alone: the
>   detail route answers while it's live, and the series is simply absent.
> - **The merge is pure** (`merge.ts`, Vitest): the archive up to `lastSeq`,
>   then live records with a higher `seq`, deduplicated, in `seq` order, turned
>   into the same columns. **An explicit break** goes where the two don't meet
>   (over 5 s apart), and within the live part the same way.
> - **Cost:** re-merging and re-joining a 3-hour race on every 200 ms batch
>   would be too much, so the chart takes the merge at most once a second, as
>   the live chart does. The prepared file is re-checked every minute, which is
>   usually a `304`.
> - **Provisional rows are marked:** the chart shades the stretch the archive
>   doesn't yet cover (a `bands` prop, drawn in the same hook as markers).
> - **The live page's switch** fetches the live session's prepared file, and
>   feeds the same merge with its own live history and updates.

> **✅ Done, 2026-09-26.**
> - **New:** `merge.ts` (7 Vitest tests; 60 in all); `lastSeq` in the
>   prepared file (version 2); a live session's page before its first chunk;
>   `Chart.svelte`'s `bands`; the session page following a live session; the
>   live page's "Last 5 minutes | Whole session" switch.
> - **Looked at in Chrome** with the synthetic race streamed live and uploaded
>   at real speed, through two chunk uploads (4,001 lines archived):
>   - before the first chunk, the page drew from the live stream alone, all of
>     it shaded;
>   - after it, the prepared file said `lastSeq` 2000, and only the part after
>     that was shaded; the join was seamless;
>   - the live page's "Whole session" showed everything from the start, the
>     archive then the shaded live part.
> - **Found by looking:** the map drew its trace once and never again, so it
>   stopped growing while the chart went on. It now redraws as positions come,
>   fitting the view only the first time, so a viewer's zoom stays put.
> - **In the synthetic file, `started` and `wall` disagree by 2 hours**, which
>   made the live page header read "14 h". Real logs have `started` equal to the
>   first `wall`; noted, not a page bug.
> - **Mutations:** 14 in the merge (a lap without a time survived until a test
>   covered it) and 3 on the server, all killed.

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
