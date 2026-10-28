# M33 — Merged sessions: several stored sessions, one session everywhere you look

**Sam, 2026-10-22:** "i'd like for something on the session list page to
allow me to merge sessions. they could be separate as data items under the
hood, but treated as one session in the UI and for all other practical
purposes." Asked how far it goes, he chose: sessions that look like one
drive are **suggested** for merging ("These look like one drive — merge?"),
merged only on a click; sessions in **different event parts can't be
merged** (same part, or none); and it's built **right after M32**, before
M34, M37 and M38, so those build on it instead of being reworked.

> **Planned 2026-10-22**, checked against the code. Builds on M32 (who
> drove belongs to the session; race stints worked out from sessions'
> drivers), whose timeline rule a merged session's drivers use.

---

## What exists, and what it means for this (checked 2026-10-22)

- **A session is a `SessionRecord`** (`archive/.../Stores.kt:24`), one
  Firestore document per session in `sessions`
  (`archive-gcp/.../FirestoreSessionIndex.kt:39`), every change a
  transaction conditional on the document's state (decision 17). Its id is a
  UUID (`SessionIds`, `archive/.../Records.kt:108`), and the site routes a
  session page only for a UUID (`web/src/lib/routes.ts`, `SESSION`).
  **So a merged session gets a UUID id of its own**: every route, regex and
  link shape works unchanged.
- **Nothing groups sessions today except views of them:**
  - `drives()` (`SessionRoutes.kt:301`) groups a car's list by a 10-minute
    gap, for display only;
  - `runs()` (`timing/.../SessionTrace.kt:147`) joins a car's sessions into
    one run of the app: same device, each one's first `at` above the last's
    last `at` (a smaller `at` means the app restarted), within 12 h.
    Re-timing carries across a run, so **a lap spanning two sessions of one
    run already exists**, as a lap of the session it ended in;
  - the race (decision 43, `Race.sessions`) reads several sessions as one
    timeline.
- **Event placement is per session:** `EventRules.sessionsIn(event,
  sessions)` (`events/.../EventRules.kt:79`) gives each session its part by
  when the server heard it, plus `added` and `removed` by hand, keyed by
  session id. It has **six callers** (`EventResults.kt:276`,
  `EventRoutes.kt:210`, `SessionRoutes.kt:143`, `CarTimings.kt:40` and `:50`,
  `EventDownload.kt:366`). Left alone, a later edit to the event's windows
  could put a merged session's members in different parts.
- **Event results are a row per session, or per driver's stretch**
  (`SessionResult`, `Results.stretches`, `EventResults.kt`); the race,
  driver pages (`DriverRecord`) and the download (`EventDownload.kt`, whose
  `MergedLog` already writes several sessions back to back) read the same
  placement.
