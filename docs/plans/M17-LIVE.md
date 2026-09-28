# M17 — Live: the car page, the event page and the tablet

The last milestone of race logging (`RACE-LOGGING.md`): what the server
knows about a car **while it is being driven**. The tablet gets the `timing`
frame (contract §22.7): who's driving and for how long, the race's lap count,
the time since the stop, the event's bests. The car page shows the same, with
each lap's sectors as they arrive. The event page counts the stint being
driven now.

Nothing here changes the contract: `timing` is §22.7 as agreed, and the tablet
already lists `timing.1` and shows it (the app's M44, `ServerTiming`).

---

## Settled with Sam, 2026-09-28

- **The event page is live in M17**: during a part, the results count the
  current stint's laps, marked as live, and the page refreshes itself.
- **The crew sets who's driving from the car page too**: a driver picker for
  the current session, behind the crew's passcode, as messages are.

---

## What exists, and what it means (checked 2026-09-28)

- **The tablet is ready.** It sends `hello.features: ["courses.1", "timing.1"]`,
  parses `timing` whole (`ServerFrame.Timing`), ignores one for another
  session, counts ages on from arrival, and keeps the last one through a drop,
  marked stale after 30 s. It uses the server's `best` as its delta's reference
  only when it holds that lap's fixes.
- **The live socket** (`LiveRoutes`) answers `hello` with `welcome`, the crew
  messages and, to a `courses.1` tablet, the `courses` frame (`CourseDownlink`,
  which also tells every listening tablet when a course changes). `timing`
  goes the same way.
- **The live lane has each lap as it's timed.** `lap` records come whole in the
  next batch (§5.2 coalesces samples, never records). But `CarLive` keeps only
  **5 minutes of history** and forgets it all on a restart. **The archive has
  the rest**: chunks arrive every 2 minutes, and a session's prepared series
  can be read before it's complete. The car page already joins the two: the
  series' laps, then the live lane's after its `lastSeq` (`lapsFrom`).
- **Results count only complete sessions.** `RetimingJobs.sessionsOf` keeps
  complete ones, so a run, an event's practice and the race (`Race.car`) see
  a session only once it's uploaded whole. **During a race the stint being
  driven is invisible** until its session ends.
- **`Race.car` takes runs** (`RunTiming`: laps on the tablet's `at` with
  `wall` offsets, pit crossings). A **provisional run** built from the live
  session's `lap` records would go through it unchanged. Its restart bridge
  only adds a lap where there's a gap between runs, so a live session
  continuing a run adds nothing false.
- **Stops come from fixes** (`PitLane.offer(session, fix)`, which already takes
  one fix at a time), and the live lane has the positions (every fix at 1 Hz;
  at least one per 200 ms batch at 10 Hz, which is ample for a line).
- **The tablet's clock against ours** is measured live (`CarLive.clockOffset`,
  from batches), so "now" on the tablet's `wall`, where every lap, stop and
  stint is, is the server's now less that.
- **Drivers are set per session** (`PUT /api/cars/{slug}/sessions/{id}/driver`,
  crew or admin), and in a race, stints as edited replace the default.
- **The replay tool** plays a `courses.1` tablet (`--courses`) and has the
  frames it receives; one playing `timing.1` is a small addition.

---

## Decided here (say if any is wrong)

- **`timing` is sent** to a tablet listing `timing.1`: once a session is
  running after every `hello` (at its `session` frame, which follows the
  `hello`), and **whenever anything in it changes**: a lap arrives, the car
  crosses a pit line, a session starts or completes, a driver is set, stints
  or flags are edited, the event or the course is saved. It's compared
  without its ages, so a frame goes only when something real changed.
- **Its fields, per §22.7:**
  - **`course`**: the event's course and layout if the car is in an event part
    now, else the course and layout of the car's latest lap; always the
    course's **current version**, so a tablet on an older one fetches.
  - **`best` and `bestSectors`**: over **this car's** laps in the event (every
    part, every driver, the live run included), never an in- or out-lap, and
    sectors by §22.6's rule. With no event: this car on this layout, over its
    complete sessions at the course and the live run. `best` names its
    driver's code, session and lap number.
  - **`driver`**: the live session's driver as set. In a race, the current
    stint's, which edited stints can name. **The stint's age**: since the
    current stint began. In a race that's the race's current stint. Outside
    one, stints split at stops the same way: since the last pit exit in this
    run, else the run's first lap.
  - **`race`**, only while the car's session is in a race part: the lap count
    through the race (complete sessions and the live run, restart laps
    included), and **the time since the car left the pits**: since the last
    stop's exit, or before the first stop, since the race's first lap began.
  - **Ages** are the tablet's `wall` now (the server's now less the measured
    offset) less the moment, measured when the frame is sent (§22.8).
