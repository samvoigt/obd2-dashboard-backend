# M35 — Finding your way: "now" first, names not slugs, a useful offline car

Phase 4 of the UX review (`plans/UX-REVIEW.md` §3.D, §4 phase 4, §6.1 and
§6.4). A visitor lands and sees what's happening now; every page says which
car it's about by name; an offline car's page says when it last ran instead
of showing an empty world; the header reads in visitors' words.
**Sam, 2026-10-22:** the review's order and scope ("restructure freely, keep
the look"); the nav "Live · Results · Drivers · Courses" ("I like #1, but
where does the car info page go?" — it stays at `/cars/<slug>`); the Hidden
badge "only on manage/edit pages".

> **Planned 2026-10-22**, checked against the code.

---

## What exists, and what it means for this (checked 2026-10-22)

- **The landing page** (`Landing.svelte`) fetches `/api/cars` every 10 s and
  each car's drives once a minute (`fetchDrives` + `latestSession`,
  `lib/landing.ts:13`), plus events, drivers and courses. It already knows
  each car's state (`live`, `stale`, `no_session`, `offline`) and latest
  session, and whether an event is under way (`underWay`, `lib/landing.ts:28`).
  It does **not** know who's driving, the race lap or the best lap: those
  reach browsers only on the car's live stream, as the `timing` frame.
- **The `timing` frame's JSON** is built by `TimingPage.json`
  (`TimingPage.kt:30`) and the latest per car is already held in
  `TimingPage.last` (a `ConcurrentHashMap`), public (the car page shows it).
  So a "Now" card can get the driver, race lap and best **without opening a
  stream**, if `/api/cars` carries that held JSON for a live car: one new
  optional field, `standing`, the same shape as the frame.
  `browserRoutes(...)` (`Application.kt:160`) is given `timingPage`.
- **`CarSummary`** (`BrowserRoutes.kt:39`, `lib/api.ts:3`): `slug`, `name`,
  `state`, `hidden?`. Adding an optional `standing` is backward-compatible.
- **The header** (`SiteHeader.svelte`, `links` at line 18) labels Cars,
  Events, Drivers, Courses; the pages each link highlights stay as they are.
- **The car page** (`CarPage.svelte`) titles itself with the slug (`<h1>{slug}</h1>`,
  line 226) and never fetches the car's name; its banner text comes from
  `stateLabel` ("Offline"). Back link "← Cars", forward "Past sessions →".
- **The sessions page** links back "← {slug}, live" and "Manage {slug} →";
  **the session page** links "← Past sessions" and has the name
  (`detail.carName`, `SessionRoutes.kt:102`).
- **The G-meter and map always show** (M10, amending decision 28, Sam
  2026-09-27), so an offline car shows "No readings" and a world map
  "Waiting for GPS". M10 left for later "the map opening on the car's last
  known position". This plan does that, and keeps both widgets on the page
  (see Decided).
- **`HiddenBadge`** is used in 8 places: `Landing.svelte:108,133,172`,
  `CarsPage.svelte:101`, `CoursesPage.svelte:31`, `CoursePage.svelte:57`,
  `EventsPage.svelte:35`, `EventPage.svelte:73`. The manage and edit pages
  show hidden state only inside `EditorsPanel` (the checkbox and a line), and
  only to whoever may share.
- **Tests:** `lib/landing.test.ts` and `lib/routes.test.ts` exist; pure logic
  goes there.

## Decided here

- **"Now" card** on the landing, above the logo's sections:
  1. a car `live` or `stale`: its name, state dot, driver, race lap, best,
     and **Watch live**; several live cars, a card each;
  2. else, an event under way: its name and **Results**;
  3. else, the most recent session over all cars: "Last out: Tha Bearmobile,
     Sun 18 Oct · 1 h 57 min · 50 laps · best 1:30.200", linking to it.
- **`standing` on `/api/cars`** for a car whose stream is live: the held
  timing JSON, nothing else. No new endpoint, no stream.
- **Names everywhere a car is named**, from `/api/cars` (one small list),
  falling back to the slug.
- **Breadcrumbs** replace the `.back` links on car, sessions, session,
  manage, event, event editor, driver, course and course editor pages:
  "Live › Tha Bearmobile › Sessions › Sat 12:00".
- **Nav words:** "Live" (`/cars`), "Results" (`/events`), Drivers, Courses.
  Page headings and the landing's section headings match ("Live →",
  "Results →"). URLs don't change.
- **The offline car page** (no session streaming): the banner reads
  "Offline · last seen Sun 18 Oct, 14:14"; under the title, the latest
  session's line (as on the landing) with **Open session** and **Past
  sessions**; the map draws that session's route (thinned series), not the
  world. **The G-meter stays** (M10's rule is kept; only the map's empty
  state improves). Nothing changes while a session streams.
- **Hidden badge** only on the car's manage page, the event editor and the
  course editor (beside the title, to anyone signed in); off the landing and
  lists and the public detail pages.