- **A session's page reads four public routes** (a fifth, `/live-laps`, is
  for a live session only), all through
  `sessionRecord()` (`SessionRoutes.kt`), which also hides a hidden car's
  sessions (M25): `/api/sessions/{id}`, `/laps` (complete sessions only,
  timed on the event's course, else its own: `laps(car, id, on)`),
  `/series` (the prepared series, or `?thin=1` for a long one; the page opens
  thinned at `LONG_SESSION_MS` = 1 h and loads full detail under a 30-minute
  view, `web/src/lib/sessionPage.ts:313–320`), and `/messages`. They are all
  in `HiddenTest`'s list of public reads (lines 129–133). A lap link
  (`lib/laps.ts` `lapLink`) is `?from=&to=` on the tablet's clock, so it
  names a moment, not an offset into one session's series.
- **The list never names a device:** `SessionItem` (`SessionRoutes.kt:65`)
  has no `device`, and `drives()` works on items. Whatever needs the device
  is worked out on the server.
- **`web/src/lib/merge.ts` already exists:** it merges the archive's series
  with the live lane's (M7.6). The new code says *join* for series and
  keeps out of that file.
- **`adminCarRoutes`** (`AdminRoutes.kt:196`, which has the delete) is given
  no `onChanged` today: a session's delete doesn't clear the results' hold.
- **Deleting** is `DELETE /api/admin/sessions/{id}` (`AdminRoutes.kt:324`),
  the car's creator or a master admin (M23), refused while live or uploading
  (`CarAdmin.checkDeletable`, `UPLOAD_QUIET` 5 min). **Naming** is
  `PUT /api/admin/sessions/{id}/name` and the crew's
  `/api/cars/{slug}/sessions/{id}/name` (`DriverRoutes.kt:90`, `:98`).
- **NHMS Saturday** (race session 1) is five of the car's sessions: 09:23
  (2 h 31), 11:55 (5 min, tablet only), 12:00 (3 h 17), 15:18 (5 min,
  tablet only), 15:23 (1 h 59). Tablet-only sessions sit between car
  sessions at each restart; whether they touch or overlap their neighbours
  is measured in M33.1, because "no overlap" depends on it.

## Decided here

- **A merge is its own small record**, not a field on the members:
  `MergedSession(id, car, members: List<String>, name, created, updated,
  revision, split: Boolean)`, in a new `MergeStore` (interface and in-memory
  in `:archive`, Firestore in `:archive-gcp`): collection `merges` (one
  document per merged session) and `mergeMembers` (one document per member
  session, holding its merge's id). Creating a merge is one transaction that
  `create`s every `mergeMembers` document, so **a session can be in only one
  merge**, by the same conditional writes as decision 17; unmerging deletes
  them with the merge's revision checked. A field on `SessionRecord` would
  need every member's document changed in one transaction through an
  interface built for one session at a time, and every reader of the index
  would carry it.
- **Rules for a merge**, checked on the server, each refusal saying why:
  one car; at least two sessions; every member complete; in time order with
  no overlap (touching is fine; how much overlap to tolerate is set from
  M33.1's measurement); none already in another merge; **all in the same
  event part, or in none**; the merger may edit the car (`Action.EDIT`, as
  setting a driver). A merge may be **extended** with more plain sessions,
  keeping its id; two merged sessions can't be merged ("Unmerge one first").
- **Placement treats a merge as one unit.** `sessionsIn` gains the car's
  merges: a merged session is placed by its whole span (first member's
  first heard to last member's last heard); a member named in a part's
  `added` or `removed` applies to the whole merge. Callers still get member
  ids, in order, so the race, timing and the download don't change, and the
  members can never land in different parts.
- **What the site shows as one:**
  - **the sessions list:** one row (started, ended, laps, best, faults,
    event, the merge's name), with `members` in the item;
  - **the page and its URL:** `/cars/{slug}/sessions/{mergeId}`. A member's
    own URL still answers, and its detail carries `mergedInto`, so the page
    moves to the merged one (lap links, which name a member and a window,
    keep working);
  - **event results:** one session row (per stretch, as now) per merged
    session in every part, practice and race alike, with the merge's id,
    its stretches over the whole merged timeline (M32's rule), and
    `merged` (member id → merged session id) so a race lap's session can be
    linked to the merged page; the race itself is unchanged;
  - **driver pages** follow the results; **the download** writes members in
    order as now and names the merge in `sessions.json`.
- **The merged page's data:**
  - **detail:** `/api/sessions/{mergeId}` answers the folded `SessionItem`,
    the signals of all members, and `members` (each with its id, start, end,
    driver and driver changes) for M34's Drivers block;
  - **laps:** `/api/sessions/{mergeId}/laps` concatenates the members' laps
    on one course: the event's if the merge is in one, else the first member
    with laps' course (`laps(car, id, on)` re-times the others on it).
    Numbered 1, 2, 3… through the merge, known by when they ended as always
    (decision 44), each carrying its member's id (`session`), so lap links
    and compare picks name the member and its window as they do now. A lap across a join exists only within one run of the
    app, where re-timing already gives it; across a restart there's a gap,
    not the race's `restart` lap;
  - **series:** joined in the browser from the members' own series (a new
    pure `joinSeries` in `web/src/lib/`), each fetched thinned when the
    merge is over `LONG_SESSION_MS`, full detail loaded only for the members
    a narrow view covers. No merged series is built or stored on the server;
    each member's stays cached and tagged as now (decision 41);
  - **messages:** the window from the first member's start to the last's end.
- **Names, deletion, unmerging:** the merge has its own name (defaulting to
  the first member's); the name routes accept its id. Deleting a merged
  session deletes every member (listed in the confirmation, `Action.DELETE`
  as now), then the merge. **Unmerging** keeps the merge document with
  `split = true`, so its URL moves to the first member instead of 404ing.
- **Suggestions, never automatic:** in a car's list, two or more consecutive
  complete sessions **in the same drive** (`drives()`'s 10 minutes), from the
  same device, unmerged, in the same part or none, show "These look like one
  drive — Merge" to whoever may edit the car. Restarts (a new run) and
  adapter reconnects (the same run) are both suggested. The server works
  them out (it knows the device) and sends each drive's `suggested` lists
  of ids; the list never carries a device.
- **Results' hold** (`ResultsHold`, M17.6; made long-lived in M35) is
  cleared on every merge, extension, unmerge and delete: the merge routes
  and `adminCarRoutes` take `onChanged = edited`.

**Decision 49 (drafted, written in the same commit as M33.3):**

> **A merged session: several stored sessions, one session on the site.**
> (M33; Sam, 2026-10-22.) A car's editors can merge two or more of its
> complete sessions, in time order without overlap, in the same event part
> or in none, into one merged session, with an id, a name and a page of its
> own. The members stay as stored, untouched: the archive, re-timing and the
> tablet never see a merge. Everything a visitor reads counts it once: the
> car's list, its page, event results, driver pages and the download.
> Placement treats a merge as one unit, so its members are always in the
> same part. A driver change on a merged session's page is stored on the
> member its moment falls in (decision 48). Unmerging restores the members;
> deleting a merged session deletes them all. Sessions that look like one
> drive are suggested for merging, never merged without a click.
> **Why:** a drive is often several sessions (an app restart, an adapter
> reconnect, a tablet-only minute between), and every page showed the
> pieces. Merging the stored logs would break every link to them (laps,
> re-timings, driver changes, events, the live page) and leave logs the
> tablet never sent; a merge record changes only what's shown.

## The steps

### M33.1 — Measure first (no code)

On production's data, by Sam or with the public APIs: NHMS Saturday's five
sessions' starts and ends (do the tablet-only ones overlap their
neighbours, and by how much?), and the five thinned series' total size and
point count. On the dev server, a page joining five synthetic series of the
same sizes, measured with `web/scripts/measure.mjs` at 4× CPU slowdown.
**Gate:** if it isn't usable (long tasks, a heap past the M8 budget), the
merged page opens on the members' thinned series only and the plan says so
before M33.7. The overlap found sets the tolerance in M33.2. Recorded here
and in JOURNAL.

**Measured 2026-10-28** (public APIs; the page through a scratch script that
times the load itself, since `measure.mjs` counts from 8 s after it):
- **Nothing overlaps.** NHMS Saturday's five sessions follow on 12.4 s,
  0.2 s, 13.2 s and 0.2 s apart (the list's `started`/`ended`, the
  summary's); Sunday's four 0.2–4.0 s apart. The tolerance is **5 s** of
  overlap, for the two clocks behind `started` and `ended`; anything more
  refuses.
- **Saturday joined weighs 2.97 MB** (952 KB gzipped; 110k number points,
  19k positions, 237 laps), against 1.21 MB for its largest member and
  1.58 MB for the site's largest session today (Sunday's 4 h 13).
- **The page holds up:** the real session page, its series answered with
  Saturday's five joined (as `joinSeries` will), on a 390 px phone at 4×
  CPU, twice each:

  | | chart drawn | long tasks | longest | heap |
  |---|---|---|---|---|
  | 57e1f881 (1.2 MB) | 1.4 s | 2, 300 ms | 227 ms | 14.1 MB |
  | c1516a5d (1.6 MB) | 1.2 s | 2–4, 295–416 ms | 240 ms | 17.4 MB |
  | Saturday joined (3.0 MB) | 1.3–2.8 s | 3–4, 485–525 ms | 346 ms | 18.9 MB |

  **Gate passed:** the merged page opens on the members' thinned series
  joined, as planned.

**Validated 2026-10-28, after M32:**
- `sessionsIn` has **seven** callers now (`MigrateStints.kt:127` too); M33.4
  passes merges to all of them except the migration, which is done.
- **One collection, not two.** Firestore's `DriverStore.put` already keeps a
  code unique by reading every driver in its transaction; a car has a
  handful of merges, so a save reads the car's merges (`car ==`) in its
  transaction and refuses a member already in another live one. That keeps
  "a session in only one merge" without a `mergeMembers` collection to keep
  in step. **One `save(merge, expected, now)`**, as `EventStore`'s: create
  (expected 0), extend, rename and split are all a save on the revision.
- **The rules take a small input**, not `SessionRecord`: each candidate's id,
  car, complete, started and ended (the summary's, as the list shows them),
  its part's name or null, and the merge it's in. Problems carry the
  sessions they're about, and their words name no clock time (the server
  doesn't know the viewer's zone; the list marks the rows).
- **Deleting one member** on its own is refused ("It's part of a merged
  session: unmerge it first"), in `CarAdmin`, so the admin tool refuses too.
- `MergeStore` goes in `:archive` beside `SessionIndex`; the Firestore one in
  `:archive-gcp`; `module` takes `merges: MergeStore = InMemoryMergeStore()`
  as it takes users and access, production passing Firestore's.

### M33.2 — The store and the rules (tested; in memory and Firestore)

`:archive`: `MergedSession`, `MergeStore` (`create`, `extend`, `split`,
`get`, `ofCar`, `mergeOf(sessionId)`), `InMemoryMergeStore`, and
`MergeRules.check(members: List<SessionRecord>, partOf: (String) -> String?,
mergedAlready: Set<String>, overlapMs: Long)` (pure) returning the
problems in Sam's words ("Session 12:00 is still uploading", "These are in
different parts: Practice and Race session 1", "15:18 overlaps 15:23 by
3 min"). `:archive-gcp`: `FirestoreMergeStore` (`merges`, `mergeMembers`,
one transaction per change).

Tests: every rule above, each alone; extending keeps the id; a session in
two merges refused at the store; split marks `split` and frees the members.
`scripts/merge-smoke.sh`, in `archive-smoke.sh`'s style, for the Firestore
store: two throwaway sessions merged, a second merge of one refused, split,
removed (a line for it in CLAUDE.md's Build block).

**Built 2026-10-28.** `archive/.../Merges.kt`: `MergedSession`, `MergeStore`
(`save` on the revision, `get`, `ofCar`, `delete`; `holding(car, id)` the
merge not split that holds a session), `InMemoryMergeStore`, and the pure
`MergeRules.check(candidates, into)` returning `Problem(message, sessions)`.
`FirestoreMergeStore` (`merges`, one transaction per save reading the car's
merges). Tests: `MergesTest` (9: each rule alone, the 5 s tolerance both
sides, extending, the store's one merge per session, split freeing its
members), `MergeMappingTest`; `scripts/merge-smoke.sh` passed on the real
Firestore. 675 Kotlin tests pass.

### M33.3 — The routes to merge, unmerge, name and delete

In `adminCarRoutes` (which gains `merges: MergeStore` and `onChanged: () ->
Unit`, wired to `edited` in `Application.kt`): `POST
/api/admin/cars/{slug}/merges` (`{ members }`, or `{ into, add }` to
extend), `POST /api/admin/merges/{id}/split`, both `Action.EDIT` on the car,
logged by `adminLog`, then `onChanged`. `DELETE /api/admin/sessions/{mergeId}`
deletes the members then the merge (`Action.DELETE`), then `onChanged`.
The name routes (`DriverRoutes.kt:90`, `:98`) accept a merge id and name
the merge; the driver routes don't (a merge id isn't a session: 404, since
drivers are stored on members). Decision 49 in `DECISIONS.md`.

Tests (`MergeRoutesTest`, in memory): merge, extend, split, delete, name;
refusals (not yours, live member, different parts, overlap, already
merged); each change empties the results' hold.

**Built 2026-10-28 (after M33.4).** `MergeRoutes.kt`: `SessionMerges` (each
session as the rules see it: live or not from the hub, its span as the list
shows it, its part's name by `sessionsIn` with merges, the merge it's in)
and `mergeRoutes`: `POST /api/admin/cars/{slug}/merges` (`{members}` → 201,
`{into, add}` → 200, members saved in time order) and `POST
/api/admin/merges/{id}/split`, `Action.EDIT`, logged, then `edited`. A
refusal is 409 `MergeRefused` with every problem and its sessions. **As
built, beside the plan:** the routes are their own `mergeRoutes`, not inside
`adminCarRoutes`, which gained only `onChanged` (its delete now clears the
hold too) and handles a merge id: `CarAdmin.deleteMerged` checks every member
as one is checked (live or uploading) before deleting any, then the merge.
**A member alone is refused** (`SessionMerged`, 409 "part of a merged
session"), by `CarAdmin`, so `admin.sh delete-session` refuses too (the tool
now builds Firestore's merges). Removing a car deletes its merges. The name
routes (admin and crew) name a merge by its id; the driver routes answer 404
for it. Decision 49 in `DECISIONS.md`. `MergeRoutesTest` (5): merge, extend,
extending into another part refused, split, its sessions free again;
refusals with their sessions (uploading, parts, overlap, another car's,
merged already); signed out or another site; naming, and the driver route's
404; deleting every member, a member alone refused, the hold cleared (the
next read sees it). **The hold after a merge or split** is seen only once
results count a merge as one (M33.6): placement can't move a merge, since
its members must share a part. 682 Kotlin tests pass.

### M33.4 — Placement as one unit

`EventRules.sessionsIn(event, sessions, merges: List<List<String>> =
emptyList())`, and the six callers pass the car's merges. Tests
(`EventRulesTest`): a merge straddling two windows lands whole in the part
it overlaps most; a member added by hand brings the others; a member removed
takes the others out; without merges, every existing test unchanged.

**Built 2026-10-28, before M33.3** (the routes' refusals name a session's
part, which should already treat a merge as one). `sessionsIn(event,
sessions, merges)`: each merge a unit of its eligible members, placed by
its span; a member added by hand takes the unit there, one removed takes it
out. **Where merges come from:** `EventStores` gained `merges: MergeStore`
(in memory unless given; production passes Firestore's), since every caller
but `sessionRoutes` already has it; `sessionRoutes` takes `merges` too.
`MergeStore.membersOf(cars)` gives the members of each merge not split. All
six serving callers pass it (`EventResults`, `adminEventRoutes`,
`EventDownload`, `sessionRoutes`' `eventsOf`, both in `CarTimings`).
`EventRulesTest` +2 (placed whole by its span, where alone its members split
across parts; added or removed by hand takes the merge; fake members left
out), every existing case unchanged. 677 Kotlin tests pass.

### M33.5 — What the session routes answer for a merged session

`SessionRoutes.kt`: `sessionRecord()` resolves a merge id (and a `split`
one to its first member); the car's list folds members into one
`SessionItem` (`members`), and each `Drive` gains `suggested:
List<List<String>>` from a pure `suggestMerges(records, partOf, merged)`
(the rule in Decided here); `/api/sessions/{id}` answers the folded detail
with `members` (id, start, end, driver, driver changes), and a member's
detail carries `mergedInto`; `/laps` (each lap with its member's
`session`) and `/messages` for a merge as decided; `/series` and
`/live-laps` for a merge id answer 404 (the page joins the members'). Admin
download of a merge id: `MergedLog` of its members, gzipped.

Tests (`SessionRoutesTest`, `HiddenTest`): a hidden car's merged id answers
404 signed out (added to `HiddenTest`'s existing `/api/sessions/{id}…`
rows; no new public route); the list shows one row with summed laps and
the best of all; laps numbered through, each naming its member, a lap
spanning a same-run join present, none across a restart; `suggestMerges`:
same device and drive suggested, a live or uploading one breaks the run,
another part breaks it, another device breaks it, already merged skipped.

**Built 2026-10-28.** `SessionRoutes.kt`: `foldMerges`/`fold` (pure: one
row per merge not split, with `members`), `suggestMerges` (pure, per drive;
`Drive.suggested`), the list passing each session's tablet (`summary.device`,
else the header's). `/api/sessions/{id}` for a merge id: `mergedDetail`
(the folded row, all members' signals, gaps and videos, `members` with each
one's driver and changes, and a driver timeline across them by decision 48:
a member nobody set carries on); for a split one, its first member's detail
(the page sees the other id and moves); a member's detail has `mergedInto`.
`/laps`: every complete member's laps on the event's course, else the first
member's, numbered through by when they ended, each with `session` (a new
optional field on `StandingLap`). `/messages`: first member's start to last
one's end, on the first's clock. `/series` and `/live-laps` 404 for a merge
id (no record). A hidden car's merge id answers as one that never was.
**Admin download** of a merge id: its members' logs through `MergedLog`,
gzipped (not covered by a test: its fixtures have no stored logs; checked on
the dev server in M33.8). Tests: `MergedSessionsTest` (4: suggested, then
one row; the merged page's members and driver timeline; a member's
`mergedInto`, a split merge as its first member; `suggestMerges`' rules),
`SessionLapsTest` +1 (three sessions of one run: laps numbered through, each
naming its member), `HiddenTest` (a hidden car's merge in the detail,
messages and laps reads). 687 Kotlin tests pass.

### M33.6 — Event results and the download count a merge once

`EventResults.kt`: a merge's rows in every part as decided (its laps from
M33.5's merged laps, `Results.stretches` over its members' drivers), and
`merged` (member → merge) on `EventResults`; driver pages follow.
`EventDownload`: `sessions.json` names the merge.

Tests (`EventResultsTest`, `EventDownloadTest`): practice and race-session
rows count the merge once, with the same bests as unmerged; `merged` maps
each member; the race's laps and stints unchanged; the download unchanged
but for the name.

**Built 2026-10-28.** `EventResults.kt`: each part's sessions grouped by
merge; a merge is one row per stretch (its id, its name or its first
member's, heard from its first member's), its members' laps joined and
numbered through as `/laps` numbers them, each with `session`; stretches by
`Results.mergedStretches` (decision 48's carry: a member nobody set carries
on). `EventResults.merged` maps member → merge. The race is unchanged (it
reads members). Driver pages follow (a part's bests now name the merge).
`EventDownload`: practice laps come from rows by each lap's `session`
(a merged row's id is no member's, so filtering rows by id would have
dropped its laps: found while validating); `sessions.json` has `merged`.
Tests: `EventResultsTest` +1 (three sessions of one run in practice and two
in the race, merged: one row per stretch, Sam's taking the unset member's
laps, every lap numbered through, bests as the drivers' own, `merged`, the
race equal to unmerged), `EventDownloadTest` +1, `MergeRoutesTest` now sees
merge and split clear the hold. 689 Kotlin tests pass.

### M33.7 — The merged page

`web/src/lib/sessionPage.ts` (not `merge.ts`, which is the live lane's):
`joinSeries(members: { t0, series }[])` (pure, tested: times shifted to the
first member's `t0`, a `null` break at each join, positions and events
carried, `dropped` summed) and `fetchMergedSeries(members, fetcher, thin)`
(each member's `fetchSeries`, then `joinSeries`; M36.7 reuses it).
`SessionPage.svelte`: a `mergedInto` detail moves to the merged page
(`history.replaceState`, keeping `?from=&to=`); a merged detail fetches
each member's series by the rule above and joins them, marks each join on
the chart (`markers`) and lists the members under the facts ("3 sessions:
09:23, 11:55, 12:00").

Check on the dev server: three replayed sessions merged; one chart and map
across them, a break at each join, laps numbered through, a member's URL
lands on the merged page, and a member's lap link lands on the merged page
focused on that lap.

**Built 2026-10-28** (checked on the dev server with M33.8, below).
`lib/sessionPage.ts`: `joinSeries` (pure: onto the first `t0`, a `null`
break per signal at each join, positions and events carried, `dropped`
summed), `fetchMergedSeries(members, fetcher, thin, full)` and `membersIn`
(the members a view covers). `LapRow.session` from the merged laps.
`SessionPage.svelte`: the session shown is `sid`; a `mergedInto` detail, or
an unmerged merge's (its first member's, another id), moves the page there
with `history.replaceState`, keeping `?from=&to=`; a merged detail fetches
its members' series (thinned when the merge is over an hour) and joins them;
a narrow view loads full detail only for the members it covers; a mark on
the chart at each join; "3 sessions merged: 09:23, 11:55, 12:00" under the
facts; a compare pick names the lap's member. **As built:** a merged page
shows who drove but doesn't edit it (its driver routes are the members',
M34.4's Drivers block). `sessionPage.test.ts` +2.

### M33.8 — The sessions list: merge, unmerge, suggestions

`SessionsPage.svelte`, for whoever may edit the car (`may.edit`): a
checkbox per row labelled **Select** (`aria-label="Select 12:00 to
merge"`; merged rows too, to extend), a **Merge** bar when two or more are
ticked ("Merge 3 sessions"), the server's refusal shown as it says it; on a
merged row, **Unmerge** beside Download and Delete (Delete's confirmation
lists the members). The suggestion line ("These look like one drive —
Merge") above each of the drive's `suggested` runs; its Merge posts those
ids. A pure `mergeBar(selected, items)` in `web/src/lib/sessions.ts`
(tested: under two → none; a merged row and plain ones → extend it; two
merged rows → "Unmerge one first").

Check on the dev server: the suggestion appears for replayed back-to-back
sessions; one click merges them; Unmerge restores three rows.

### M33.9 — Deployed

With a replay streaming across it. Then Sam merges NHMS Saturday's sessions
(race session 1) on production and checks: the race results and laps per
driver unchanged (SAM 122, HUG 118, PAU 113, DRE 107); the merged page
loads within M33.1's budget; the event page lists one session for the day.

## Where the later plans use this

Folded into their steps (2026-10-22): M34.4–5 (the Drivers block over a
merged timeline; a change stored on the member its moment falls in), M35.2
and M35.3 (the hold's clearing; laps first for a merge), M36.7 (an offline
car's last route from a merged session), M37.1 and M37.3 (one row per
merged session; stint links through `merged`), M38 (the order, compare
picks naming members, the list's two kinds of checkbox) and M39.1–M39.4
(placement and Take out / Add to on the whole merge).

## Open questions

- None blocking. The overlap tolerance and the page-weight gate are set by
  M33.1's measurement, before anything is built on them.
