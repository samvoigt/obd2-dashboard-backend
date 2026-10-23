# M38 — Merged sessions: several stored sessions, one session everywhere you look

**Sam, 2026-10-22:** "i'd like for something on the session list page to
allow me to merge sessions. they could be separate as data items under the
hood, but treated as one session in the UI and for all other practical
purposes." Asked how far it goes, he chose: sessions that look like one
drive are **suggested** for merging ("These look like one drive — merge?"),
merged only on a click; sessions in **different event parts can't be
merged** (same part, or none); and it's built **right after M31a**, before
M31b, M34 and M35, so those build on it instead of being reworked.

> **Planned 2026-10-22**, checked against the code. Builds on M31a (who
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
  - `drives()` (`SessionRoutes.kt:281`) groups a car's list by a 10-minute
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
- **A session's page reads four public routes**, all through
  `sessionRecord()` (`SessionRoutes.kt`), which also hides a hidden car's
  sessions (M25): `/api/sessions/{id}`, `/laps` (complete sessions only,
  timed on the event's course, else its own: `laps(car, id, on)`),
  `/series` (the prepared series, or `?thin=1` for a long one; the page opens
  thinned at `LONG_SESSION_MS` = 1 h and loads full detail under a 30-minute
  view, `web/src/lib/sessionPage.ts:313–320`), and `/messages`. They are all
  in `HiddenTest`'s list of public reads (lines 129–133).
- **Deleting** is `DELETE /api/admin/sessions/{id}` (`AdminRoutes.kt:324`),
  the car's creator or a master admin (M23), refused while live or uploading
  (`CarAdmin.checkDeletable`, `UPLOAD_QUIET` 5 min). **Naming** is
  `PUT /api/admin/sessions/{id}/name` and the crew's
  `/api/cars/{slug}/sessions/{id}/name` (`DriverRoutes.kt:90`, `:98`).
- **NHMS Saturday** (race session 1) is five of the car's sessions: 09:23
  (2 h 31), 11:55 (5 min, tablet only), 12:00 (3 h 17), 15:18 (5 min,
  tablet only), 15:23 (1 h 59). Tablet-only sessions sit between car
  sessions at each restart; whether they touch or overlap their neighbours
  is measured in M38.1, because "no overlap" depends on it.

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
  M38.1's measurement); none already in another merge; **all in the same
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
  - **event results:** one practice row per merged session, its stretches
    over the whole merged timeline (M31a's rule); the race is unchanged;
  - **driver pages** follow the results; **the download** writes members in
    order as now and names the merge in `sessions.json`.
- **The merged page's data:**
  - **detail:** `/api/sessions/{mergeId}` answers the folded `SessionItem`,
    the signals of all members, and `members` (each with its id, start, end,
    driver and driver changes) for M31b's Drivers block;
  - **laps:** `/api/sessions/{mergeId}/laps` concatenates the members' laps
    on one course: the event's if the merge is in one, else the first member
    with laps' course (`laps(car, id, on)` re-times the others on it).
    Numbered 1, 2, 3… through the merge, known by when they ended as always
    (decision 44). A lap across a join exists only within one run of the
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
  adapter reconnects (the same run) are both suggested.
- **Results' hold** (M32) is cleared on every merge, extension, unmerge and
  delete (`onChanged` → `edited`).

**Decision 48 (drafted, written in the same commit as M38.3):**

> **A merged session: several stored sessions, one session on the site.**
> (M38; Sam, 2026-10-22.) A car's editors can merge two or more of its
> complete sessions, in time order without overlap, in the same event part
> or in none, into one merged session, with an id, a name and a page of its
> own. The members stay as stored, untouched: the archive, re-timing and the
> tablet never see a merge. Everything a visitor reads counts it once: the
> car's list, its page, event results, driver pages and the download.
> Placement treats a merge as one unit, so its members are always in the
> same part. A driver change on a merged session's page is stored on the
> member its moment falls in (decision 47). Unmerging restores the members;
> deleting a merged session deletes them all. Sessions that look like one
> drive are suggested for merging, never merged without a click.
> **Why:** a drive is often several sessions (an app restart, an adapter
> reconnect, a tablet-only minute between), and every page showed the
> pieces. Merging the stored logs would break every link to them (laps,
> re-timings, driver changes, events, the live page) and leave logs the
> tablet never sent; a merge record changes only what's shown.

## The steps

### M38.1 — Measure first (no code)

On production's data, by Sam or with the public APIs: NHMS Saturday's five
sessions' starts and ends (do the tablet-only ones overlap their
neighbours, and by how much?), and the five thinned series' total size and
point count. On the dev server, a page joining five synthetic series of the
same sizes, measured with `web/scripts/measure.mjs` at 4× CPU slowdown.
**Gate:** if it isn't usable (long tasks, a heap past the M8 budget), the
merged page opens on the members' thinned series only and the plan says so
before M38.6. The overlap found sets the tolerance in M38.2. Recorded here
and in JOURNAL.

### M38.2 — The store and the rules (pure and in memory, tested)

`:archive`: `MergedSession`, `MergeStore` (`create`, `extend`, `split`,
`get`, `ofCar`, `mergeOf(sessionId)`), `InMemoryMergeStore`. `:events` or
`:archive`: `MergeRules.check(members, records, partOf)` returning the
problems in Sam's words ("Session 12:00 is still uploading", "These are in
different parts: Practice and Race session 1", "15:18 overlaps 15:23 by
3 min").

Tests: every rule above, each alone; extending keeps the id; a session in
two merges refused at the store; split marks `split` and frees the members.

### M38.3 — Firestore, and the routes to merge

`:archive-gcp`: `FirestoreMergeStore` (`merges`, `mergeMembers`, one
transaction per change). Server: `POST /api/admin/cars/{slug}/merges`
(`{ members }`, or `{ into, add }` to extend), `POST
/api/admin/merges/{id}/split`, both `Action.EDIT` on the car, logged by
`adminLog`, `onChanged`. `DELETE /api/admin/sessions/{mergeId}` deletes the
members then the merge (`Action.DELETE`). Decision 48 in `DECISIONS.md`.

Tests (`MergeRoutesTest`, in memory): merge, extend, split, delete;
refusals (not yours, live member, different parts, overlap, already
merged); a hidden car's merge answers 404 signed out (`HiddenTest` gains the
merged id in its existing `/api/sessions/{id}…` rows; no new public route).
A Firestore smoke in `scripts/archive-smoke.sh`'s style for the
two-collection transaction.

### M38.4 — Placement as one unit

`EventRules.sessionsIn(event, sessions, merges: List<List<String>> =
emptyList())`, and the six callers pass the car's merges. Tests
(`EventRulesTest`): a merge straddling two windows lands whole in the part
it overlaps most; a member added by hand brings the others; a member removed
takes the others out; without merges, every existing test unchanged.

### M38.5 — What the API answers for a merged session

`SessionRoutes.kt`: `sessionRecord()` resolves a merge id (and a `split`
one to its first member); the car's list folds members into one
`SessionItem` (`members`); `/api/sessions/{id}` answers the folded detail
with `members`, and a member's detail carries `mergedInto`; `/laps` and
`/messages` for a merge as decided; `/series` for a merge id answers 404
(the page joins the members'). `EventResults`: one practice row per merge.
`EventDownload`: `sessions.json` names the merge. Admin download of a merge
id: `MergedLog` of its members, gzipped.

Tests (`SessionRoutesTest`, `EventResultsTest`, `EventDownloadTest`): the
list shows one row with summed laps and the best of all; laps numbered
through, a lap spanning a same-run join present, none across a restart;
practice results count the merge once; the download unchanged but for the
name.

### M38.6 — The merged page

`web/src/lib/`: `joinSeries(members: { t0, series }[])` (pure, tested:
times shifted to the first member's `t0`, a `null` break at each join,
positions and events carried, `dropped` summed). `SessionPage.svelte`: a
`mergedInto` detail moves to the merged page (`history.replaceState`); a
merged detail fetches each member's series by the rule above and joins
them, marks each join on the chart (`markers`) and lists the members under
the facts ("3 sessions: 09:23, 11:55, 12:00").

Check on the dev server: three replayed sessions merged; one chart and map
across them, a break at each join, laps numbered through, a member's URL
lands on the merged page.

### M38.7 — The sessions list: merge, unmerge, suggestions

`SessionsPage.svelte`, for whoever may edit the car: a checkbox per row
(merged rows too, to extend), a **Merge** bar when two or more are ticked
("Merge 3 sessions"), the server's refusal shown as it says it; on a merged
row, **Unmerge** beside Download and Delete (Delete's confirmation lists the
members). The suggestion line above a drive's rows, from a pure
`suggestMerges(drive, canEdit)` in `web/src/lib/sessions.ts` (tested: same
device and drive suggested; a live or uploading one breaks the run; another
part breaks it; already merged skipped).

Check on the dev server: the suggestion appears for replayed back-to-back
sessions; one click merges them; Unmerge restores three rows.

### M38.8 — Deployed

With a replay streaming across it. Then Sam merges NHMS Saturday's sessions
(race session 1) on production and checks: the race results and laps per
driver unchanged (SAM 122, HUG 118, PAU 113, DRE 107); the merged page
loads within M38.1's budget; the event page lists one session for the day.

## What M31b, M34 and M35 must account for

- **M31b (the screens):** the session page's Drivers block works over a
  merged session's whole timeline (`members` in the detail): "From the
  start" is the first member's driver; a later member's start driver shows
  as an entry ("From 12:00, carried over"); a change at merged lap N is
  turned into a moment and stored on the member that moment falls in
  (`PUT /api/admin/sessions/{memberId}/driver-changes`). The live page is
  unaffected: a live session is never merged.
- **M34 (the event page):** the Sessions panel lists one row per merged
  session (results already fold it, M38.5); a stint's Session link goes to
  the merged page (a member's id moves there by itself, or results carry the
  merge id: prefer the latter, one hop fewer).
- **M35 (the session page and list):**
  - the list's checkboxes for comparing laps (on the session page) and for
    merging (on the list) are different pages; keep them apart in wording
    ("Choose" vs "Pick to merge");
  - the panels' new order applies to the merged page; the chart shows the
    joins;
  - "50 laps · best 1:30.200" and the trouble-code line are worked out over
    the merge;
  - the compare pick names a member and a window (`lapLink`), which still
    lands on the merged page.
- **Also M36 (the event editor):** "Take out" and "Add to" act on the whole
  merged session, which shows as one row; and **M32:** clear the results'
  hold on merge, extend, unmerge and delete.

## Open questions

- None blocking. The overlap tolerance and the page-weight gate are set by
  M38.1's measurement, before anything is built on them.
