# The website's UX: a review, and a plan to improve it

**For Sam to review and comment on before anything is built** (Sam,
2026-10-22: "DO NOT IMPLEMENT THIS PLAN, I WANT TO REVIEW IT AND COMMENT ON
IT MYSELF FIRST"). Once agreed, each phase below becomes a milestone row in
`PLAN.md` (M31 onward) with its own plan, validated step by step against the
code as usual. Every phase is sized to be built by an Opus-level model or
lower: concrete files, concrete acceptance checks, no open-ended design.

> **Corrected 2026-10-22, by the milestone plans (M31–M40), which checked
> each step against the code.** Four corrections, and one addition:
> - **Phones (§2.1, §3.A):** the 390 px screenshots were probably laid out
>   at about 500 px and cropped: desktop Chrome won't lay a window out
>   narrower, and a landing card's text was cut mid-word rather than
>   wrapping. The overflow may be smaller than "Blocker" says. M31.1
>   measures it properly (phone emulation, as `measure.mjs` does) before
>   anything is fixed.
> - **A privacy bug the review missed:** the event results' hold is keyed by
>   the event's id and revision only (`EventResults.kt:413`), not by which
>   cars the viewer may see. For up to 10 s after someone signed in opens an
>   event, a signed-out visitor can be sent results that include a hidden
>   car (decision 42 says they never should). M35.1 fixes it, first.
> - **Empty stints (§3.E)** are real stored marks from hand edits, not a
>   display bug; **the G-meter (§3.D)** stays on an offline car's page
>   (M10); **removes in the course editor (§3.G)** get Undo, not a
>   confirm, since nothing is saved until Save.
> - **The compare pick (§3.F)** is shared across pages; M38 keeps that.
> - **Merged sessions (M33)**, asked for after the review, are built right
>   after M32; M34 and M35–M39 account for them in their steps. The
>   cross-cutting glossary (below) is in no milestone yet.

> **Reviewed 2026-10-22.** The live site (public pages, read-only, signed in
> as Sam in his own browser) and the dev server (crew login, the driver
> picker, the stint editor, the event and course editors), at desktop width
> and at a phone's 390 px (headless Chrome against the dev server). Page
> markup and CSS read alongside. Timings from the server's logs and the
> browser.

---

## 1. Who the site is for, and what that means

From Sam (2026-10-22), in order:

1. **Crew in the pits, on phones**, often in sunlight: the live car, "Pit
   Now", who's driving.
2. **Friends and family at home**: the live car and the race's standings.
3. **Drivers afterwards**: their own laps, comparisons, sessions.
4. **Sam as admin**: courses, events, drivers, users, stints.

Plus one use case Sam called out as having been annoying: **updating who is
currently driving**. And three places people have got lost: setting the
driver and stints, the session page, and the admin editors.

**What this means for priorities.** Audience 1 and 2 are on phones and
tablets, under time pressure, wanting one thing each: *what's happening
now*. The site today is built desktop-first, around the data model (cars,
sessions, events, courses) rather than around those moments. So the plan
leads with the phone and with "now", and leaves the admin editors (audience
4, one person, at a desk) until last even though they're the most intricate.

**Scope agreed:** restructure freely, keep the look. Colour roles in
`app.css` (decision 29), the dark theme, and the typography stay.

## 2. Executive summary: the five things that matter most

| # | Finding | Severity | Who it hurts |
| --- | --- | --- | --- |
| 1 | **The site doesn't fit a phone.** At 390 px wide every page overflows sideways: the header wraps and the sign-in buttons run off the edge; the car page's gauges are clipped; the sessions list loses its right half (the event, best lap); tables on the event and session pages are cut off mid-column. Only three media queries exist in the whole site (`SiteHeader`, `CarPage`, `CourseEditor`). | **Blocker** | Crew, family |
| 2 | **Pages sit on "Loading…" for 5–20 s with nothing else to look at.** The NHMS event page took 6–8 s (server logs: `GET /api/events/nhms-lemsons-2026 in 5926ms`, `8293ms`); a 3 h 17 min session's page showed its facts at once but "Loading the session's data…" for over 6 s and under 20; a driver's page 6–10 s, since it computes every event's results. No skeleton, no progress, no partial content. | **Major** | Family, drivers |
| 3 | **"Who's driving" is hidden and fragile.** It's an unstyled `<select>` labelled "Who's driving", shown only after the crew passcode is typed, wedged between the big "Driver: Not set" panel and "Trouble codes". The panel that says the driver is wrong offers no way to fix it. A change made there is silently overridden if the race's stints were ever edited by hand (`Standings.kt:106`, found on 2026-10-19 when the driver "wouldn't update"). | **Major** | Crew (Sam's #4) |
| 4 | **The site is organised by data, not by moments.** The landing page is a site map (Cars, Events, Drivers, Courses); nothing says "the race is on, here's the car". An offline car's page is a dead end: a world map "Waiting for GPS", an empty G-meter, "No signals yet", the car named by its slug (`tha-bearmobile`). Every browser tab is titled "OBD2 Live". | **Major** | Family, crew |
| 5 | **The event and session pages are dense, duplicated and mix admin tools into public reading.** The practice table appears twice; "The flags" form and "Edit stints" sit in the public results; stints are also listed as a run-on sentence; stops are "1139.1 s in the pit lane"; one session appears as several rows ("Tha Bearmobile, 09:26" × 2) because each driver's stretch is a row; a 109-lap table has "Compare…" and "vs best" on every row; re-timing notes are in the error colour. | **Major** | Drivers, family |

Everything else (wording, consistency, the editors, accessibility) is real
but secondary, and is in §3.

## 3. Findings, by theme

Severity: **Blocker** (the audience can't do the thing), **Major** (they can,
with effort or confusion), **Minor** (polish). Each finding says where it
was seen and what to do instead; the "do" lines are what the phases in §4
are built from.

### A. Phones (Blocker)

Seen at 390 px on the dev server, signed out (`scratchpad/ux/phone-*.png`,
kept outside the repo):

- **Header:** "Courses" wraps to a second line; the sign-in buttons overflow
  to the right. `SiteHeader`'s only media query (520 px) hides the site name
  and shrinks the nav; `.who` has `margin-left: auto` and never wraps.
- **Car page:** the gauge grid (`minmax(170px, 1fr)`) fits two across, but the
  page is wider than the viewport, so the right column is clipped; "Past
  sessions →" is clipped; the crew login row and "Trouble codes" overflow.
- **Sessions list:** each row is a flex line of `.when` and `.facts`; the facts
  (course, event, best, lines) are off-screen to the right. On a phone the
  list shows only a time and a duration.
- **Event page:** the practice table's "Compare…" buttons are cut; the stints
  table loses its Time/Best/Median/Spread columns; the lap chart is fine.
  Panels have `overflow-x: auto` but the page itself is wider than the
  viewport, so the panel never gets to scroll.
- **Session page:** the laps table is cut at the notes column; `.lapswide`
  scrolls only once its panel is narrower than its content, which it isn't.
- **Landing:** the car cards and course cards overflow.
- **Touch targets:** the driver `<select>`, "small" buttons (Download,
  Delete, Remove, Rename, Take out) and the units switch are under 30 px
  tall. Apple's and Google's floor is 44 / 48 px.
- **Type:** 85 declarations of `font-size: 0.8–0.9rem` across components;
  on a phone in sun that's unreadable.

**Do:** one phone-first layout pass (phase 1). Find the page-level overflow
first (it's probably the header and one or two `nowrap` rows: `CarsPage:144`,
`Landing:198,201`, `SessionPage:490`, `SiteHeader:119`), then make every
table either stack into cards or scroll inside its panel with a sticky first
column. Raise small controls to 44 px. Add a repeatable check (§5).

### B. Waiting (Major)

- The event page's results take 5–8 s to compute on the server for the NHMS
  weekend (11 sessions, 460 laps, re-timing per session) and are held for
  only 10 s (`ResultsHold`, `forMs = 10_000`), though the hold is already
  emptied whenever anything they depend on is edited. So nearly every visit
  recomputes.
- The driver's page asks for every event's results, so it waits on all of
  them.
- The session page opens on the thinned series (`?thin=1`, M19.6) and then
  the full one; the facts and the driver picker render first, but the chart,
  map and laps all wait for the series. The laps are small and could come
  first (`/api/sessions/{id}/laps` exists).
- Meanwhile the page says "Loading…" in grey. Nothing moves, nothing
  arrives in stages.

**Do:** (phase 3) hold results until edited, with a short hold only while a
part is under way; render each panel's skeleton at once; load laps before
the series; put a progress line on the slow ones ("Working out the race's
460 laps…").

### C. Who's driving (Major; Sam's #4)

- `DriverPicker` is a bare `<select>` under the standing panel, only after
  the crew login. The standing panel (`StandingPanel`) shows "Driver / Not
  set" in large type with no control in it.
- "A change is from now on." is the only explanation; there's no
  confirmation of what was set or from when.
- On the session page, the driver is three separate controls: "Driver from
  the start" select, a list of changes each with Remove, and "Driver change
  at lap [ ] to [select] Add". Nothing shows the resulting timeline.
- When a race's stints were edited by hand, a later driver change on the
  live page doesn't show in the standings (`Standings.kt:106`: edited stints
  win). The crew sees their change ignored with no message.

**Do:** (phase 2) put "Set driver" in the Driver panel; a picker with one big
button per driver (there are four); a confirmation line "Drew from 14:05";
when the race's stints are edited, a change from the live page also adds a
stint mark (the fix noted on 2026-10-19 and not built); on the session page,
one timeline of stretches with "change at lap…" inline.

### D. Finding your way (Major)

- **Landing:** four lists under the logo. No "now". The event list's
  "Under way" badge is the only live cue, and it's on the event, not the car.
- **Header nav:** Cars · Events · Drivers · Courses is the data model.
  Visitors think "live", "results", "my laps".
- **Car page, offline:** world map, "Waiting for GPS", "No readings", "No
  signals yet", and the slug as the title. Nothing about when it last ran or
  where its last session is.
- **Back links use slugs:** "← tha-bearmobile, live", "Manage tha-bearmobile
  →"; the session page's back link says "← Past sessions" with no car.
- **Titles:** `<title>OBD2 Live</title>` and no page sets `document.title`.
  Bookmarks, shared links and tab bars can't tell pages apart.
- **Hidden things** are badged "Hidden" even on the landing page; fine for
  Sam, noise for a user with one car.
- The units switch is under the car's title on one page and at the right of
  the facts line on another.

**Do:** (phase 4) a "Now" section at the top of the landing (a live car with
its driver, race lap and best; else the latest session), car names
everywhere, breadcrumbs ("Tha Bearmobile › Sessions › Sat 12:00"), an
offline car page that leads with "Last seen Sun 14:14 · 1 h 57 min · 50
laps" and a link, and a title per page.

### E. The event page (Major)

Seen on NHMS Lemons 2026 (`get_page_text` of the live page):

- "Practice, every part" and the "Practice" part show the same table when
  there's one practice.
- "The race" panel: a two-line sentence about flags and counting, then the
  standings, then the chart, then **"The flags" form** (datetime-local
  inputs and Now buttons) because Sam is signed in, then the stints table,
  then "Stints as set by hand.", then the stops sentence, then "Edit Race
  Session 1's stints", then "Every lap (460)".
- The stints text under the chart ("Stints, shaded in turn: 1 Hugh Enxing
  (laps 1–60) · 2 …") repeats the table.
- Stints 6, 8 and 13 are empty rows ("— 0:00:00 — — —"): marks with no laps,
  from editing. They look like a mistake.
- "Stops: lap 22, 1139.1 s in the pit lane · …" — seconds, in a sentence.
- Each part lists its sessions as "Tha Bearmobile, 09:26 / Hugh Enxing
  (HUG) / 60 laps"; a session with driver changes appears once per stretch,
  so "Tha Bearmobile, 08:43" appears three times.
- "Lap--Tha Bearmobile--" is the chart's legend when nothing is hovered.
- Race Session 2's part says "The race as one timeline is above; its
  sessions are here." — a sentence explaining the page's own structure.

**Do:** (phase 5) one page, in order: standings → the lap chart → stints (one
table, no sentence, no empty rows unless editing) → every lap (collapsed) →
practice (once) → sessions (one row per session, stretches inside it). Admin
tools behind one "Edit the race" toggle. Stops as m:ss in the stints table's
own column.

### F. The session page (Major; Sam's pain)

- The chart comes first, with two raw signal names (`engine.rpm`,
  `vehicle.speed`) in selects and no units until the legend.
- The laps table has 109 rows, each with "Compare…" and "vs best": two
  controls × 109. The best lap and the sector bests are the only colour.
- "Re-timing found 1:45.045" in the critical pink (`.note.flag`) reads as an
  error; it's information.
- Column heads "Top / Low / Avg" rely on a `title` tooltip for the unit;
  phones have no hover.
- In the sessions list, "Best 1:30.200 (lap 214 of 50)" means "lap number 214
  (numbered through the day), one of this session's 50 laps". Nobody reads
  it that way.
- "498,166 lines", "Tablet only", "P0037" (a trouble code as plain pink
  text, unexplained), "nhms · road" on every row: the crew doesn't need
  lines or slugs; the code needs a word.
- "Name this session" is a bare link above the facts.

**Do:** (phase 6) lead with what happened (driver, laps, best, map), the laps
table with one "Compare" affordance (pick two laps) and "vs best" as a single
link on the best lap, units in the headers, re-timing notes in the muted
colour, humanised signal names ("Engine RPM"), the chart under the laps and
zoomed by choosing a lap (which already works). In the list: "50 laps · best
1:30.200"; line counts and slugs only when signed in.

### G. The admin editors (Major; Sam's pain)

**Event editor:**
- Parts are rows of `datetime-local` inputs. The note "Times are yours
  (America/New_York). A session joins the part the server heard it during."
  is the whole explanation of the windows model, which is the thing people
  get wrong (sessions landing in the wrong part, or none).
- The Sessions section only exists after the first save, and warns "Save
  your changes first" while editing. You can't see which sessions a window
  would catch before saving.
- "Take out" / "Add to Practice" with a select per session is clear enough
  once found, but it's at the bottom of a long page.
- Nothing says what a part's kind means for the results (practice = bests;
  race = one timeline).

**Course editor:**
- A single tall sidebar: Name, Id, Aerial imagery, Open file / Download,
  Layouts, Timing lines, Pit, Save, re-timing status, A session's route,
  Editors, Remove course, Versions. Twenty-odd buttons, seven of them pink
  "Remove".
- The drawing mode is implicit: press "Draw", a hint appears *above the map*
  with a Stop button; nothing on the map itself says what a click will do.
- "only for road" (a layout id) as a checkbox label; "Redraw start/finish".
- Save is a button mid-sidebar, "Save as version 2", disabled until something
  changes; a long sidebar scrolls it out of view.

**Do:** (phase 7) event editor as three steps on one page (the event, the
schedule, the sessions) with a live preview of which sessions each window
catches, using `EventRules.sessionsIn` as the server already does; the
course editor with a toolbar on the map (mode shown, Esc stops), the sidebar
grouped into the four things a course needs (layout, start/finish, sectors,
pit) with a done/not-done mark on each, removes behind a confirm, and a
sticky save bar.

### H. Wording and consistency (Minor, many)

A single glossary would fix most of these; the terms are used in several
spellings:

- Dates: "2026-10-16 – 2026-10-18" (ISO) beside "Sun, 18 Oct 2026" and
  "10/22/2026, 8:00:00 AM" (locale).
- "Tablet only", "re-timed", "Re-timing found", "start/finish not yet
  checked at the track", "Theoretical best", "Spread ± 25.604 s", "Within
  1%": jargon with no hover and no glossary.
- "Compare…" (ellipsis = opens something) vs "vs best" (a link that
  navigates) in the same cell.
- Error colour used for trouble codes, re-timing notes, Remove buttons and
  real errors alike.
- "Message the driver" panel: good. Its presets ("PIT NOW", "BOX THIS LAP",
  "FUEL", "PUSH", "SLOW DOWN") are the clearest controls on the site; the
  rest should feel like them.
- "Sign in for edit capability. Must be an invited user." — fine for Sam,
  but the Sign in button is on every page for everyone, and family will
  click it.

### I. Accessibility and the pits (Minor now; Major on race day)

- Contrast is fine (muted `#9aa3ae` on `#0a0b0d` ≈ 7:1), but sizes aren't:
  see A.
- Focus styles are the browser's; the chart and map are mouse-first.
- No light / high-contrast option for sunlight. The look stays dark, but a
  "bright" switch for the pits (same roles, lighter palette in `app.css`,
  decision 29 allows it) is cheap and would be used.
- `aria-live` is on the car banner (good); the laps panel and the standing
  panel update silently.

### J. What's good, and should be kept

So the plan doesn't flatten what works:

- The live car page's banner, gauges and map at desktop width.
- The crew message panel: presets, lifetime, "Nothing on the driver's
  screen."
- The sessions list grouped by drive and day.
- Lap links everywhere: any lap in a table opens its session zoomed to it.
- The compare page.
- Confirmations by typing the id for destructive actions.
- Plain-language explanations where they exist ("Coloured by speed, blue
  slow to pink fast.").

## 4. The plan: seven phases

Ordered by audience. Each phase is one milestone, built and deployed on its
own, so Sam can stop after any of them. Effort is in days for an Opus-level
model with Sam reviewing: **S** under a day, **M** one to two, **L** three
to four.

### Phase 1 — The site fits a phone (M31) · L · crew, family

**Goal:** every page usable at 360–430 px, one-handed, no sideways scroll.

1. **A phone check script** (`web/scripts/phone-check.mjs`, after
   `measure.mjs`): headless Chrome at 390 × 844 against the dev server,
   every route in `lib/routes.ts`, asserting `document.documentElement.scrollWidth
   <= window.innerWidth` and screenshotting each page to `web/build/phone/`.
   Fails the build on overflow. **Checked:** `measure.mjs` already spawns
   Chrome; the dev server already seeds a car; the plan's own screenshots
   were made this way.
2. **The header:** at under 600 px, a single row: logo, then a compact nav
   (four links, or a "menu" disclosure), sign-in as an icon-sized button.
   The signed-in email goes into the disclosure.
3. **Page-level overflow:** fix the `nowrap` rows named in §3.A and any
   fixed-width elements the script finds, page by page, until the script
   passes with the content unchanged.
4. **Tables:** one shared pattern in `app.css` (`.table-scroll`: horizontal
   scroll inside the panel, first column sticky, a faint right-edge fade) for
   the laps and stints tables; the sessions list and the event's session
   list stack into cards at under 600 px (time and duration on one line,
   course/event/best on the next).
5. **Touch targets:** buttons and selects at least 44 px tall at under
   600 px; the `.small` class keeps its type size but gains padding.
6. **Type:** a 0.95rem floor on phones for anything that must be read in a
   hurry (banner, standing panel, laps panel, message panel); `.small` stays
   for metadata.
7. **Bright mode, minimal** (§6.3): a switch beside the units switch,
   remembered in `localStorage`; a second palette for the same roles in
   `app.css` under `:root[data-theme="bright"]`; the colour-roles test
   stays green. No component changes colour on its own.

**Acceptance:** the check script passes for every route; the car page's
banner, gauges, driver, laps and message panel are all reachable without
sideways scroll; Sam opens it on his phone at a drive.

### Phase 2 — Who's driving (M32, M34) · M · crew

> **Superseded in part by §7 (Sam, 2026-10-22):** who drove belongs to the
> session and is edited on its page; race stints are worked out from it. The
> phase is built as M32 (the model) and M34 (the screens); step 3 below
> (a stint mark added on a live change) is replaced by M32.

**Goal:** from the live page, on a phone, set the driver in two taps and see
it took.

1. **Driver panel with a control:** `StandingPanel` gains a "Set driver" /
   "Change driver" button when the crew is logged in; it opens a picker with
   one large button per driver and "Not set", plus Cancel. The bare
   `<select>` goes.
2. **Confirmation:** after setting, the panel shows "Drew Blouch · from
   14:05" (the tablet's clock, as now) for 10 s, then the usual.
3. **Edited stints:** when the race's stints for this car were edited by
   hand, a change from the live page also appends a stint mark at the same
   moment (the server side, in `driverRoutes`' change handler, through the
   race store), so the standings follow. **Checked:** the condition is
   `Standings.kt:106`; the race store's stint write is
   `PUT /api/admin/events/{id}/race/stints/{car}`; the crew can't call it
   today, so this is a server change with a decision (amends 44).
4. **Session page:** one "Drivers" block: a list of stretches ("From the
   start: Sam · From lap 48: Drew") each with change/remove, and one "Add a
   change at lap…" row. Same data, one place.
5. **Without the crew login:** the Driver panel says who's driving and, if
   nobody is set, "Not set · the crew sets it here" so family know it isn't
   broken.
6. **The crew login moves to the header** (§6.2): a "Crew" button opens a
   small panel (which car, the passcode); the crew cookie is per car as now,
   so the panel lists the cars and marks the ones logged in. The passcode
   field leaves the car page; "Message the driver" and "Set driver" appear
   there when logged in, as now.

**Acceptance:** on the dev server with a stream running, set a driver from a
phone-width window in two taps; the standings, the laps panel and the event
page agree within 30 s; with edited stints, the same.

### Phase 3 — Nothing waits in silence (M35) · M · family, drivers

1. **Results hold:** `ResultsHold` keeps results until `edited()` clears them
   (already wired), and uses the 10 s hold only while a part of that event is
   under way (live sessions change the results). The driver's page then
   reads held results. **Checked:** `edited` clears the hold on driver, race,
   event and course changes; a session's driver change goes through
   `driverRoutes`' `onChanged`, also `edited`. Session completion isn't wired
   to it: add `resultsHold.clear()` to the `completed` callback in
   `Application.kt`.
2. **Skeletons:** each page renders its panels at once with a placeholder
   block of the panel's usual height; `Loading…` goes.
3. **Session page order of arrival:** laps (`/api/sessions/{id}/laps`) first,
   then the thinned series, then the full one; the laps table and the facts
   show before the chart. The map and chart panels show skeletons.
4. **Progress on the slow ones:** for an event with more than N laps, the
   server's results endpoint answers 202 with a progress line while it
   computes ("Working out the race's 460 laps…"), the page polls; or, if the
   hold in step 1 makes this rare enough, skip it. Decide after measuring
   step 1 on NHMS.
5. **Titles:** `document.title` per page ("Tha Bearmobile · live",
   "NHMS Lemons 2026 · results", "Sat 12:00 · Tha Bearmobile").

**Acceptance:** the NHMS event page shows standings within 1 s on a second
visit; the session page shows laps within 2 s on the 3 h 17 min session;
JOURNAL records before/after timings from the logs.

### Phase 4 — Finding your way (M36) · M · family, crew

1. **Landing "Now":** above the lists, one card: a car streaming (driver,
   race lap, last lap, best, a "Watch live" button), or the latest session
   with its best and a link, or "Nothing running. Last: Sun 18 Oct, Tha
   Bearmobile." The event under way, if one, with "Standings".
2. **Names, not slugs:** the car page's title and every back link use the
   car's name (`CarsPage` already has it; the car page gets it from
   `/api/cars`).
3. **Breadcrumbs:** one component, used on car, sessions, session, event and
   driver pages: "Cars › Tha Bearmobile › Sessions › Sat 12:00".
4. **Offline car page:** the banner says "Offline · last seen Sun 14:14";
   under it, the latest session's summary and link, the car's events; the
   map shows the latest session's route (static) instead of the world; the
   G-meter is hidden until there's data (M10 kept it visible to show nothing
   has come; with the "last seen" line that job is done).
5. **Nav labels:** the four pages stay; the header reads "Live · Results ·
   Drivers · Courses" (§6.1). The "Hidden" badge leaves the landing page and
   the lists, and stays on manage and edit pages (§6.4).

**Acceptance:** from the landing, one tap to the live car when one is
streaming; a visitor can say which car page they're on without reading the
URL.

### Phase 5 — The event page (M37) · M · family, drivers

1. Reorder as in §3.E; practice rendered once when there's one practice.
2. Stints: one table with a Stops column (m:ss); empty marks hidden unless
   editing; the stints sentence under the chart removed (the chart's shading
   stays, with a legend of driver → shade).
3. Sessions: one row per session; stretches as a line inside it ("Hugh 60
   laps · Drew 4 laps").
4. Admin tools behind "Edit the race": the flags form and the stint editor
   appear in one panel when pressed; `Edit` (the event) and `Download event
   data` stay as buttons.
5. The chart's idle legend reads "Hover a lap" (or nothing).

**Acceptance:** the NHMS page, signed out, has no form controls above "Every
lap"; the stints table has no empty rows; each session appears once.

### Phase 6 — The session page and the sessions list (M38) · M · drivers

1. Order: facts → drivers → map → laps → chart → what happened.
2. Laps table: units in headers ("Top mph"), one "Compare" checkbox per row
   (pick two, a bar appears: "Compare lap 72 with lap 107"), "vs best" once,
   on the chosen lap; re-timing notes muted; the best lap and sector bests as
   now.
3. Signal names: a `label()` map used by the session page's selects as the
   car page already has (`CarPage` uses `label(n)`; the session page shows
   raw names).
4. The sessions list: "50 laps · best 1:30.200"; "lines" and the course slug
   only when signed in; trouble codes as "1 trouble code" with the code on
   hover/tap.
5. "Name this session" becomes a pencil beside the title for whoever may.

**Acceptance:** Sam opens Sunday 12:56 and finds the best lap, who drove and
the map without scrolling past the chart; comparing two laps takes two taps
and one button.

### Phase 7 — The editors (M39, M40) · L · Sam

**M39, the event editor:**
1. Three sections on one page: The event (name, dates, course, layout, cars),
   The schedule (parts as a day-by-day list: day, kind, from–to, with
   sensible defaults when adding), The sessions.
2. The sessions section works before saving: it shows, for the windows as
   drawn, which of the cars' sessions each would catch and which would be
   left out, from a dry-run endpoint (`EventRules.sessionsIn` on the
   unsaved parts). Save then commits both.
3. "Take out" / "Add to" stay, as a per-session row with the part shown as a
   select, in that preview.
4. One paragraph, at the top, says what a part is and how sessions join it.

**M40, the course editor:**
1. A toolbar over the map: the current mode as a pill ("Drawing the pit
   lane · click to add points · Esc to stop"), Undo, Stop.
2. The sidebar in four groups with a done mark: Layout ✓, Start/finish ✓,
   Sectors (3), Pit ✓; each group's draw/redraw/remove inside it; Remove
   behind a confirm.
3. A sticky bottom bar: "Version 7 · unsaved changes · Save as version 8".
4. The rest (Open file, Download, A session's route, Editors, Versions,
   Remove course) under "More" at the bottom.

**Acceptance:** Sam makes a two-day event with three parts and checks the
session assignment before saving; draws a course's four things without
reading the plan.

### Cross-cutting, in every phase

- **Wording pass with a glossary** (`docs/GLOSSARY.md`, mirrored in a
  `lib/terms.ts` used by components): one term per concept, dates in one
  format (`Sun 18 Oct, 14:14`), the error colour only for errors and
  destructive buttons.
- **Keep decision 29:** colours stay roles in `app.css`; the test that
  enforces it stays green.
- **Nothing on the tablet or in the contract changes** in any phase.
- **Each phase is deployed with a live stream across it** (JOURNAL: M4,
  M13), and Sam looks at it on his phone before the next starts.

## 5. How we'll know it worked

- The phone check script (phase 1) runs in `npm test`; a page that overflows
  fails the build.
- Timings in JOURNAL before and after phase 3, from the logs, on the NHMS
  event and the Sunday 12:56 session.
- Three task walk-throughs, each timed with a phone and a stopwatch by Sam
  or a crew member, before phase 1 and after phase 4:
  1. "The race is on. Find the car and tell me who's driving and the last
     lap." (from the landing page)
  2. "Drew just got in the car. Set it." (crew, logged in)
  3. "What was my best lap on Saturday afternoon?" (a driver)
- The next race weekend's JOURNAL entry says what the crew asked for.

## 6. Sam's answers (2026-10-22)

Asked and answered the day the review was written; each phase above is to
be read with these.

1. **Nav labels:** "Live · Results · Drivers · Courses". Only the words
   change: "Live" is `/cars` (every car and its state), and each car's page
   stays at `/cars/<slug>` with its live view, its past sessions and, for its
   editors, Manage. "Results" is `/events`. (Sam: "I like #1, but where does
   the car info page go?" — it doesn't move.) Phase 4.
2. **The crew login** moves to a **Crew button in the header** that
   remembers the car; the passcode field leaves the live page. Phase 1 lays
   out the header; phase 2 moves the login, since it's the crew's.
3. **A bright mode for the pits:** a **minimal version in phase 1**: a
   switch, and a lighter palette for the same colour roles in `app.css`
   (decision 29 kept). A fuller design can follow if it's used.
4. **The "Hidden" badge** shows **only on manage and edit pages**, not on
   the landing page or the lists. Phase 4.
5. **Phase order stays:** phones first, the editors last.
6. **Comparing laps:** **pick two laps** (a checkbox per row, one "Compare
   lap 72 with lap 107" bar; "vs best" once, on the best lap). The per-row
   buttons go. Phase 6.

## Appendix: method and evidence

- **Heuristics:** Nielsen's ten, scored per page; the ones that failed
  most: *visibility of system status* (B), *match to the real world*
  (wording, H), *recognition over recall* (D, G), *aesthetic and minimalist
  design* (E, F), *flexibility and efficiency* (C).
- **Pages walked:** landing, cars, car (offline, live, crew), sessions,
  session (3 h 17 min, NHMS Sat 12:00), events, event (NHMS Lemons 2026),
  drivers, driver, courses, course, compare (empty), manage car, event
  editor, course editor, stint editor.
- **Phone captures:** dev server, 390 × 2000, headless Chrome
  (`--headless=new --window-size=390,2000 --screenshot`); kept in the
  session's scratchpad, not the repo.
- **Timings:** Cloud Run logs 2026-10-20 19:30–19:34 (`/api/events/…
  5926ms`, `8293ms`, `4989ms`); browser waits on 2026-10-22.
- **Code read:** every `.svelte` template (markup only), `app.css`,
  `lib/routes.ts`, `EventResults.kt` (`ResultsHold`), `Standings.kt:106`.

## 7. Who drove: one truth, one place to edit (proposed 2026-10-22)

**Sam, 2026-10-22:** "any change to the stint takes place everywhere. it
may be worth considering how the stints/sessions/events all work together.
Ideally, there would only be one place where each edit is possible, and it
should be a logical place."

Of everything the site lets you edit, only **who drove** is edited in more
than one place. A session's name, its event part, the flags, courses,
drivers' names and cars each have exactly one. So this section is about
drivers.

### Today: three records, four editors, two precedence rules

| Record | Stored on | Edited from | Read by |
| --- | --- | --- | --- |
| A session's driver | `SessionRecord.driver` | the session page's select; the live page's "Who's driving" before the session has laps | practice results, the live standing, default stints |
| A session's driver changes (M27) | `SessionRecord.driverChanges` | the session page ("change at lap"); the live page once the session has laps ("from now") | practice stretches, default stints, the laps tables |
| A race part's stints as edited | `Part.stints[car]` (`StintMark(start, driver)`) | the event page's stint editor (driver, split at lap, merge) | race results, driver pages, the live standing, the download |
| Default stints | worked out (`Race.kt:264–276`): the first lap, each stop's exit, each driver change | — | race results when no stint was edited |

What goes wrong:
- **Precedence decides silently.** When stints were edited, they win and
  session drivers and changes stop counting (`Standings.kt:106`); when they
  weren't, the session's driver wins over the stint's. That was the
  2026-10-19 "why won't the current driver update?".
- **Stints split at every stop.** A fuel stop with the same driver makes two
  stints, so the only fix is the stint editor's merge, which flips the
  race into "edited" mode, which is what makes later driver changes stop
  counting.
- **Every new session starts with nobody driving.** A restart of the app, or
  (proposed) a STREAM toggle, means setting the driver again
  (`proposals/SESSIONS-WHILE-STREAMING.md`).
- **The same fact has different addresses:** "Drew from 14:05" is a
  session's change, or a race stint, depending on where it was typed.

### Decided (Sam, 2026-10-22): who drove belongs to the session

Sam was offered a per-car driver log and chose otherwise: "Who Drove can be
a per-session thing, sessions should be tied to cars, but i think the driver
should mostly be edited by session, there's not a ton needed for the car
itself." And past corrections are made "Always the session page".

- **One record: the session's drivers.** Its driver from the start, and its
  driver changes (M27, `from` on the tablet's `wall`), as stored now on
  `SessionRecord`. Nothing else says who drove.
- **Race stints are worked out from them, never edited.** A stint is a
  driver's run: a new stint at each session's start driver and at each
  driver change, across the race's sessions. **Stops no longer split a
  stint**; they show inside it. A change to the same driver (entered at a
  stop, for a double stint) starts a new stint. Hand-edited stints
  (`Part.stints`) and the stint editor's split and merge go, and with them
  both precedence rules (`Standings.kt:106`).
- **A correction replaces, not adds** (answer 1): a change less than 60 s,
  or less than one lap, after the one before it replaces it.
- **A new session straight after another starts with the driver the
  previous one ended with** (the same tablet, at most 10 minutes between
  them, restarts of the app included; Sam, 2026-10-22), stored on the new session when it's created and editable on its
  page. That's the STREAM proposal's carry-over, needed now for restarts
  too; nobody has to set the driver again after a restart.
- **Migration, once:** an admin tool turns each race part's edited stints
  into the drivers and driver changes of the sessions they fall in (a stint
  starting inside a session becomes a change there; the stint in force at a
  session's start becomes its driver). Checked on NHMS: laps per driver
  unchanged (SAM 122, HUG 118, PAU 113, DRE 107), then `Part.stints` is
  cleared.
- **A decision of its own** (amends 34, 35, 43 and 44; replaces drafted 48).

### Where each edit lives

| Edit | The one place |
| --- | --- |
| Who's driving now | The live car page, crew logged in: Set driver, who then when. It writes the live session's drivers, the same record the session page edits. |
| Who drove when, afterwards | **The session page, always.** The event page's race stints are read-only; each stint names its session and links to it ("Edit drivers on Sat 12:00 →"). Practice and driver pages likewise. |
| Which part a session is in | The event editor |
| The drivers themselves | The Drivers page |

### What this changes in the milestones

- **Phase 2 splits:** M32, the model (stints from session drivers; stops inside
  stints; the correction rule; the carry-over at session creation; the
  migration; the decision; the race, standing, driver pages and download
  read the result), then M34, the UI (Set driver with "when", the header's
  Crew button, the session page's Drivers block).
- **M37:** the stint editor and "Back to splitting at every stop" go; the
  stints table links each stint to its session. "Edit the race" keeps the
  flags only.
- **M38:** the session page's Drivers block (M34) is the one editor for
  the past, for every session.
- **The STREAM proposal:** its carry-over is built in M32, for restarts
  now and STREAM toggles later.