- **The live run is provisional and never stored**: the current session's `lap`
  records (the partial series, then the live lane's), and its pit crossings
  from live fixes. When the session completes, its re-timed run replaces it.
  After a server restart it's rebuilt from the partial series.
- **Its laps stand as the tablet sent them**: no re-timing until the session
  completes (decision 33 re-times complete runs only).
- **The car page gets it too**, on its public stream: a `timing` event with
  the driver's name, the stint's time, the race's lap count, the time since
  the stop, and the best and theoretical best. The public stream never
  carries more than the page shows.
- **The event page, live**: while a part is on, results include the live
  run's laps, each marked live, and the page refreshes every 30 s. The server
  holds an event's results for 10 s, so many viewers cost one computation.

---

## The steps

Each validated against the code just before it's built, the validation
written here.

### M17.1 — Answer the tablet's §23

§23 asks the backend two things: that a second `complete` for a complete
session answers `200` with the same values and changes nothing (the code
does: `ArchiveService.complete`, same end and hash), and when the session of
2026-09-28 (started 17:22:27Z, car ending …394138) was first completed.

**Done when:** a test pins the repeated `complete` (the same answer; nothing
changed, the session not prepared twice); the first `complete`'s time is
found in Cloud Run's logs; the answer is written for the tablet side, in chat
(never in the contract), and noted in `PROTOCOL.md`.

> **Validated against the code and the logs, 2026-09-28, before building.**
> - **A second `complete`** with the same end and hash answers `Done`, so
>   `200 {"complete": true}` (§6.3's whole answer), and changes no record. But
>   the route then **prepares and re-times the session again**, in the
>   background: harmless, and not "nothing".
> - **The session** is `69c4ace7-…` (heard from 17:22:27Z, 58,381 lines, about
>   135 chunks). Cloud Run's log: **two `complete`s at 17:48:30.66 and
>   17:48:31.24, both `200`, after 16.7 s and 17.3 s**, then a third at
>   18:07:45, `200` in 99 ms. `0a8dc089-…` (the morning's, 52,138 lines) took
>   **30.0 s**, then 81 ms. Short sessions take about 0.5 s.
> - **The tablet's archive client is OkHttp's default** (`OkHttpArchiveApi`,
>   `OkHttpClient()`): a **10 s read timeout**. So the first `complete`
>   succeeded here and **the tablet gave up waiting for the answer** before it
>   came. That, not a failed mark, is why it wasn't marked sent.
> - **Why 17–30 s:** completing reads every segment from the bucket **one
>   after another** (`assemble`), about 100 ms each.
> - **Two `complete`s at once** both assemble; the first then deletes the
>   segments, which the second may still be reading (a 500). They were lucky.
> - So, beyond the answer: **segments read ahead** (8 at a time, in order),
>   **one `complete` per session at a time** (the second waits, and finds it
>   done), and **a repeat does nothing again** (`AlreadyDone`, answered as
>   `Done`).

> **✅ Done, 2026-09-28.** `ArchiveService.complete`: a lock per session
> (striped, 64), `assemble` reading 8 segments ahead, `Complete.AlreadyDone`
> answered `200 {"complete": true}` with nothing prepared again.
> - **Tests:** 1 new (two at once: one assembles, the other waits and finds it
>   done, one write), 3 extended (a repeat changes no record and writes
>   nothing; the route's repeat answers the same body).
> - **Mutations: 5, all killed.**
> - **The answer for the tablet side** is in chat; the timing after the deploy
>   is measured in M17.7.

### M17.2 — The live run, held on the server

`:timing`, pure: a `LiveRun` fed the current session's `lap` records (whole,
once each by `seq`) and its fixes (`PitLane.offer`), giving a provisional
`RunTiming`. `:server`: one per car, fed from the live lane, and rebuilt from
the session's partial series when it's missing (a restart, a reconnect).
Dropped when its session completes.

**Done when:** tests: laps kept whole past five minutes; a lap sent twice
counted once; pit crossings from a stream of fixes the same as from the whole
log; rebuilt from a partial series, then carrying on from the live lane
without a lap lost or doubled; `Race.car` with the live run continuing a run,
and after a restart (the bridge lap).

> **Validated against the code, 2026-09-28, before building.**
> - **Re-timing already takes a run as sessions with their traces**
>   (`Retiming.retime(course, [(id, SessionTrace)])`): the tablet's laps on the
>   current version stand as sent, re-timed ones fill in, pit crossings come
>   from `PitLane.offer` fix by fix, and `wallOffsets` from the fixes. So **the
>   live run is a `SessionTrace` fed record by record**, re-timed the same way
>   when asked. `SessionTrace` parses lines; it gains `record(JsonObject)`, and
>   the session record's `device` and the records' first and last `at`, to
>   group sessions into runs as `runs()` does from summaries.
> - **Not only the live session:** at a driver change the live session ends,
>   and its upload completes minutes later (§23 shows how late). Until then
>   it's in no run. So **every session the server streamed and that isn't
>   complete yet is provisional**, grouped into runs of the app (one device,
>   `at` rising), and dropped once complete (the archive's `prepared` hook) or
>   12 h after it was last heard.
> - **Feeding it:** batches' records after `CarLive` takes them, never a
>   snapshot's (the latest of each signal, however old). The live lane's `seq`
>   has gaps by design (coalescing), so gaps can't show a lost lap. So a session
>   first seen mid-drive (a server restart, or a reconnect after one) is **read
>   from its partial archive first**, then fed live records past its last `seq`;
>   and **after any reconnect, the archive is read again for `lap` records** a
>   few minutes later (they were sent while the link was down), each kept once
>   by `seq`.
> - **Off the socket:** a queue per car and one worker, so reading an archive
>   never holds up a tablet's frames; readers take a lock and a copy.
> - **`Race.car` with provisional runs:** a live session continuing a complete
>   one in the same run of the app joins without a bridge when the tablet timed
>   it (its first lap starts where the last ended, to the millisecond). Where
>   only re-timing timed it, the lap across the two sessions shows as a restart
>   lap until the session completes and its run is re-timed whole: marked, and
>   temporary.

> **✅ Done, 2026-09-28.** `:timing`: `SessionTrace.record` (a record at a
> time; `device`, first and last `at` and `wall`, `lastSeq`; a `lap` kept once
> by `seq`), `LiveSession`, `Provisional.runs` (grouped by `runs()`, each
> re-timed by `Retiming.retime`). `:server`: `LiveTimings` (a queue and worker
> per car; loaded from the partial archive; refilled with laps after a
> reconnect; let go on `complete` or after 12 h), fed by the socket after
> `CarLive` takes a frame.
> - **Tests:** `:timing` 5 (fed a record at a time, the same as the whole log;
>   a lap twice, once; runs joined and parted by a restart; nothing yet, left
>   out; the race with a live session continuing a complete one, and after a
>   restart), `:server` 4 (held whole, a lap twice once, a snapshot never;
>   first seen mid-drive; laps after a reconnect from the archive; let go).
>   The box fixture's `lap` records now have their own `seq`, as a real log's.
> - **Mutations: 16, 15 killed, 1 as good as equivalent** (a refill feeding
>   fixes as well as laps: the trace drops any older than the last, and the
>   archive is always behind the lane; laps only is for cost). Two survivors
>   first, killed after the tests fed a lap out of order and restarted `at`
>   partway into the run before.
> - **Not tested yet:** the `complete` route letting the live run go; tested end
>   to end with `timing` (M17.4).

### M17.3 — Where the car stands

Pure: from the event (if the car is in a part now), its results over complete
sessions, and the live run: `course`, `best`, `bestSectors`, the driver and
the stint's start, the race's lap count and the last pit exit. The same state
for the tablet, the car page and the event page.

**Done when:** tests: no event (the car's best on this layout); practice;
the race before and after a stop; edited stints naming the driver; a best set
in the live run; in- and out-laps never best; a car not entered in the event
now.

> **Validated against the code, 2026-09-28, before building.**
> - **Whether the car is in an event now** is the rule results use:
>   `EventRules.sessionsIn` over the car's records. A streamed session has an
>   index record from its announcement (`archive.announce`), so the current
>   session joins a part as soon as it starts, and the tablet, the car page and
>   the event page agree.
> - **One set of runs for everything**: the car's complete runs on the course
>   (`RetimingJobs.lapsOf`, stored re-timings) and its provisional ones
>   (`Provisional.runs` over `LiveTimings`), on the event's layout. The best and
>   best sectors come from their laps in the event's sessions (by §18 and
>   §22.6's rules), the race from `Race.car` over them, as results do.
> - **Everything on the tablet's `wall`** (a lap's `at` plus its session's
>   `wallOffsets`, as `Race` does), turned into ages only when a frame is sent.
> - **`best.lap`** is the tablet's own lap number (`RunLap.tabletLap`), so the
>   tablet can tell whether it holds that lap; null for a re-timed lap. Its
>   driver is the race stint's where the lap is in the race, else the session's.
> - **In the pits** (a stop with no exit yet), `race.sinceStopAgeMs` is left
>   out: the car hasn't left.
> - **Pure** (`Standings.of`, tested with runs as `RaceTest` builds them); the
>   gathering (`CarTimings`) holds the complete runs until the car's complete
>   sessions, the event or a course change.

> **✅ Done, 2026-09-28.** `Standings.of` (pure: `CourseAt`, `BestLap`,
> `DriverNow`, `RaceNow`), `CarTimings` (the event by `sessionsIn`; the course,
> the event's or the latest live lap's; complete runs held per car until the
> course version or the sessions change; provisional runs from `LiveTimings`).
> - **Found while building:** in a race with stints as they fall, a driver set
>   from the car page wouldn't show until the next stop, since default stints
>   split only at stops. So **stints the crew edited name the driver; else the
>   session's driver as set; else the stint's**. And outside a race, the last
>   pit exit is looked for **over all the car's runs**: a complete run and a
>   live one can be one run of the app, split only by an upload.
> - **Tests:** `StandingsTest` 6 (no event: best on the layout, its tablet lap
>   and driver, the pit rule for sectors; a stint from the last pit exit, in any
>   run; only the event's sessions; the race's count, time since the stop, in
>   the pits, stints as they fall and as edited), `CarTimingsTest` 4 (through
>   in-memory stores, a hub and `LiveTimings`: no event; a race through a
>   complete session and a live one; a session outside the parts; not in a
>   session).
> - **Mutations: 17, 16 killed, 1 not showable with this data** (`counted`
>   ignored inside an event: `a` is outside it, so its run is never gathered;
>   the rule itself is `StandingsTest`'s). One survivor first: practice read as
>   a race, killed once the test said there's no race.

### M17.4 — `timing` down to the tablet

A `TimingDownlink` beside `CourseDownlink`: to a tablet listing `timing.1`,
after its `session`, and whenever the state changes (compared without ages).
Ages from the tablet's `wall` now. `replay --timing` plays such a tablet and
prints what it receives.

**Done when:** tests: none to a tablet without `timing.1`; one after
`session`; one on each change (a lap, a driver set, stints edited, a stop), and
none when nothing changed; another car's never; ages from the measured offset;
the JSON exactly §22.7's shape, as the tablet's parser reads it (the app's
`ServerFrame.parse`, read, not run). A replay into the dev server shows the
frames.

### M17.5 — The car page, live

The public stream's `timing` event; on the page, a panel for the driver, the
stint's time and, in a race, the lap count and time since the stop, counted
on in the page between events; each lap's sectors in the laps panel, the best
of each marked; for the crew, the driver picker for the current session.

**Done when:** tests for the panel's logic (ages counted on; nothing shown
before an event; a sector's best); looked at in Chrome with a replay
streaming: a driver set from the car page, reaching the tablet's frame and the
panel within seconds.

### M17.6 — The event page, live

Results with the live run: its laps counted, marked live; the race's
section and the lap chart with the stint being driven; the page refreshing
every 30 s during a part; results held 10 s on the server.

**Done when:** tests (the live run in practice results and in the race; a
completed session replacing its live run without a lap lost or doubled; the
10 s hold); looked at in Chrome: a race's lap count going up while a replay
streams.

### M17.7 — Deploy, and prove it

Deployed with a stream across it (a second throwaway car's, under
`caffeinate -i`). **Proof in production:** a throwaway car streaming live as
a `timing.1` tablet into a test event's race (a test course, two test
drivers): `timing` frames arriving with the lap count and a driver set
through the crew's passcode, the car page's panel, the event page counting
the live stint. All removed after.

### M17.8 — Record it

A decision for what the server says live; `COMPLETED`, `JOURNAL`, `PLAN`,
`README`, `PROTOCOL.md` (`timing` built); race logging's plan closed; this
plan deleted.

---

## Not in M17

- Anything that changes the contract, or the tablet.
- Re-timing a session before it completes.
- Other teams' cars; positions between cars.
