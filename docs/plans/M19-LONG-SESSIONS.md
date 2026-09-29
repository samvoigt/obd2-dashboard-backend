# M19 — Long sessions: two 8-hour stints without anyone waiting

A race can have **two 8-hour sessions** (Sam, 2026-09-28). At the tablet's
real rate that's about **1.07 million lines in about 2,400 chunks each**, and
the server handles long sessions by reading every chunk back, one object at a
time, in several places. This milestone makes the cost of each thing a car or
a viewer does **independent of how long the session has run**: the car page
during the race, the tablet's `complete` at the end of a stint, the live
timing, and the session's page afterwards. Measured before and after.

Nothing here changes the contract or the tablet.

---

## Asked by Sam, 2026-09-28

"We will have potentially 2 8-hour sessions in one race. What fixes should
we make so that no one gets bogged down?" The fixes proposed in chat, in
order of how badly each would bite, are the steps below.

---

## What exists, and what it means (checked 2026-09-28)

- **The tablet's rate** (the drive of 2026-09-28, `69c4ace7-…`): 58,381 lines
  in 26 minutes, about 37 lines a second, uploaded in about 135 chunks, **one
  every ~12 s** (the code's comments assume one every 2 minutes). An 8-hour
  session: about 1.07 M lines, about 2,400 chunks.
- **Each chunk is its own object** (`sessions/{id}/segments/{first}-{last}`),
  a whole gzip file (`GcsSegmentStore.put`), listed in the session's index
  record (`SessionRecord.segments`, Firestore), which is **rewritten whole on
  every chunk** (`conditional`, `toFields`): at 2,400 entries, several hundred
  KB a write, toward Firestore's 1 MiB limit.
- **Reading a session still uploading** (`ArchiveService.read`) streams its
  segments **one after another**; `complete` (`assemble`) reads 8 ahead
  (M17.1), about 24 ms a segment: **about 60 s** at 2,400.
- **The car page** (`CarPage`) fetches the live session's series every 60 s
  for its laps and "Whole session" (M7.6, M8.3). Each fetch after a new chunk
  **rebuilds the whole series** (`prepare`, per `ackedThrough`), reading every
  segment one at a time, **for each viewer separately** (nothing shares a build
  in progress). Seven hours in: about 2,100 reads and a million lines, every
  minute, per viewer.
- **The live run** (`LiveTimings`, M17.2) holds every fix of the live
  sessions (about 144k per 8-hour session; tens of MB for two): fine. **But
  `Provisional.runs` re-times the whole live session** every 10 s and on each
  lap (`TimingDownlink`, event results). `LapRule.offer` and `PitLane.offer`
  already take one fix at a time; nothing keeps their state.
- **After a restart or a reconnect**, `LiveTimings` reads the session's whole
  partial archive, one segment at a time (the refill reads it all for a few
  laps).
- **A finished session's series** is built once (`prepare`): M7 measured a
  3-hour synthetic race at 62 MiB peak on a 128 MiB heap. Production has 512
  MiB, the heap 75% of it (384 MiB, the Dockerfile). An 8-hour session at the
  real rate is untested; **two finishing together build at once** (`prepare`
  has no lock; re-timing has one).
- **The session page** downloads the whole series and plots every point.
- **Cloud Storage's compose** joins up to 32 objects into one, server side;
  joined gzip files are a valid multi-member gzip, which `GZIPInputStream`
  reads straight through.

---

## Decided here (say if any is wrong)

- **Compaction**: while a session uploads, every few minutes its new segments
  are composed into one piece and the index lists the piece instead. An
  8-hour session stays **tens of objects**, and its index record stays small.
- **`complete` answers at once**: the SHA-256 is kept **running as chunks are
  acknowledged**, its state stored with `ackedThrough` (so a restart carries
  on), and `complete` compares it, marks the session complete and answers.
  The single object is **composed in the background** after the answer;
  until it exists, readers use the pieces; a restart resumes it.
- **The car page stops rebuilding the session**: its laps come from the live
  run the server already holds (a small route, or the live stream), and
  "Whole session" is **built on request, incrementally** (only new pieces fed
  to a kept builder), **one build shared by every viewer**, **thinned** for
  display (each span's minimum and maximum, so peaks survive).
- **The live run keeps its re-timing's state** and feeds only new fixes.
- **One series build at a time**; memory measured with an 8-hour session at
  the real rate on the production heap. **Cloud Run's memory is raised to 1
  GiB only if the measurement says so.**
- **The session page gets a thinned series** for the whole-session view and
  full detail when zoomed in.

---

## The steps

Each validated against the code just before it's built, the validation
written here.

### M19.1 — Measure first

A generator for a synthetic session at the tablet's real rate (the real
drive's signals and line mix, any length), and a load harness: two 8-hour
sessions streamed and uploaded at speed into the dev server, with the car
page open. Baseline numbers written here: `complete`'s time, the car page's
series requests and their times, the server's heap, the page's frame rate.

**Done when:** the generator's output checked against the real drive's
rates; the baseline recorded.

> **Validated against the code and the logs, 2026-09-28, before building.**
> - **The tablet's real upload pattern** (Cloud Run's request log for
>   `69c4ace7-…`): **270 chunk requests in 26 minutes**, all `200`: every 2
>   minutes one ~80 KB chunk (its 2 minutes of lines), plus 15–25 requests
>   under 1 KB each, in bursts 0.3 s apart. About 10 a minute: **an 8-hour
>   session could be up to ~5,000 chunks**, not the 2,400 first estimated.
>   (The tiny chunks are a question for the tablet side.) A duplicate chunk
>   stores nothing; each distinct one is a segment.
> - **The generator** writes the real drive's record mix at its rates
>   (measured from the app's file, only read: 37.4 lines/s), round the box.
> - **Where to measure**: `complete` and the car page's request are dominated
>   by Cloud Storage's latency, which the dev server's in-memory store doesn't
>   have, so **the baseline is production as deployed** (a throwaway car);
>   memory and re-timing are measured locally, the heap capped as production's.

> **✅ Done, 2026-09-28.** `scripts/synthetic-session.py` (any length, the
> real rates, the box's GeoJSON beside); `MeasureSeries` takes a log
> (`MEASURE_FILE`); `MeasureRetiming` (a live run re-timed from its start).
> - **Checked:** 1 hour: 37.4 lines/s, time never backwards, a lap every
>   70 s (51). **8 hours: 1,075,677 lines, 125 MB, 12.6 MB gzipped, 411 laps.**
> - **The baseline**, one 8-hour session in 12 s chunks (~2,400), to
>   production (`00030`) as a throwaway car:
>   - **the car page's request for the session while uploading** (2,350
>     chunks in): **the first failed, `503` after 34 s: the container passed
>     its 512 MiB ("Memory limit of 512 MiB exceeded with 513 MiB used") and
>     Cloud Run restarted it**, dropping every socket. The second: **`200`
>     after 150 s** (3.9 MB). One viewer, once; the page asks every minute;
>   - **`complete`: 39.9 s** (the tablet waits 10 s);
>   - **building its series once complete** (local, a 384 MiB heap): 2.4 s,
>     **peak 178 MiB**: two at once would need ~356 MiB. The series is **16 MB
>     raw, 4 MB gzipped**, what the session page downloads;
>   - **re-timing the live run from its start: 6 ms** (25,623 fixes). At a
>     10 Hz receiver about 60 ms: **not worth making incremental**, so M19.5
>     is closed on this measurement.
> - All removed after.

### M19.2 — Compaction while uploading

Every few minutes (and at `complete`), a session's new segments composed into
one piece; the index updated conditionally, then the old objects deleted.
Readers holding an old list retry on a missing object. The in-memory store
composes by concatenation.

**Done when:** tests (a session compacted as it uploads reads back byte for
byte; a reader racing a compaction still reads it whole; the index record's
size bounded; a failed compose leaves the segments as they were); the real
bucket (`archive-smoke.sh`, a compaction and a byte-for-byte read).

> **Validated against the code, 2026-09-28, before building** (with M19.3,
> which touches the same code).
> - **Segments are gzip files**, so a composed piece is a multi-member gzip,
>   which `GZIPInputStream` reads straight through (the real bucket checks it).
> - **The index is the authority** (decision 17): a piece replaces a run of
>   segments by one conditional write that finds exactly those keys still
>   there; appends, which add at the end, can't conflict.
> - **Superseded objects aren't deleted until the session is finished**: a
>   reader holding an older list then never meets a missing object, and the
>   duplicate bytes (a few MB) live only until `complete`'s clean-up, which
>   deletes the whole segments folder as today.
> - **One piece, growing**: segment 0 (line 0) stays alone (a hash mismatch
>   keeps it); when 32 more have come after it, the first 32 after it (the
>   piece so far and 31 chunks) are composed into the next piece, within
>   compose's 32 sources. A session stays at most ~33 objects.
> - **When**: after a chunk is answered, in the background (as `prepare` is),
>   under the session's lock (M17.1's), so it never races `complete` or itself.
> - **`complete` read each segment whole into memory** (`store.read`): a piece
>   of an 8-hour session is 125 MB raw. Reads of pieces stream (M19.3 then
>   leaves `complete` nothing to read).

> **✅ Done, 2026-09-29.** `SegmentStore.compose` (Cloud Storage's compose;
> in memory, concatenation), `SessionIndex.compact` (`compacted`: the run's
> keys all still listed, the session not complete), `ArchiveService.compact`
> (under the session's lock; only sessions with a running hash), launched
> after each chunk's answer.
> - **Tests:** `LongSessionTest` (200 one-line chunks: at most 33 objects,
>   6 composes, byte for byte; a reader holding the list from before a
>   compaction reads it whole; a failed compose leaves the segments; a stale
>   compaction refused; exactly 32 after line 0 starts one), `ArchiveRoutesTest`
>   (one-line chunks through the route compacted as they come). **The real
>   bucket:** `archive-smoke.sh` (32 composed into a piece, read back through
>   its gzip members byte for byte).

### M19.3 — `complete` answers at once

A SHA-256 whose state can be stored (checked against the JDK's), kept with
`ackedThrough`; `complete` compares and answers; the single object composed
after, resumed on start if a session is complete and not yet one object.

**Done when:** tests (the running hash equals the whole log's; a mismatch
still resets as today; a restart mid-upload carries the hash on; a restart
before the final compose finishes it; reads before it use the pieces); the
real bucket; `complete` timed at 2,400 chunks.

> **Validated against the code, 2026-09-28, before building.**
> - **The JDK's SHA-256 can't hand over its state**, so a small SHA-256 of
>   our own (FIPS 180-4), checked against the JDK's on random data split
>   anywhere; its state (the eight words, the length, the partial block) is a
>   short string stored on the session's record, updated in the same
>   conditional write that acknowledges a chunk.
> - **Where the log begins**: line 0 is stored by `open` (a new record) or by
>   `setLine0` (a record the live lane made); both start the hash. A hash
>   mismatch resets to line 0 (`resetToLine0`), which restarts it from line
>   0's bytes (segment 0, one small read).
> - **`complete`** then compares, marks the session complete **keeping its
>   pieces**, and answers; `finish` composes the pieces into
>   `session.jsonl.gz`, marks the record assembled (segments cleared) and
>   deletes the segments folder. **Readers** use the pieces until then
>   (`read`), and **the admin's download** streams them zipped, as it does for
>   a session uploading. `finish` runs after the answer, and **on start for any
>   session complete and not assembled**.
> - **Sessions opened before M19** have no running hash: `complete` assembles
>   them as today, streamed.

> **✅ Done, 2026-09-29** (its timing at 2,400 chunks is M19.7's, in
> production). `RunningSha256` (FIPS 180-4, state as a short string);
> `SessionRecord.hashState` (memory, Firestore), carried by `open`,
> `setLine0` and each append, restarted by `resetToLine0`;
> `complete` compares and answers (`completeRunning`), keeping the pieces;
> `finish` composes them (in rounds past 32), marks the record assembled and
> deletes the folder, after the answer and on start (`unfinished`); `read` and
> the admin's download use the pieces until then. Sessions from before M19
> keep the old path, streamed, never compacted.
> - **Tests:** `RunningSha256Test` 3 (known answers; against the JDK's over
>   200 random inputs split and restored anywhere; the padding's edges);
>   `LongSessionTest` (complete reading nothing, finish; the hash across a
>   restart and finished by the next; a wrong hash starting again with the hash
>   from line 0; before M19; composed in rounds; announced by the live lane
>   first), `ArchiveRoutesTest` (finished after the answer; finished on
>   start), `SessionRoutesTest` (the download before it's one object),
>   `ArchiveServiceTest` updated (the corrupt-segment guard now on the path
>   before M19: the running hash trusts what was stored as acknowledged, as the
>   index does). **The real bucket:** `archive-smoke.sh` (answered with the
>   segments listed; finished into one object; three gzip members read back).
> - **Mutations (M19.2 and M19.3): 21, all killed**, four after the tests
>   above gained a session announced first, exactly 32, and the routes'
>   launches (two first rewritten, not compiling as written).
> - **Caught by the full suite before committing:** the "announced first"
>   test failed on its own (its helper skipped the `PUT` once a record
>   existed), so the mutant it "killed" hadn't been caught. Fixed, and **the
>   mutation tool now refuses to measure against a suite that fails without a
>   mutant**; that mutant, rerun, is caught.

### M19.4 — The car page without rebuilding the session

The live session's laps from the live run; "Whole session" built on request,
incrementally, shared, thinned; every read of segments with read-ahead; the
refill after a reconnect reading only the pieces its gap needs.

**Done when:** tests (laps from the live run the same as from the series;
two viewers asking at once cause one build; a second request after a new
piece reads only that piece; thinning keeps each span's peaks); the car page
measured with two 8-hour sessions streaming (frame rate, request times).

### M19.5 — The live run without starting over

`LapRule`, `PitLane` and the run's state kept per live session and course,
fed only new fixes; the result the same as re-timing from the start.

**Done when:** tests (incremental equals from-scratch on the box, across a
session change and a restart of the app); measured at 8 hours: a standing
worked out in milliseconds.

> **Closed on M19.1's measurement, 2026-09-28:** re-timing an 8-hour live run
> from its start takes 6 ms (25,623 fixes), about 60 ms at a 10 Hz
> receiver, every 10 s: a standing is already worked out in milliseconds.
> Nothing built; `MeasureRetiming` stays for measuring again.

### M19.6 — Finished long sessions

One `prepare` at a time; its memory measured with an 8-hour session at the
real rate on a 384 MiB heap (and the memory setting changed if needed); the
session page's series thinned for the whole view, full detail when zoomed.

**Done when:** tests (one build at a time; thinning); the heap measured; the
session page of an 8-hour session measured on a phone-sized page at 4x
slower CPU.

### M19.7 — Deploy, and prove it

Deployed with a stream across it. **Proof in production:** two throwaway
cars uploading synthetic 8-hour sessions at the tablet's chunk rate (at speed),
the car page open throughout: `complete` answered at once, the car page's
requests quick, the heap in Cloud Run's metrics, the session pages after.
All removed.

### M19.8 — Record it

A decision for how long sessions are stored and served; `COMPLETED`,
`JOURNAL` (the numbers before and after), `PLAN`, `README`; this plan deleted.

---

## Not in M19

- Anything on the tablet (its `complete` timeout, its clock): for the tablet
  side, through Sam.
- Re-timing a session before it completes; positions between cars.
