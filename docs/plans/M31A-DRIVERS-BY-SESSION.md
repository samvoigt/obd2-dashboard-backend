# M31a — Who drove belongs to the session; race stints follow from it

One record says who drove: each session's driver from its start and its
driver changes. Race stints are worked out from those and are never edited
on their own. The stint editor, edited stints and the two precedence rules
that made a crew's change "not take" go. A new session that follows straight
on from the last carries its driver over, and a change made by mistake and
corrected within a minute or a lap replaces the mistake. This is the model
half of UX review phase 2 (`UX-REVIEW.md` §3.C, §7); the screens are M31b.

**Sam, 2026-10-22:**
- "yes, any change to the stint takes place everywhere."
- "Who Drove can be a per-session thing, sessions should be tied to cars,
  but i think the driver should mostly be edited by session, there's not a
  ton needed for the car itself."
- Past corrections: "Always the session page".
- "a stint shorter than 60s or 1 lap is likely a correction".

> **Planned 2026-10-22**, checked against the code. Replaces the drafted
> decision 47 in the old M31 plan (replaced by this one and M31b, 2026-10-22).
> Buildable and deployable before M31b: the live page's picker and the
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
  - `EventResults.kt:331` (`RaceSessionInput.edited`) and `:112–120`
    (`RaceSessionResults.edited`, sent to the page);
  - `CarTimings.kt:71` (the live standing and the tablet's `timing` frame);
  - `Standings.kt:69` and **`Standings.kt:106`, the two precedence rules**:
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
  same `device`, `at` rising (`firstAt > prev.lastAt`), within 12 h. `at`
  starts again when the app restarts. `SessionHeader` (`Records.kt:56`)
  doesn't keep line 0's `at` today.
- **Laps for a session, server side:** `retiming.sessionLaps(car, id)`
  (complete sessions) and `liveTimings.laps(car, id)` (live), as
  `sessionRoutes` is already wired in `Application.kt`.

## Decided here

- **Decision 47 (amends 34, 35, 43 and 44): who drove belongs to the
  session; race stints follow from it.**
  - A session's driver from its start and its driver changes are the only
    record of who drove. They're edited on the session page, and the live
    page's crew sets the live session's (M31b).
  - **A race's stints are worked out, never edited:** within each race part,
    a stint starts at the part's first lap, at each driver change in its
    sessions, and at a session's start when its driver differs from the
    driver in force at the end of the one before. A change to the same
    driver (entered at a stop, for a double stint) starts a stint too.
    **Stops no longer split a stint**; they're shown inside it. A stint
    never crosses a race part (decision 43).
  - **A correction replaces, not adds** (Sam): a driver change less than
    60 s after the one before it (the session's start counts as one), or
    with no lap ending between them, replaces it.
  - **A session following straight on carries its driver over:** when a
    session's line 0 arrives and nobody has set its driver, it's given the
    driver the car's previous session ended with, if that session was on
    the same device, earlier in the same run of the app (its line 0's `at`
    lower), and began within 12 h.
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

### M31a.1 — Stints from session drivers (pure, tested)

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

### M31a.2 — The correction rule (pure, tested)

`events/.../DriverRules.kt`: `normalise(start: Long, driver: String?,
changes: List<DriverChange>, lapEnds: List<Long>): Normalised(driver,
changes, replaced: List<DriverChange>)`. Walks in order from the session's
start: a change under 60 s after the one before, or with no lap end between,
replaces it (a change replacing the start changes the session's driver).

Tests (`DriverRulesTest`): 30 s apart → replaced; 5 min apart with no lap
between → replaced; 5 min with a lap between → both kept; replacing the
start sets the driver; three in a row collapse to the last; already
normal → unchanged and nothing reported.

### M31a.3 — The server applies it where drivers are saved

`DriverRoutes.kt`: `setDriver` and `setDriverChanges` normalise before
storing, with the session's lap ends from a `lapEnds: suspend (car, id) ->
List<Long>` passed into `driverRoutes` (wired in `Application.kt` from
`retiming.sessionLaps` and `liveTimings.laps`, as `sessionRoutes` is). The
answer carries the stored list and `replaced` (M31b shows it).

Tests (`DriverRoutesTest`): a change 30 s after another stores one, and the
answer names the replaced one; the existing `driver changes within a session
(M27)` case still passes.

### M31a.4 — The carry-over

`SessionHeader` gains `firstAt: Long?` (line 0's `at`; nullable, so old
records read as before; the Firestore mapping in `:archive-gcp` adds the
field). The server, on `Open.Created` in `ArchiveRoutes.kt`, calls
`carryDriver(car, id)`: finds the car's previous session as decided, works
out the driver it ended with (its last change's, else its own), and sets it
**only if still unset** (a new `index.setDriverIfUnset`, conditional like
every write, so a crew's set in the meantime wins).

Tests: `ArchiveServiceTest` (header keeps `firstAt`); `DriverCarryTest`:
same device and run → carried; another device → not; `at` lower (a
restart) → not; over 12 h → not; already set → untouched; previous ended
with a change → that driver.

### M31a.5 — Readers switched, the editor and its routes removed

- `EventResults.kt`, `CarTimings.kt`, `Standings.kt`: no `edited`;
  `driverNow` in a race is the last stint's driver, one rule.
- `RaceRoutes.kt`: both stints routes go (404, as any unknown path).
  `RaceSessionResults.edited` goes.
- `EventDownload.kt`: `ExportPart.stints` and the `edited` column go; README
  text updated.
- `RaceSection.svelte`: the stint editor, "Edit … stints", "Back to
  splitting at every stop" and the "as set by hand" line go; the stints
  table stays (M34 links each stint to its session). `lib/race.ts` loses
  `stintsEdited`, `edited` and the stint-editing helpers.

Tests changed: `RaceRoutesTest` (`a car's crew sets its stints…`, the
stints halves of `the admin sets any car's stints…`, `a race's flags and
stints are set by…`, `a race in sessions - each session's flags and stints
are its own…`: flags kept, stints removed); `StandingsTest` (`in a race with
stints as they fall, the session's driver as set…` and `stints as edited
name the driver…` become one: the last stint's driver); `EventDownloadTest`
(`stints.csv` columns); `web/src/lib/race.test.ts` (`the stints as they
stand, to edit`, `merge a stint…`, `split at a lap…`; the paths test keeps
flags). Every other test passes unchanged.

### M31a.6 — The migration (Sam runs it)

`scripts/admin.sh migrate-stints [--apply]` (`:tools`): for each event,
each race part, each car with `Part.stints`, works out the race both ways
(edited, then from the drivers it would write) and writes, per mark: inside
a session's laps → a driver change at `start`; at or before a session's
first lap → that session's driver. Marks with no laps are dropped. Each
mark is written even when its driver is the one before, so a stint the
edits split at a stop stays split. Prints, per car, laps and time per driver
before and after, and the changes it would write; refuses to apply unless
they match. `--apply` writes the sessions' drivers and changes only;
`--clear` (run after the deploy) empties `Part.stints`, event revision
checked. Writing first is safe on the old server: there, edited stints still
win, so the new session changes change nothing anyone sees.

Check: on the dev server against a copy of NHMS's shape (a test fixture);
then Sam's dry run on production shows SAM 122, HUG 118, PAU 113, DRE 107
both sides.

### M31a.7 — Deployed, then migrated

Decision 47 into `DECISIONS.md`. **The order matters**, so NHMS's race never
shows stints from the old session drivers alone:
1. Sam runs the dry run, then `--apply`, against production (old server
   still running; nothing visible changes).
2. Deploy with a live stream across it. The race now comes from the session
   drivers the migration wrote; the event page is checked against the
   numbers (SAM 122, HUG 118, PAU 113, DRE 107).
3. Sam runs `--clear`.

JOURNAL records the dry run's table and the check.

## Open questions

None for building; the rules are Sam's choices of 2026-10-22.
