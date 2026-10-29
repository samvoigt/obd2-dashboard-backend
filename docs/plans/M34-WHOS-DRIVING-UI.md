# M34 — Who's driving, on screen: set it from the Driver panel, correct it on the session page

The crew sets who's driving from the car's live page, on a phone, saying
when the swap happened, and sees that it took. The crew login moves to a
**Crew** button in the header. Afterwards, who drove is corrected in one
place, the session page, for every session. This is the screen half of UX
review phase 2 (`UX-REVIEW.md` §3.C, §4 phase 2, §6 answer 2, §7); the model
is M32, which comes first.

**Sam, 2026-10-22:**
- "updating who is currently driving is a use case that was annoying".
- "setting a driver on an in-progress session should probably be a special
  case where you can pick at which actual point the swap happened (either
  time or lap)".
- For the login: "A Crew button in the header".
- Past corrections: "Always the session page".

> **Planned 2026-10-22**, checked against the code. Builds on M32 (who
> drove belongs to the session; the server normalises corrections and
> answers what it replaced), M33 (a merged session's detail lists its
> `members`, each with its driver and changes; its laps are numbered through
> and name their member) and M31 (the header's empty Crew slot).

---

## What exists, and what it means for this (checked 2026-10-22)

- **The picker** is `web/src/widgets/DriverPicker.svelte`: a bare `<select>`
  labelled "Who's driving", shown by `CarPage.svelte:262` only when `crew &&
  liveId`. With no laps yet it `PUT`s the session's driver
  (`/api/cars/{slug}/sessions/{id}/driver`); with laps, it reads the session
  and `PUT`s the whole list of driver changes with one more at the tablet's
  `wall` now (`saveDriverChanges` in `lib/driverChanges.ts`, M27). Its only
  feedback is an error.
- **The Driver panel** is `widgets/StandingPanel.svelte`: "Driver", the name
  or "Not set", and "stint 1 h 05 min". It has no control.
- **The live page's laps** (`LapRow`: `lap`, `start`, `end`, `pitIn`,
  `pitOut`) are on the tablet's `wall`; `live.offsetMs` turns the server's
  clock into the tablet's. That's all "when" needs.
- **Crew login** is in `MessagePanel.svelte` (`login`, `logout`, `POST
  /api/cars/{slug}/login`). `CarPage` learns crew state from `GET
  /api/cars/{slug}/crew` (`checkCrew`, line 164) and reconnects its stream
  after a login (`onCrewChange → reconnect`, line 201).
- **The crew cookie** (`CrewRoutes.kt`, `cookie()`): one per car, `HttpOnly`,
  `Secure`, `SameSite=Strict`, **path `/api/cars/{slug}`**. The browser sends
  it on any request to that path, whichever page makes it, and login,
  `/crew` and every crew action are under it. **So the cookie needn't
  change** for a header button; the header only has to ask each car's
  `/crew` to learn which it's logged into (four cars, once, when opened).
- **The session page's drivers** (`SessionPage.svelte` around 345–372): a
  "Driver" / "Driver from the start" select, each change as "From lap N:
  name [Remove]", and "Driver change at lap [ ] to [select] [Add]". Who may
  edit (`setter`): the car's editors, a master admin, its crew.
- **After M32**, the driver routes answer the stored list and `replaced`
  (changes the 60 s / one-lap rule replaced), and race stints follow the
  session's drivers, so these two places are the only editors.
- **Tests to extend:** `web/src/lib/driverChanges.test.ts`.

## Validated 2026-10-28, after M32 and M33

