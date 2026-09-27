# M11 — What the first drive found

Fixes for the site from the first real drive (2026-09-27, `JOURNAL.md`). The
tablet's own findings (its clock, the G-meter's offset, the live link, the
fake session's `protocol`) are the app's, passed on in chat.

---

## What exists, and what it means for this (checked 2026-09-27)

- **The charging gauge's slot is one signal**, `control_module.voltage`
  (`SLOTS` in `web/src/lib/dashboard.ts`, with its own range and zones). The
  drive sent `vehicle.system_voltage` instead, also in V and in the contract's
  catalogue, which is the battery's voltage at the OBD port: while the engine
  runs, the charging system's. A slot can only show what the tablet sends,
  and **the tablet sends only its own dashboard's signals plus ticked
  extras**, so a slot naming one signal is fragile across cars and tablets.
- **`fuel.system_1_status` wasn't sent** for the same reason. That one is for
  Sam to tick as an extra on the tablet, not for the site to replace: there
  is no other signal that says it.
- **`source` isn't read anywhere.** `SessionHeader.parse` keeps `id`, `v`,
  `started`, `device`, `app`, `vin` and `protocol` from line 0, and the index
  record holds that header (so the session list, built from records, can't
  tell a tablet or fake session from a drive). The page's live stream sends
  the session record (without the VIN), so **the car page already has
  `source`**, and ignores it.
- **Drives** are sessions less than 10 minutes apart (`drives()` in
  `SessionRoutes.kt`), with no regard to what they are. §21 asks that fake data
  never count in a car's drives, bests, peaks or laps. The site keeps no bests
  or peaks across sessions; the G-meter's peaks are per page, and laps per
  session. So "never count" is: **not in a drive**, and marked wherever it's
  shown.
- **Sessions stored before M11** hold a header without `source`. Their log's
  line 0 has it; the summary (version 1) is built by reading the whole log
  once, so a **summary version 2** that reads `source` too rebuilds them on
  first view, as M7 did for sessions made before it.
- **The series route answers 404** for a session with no lines stored
  (`sessionRecord` without the live allowance), which a tablet session is for
  its whole life (it uploads at its end), and the car page asks every minute.
  `fetchSeries` treats 404 as "none" already.
- **The server serves only the pages and `/assets`** (`WebRoutes.kt`); the
  tab's icon is a hashed asset. An iPhone asks for `/apple-touch-icon.png`
  (and `-precomposed`) and `/favicon.ico` at the root: all 404 now.
- **The tablet's clock:** every live batch carries the tablet's `wall`, and the
  server has its own time as it arrives. The live lane (`CarLive`) keeps when
  data last came, not the difference.

---

## Decided here (say if any is wrong)

- **A gauge slot lists signals, in order; the first one a session sends is
  shown.** Charging: `control_module.voltage`, then `vehicle.system_voltage`,
  with the same range and zones. Generic (any car may send either), so the
  app's decision 33 holds.
- **Sessions say what they are**, on the car page's session line, in the
  session list and on a session's page: **"Tablet only"** (no car read) and
  **"Test data"** (invented readings, in the caution colour). A car's drive
  says nothing extra.
- **Test-data sessions are listed, but never grouped into a drive**: each
  stands alone, marked. Tablet sessions still group (they're the tablet's
  real signals, often the minutes around a drive).
- **The series route answers `204 No Content`** for a live session with no
  lines yet, rather than 404.
- **Icons at the root**: `/apple-touch-icon.png` (180 px, the bear, on the
  dark background), `-precomposed` the same, and `/favicon.ico`, made by
  `make_images.sh` from the logo like the others.
- **A tablet clock off by more than 2 minutes is flagged on the admin page**
  ("Tablet clock 10 h 58 min slow"), measured from live batches. Only
  flagged: times are the tablet's, as the contract says, and never corrected.

---

## The steps

Each validated against the code just before it's built, and the validation
written here.

### M11.1 — The charging gauge takes either voltage

`SLOTS.gauges` holds a list per gauge; the page shows the first signal in the
session's `signals` (or, before a session record, the first with a reading).
The tiles below leave out every signal in a slot's list.

> **Validated against the code, 2026-09-27, before building.**
> - **Slots are used as one name** in three places: the car page and the
>   preview (`{#each SLOTS.gauges as n}`, then `unitOf`, `latest[n]` and
>   freshness by that name) and `SHOWN` (what the tiles leave out, and what
>   `timings` times). So **every slot becomes a list** (most of one signal), and
>   one pure function, `slotSignal(choices, declared, latest)`, picks the
>   signal: the first the session declares; with no session record yet, the
>   first with a reading; else the first, which shows "—". `SHOWN` holds every
>   choice, so neither voltage becomes a tile.
> - **What a session declares** is `LiveState.signals` (from the session
>   record and any `signals` record), already what `unitOf` reads.
> - **The profile is by signal**: `vehicle.system_voltage` gets the same range
>   and zones as `control_module.voltage` (10–16 V, caution under 12.0,
>   critical under 11.5), generic battery facts.
> - **The existing slot test** (each signal once, `SHOWN` complete, at most 4
>   gauges, 6 numbers, 4 bars) carries over to the lists.

