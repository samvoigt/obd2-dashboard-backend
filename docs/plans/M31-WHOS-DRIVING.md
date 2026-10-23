# M31 — Who's driving: set in two taps from the live page, and it sticks

The crew sets who's driving from the car's live page, on a phone, in two
taps, sees that it took, and the standings follow even when the race's
stints were edited by hand. The crew login moves to a **Crew** button in the
header. This is phase 2 of the UX review (`UX-REVIEW.md` §3.C, §4 phase 2,
§6 answer 2).
**Sam, 2026-10-22:** "updating who is currently driving is a use case that
was annoying"; and, for the login, "A Crew button in the header".

> **Planned 2026-10-22**, checked against the code. Builds on M30 (phase 1:
> the site fits a phone), which lays out the header this adds a button to.

---

## What exists, and what it means for this (checked 2026-10-22)

- **The picker** is `web/src/widgets/DriverPicker.svelte`: a bare `<select>`
  labelled "Who's driving", shown by `CarPage.svelte:262` only when `crew &&
  liveId`. With no laps yet it `PUT`s the session's driver
  (`/api/cars/{slug}/sessions/{id}/driver`); with laps, it reads the session
  and `PUT`s the whole list of driver changes with one more at the tablet's
  `wall` now (`saveDriverChanges` in `lib/driverChanges.ts`, M27). Its only
  feedback is an error; success shows nothing until the standing comes back.
- **The Driver panel** is `widgets/StandingPanel.svelte`: "Driver", the name
  or "Not set", and "stint 1 h 05 min". It has no control.
- **Crew login** is in `MessagePanel.svelte` (`login`, `logout`, through
  `call('POST', '/login')` on `/api/cars/{slug}/login`). `CarPage` learns
  crew state from `GET /api/cars/{slug}/crew` (`checkCrew`, line 164) and
  reconnects its stream after a login (`onCrewChange → reconnect`, line 201).
- **The crew cookie** (`CrewRoutes.kt`, `cookie()`): one per car
  (`auth.cookieName(slug)`), `HttpOnly`, `Secure`, `SameSite=Strict`,
  **path `/api/cars/{slug}`**. A header button on any page can still log in:
  the browser sends the cookie on any request *to* a path under it, whatever
  page made the request. Logging in (`POST /api/cars/{slug}/login`),
  checking (`GET …/crew`), and every crew action (driver, driver changes,
  name, stints, flags, messages) are all under that path. **So the cookie
  needn't change.** What the header can't do is learn which cars it's logged
  into without asking each car's `/crew` (one request a car; there are four).
- **Driver routes** (`DriverRoutes.kt`): `setDriver` and `setDriverChanges`,
  each reachable by the car's editors (`/api/admin/sessions/{id}/…`) and its
  crew (`/api/cars/{slug}/sessions/{id}/…`); both refuse test data, check the
  drivers exist, store on the session record, log, and call `onChanged`
  (`edited` in `Application.kt`: timing nudge and results hold cleared).
- **Why a change "doesn't take"**: `Standings.driverNow` (`Standings.kt`,
  the `if (race != null)` branch): when the race session's stints for this
  car were edited (`edited`), the driver now is **the last edited stint's**,
  falling back to the session's only if that stint names nobody. A crew
  change from the live page goes on the session (driver or driver change)
  and so is ignored. The event page agrees with the standing: with edited
  stints, `Race.sessions` uses the marks and never the changes (decision 44:
  "edited stints still win").
