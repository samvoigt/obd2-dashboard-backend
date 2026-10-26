# M32 — Who drove belongs to the session; race stints follow from it

One record says who drove: each session's driver from its start and its
driver changes. Race stints are worked out from those and are never edited
on their own. The stint editor, edited stints and the two precedence rules
that made a crew's change "not take" go. A new session that follows straight
on from the last carries its driver over, and a change made by mistake and
corrected within a minute or a lap replaces the mistake. This is the model
half of UX review phase 2 (`UX-REVIEW.md` §3.C, §7); the screens are M34.

**Sam, 2026-10-22:**
- "yes, any change to the stint takes place everywhere."
- "Who Drove can be a per-session thing, sessions should be tied to cars,
  but i think the driver should mostly be edited by session, there's not a
  ton needed for the car itself."
- Past corrections: "Always the session page".
- "a stint shorter than 60s or 1 lap is likely a correction".

> **Planned 2026-10-22**, checked against the code. Replaces the drafted
> decision 48 in the first M32 plan (replaced by this one and M34, 2026-10-22; renumbered in build order the same day).
> Buildable and deployable before M34: the live page's picker and the
> session page keep working as they are, on the new model.

---

## What exists, and what it means for this (checked 2026-10-22)

- **Session drivers** are `SessionRecord.driver` and
  `SessionRecord.driverChanges` (`Stores.kt:41`, `:61`; `DriverChange(from,
  driver)` on the tablet's `wall`, `:75`), written by `index.setDriver` and
  `index.setDriverChanges` (`Stores.kt:129`, `:135`, each touching nothing
  else), through `DriverRoutes.kt`'s `setDriver` and `setDriverChanges`
  (admin and crew routes for each). `setDriverChanges` takes the whole list,
  refuses two at one moment, more than 50, unknown drivers and test data.
- **Race stints today** (`Race.kt`): `Race.car` takes `edited:
  List<StintMark>?` and otherwise `defaultStints` (`Race.kt:264–276`): a
  stint at the first lap, **one at each stop's exit**, and one at each driver
  change inside the race. Each is driven by whoever drove its first lap.
  `Race.sessions` (M26) runs `car` per race part with that part's `edited`,
  and joins them, so a stint never crosses a race part. `CarRace.stintsEdited`
  says which way it went.
- **Edited stints** are `Part.stints: Map<car, List<Stint(start, driver)>>`
  (`Event.kt:32–36`), written only by `RaceRoutes.kt`'s `stints` → `editRace`
  (admin `PUT /api/admin/events/{id}/race/stints/{car}`, crew `PUT
  /api/cars/{slug}/events/{id}/race/stints`). `Event.keepingRaceEdits`
  carries them through the event editor's saves; `EventRules.eventProblems`
  checks them (`EventRulesTest`, "only the race has flags and stints…").
- **Readers of edited stints:**
  - `EventResults.kt:331` (`RaceSessionInput.edited`), `:112–120`
    (`RaceSessionResults.edited`, sent to the page) and `:271` (`seen`, which
    drops a hidden car's stints);
  - `CarTimings.kt:71` (the live standing and the tablet's `timing` frame);
  - `Standings.kt:69`, `:92` (`driverNow`'s `edited` argument) and
    **`Standings.kt:106`, the two precedence rules**:
    with edited stints the stint's driver wins over the session's; without,
    the session's driver wins over the stint's;
  - `EventDownload.kt` (M29): `ExportPart.stints` in `event.json`, and
    `stints.csv`'s `edited` column (`race.stintsEdited`, `:296`);
  - the site: `RaceSection.svelte`'s stint editor and its line "Stints as
    set by hand." / "Split at every stop; merge a fuel stop…" (`:171`), and
    `lib/race.ts`'s `stintsEdited`, `edited`, and the stint-editing helpers.
- **The tablet's `timing` frame** carries the standing's driver
  (`TimingDownlink.kt:131`), from `Standings`; it follows without a change
  of its own.
- **Driver pages** (`DriverStint` in `EventResults.kt`) read `CarRace.stints`;
  they follow too.
- **Where a session is created:** `ArchiveService.announce` (the live lane's
  `session` frame, `LiveRoutes.kt:167`; no header, no device yet) and
  `ArchiveService.open` (the archive's `PUT /v1/sessions/{id}`, line 0 parsed
  into `SessionHeader`; on an announced record it fills the header in,
  `reopen` → `setLine0`). **Only `open` knows the device**, and it's called
  for every archived session, a streamed one within one upload of its start.
- **Runs of the app** (`:timing`'s `runs`, `SessionTrace.kt:147`): same car,
  same `device`, this session's first `at` above the previous one's last
  (`SessionSummary.firstAt`/`lastAt`, known only once a session is complete
  and prepared), and starting at most 12 h after the previous one ended.
  `at` starts again when the app restarts, so **a restart is always a new
  run**. `SessionHeader` (`Records.kt:56`) has `device` and `started` (ISO,
  the tablet's clock) but not line 0's `at`.
- **Modules:** `:events` depends on nothing, so a rule over `DriverChange`
  (`:archive`) can't live there; `:archive` (beside `DriverChange`) can.
  `:tools` (`admin.sh`) has no `:server`, so it can't work out a race: the
  race's inputs (`RetimingJobs.sessionLaps`, `EventResultsService`) are in
  `:server`.
- **Laps for a session, server side:** `retiming.sessionLaps(car, id)`
  (complete sessions) and `liveTimings.laps(car, id)` (live), as
  `sessionRoutes` is already wired in `Application.kt`.

## Decided here

- **Decision 48 (amends 34, 35, 43 and 44): who drove belongs to the
  session; race stints follow from it.**
  - A session's driver from its start and its driver changes are the only
    record of who drove. They're edited on the session page, and the live
    page's crew sets the live session's (M34).
  - **A race's stints are worked out, never edited:** within each race part,
    a stint starts at the part's first lap, at each driver change in its
    sessions, and at a session's start when it's set to a driver other than
    the one in force at the end of the one before. A session nobody set
    carries on whoever was driving. A change to the same
    driver (entered at a stop, for a double stint) starts a stint too.
    **Stops no longer split a stint**; they're shown inside it. A stint
    never crosses a race part (decision 43).
  - **A correction replaces, not adds** (Sam): a driver change less than
    60 s after the one before it (the session's start counts as one), or
    with no lap ending between them, replaces it.
  - **A session following straight on carries its driver over:** when a
    session's line 0 arrives and nobody has set its driver, it's given the
    driver the car's previous session ended with, if that session was on
    the same device and was last heard at most **10 minutes** before this
    one was first heard (`updated` → `created`): back to back, the same gap
    the sessions list uses for a drive (`DRIVE_GAP`), **restarts of the app
    included**. Sam, 2026-10-22: "Same tablet, back to back (10 min)". A
    session after a longer break starts with nobody set.
  - Hand-edited stints, the stint editor's split and merge, and both
    precedence rules go. **Why:** the same fact had several addresses and
    rules decided silently between them (2026-10-19, "why won't the current
    driver update?"); stops splitting stints forced hand edits that then
    disabled driver changes; every restart of the app started with nobody
    driving.
- **Stored data:** `Part.stints` stays readable (the field and its
  serialization unchanged) so the migration can read it, but nothing else
  reads or writes it. After the migration it's empty everywhere.
- **The migration tool is Sam's to run on production** (it writes session
  records): dry run first, which prints before and after, then `--apply`.

## The steps

### M32.1 — Stints from session drivers (pure, tested)

`timing/.../Race.kt`: `defaultStints` becomes `stintsOf(laps, drivers,
changes)` as decided (first lap; each change inside the part; a session's
start where its driver differs from the one in force); stops make none.
`Race.car` and `Race.sessions` lose `edited`; `RaceSessionInput.edited` and
`CarRace.stintsEdited` go.

Tests (`RaceTest`), rewritten:
- `one run through two driver changes, … stops and stints at each`: the
  same three stints (each session starts a new driver), now named "stints
  at each new driver";
- `stints as edited replace the default…` becomes "a fuel stop with the same
  driver is one stint; a change to the same driver at a stop is two";
- `a restart within a session is still bridged, and edited stints stay in
  their session (M26)`: the edited half goes; a restart with the same
  driver is one stint;
- `a driver change within a session starts a default stint, …, edited
  stints still winning (M27)`: "still winning" goes;
- new: three back-to-back sessions with the same driver are one stint; a
  stint never crosses a race part.

**Revalidated 2026-10-26** against the code after Hugh's M30 (merged
2026-10-24): it added `gpsLatencyMs` and `videos` to `SessionRecord`
(`Stores.kt:66`, `:68`; `DriverChange` is now `:82`, `setDriver` `:143`,
`setDriverChanges` `:149`) and gps-latency and videos routes to
`DriverRoutes.kt` (`setDriver` now `:225`, `setDriverChanges` `:237`),
touching nothing this plan changes. `defaultStints` was `Race.kt:259–276`.
**Decided on building:** a correction keeps the replaced entry's moment and
takes the new driver ("Paul from 14:05", where 14:05 Drew was corrected at
14:05:30): the first moment is when the swap happened. Replacing the
session's start sets the session's driver.

**Built 2026-10-26, in two halves so every commit builds:** here, the rule
(`stintsOf` replaces `defaultStints`); `edited` stays working until M32.5
removes it with its readers. A session's start makes a stint when the
session's own driver isn't who drove the lap before. Tests: the first case
renamed ("…a stint at each new driver", unchanged otherwise, since every stop
in it is also a new session and driver); new: two fuel stops with one driver
are one stint and a change to the same driver at a stop makes two; three
sessions back to back with Sam are one stint, a new driver at a session's
start makes one, and a session nobody set is a stint of nobody's; a stint
never crosses a race part. The other existing cases pass unchanged (17).

**Refined 2026-10-26, after M32.3's full test run** (M32.1 had been committed
after only `:timing`'s tests, a mistake: five server tests then failed):
**a session nobody set carries on whoever was driving.** Only a session set
to *another* driver starts a stint at its start. Otherwise every restart of
the app before anyone set its driver, in old races too, became a stint of
nobody's in the middle of one driver's run. The carry-over (M32.4) sets new
sessions anyway. Server tests updated to the new rule, each now asserting
what decision 48 says:
- `StandingsTest` "in a race, the lap count…": Alex's stint from his
  session's first lap (`W + 140_000`), not the stop's exit;
- `StandingsTest` "…stints as they fall…" became "in a race, a session set
  to a new driver starts that driver's stint, one nobody set carries on";
- `CarTimingsTest` "in a race, laps counted…": Alex's stint from the live
  session's first lap.

### M32.2 — The correction rule (pure, tested)

`archive/.../DriverRules.kt` (beside `DriverChange`; `:events` can't see
it): `normalise(start: Long?, driver: String?,
changes: List<DriverChange>, lapEnds: List<Long>): Normalised(driver,
changes, replaced: List<DriverChange>)`. Walks in order from the session's
start: a change under 60 s after the one before, or with no lap end between,
replaces it (a change replacing the start changes the session's driver).
`start` is the session's `header.started` (the tablet's clock, ISO, parsed
to ms); null (a live session the live lane announced, no line 0 yet) means
no change can replace the start.

Tests (`:archive`'s `DriverRulesTest`): 30 s apart → replaced; 5 min
apart with no lap between → replaced; 5 min with a lap between → both kept; replacing the
start sets the driver; three in a row collapse to the last; already
normal → unchanged and nothing reported.

**Built 2026-10-26** as planned (`DriverRules.normalise`, `:archive`, 6 tests).
**Decided on building:** "no lap ending between" applies only when the
session has laps; a session never timed has none, and the lap test would
take every change for a correction, so there only the minute applies.

### M32.3 — The server applies it where drivers are saved

`DriverRoutes.kt`: `setDriver` and `setDriverChanges` normalise before
storing, with the session's lap ends from a `lapEnds: suspend (car, id) ->
List<Long>` passed into `driverRoutes` (wired in `Application.kt` from
`retiming.sessionLaps` and `liveTimings.laps`, as `sessionRoutes` is). The
answer carries the stored list and `replaced` (M34 shows it).

Tests (`DriverRoutesTest`): a change 30 s after another stores one, and the
answer names the replaced one; the existing `driver changes within a session
(M27)` case still passes.

**Built 2026-10-26.** `driverRoutes` takes `lapEnds` (wired in `Application.kt`:
`retiming.sessionLaps(…).laps` ends once complete, else `liveTimings.laps`'
`wall`s). Both `setDriver` and `setDriverChanges` go through one
`saveDrivers`: the session's start from its header's `started`, then
`DriverRules.normalise`, then both the driver and the changes stored. Both
answer `DriversSaved(driver, changes, replaced)` (the site ignores the
answer and reloads, so nothing there changes). **The plan was wrong** that the
M27 case would pass unchanged: its changes were 1 s apart, at moments before
the session began, so the rule rightly took them for one correction. It now
uses changes 10 and 20 minutes in. New case: a change 30 s after another is
stored as one at the first moment, with the replaced one in the answer, and
a change in the first minute sets the session's driver. 656 Kotlin tests
pass.

### M32.4 — The carry-over

`archiveRoutes` gains an `opened: suspend (car, id) -> Unit` callback,
called on `Open.Created` (a new session's line 0, or an announced one's
line 0 arriving: both answer `Created`). `Application.kt` wires it to
`carryDriver(car, id)` (in `DriverRoutes.kt`): the car's latest session
created before this one (`listByCar`), as decided; the driver it ended with
(its last change's, else its own); set **only if still unset** through a
new `SessionIndex.setDriverIfUnset(id, driver)`, in memory and in
`FirestoreSessionIndex` (a transaction, as every write), so a crew's set in
the meantime wins. `onChanged` after a carry.

Tests (`DriverCarryTest`, in memory): same device, 5 min apart → carried;
a restart (same device, `at` begun again) → carried, as decided; another
device → not; 11 min apart → not; exactly 10 min → carried; already set → untouched; previous ended
with a change → that driver; test data (`fake`) on either side → not.

**Built 2026-10-26.** `SessionIndex.setDriverIfUnset` (in memory; Firestore
through its one `conditional` transaction, writing only when there's no
driver and no change), `ArchiveService.setDriverIfUnset`, `carryDriver` in
`DriverCarry.kt`, and `archiveRoutes`' `opened` hook, called after the
`Created` answer and never failing the upload. `Application.kt` logs
"driver carried over: …" and calls `edited()`. **As built:** "the session
before" is the car's latest created before this one, test data never on
either side; the gap is its `updated` to this one's `created`, at most
`DRIVE_GAP`; a previous session ending with a change to nobody carries
nothing. Tests (`DriverCarryTest`, 6): back to back carries; its last
change's driver, and nothing when that was nobody; exactly 10 min carries,
11 doesn't; another tablet, a driver already set, or test data carries
nothing; a restart carries; and through the real `PUT /v1/sessions/{id}`
(line 0 with a device). 662 Kotlin tests pass.

### M32.5 — Readers switched, the editor and its routes removed

- `EventResults.kt` (`RaceSessionInput`, `RaceSessionResults.edited`,
  `seen`'s stints filter), `CarTimings.kt`, `Standings.kt`: no `edited`;
  `driverNow` in a race is the last stint's driver, one rule.
  `Event.keepingRaceEdits` and `eventProblems` keep carrying and checking
  `Part.stints` until M32.6 clears it.
- `RaceRoutes.kt`: both stints routes go (404, as any unknown path).
  `RaceSessionResults.edited` goes.
- `EventDownload.kt`: `ExportPart.stints` and the `edited` column go; README
  text updated.
- `RaceSection.svelte`: the stint editor, "Edit … stints", "Back to
  splitting at every stop" and the "as set by hand" line go; the stints
  table stays (M37 links each stint to its session). `lib/race.ts` loses
  `CarRace.stintsEdited`, `RaceSessionResults.edited`, `StintMark`,
  `marksOf`, `mergeWithPrevious`, `splitAt` and `setDriver`; `racePath`
  keeps only `'flags'`.

Tests changed: `RaceRoutesTest` (`a car's crew sets its stints…`, the
stints halves of `the admin sets any car's stints…`, `a race's flags and
stints are set by…`, `a race in sessions - each session's flags and stints
are its own…`: flags kept, stints removed); `StandingsTest` (`in a race with
stints as they fall, the session's driver as set…` and `stints as edited
name the driver…` become one: the last stint's driver); `EventDownloadTest`
(`stints.csv` columns); `web/src/lib/race.test.ts` (`the stints as they
stand, to edit`, `merge a stint…`, `split at a lap…`; the paths test keeps
flags). Every other test passes unchanged.

**Built 2026-10-26.** `Race.car`/`Race.sessions` lost `edited`;
`RaceSessionInput.edited`, `CarRace.stintsEdited` and
`RaceSessionResults.edited` went. `Standings.driverNow` in a race is the last
stint's driver, one rule. `seen` keeps only its car filter (a hidden car's
sessions, and so its stints, already go with it). `CarTimings` passes no
stints. `RaceRoutes`: both stints routes, `SaveStints` and `StintView` gone;
flags as before. `EventDownload`: `ExportPart.stints`, `ExportStint` and
`stints.csv`'s `edited` column gone; README says where stints come from.
Site: `RaceSection` lost the stint editor and its buttons, saying instead "A
stint is one driver's run, from who drove each session: set on the session's
page."; `lib/race.ts` lost `stintsEdited`, `edited`, `StintMark`, `marksOf`,
`mergeWithPrevious`, `splitAt`, `setDriver`; `racePath` is flags only.
`Part.stints`, `keepingRaceEdits` and `eventProblems`' checks stay for the
migration. **Tests changed:** `RaceRoutesTest` (stints routes now 404; flags
kept in each case), `StandingsTest` (the edited case is now a driver change),
`EventResultsTest` (the split at lap 6 by a driver change), `RaceTest` (the
edited cases removed or made from driver changes), `race.test.ts`. 661
Kotlin and 273 site tests pass.

### M32.6 — The migration (Sam runs it)

The race can only be worked out in `:server` (see What exists), so the
migration is a second entry point there: `server/src/main/.../MigrateStints.kt`,
building the same stores `module` does from `GCP_PROJECT` and
`SESSIONS_BUCKET` (Firestore, the bucket, `RetimingJobs`,
`EventResultsService`) without serving, run as `./gradlew
:server:migrateStints --args="[--apply | --clear]"` through a
`scripts/migrate-stints.sh` that sources `scripts/env.sh`. The mapping from
marks to session drivers is a pure `StintMigration.plan(marks, sessions)`
in the same file, tested. For each event, each race part, each car with
`Part.stints`, it works out each race lap's driver both ways: **before**,
by the edited marks (the last mark that began before the lap ended, today's
rule at `Race.kt:141`, kept in `StintMigration` since `Race` no longer
takes marks after M32.1); **after**, by `Race.sessions` on the session
drivers it would write. It writes, per mark: inside
a session's laps → a driver change at `start`; at or before a session's
first lap → that session's driver. Marks with no laps are dropped. Each
mark is written even when its driver is the one before, so a stint the
edits split at a stop stays split. Prints, per car, laps and time per driver
before and after, and the changes it would write; refuses to apply unless
they match. `--apply` writes the sessions' drivers and changes only;
`--clear` (run after the deploy) empties `Part.stints`, event revision
checked. Writing first is safe on the old server: there, edited stints still
win, so the new session changes change nothing anyone sees.

Check: `StintMigrationTest` on a fixture of NHMS's shape (marks inside
sessions, at a session's start, empty ones, a same-driver split at a stop):
laps per driver equal both ways; then Sam's dry run on production shows
SAM 122, HUG 118, PAU 113, DRE 107 both sides.

### M32.7 — Deployed, then migrated

Decision 48 into `DECISIONS.md`. **The order matters**, so NHMS's race never
shows stints from the old session drivers alone:
1. Sam runs the dry run, then `--apply`, against production (old server
   still running; nothing visible changes).
2. Deploy with a live stream across it. The race now comes from the session
   drivers the migration wrote; the event page is checked against the
   numbers (SAM 122, HUG 118, PAU 113, DRE 107).
3. Sam runs `--clear`.

JOURNAL records the dry run's table and the check.

## Open questions

- **The carry-over across a restart** — answered 2026-10-22: same tablet,
  back to back (10 min), restarts included (M32.4).