**Done when:** tests for choosing a slot's signal (the first declared, the
second when the first is absent, neither: "—"); looked at in Chrome with the
drive's shape (a replay declaring `vehicle.system_voltage` only) and the
synthetic race (`control_module.voltage`).

> **✅ Done, 2026-09-27.** Slots are lists; `slotSignal` picks; the car page
> and the preview use it; `vehicle.system_voltage` has the charging zones.
> - **Tests:** 2 new (101 in all). **Mutations: 5, all killed**: no declared
>   step, readings before the declared list (which needed a new case: a
>   declared signal beats a leftover reading), no readings step, the last
>   choice instead of the first, no battery profile.
> - **Looked at in Chrome:** the race changed to send only
>   `vehicle.system_voltage`, as the drive did: the fourth gauge "vehicle ·
>   system voltage", 13.6 V on 10–16 V with its bands, no voltage tile below.
>   The race as it is: the same gauge "control module · voltage", 13.7 V.

### M11.2 — Tablet and test-data sessions, said

- `:archive`: `SessionHeader` keeps `source`; the summary (version 2) records
  it, so older sessions gain it on first view.
- `:server`: the list and a session's detail carry `source`; `drives()` never
  puts a test-data session in a drive.
- `web/`: "Tablet only" and "Test data" on the car page's session line, the
  list and a session's page.

> **Validated against the code, 2026-09-27, before building.**
> - **The header** is parsed at `PUT` (`SessionHeader.parse`) and written to
>   Firestore field by field (`FirestoreSessionIndex`): `source` joins
>   `protocol` there, optional, absent when absent.
> - **The summary** is rebuilt whenever its `version` differs
>   (`ArchiveService.summary`), which the list does on first view: version 2
>   reads `source` from line 0 in `SessionReader`, and its Firestore mapping
>   gains the field. The Outback's seven sessions rebuild on the first look at
>   its list (7 logs read once, the largest 427 KB).
> - **The list item** (`SessionItem`) takes the summary's `source`, or the
>   header's while uploading. A live session before its `PUT` has neither; the
>   car page gets `source` from the live stream's session record anyway.
> - **`drives()`** groups by time only. Test-data sessions become drives of
>   their own, and **don't bridge** the real ones: a drive's gap is measured
>   from its last real session.
> - **Unknown `source` values** are shown as sent, never guessed at (§3.1's
>   spirit); only `fake` is kept out of drives.

**Done when:** tests for the header and summary reading `source` (absent,
`tablet`, `fake`, and an unknown value shown as sent); for `drives()` keeping
test data apart; a session stored before M11 gaining `source` on rebuild.
Replays of a tablet session and a fake one (the drive's own shape, made
synthetic) seen in Chrome: labelled, and the fake one alone in the list.