## The steps

### M35.1 — `standing` on `/api/cars` (server, tested)

`TimingPage` gains `fun held(car): JsonObject?` (reads `last`).
`browserRoutes` takes `timingPage` and adds `standing = timingPage.held(slug)`
to `CarSummary` (optional, `EncodeDefault.NEVER`) when the car's freshness is
live or stale. `lib/api.ts`'s `CarSummary` gains `standing?: Standing` (the
type the car page already uses for the frame).

Tests (`CarsApiTest`): a car with a held standing lists it; an offline car
doesn't; a hidden car is still left out to someone signed out (M25). The
test "each car is exactly its slug and name…" still passes (its car is
offline), but its comment says a new public field is a decision: a line
added to decision 37 (the standing is already public on the car's stream;
Sam agreed, 2026-10-22).

### M35.2 — The "Now" card's choice (pure, tested)

`lib/landing.ts`: `nowCard(cars, latest, events, now)` returns
`{ kind: 'live', cars } | { kind: 'event', event } | { kind: 'last', car, session } | null`
by the rule above. Tests: one live car; two; stale counts as live; an event
under way with no car live; neither, the latest session over cars; nothing
at all.

### M35.3 — The card on the landing

`Landing.svelte` renders it above the sections, with the existing
`sessionLine` and `lapTime`; "Watch live" is a button-styled link. Checked on
the dev server: a replay streaming (live card with driver once set), the
replay stopped (last-out card), an event part covering now (event card).

### M35.4 — Nav words and headings

`SiteHeader` labels; `CarsPage` h1 "Live"; `EventsPage` h1 "Results";
`Landing` section headings. Checked by eye at desktop and 390 px (after
M30's header).

### M35.5 — Breadcrumbs

`Breadcrumbs.svelte` (`items: {href?, label}[]`, the last unlinked, `›`
between, `nav aria-label="Breadcrumb"`), replacing `.back` on the pages
listed above. Car pages get the name from `fetchCars()` (cached per page
load); the session page uses `detail.carName` and its start time. A pure
`crumbs(route, names)` in `lib/routes.ts` builds the items, tested in
`routes.test.ts` for each route.

### M35.6 — The car page by name

`CarPage` fetches the name (M35.5's helper) for its `<h1>`; the slug stays
in the URL only. Checked: dev car shows "Dev car".

### M35.7 — The offline car page

`CarPage`, when no session is live: fetch the car's drives once
(`fetchDrives`, `latestSession`), show "Offline · last seen …" in the banner
and the latest session's line with its two links; fetch that session's
thinned series (`fetchSeries(id, fetch, true)`; for a merged session, whose
own `/series` is 404, `fetchMergedSeries(members, fetch, true)` from M32.7)
and give its positions to `SessionMap` without `follow`, so it fits the
route. When a session starts,
the live view takes over as now. A pure `lastSeenText(session)` in
`lib/landing.ts`, tested. Checked on the dev server: a car with past
sessions and none live; a car whose latest is a merged session (its whole
route); a car with no sessions at all ("No sessions yet", the world map as
before); a replay starting while the page is open.

### M35.8 — The Hidden badge where it's managed

Remove `HiddenBadge` from the 8 places listed; add it beside the title on
`CarManage`, `EventEditor` and `CourseEditor` when the thing is hidden (each
already loads its access record or can, via `allowedFor`/`mine`). Checked:
signed in, a hidden car shows no badge on the landing or `/cars`, and shows
it on its manage page.

### M35.9 — One word for each thing, one date format

From the review's cross-cutting item (§4; Sam, 2026-10-22: "A final step in
M35"). `docs/GLOSSARY.md`: one line per term the site shows (session, merged
session, drive, part, race session, stint, stop, in-lap, out-lap, re-timed,
tablet only, theoretical best, spread, within 1%), each with the words a
visitor sees and a one-sentence meaning. `web/src/lib/terms.ts`: those words
as constants, and `dayTime(ms)` → "Sun 18 Oct, 14:14" and `dayRange(a, b)`
→ "Fri 16 – Sun 18 Oct" as the one date format (tests in `terms.test.ts`).
Every page's dates switch to them (`grep` for `toLocaleString`,
`toLocaleDateString`, `dateText` and ISO dates in components; each change a
line). Jargon gets a `title` and, on the session and event pages, a small
"?" that shows the glossary line on tap. The error colour (`--critical`) is
kept for errors, trouble codes and destructive buttons only; a test in the
colour-roles suite lists its allowed uses. Checked: the phone check's
screenshots show one date format on every page.

## Acceptance

From the landing, one tap to the live car while one streams, with who's
driving visible before the tap; every car-related page names the car; an
offline car's page shows when it last ran and its last route. Deployed with
a live stream across it. Then the review's walk-throughs 1 and 3 (§5) are
timed again on a phone, against M30.7's baseline, into JOURNAL.

## Not in this milestone

- (The glossary and one date format are M35.9.)