- **Stints as edited** are stored on the race part, by car
  (`Part.stints: Map<String, List<Stint>>`, `Stint(start, driver)` on the
  tablet's clock), written by `RaceRoutes.kt`'s `stints` → `editRace`: the
  event must contain the car, the part is named (`part`), the save names the
  revision it was made on (`stores.events.save(changed, expected, now)`,
  conflict otherwise), logs "race of … set", calls `onChanged`. The crew
  already may write them (`PUT /api/cars/{slug}/events/{id}/race/stints`).
- **Which race part a session is in**: `CarTimings.eventOf(car, session)`
  (`CarTimings.kt:37`), through `EventRules.sessionsIn`.
- **The session page's drivers** (`SessionPage.svelte` around 345–372): a
  "Driver" / "Driver from the start" select, each change as "From lap N:
  name [Remove]", and "Driver change at lap [ ] to [select] [Add]".
- **Tests to extend**: `DriverRoutesTest.kt`, `RaceRoutesTest.kt`,
  `StandingsTest.kt` (its "stints as edited name the driver" and "a driver
  change within the session (M27)" cases), `web/src/lib/driverChanges.test.ts`.

## Decided here

- **Decision 47 (amends 44): a crew's driver change during a race with
  edited stints adds a stint there too.** When the car's driver is set or
  changed on a session that is in a race part whose stints for that car were
  edited by hand, the server also adds a stint to that part, from the same
  moment (the change's `from`; for a session's driver, the session's first
  record's `wall`, else its start), naming the same driver. If a stint
  already starts within 60 s of that moment, its driver is replaced instead
  of adding another. Without edited stints nothing else happens: default
  stints already split at each change (decision 44). **Why:** edited stints
  win (decision 35), so the crew's change was silently ignored by the
  standing, the live page and the race's results (2026-10-19, "why won't
  the current driver update?"). Writing the stint keeps one truth that every
  reader already uses, and an editor sees it in the stint editor as any
  other. The race's revision moves, so a stint editor open elsewhere is told
  to reload, as for any other edit.
- **Where the rule lives:** a pure function in `:events`
  (`EventRules.withDriverStint(part, car, at, driver): Part`), tested there;
  the server calls it from `setDriver` and `setDriverChanges` after the
  session is saved, for the *newest* change only (an older change edited on
  the session page is the editor's to mirror in the stints, as now).
- **On a revision conflict**, the server reads the event again and retries
  once; if it fails again, the session's change still stands and the answer
  says "Driver saved; the race's stints couldn't be updated — edit them on
  the event page." The crew sees that line.
- **The header's Crew button** lists the cars, asks each car's `/crew` once
  when opened, and marks those logged in. The last car logged into is
  remembered in `localStorage` (per-viewer convenience, wrapped in
  try/catch) and preselected. The cookie stays as it is.
- **The passcode field leaves the car page.** When not logged in, the
  message panel says "Crew? Log in with the Crew button, top right" and the
  Driver panel says "Not set · the crew sets it here".

## The steps

### M31.1 — The rule (pure, tested)

`events/.../EventRules.kt`: `withDriverStint(part: Part, car: String, at:
Long, driver: String?): Part`. Returns `part` unchanged when it isn't a race
part or the car has no edited stints; else adds `Stint(at, driver)` in order,
or replaces the driver of a stint starting within 60 s of `at`.

Tests (`EventRulesTest`): no edited stints → unchanged; a practice part →
unchanged; added in order between two stints; within 60 s → replaced, not
added; `driver = null` (Not set) is a stint naming nobody; another car's
stints untouched.

### M31.2 — The server follows it

In `DriverRoutes.kt`, after `archive.setDriver` / `archive.setDriverChanges`
succeed: find the session's race part (`eventOf`, as `CarTimings` does; pass
the event store into `driverRoutes`), apply `withDriverStint` for the newest
change (or the session's start, for `setDriver`), and save through the same
path as `editRace` (revision checked, logged as "race of … set, stint at …
from a driver change by …", `onChanged`). Retry once on a conflict; on a
second, answer 200 with a `note`.

Write decision 47 in `DECISIONS.md` (the text above) in the same commit.

Tests (`DriverRoutesTest`): the crew sets a driver on a session in a race
with edited stints → the event's stints for that car gain one, and
`GET /api/events/{id}` shows it; the same without edited stints → the event
is untouched (revision unchanged); a session in no event → untouched; a
conflict the first time → retried and saved; test data → refused as now.
`StandingsTest`: with the added stint, who's driving now is the new driver.

### M31.3 — The Driver panel sets the driver

`StandingPanel.svelte` gains, when `crew` is true, a **Set driver** button
(**Change driver** once someone is set) inside the Driver box. It opens an
inline picker: one button per driver (name and code, at least 44 px tall),
**Not set**, **Cancel**. Choosing one calls the same logic as today's
`DriverPicker.set` (move it to `lib/driverChanges.ts` as `setDriverNow(slug,
session, driverId, hasLaps, nowWall)`, tested with a fake fetcher), then
shows for 10 s: "Drew Blouch · from 14:05" (the tablet's clock, as
`StandingPanel` already formats stints), or the server's `note`, or the
error. `DriverPicker.svelte` and its line in `CarPage.svelte` go.

Check: on the dev server with a replay streaming, logged in as crew at
390 px wide, two taps set a driver; the panel confirms; within one timing
update the standing names them.

### M31.4 — The crew login in the header

A `lib/crew.ts` store: `{ cars: Record<slug, boolean> }`, with
`check(slugs)`, `login(slug, passcode)`, `logout(slug)` (the three existing
routes), and the remembered car. `SiteHeader.svelte` gets a **Crew** button
(beside sign-in); it opens a small panel: a car `<select>` (cars from
`/api/cars`), the passcode field, **Log in**; below, each car logged in with
**Log out**. `CarPage.svelte` subscribes to the store instead of its own
`checkCrew` and reconnects when its car's entry changes.
`MessagePanel.svelte` loses its login form and shows the hint line instead.

Check: log in from the landing page; open the car page; message panel and
Set driver are there without reloading. Log out from the header on the car
page; both disappear. Password manager prompts are on the header field now,
not the live page.

### M31.5 — The session page's drivers in one block

`SessionPage.svelte`'s three controls become one **Drivers** block: a list of
stretches ("From the start: Sam Voigt", "From lap 48: Drew Blouch"), each
with **Change** (an inline select) and, except the first, **Remove**; and
one row "Add a change at lap [ ] to [driver] [Add]". Same calls as now
(`chooseDriver`, `saveChanges`), same data. For viewers who can't set it,
the line "Driven by Sam, then Drew" stays.

Check: on a session with two changes, each can be changed or removed, and
the laps table's Driver column follows.

### M31.6 — Deployed

With a replay streaming across the deploy (as every deploy). Then on the
dev server, the review's walk-through 2 timed: "Drew just got in the car.
Set it." (crew, on a phone width), before (on the current site) and after.
The numbers go to JOURNAL.

## Open questions

None: the stint rule's details (60 s, newest change only, retry once) are
choices Sam can change on reading; they don't block building.