> **✅ Done, 2026-09-27.** `source` in the header (and its Firestore field),
> in the summary (version 2, and its field), in the list item (the summary's,
> else the header's); `drives()` keeps test data apart; `sourceLabel` on the
> car page's session line, the list and a session's page.
> - **Tests:** Kotlin 6 new: the header reading `source` as sent; the
>   summary carrying it; both Firestore mappings; `drives()` (tablet sessions
>   grouping, a fake one alone and bridging nothing); the list's `source`
>   from summary and header; **a session stored before M11** (a header
>   without `source`, a version-1 summary) gaining it on rebuild. Vitest 2 new
>   (103 in all).
> - **Mutations: 9, all killed**: fake grouped, the list reading only the
>   header, the reader dropping `source`, the version not bumped, the summary
>   mapping dropping it, the header parse dropping it; on the site, an empty
>   `source` labelled, test data called "Tablet only", an unknown source hidden.
> - **Looked at in Chrome:** a tablet session, a test-data one and a car's
>   uploaded: the list shows "Tablet only" grouped with the car's session, and
>   "Test data" (light pink) as a drive of its own; a session's page and the
>   car page's live session line say "Test data".
> - **Found by looking:** the list keyed drives by their start time, unique
>   while drives couldn't overlap. A test-data session now can start with
>   another drive, and the duplicate key stopped the list rendering at all.
>   Drives are keyed by a session's id.

### M11.3 — The small ones

- `GET /api/sessions/{id}/series`: `204` for a live session with no lines;
  `fetchSeries` treats it as none.
- The icons at the root.

> **Validated against the code, 2026-09-27, before building.**
> - **Why 404 now:** the series route finds a session only once it has lines
>   (`sessionRecord` without the live allowance). A session the live lane
>   created has a record from its first frame (M4), so with the allowance
>   (as `/api/sessions/{id}` has) it's found, `prepare` has nothing, and that
>   is the `204`. An unknown or malformed id stays `404`.
> - **Icons:** Vite copies `web/public/` to the site's root, in the dev server
>   and into the jar's `web/`. The server has explicit routes and no
>   fallback (on purpose: an unknown `/api/...` must stay `404`), so three
>   explicit routes serve `web/apple-touch-icon.png` (also as
>   `-precomposed`) and `web/favicon.ico`, cached for a day. They're made by
>   `make_images.sh` from the bear it already cuts: 180 px on the site's
>   background (read from `app.css`'s `--bg`, not written twice), and a
>   16/32/48 px `.ico`. The test resources' stub site gains stub icons.

**Done when:** a route test for the 204; the icons served (`curl`) and an
iPhone-sized "Add to Home Screen" looked at if Sam can.

> **✅ Done, 2026-09-27.** The series route finds a live session and answers
> `204` with nothing uploaded; `fetchSeries` reads `204` as none.
> `make_images.sh` makes `public/apple-touch-icon.png` (the bear on `--bg`,
> 180 px) and `public/favicon.ico` (16/32/48); three named routes serve them,
> cached for a day.
> - **Tests:** Kotlin: the `204` while live (and `404` before, and for no
>   session); the icons at the root, typed and cached, and an unnamed size
>   still `404`. An older assertion pinned the old `404` while live and was
>   replaced. Vitest 1 new (104 in all).
> - **Mutations: 4, all killed**: the series without the live allowance, the
>   precomposed name dropped, cached a year, `204` not understood.
> - **Checked for real:** the dev server serves both icons byte for byte as
>   made, and Vite serves them too; a live tablet session with nothing
>   uploaded answers `204`, an unknown one `404`.
> - **Found by looking at the icon:** the first cut was black and white. The
>   bear's transparent pixels kept whatever colour they held when the alpha
>   went, because ImageMagick's earlier `DstIn` still applied to the flatten;
>   `-compose Over` first, and it's the bear in colour on the dark ground.
> - **Left for Sam:** "Add to Home Screen" on the iPhone, after the deploy.

### M11.4 — The tablet's clock, flagged

The live lane records each car's clock difference (the server's receipt time
minus the batch's last `wall`, smoothed); the admin page shows it when it's
over 2 minutes.

> **Validated against the code, 2026-09-27, before building.**
> - **Where:** `CarLive.apply` takes each batch at `clock.instant()`, and every
>   record carries the tablet's `wall`. A batch's newest `wall` is "now" on the
>   tablet, give or take the network's delay, so **now minus it** is the
>   clock's difference plus that delay.
> - **Smoothed by the minimum**, over the last 50 batches (10 s at 5 a
>   second): delay only ever adds, so the smallest is the truest. A batch held
>   back by a dead zone and sent late doesn't move it, and a corrected clock
>   shows at once. **Snapshots don't count**: they carry each signal's latest
>   reading, which can be minutes old.
> - **Kept in `CarStatus`** (as `clockOffset`), which the admin page's car
>   view already reads. **Not public:** browsers' status is written field by
>   field (`BrowserRoutes`: the state and `lastDataAgoMs`), so a new field
>   isn't sent. It's kept after a disconnect, so the page can still say what
>   the tablet's clock last was.
> - **Shown** by the admin page when over 2 minutes either way: "Tablet clock
>   10 h 58 min slow" (or "fast"). A pure function words it, tested.

**Done when:** tests for the difference (steady, one late batch not moving
it, a fixed clock bringing it back under 2 min); the admin page with a replay
whose clock is shifted by hours (the replay's times moved, never the log's).

> **✅ Done, 2026-09-27.** `CarLive` keeps the smallest (arrival − newest
> `wall`) over the last 50 batches as `CarStatus.clockOffset`; the admin car
> view carries it as `clockOffsetMs`; `clockNote` words it past 2 minutes.
> - **Tests:** Kotlin: the drive's 10 h 58 min measured from batches, the
>   least-delayed batch winning; a snapshot before any batch giving none;
>   kept after a disconnect; a batch four minutes late not moving it; a
>   corrected clock at once; a fast clock forgotten when it leaves the window;
>   the admin list carrying it, and none before streaming; **the public
>   stream never holding it**. Vitest 2 new (106 in all).
> - **Mutations: 6, all killed**: the largest instead of the smallest,
>   snapshots counted (it survived at first: with the minimum, an old
>   snapshot only raises it, so the test now sends one before any batch,
>   where it would have said minutes slow), the window never trimmed, the
>   admin view dropping it, exactly 2 minutes said, slow and fast swapped.
> - **Looked at in Chrome** (admin page, dev sign-in): a replay whose times
>   were moved 10 h 58 min back reads "Tablet clock 23 h 30 min slow": right,
>   since a replay sends its log's own times, and the synthetic race's were
>   12 h 33 min old already. The note is in the caution colour.

### M11.5 — Deploy, and record

Deployed with a drive streaming; the Outback's page and past sessions looked
at on badnewsbears.live (its seven sessions from the first drive: one drive
of the car and tablet sessions, the test-data one apart and marked, the
charging gauge filled when the next drive comes). A decision for session
sources and slot lists; `COMPLETED.md`, `PLAN.md`; this plan deleted.

---

## Not in M11

- Correcting the tablet's times (they're the tablet's; its clock is to be
  fixed on the tablet).
- The G-meter's offset, and the live link's reconnects: the tablet's.