- **`live.offsetMs` is the viewer's clock less the server's** (`localNow -
  serverNow`, `lib/live.ts:62`), not the server's against the tablet's as
  written above. "At [hh:mm]" needs the tablet's clock too: each live point
  has `t` (`atMs`, when the server had it) and its record's `wall`, so a
  viewer time T is the tablet's `wall` `newest.wall + (T - offsetMs -
  newest.t)`, and a `wall` shows on the viewer's clock as `wall -
  newest.wall + newest.t + offsetMs`. `swapMoment` takes the newest point,
  not a bare offset.
- **The message panel is `web/src/MessagePanel.svelte`**, not under
  `widgets/`; its login is `login`/`logout` there, as described.
- **The Driver panel shows only with `live.standing`** (`CarPage.svelte:262`),
  and a live session at no course has none. So the panel takes `standing`
  as optional and is shown whenever there's a standing or the crew has a
  live session; otherwise the crew could never set a driver there.
- **The driver routes' answer** (M32): `DriversSaved(driver, changes,
  replaced)`, each change `{from, driver: id}`. `setSessionDriver` and
  `saveDriverChanges` ignore it today; they return it now, for the
  confirmation and the session page's "replaced" line.
- **A merged session's detail** (M33.5) has `members`, each with `id`,
  `started`, `ended`, `driver` and `driverChanges`, and its laps name their
  member in `session`: what M34.4 needs, as planned. Its page shows who
  drove but doesn't edit it yet (M33.7), which M34.5 changes.
- The header's Crew slot is `<span class="crew-slot">` in `SiteHeader.svelte`
  (M31); the crew cookie's path is `/api/cars/{slug}` (`CrewRoutes.kt:79`),
  so a header login works from any page, as planned.

## Decided here

- **The Driver panel holds the control.** Crew logged in: **Set driver**
  (**Change driver** once someone is set), opening an inline picker in two
  steps:
  - **Who:** one button per driver (name and code, at least 44 px tall),
    **Not set**, **Cancel**.
  - **When** (only once the live session has laps):
    - **Now** (the tablet's newest `wall`, as today);
    - **At the last stop (lap 59, 14:02)**, when the session has one: the
      start of its latest out-lap (`pitOut`), so the in-lap stays the old
      driver's and the out-lap is the new one's;
    - **From lap [ ]**: the start of lap N, as the session page's change at
      a lap already is;
    - **At [hh:mm]** on the viewer's clock, turned into the tablet's `wall`
      with `live.offsetMs`;
    - then **Set**.
  - Before the session has laps, "who" sets the session's driver, as today.
- **Confirmation:** for 10 s the panel shows "Drew Blouch · from 14:05", or
  "Drew Blouch · from 14:05 (replaced Sam Voigt from 14:04)" when the server
  replaced a correction, or the error.
- **The crew login lives in the header.** A **Crew** button lists the cars,
  asks each car's `/crew` once when opened, and marks those logged in. The
  last car logged into is remembered in `localStorage` (try/catch) and
  preselected. The passcode field leaves the car page; when not logged in,
  the message panel says "Crew? Log in with the Crew button, top right" and
  the Driver panel says "Not set · the crew sets it here".
- **The session page is the one editor of the past**, for every session,
  race or not, merged or not. The event page's stints and the driver pages
  only read (M37 links each stint to its session).
- **A merged session's Drivers block covers its whole timeline** (decision
  49, M33): "From the start" is the first member's driver; a later member's
  start shows as an entry ("From 12:00 (next session): Drew Blouch") only
  when its driver differs from the one in force before it; a change is
  stored on the member its moment falls in, as a change inside it, or as
  its driver when the moment is at or before its first lap. A plain session
  is the same code with one member. The live page is unaffected: a live
  session is never merged.

## The steps

### M34.1 — When the swap happened (pure, tested)

`lib/driverChanges.ts`: `swapMoment(choice, laps, nowWall, offsetMs)` →
the moment on the tablet's `wall`, or why not. `setDriverAt(slug, session,
driverId, moment)` (today's `DriverPicker.set`, moved), and
`replacedText(answer, drivers)`.

Tests: now; the last stop is the latest out-lap's start; lap N is lap N's
start, an unknown lap refused; a time on the viewer's clock to the tablet's
`wall`; a time in the future or before the session refused; no laps → the
session's driver is set; the paths for crew and admin; a `replaced` answer
in words.

**Built 2026-10-28.** `lib/driverChanges.ts`: `SwapChoice`, `LiveClocks`
(the newest point and the viewer's offset), `viewerTime`/`tabletWall`,
`lastStop`, `swapMoment` (as decided; a time is today on the viewer's clock,
refused in the future or before the session's first moment), `setDriverAt`
(today's `DriverPicker.set`, moved: no moment sets the session's driver,
else a change added to its list), `replacedText`. `saveDriverChanges` and a
new `saveSessionDriver` return the server's `DriversSaved`;
`events.setSessionDriver` goes through it. `driverChanges.test.ts` +4.

### M34.2 — The Driver panel sets the driver

`StandingPanel.svelte` gains the button and the two-step picker, as decided;
the confirmation line for 10 s. Without the crew login, an unset driver
reads "Not set · the crew sets it here". `DriverPicker.svelte` and its line
in `CarPage.svelte` go.

Check: on the dev server with a replay streaming, logged in as crew at
390 px wide: set a driver now in three taps (Set driver, the driver, Now)
and at the last stop in three; the panel confirms; within one timing update
the standing names them; setting another driver 20 s later shows "replaced";
logged out, "Not set · the crew sets it here".

### M34.3 — The crew login in the header

A `lib/crew.ts` store: `{ cars: Record<slug, boolean> }`, with
`check(slugs)`, `login(slug, passcode)`, `logout(slug)` (the three existing
routes), and the remembered car. `SiteHeader.svelte` fills M31's slot with
**Crew**: a small panel with a car `<select>` (cars from `/api/cars`), the
passcode field, **Log in**; below, each car logged in with **Log out**.
`CarPage.svelte` subscribes to the store instead of its own `checkCrew`, and
reconnects when its car's entry changes. `MessagePanel.svelte` loses its
login form and shows the hint line.

Tests (`lib/crew.test.ts`, a fake fetcher): check marks each car; a refused
passcode says why; logout clears one car only; the remembered car survives
a storage that throws.

Check: log in from the landing page; open the car page; the message panel
and Set driver are there without reloading. Log out from the header on the
car page; both disappear.

### M34.4 — A session's drivers as one timeline (pure, tested)

`lib/driverChanges.ts`, over `members: { id, start, end, driver, changes }[]`
(a plain session's detail as one member; a merged one's `members`, M33.5)
and the page's laps (each naming its member's `session`):
- `driverTimeline(members, laps)` → entries `{ from, lap: number | null,
  driver, member, kind: 'start' | 'change' }`, as decided (a later
  member's start only when its driver differs);
- `changeTarget(members, laps, lap)` → `{ member, kind: 'driver' |
  'change', from }`: lap N's start, on the member it falls in; at or
  before that member's first lap → its driver.

Tests: one member, no changes; one member, two changes; three members, the
second carrying the same driver (no entry) and the third another (an
entry); a change at a later member's first lap → that member's driver; a
change mid-member → a change on it; Remove on a later member's start entry
is that member's driver set to the one in force before it.

### M34.5 — The session page's Drivers block

`SessionPage.svelte`'s three controls become one **Drivers** block, from
`driverTimeline`: a list of stretches ("From the start: Sam Voigt", "From
lap 48: Drew Blouch"), each with **Change** (an inline select) and, except
the first, **Remove**; and one row "Add a change at lap [ ] to [driver]
[Add]". Each save goes, through `changeTarget`, to the member's `/driver`
(`setSessionDriver`) or `/driver-changes` (`saveDriverChanges`, the
member's whole list), as `chooseDriver` and `saveChanges` do now. After a
save, a `replaced` answer shows under the block ("Replaced the change at
14:05: under a minute after the one before"). For viewers who can't edit,
the line "Driven by Sam, then Drew" stays.

Check: on a race session with two changes, each can be changed or removed;
on three replayed sessions merged (M33.7's check), a change at a lap in the
second is stored on the second (its own page, unmerged, shows it); the laps
table's Driver column and the event page's stints follow after a reload.

### M34.6 — Deployed

With a replay streaming across the deploy. Then on the dev server, the
review's walk-through 2 timed: "Drew just got in the car. Set it." (crew, at
phone width), before (the current site) and after. The numbers go to
JOURNAL.

## Open questions

None.
