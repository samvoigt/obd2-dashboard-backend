# M30 — The site fits a phone

Every page usable at 360–430 px wide, one-handed, with no sideways scroll,
and a bright mode for the pits in sunlight. This is phase 1 of the UX review
([`UX-REVIEW.md`](UX-REVIEW.md) §3.A, §3.I, §4 phase 1, §6 answers 2 and 3).
The crew, on phones in the pits, are the site's first audience.
**Sam, 2026-10-22:** "Restructure freely, keep the look", phases in the
review's order ("Keep: phones first, editors last"), a bright mode as a
"Minimal version in phase 1", and the crew login as "A Crew button in the
header" (built in M33; M30 leaves its place).

> **Planned 2026-10-22**, checked against the code.

---

## What exists, and what it means for this (checked 2026-10-22)

- **The viewport is right:** `web/index.html:5` has
  `<meta name="viewport" content="width=device-width, initial-scale=1">`.
- **The review's phone screenshots may overstate the overflow.** They were
  taken with `chrome --headless=new --window-size=390,2000 --screenshot`.
  Desktop Chrome won't lay a window out narrower than about 500 px, so those
  pages were probably laid out at ~500 px and then cropped to 390. Signs of
  it: a landing card's "Latest: … · Live n" is cut off mid-word instead of
  wrapping, and the sessions list's rows are cut off although `li a` and
  `.facts` have `flex-wrap: wrap` (`SessionsPage.svelte:153–159`).
  **So M30.1 measures first, the right way,** and M30.3 fixes only what it
  finds. The review's list of suspects stands as a starting point.
- **`web/scripts/measure.mjs` already lays a page out as a phone:** it starts
  Chrome with a throwaway profile and drives it over the DevTools protocol
  (Node's built-in WebSocket, nothing installed), with
  `Emulation.setDeviceMetricsOverride { width: 390, height: 844,
  deviceScaleFactor: 2, mobile: true }` (`measure.mjs:61`). A phone check can
  reuse that start-up code as it is.
- **The dev server** (`server/src/dev/.../DevServer.kt`) seeds one car,
  `dev-car` (passcode in `server/build/dev-passcode`), and the course
  `nhms` from `courses/seed/nhms.geojson`. It has no events, drivers or
  sessions until a replay or the admin API adds them. Vite's dev server
  (`npm run dev`, port 5173) proxies the API to it (`vite.config.ts:9`).
- **Routes** are in `web/src/lib/routes.ts`; `App.svelte` mounts one page per
  route, between `SiteHeader` and `SiteFooter`.
- **The header** (`SiteHeader.svelte`) is a wrapping flex row: `.home` (logo,
  site name), `nav` (`flex: 1`, four links), and `.who` (`margin-left: auto`)
  holding Users, the email, and Sign in / Sign out. When signed out on the
  dev server it shows *two* buttons (Dev sign-in, Dev sign-in as a user).
  Its only media query (`max-width: 520px`, line 126) hides the site name.
  `.email` is `white-space: nowrap` with `max-width: 40vw` (line 119), which
  is fine on its own.
- **Media queries:** three in the whole site, in `SiteHeader` (520 px),
  `CarPage` (480 px, the banner's font size) and `CourseEditor` (900 px).
- **`white-space: nowrap`** appears at `CarsPage.svelte:144` (`.state`),
  `Landing.svelte:198` (`.state`) and `:201` (`.under-way`),
  `SessionPage.svelte:490` (`.laps .note`, the re-timing note),
  `SiteHeader.svelte:119` (`.email`) and `ComparePick.svelte:30` (`.pick`,
  inside table rows). `CourseEditor.svelte:644` is `.row { flex-wrap:
  nowrap }`, not `white-space`. Only `.laps .note` and `.pick` can widen a
  table, and both sit inside a panel that scrolls (below).
- **Tables already scroll inside their panels** in some places:
  - `EventPage.svelte` and `RaceSection.svelte`: `.panel { overflow-x: auto }`;
  - `SessionPage.svelte`: `.lapswide { overflow-x: auto }`;
  - `LapsPanel.svelte`: `.scroll` scrolls vertically only, with the table at
    `width: 100%`.

  There's no shared table style: each component styles its own.
- **There's no shared button style either.** Every component has its own
  `button { … }` with padding of 4–6 px, so buttons are 26–32 px tall. A rule
  in the global `app.css` (`main.ts` imports it once) reaches every
  component's elements, since the components' styles only scope their own
  selectors. `.button` (a link styled as a button) is defined four times,
  identically, in `EventPage`, `CoursePage`, `CoursesPage` and `EventsPage`.
- **Colours:** every colour is a role in `app.css`'s `:root` (decision 29).
  `lib/theme.test.ts` checks three things:
  - the four logo colours are exact;
  - each foreground role reaches 3:1 contrast on `bg` and on `panel`;
  - the four meaningful roles are at least 25 apart in CIE Lab, and no colour
    literal appears outside `app.css`.

  Its `role()` reads the *first* `--name:` in the file. Canvas drawing (uPlot
  charts, Leaflet maps) reads colours from `lib/theme.ts` `color()`, which
  calls `getComputedStyle` when a chart or map is built, so changing the
  theme changes CSS at once but not what's already drawn.
- **Units** are the model for a remembered choice: `lib/units.ts`
  `loadSystem` / `saveSystem` wrap `localStorage` in try/catch;
  `lib/unitsState.svelte.ts` holds `$state`; `widgets/UnitsSwitch.svelte` is a
  two-button group. The switch appears on the car page (`.sessionline`) and
  the session page (`.facts`).

## Decided here

- **Breakpoint:** one, `600px`, for "phone" rules, in `app.css` and in
  components alike. CarPage's existing 480 px banner rule stays.
- **The phone check is the arbiter.** A page passes when, at 390 × 844
  mobile emulation, `document.documentElement.scrollWidth <=
  innerWidth`. Tables may scroll *inside* their panel; the page itself never
  does.
- **Tables:** a shared `.table-scroll` wrapper in `app.css`
  (`overflow-x: auto; max-width: 100%`), with the first column sticky. The
  laps and stints tables use it. The event page's sessions become rows that
  wrap in M36.6, when that page is reordered; M30 only makes sure nothing
  escapes its panel. The exception is the sessions list, whose rows already
  wrap.
- **Touch targets:** at under 600 px, `button`, `select`, `input` (not
  checkbox or radio), `a.button` and `summary` are at least 44 px tall,
  through a global rule in `app.css`. `.small` keeps its type size.
- **Type floor:** at under 600 px, the readouts the crew reads in a hurry
  never go under 0.95rem: the banner text, the standing panel, the laps
  panel and the message panel. Metadata may stay `.small`.
- **The header at under 600 px** is one row: the logo (home), the four nav
  links, and a single compact account control at the right. Signed out:
  "Sign in" (on the dev server, its two buttons). Signed in: a button
  showing the first letter of the email, which opens a small panel holding
  the email and role, Users (for a master) and Sign out. **The Crew button
  goes in `.who`, before the account control,** as an empty slot M33 fills;
  M30 adds no Crew control.
- **Bright mode** (§6.3) is a second palette for the same ten roles, under
  `:root[data-theme="bright"]` in `app.css`. It's chosen by a "Dark / Bright"
  switch beside the units switch, and remembered as the units are.
  - Canvas drawing picks it up by rebuilding the chart or map when the theme
    changes.
  - The logo's four colours may be darkened in bright mode only as far as
    contrast needs. The theme test checks both palettes.

## The steps

### M30.1 — The phone check (script and npm task)

`web/scripts/phone-check.mjs`, built on `measure.mjs`'s start-up code. Move
the shared part (start Chrome, `target()`, `send()`) into
`web/scripts/chrome.mjs` and have both import it.

- **Input:** a base URL (default `http://localhost:5173`) and a list of paths.
  - Defaults: every route that exists on a fresh dev server: `/`, `/cars`,
    `/cars/dev-car`, `/cars/dev-car/sessions`, `/cars/dev-car/manage`,
    `/events`, `/drivers`, `/courses`, `/courses/nhms`, `/compare`,
    `/privacy`, `/terms`; then, signed in with the dev sign-in (`POST
    /api/admin/login` `{"credential":"dev"}` from the page), `/courses/nhms/edit`,
    `/courses/new`, `/events/new` and `/users`.
  - Any extra paths given as arguments, such as an event or a session once a
    replay has made one.
- **For each path**, at 390 × 844 with `mobile: true`:
  1. navigate and wait for the network to go quiet (2 s with no request);
  2. read `scrollWidth` and `innerWidth`;
  3. list the elements whose right edge passes the viewport: tag, class and
     width, the widest five;
  4. save a full-page screenshot to `web/build/phone/<path>.png` with
     `Page.captureScreenshot { captureBeyondViewport: true }`.
- **Exits 1** if any page overflows, printing each one's offenders.
- **Not part of `npm test`.** It needs Chrome and a running server. Document
  it in CLAUDE.md's Build block beside `measure.mjs`:
  `node web/scripts/phone-check.mjs [base] [paths…]`.

**Check:** run it against `./gradlew :server:devServer` plus `npm run dev`.
Record each page's result in this plan; that list is M30.3's work. Then
replay a short session with the M29 dev-check approach (a synthetic NHMS log)
and check the session and event pages with extra paths.

**Built 2026-10-22.** `web/scripts/chrome.mjs` (Chrome's start-up, `send`, and
now `on` for events) shared by `measure.mjs` and the new `phone-check.mjs`.
"Quiet" is no *new* request for 2 s (capped at 15 s), since the car page
keeps its live stream open. An element counts as poking out only if no
ancestor scrolls or clips it, so a table scrolling inside its panel isn't an
offender (that's M30.4's look, not a page-level overflow).

**Found:** at 390 × 844 with mobile emulation, **every page fits**: the 12
signed-out and 6 signed-in default paths, and, with the M29 synthetic NHMS
sessions and event replayed in, the event page, two session pages and a
driver's page, signed out and in (26 checks). The review's screenshots were
indeed laid out wider and cropped. Seen in the screenshots instead:
- the header takes two rows (M30.2);
- tables are cut at the panel's right edge, and the whole panel scrolls,
  headings and all, not just the table (M30.4);
- small buttons: "Now", "Save the flags", "Compare…", "Remove", "Add",
  the stint editor's (M30.5).

So **M30.3 has nothing to fix**; it stays as a check that the later steps
keep every page passing.

### M30.2 — The header at phone width (§3.A, §6.2)

`SiteHeader.svelte`:
- Under 600 px, `header` stops wrapping and the four links shrink to fit:
  `gap: 10px`, 0.95rem.
- `.who` becomes one control, as decided. A small `AccountMenu` component,
  or a `<details>` inside `SiteHeader`, holds the email, role, Users and Sign
  out. It closes on Escape and on a click outside.
- Above 600 px nothing changes.
- An empty `<span class="crew-slot"></span>` goes first in `.who`, with a
  comment pointing at M33.
- On the dev server, signed out, the two dev buttons go into the same menu
  under 600 px.

**Check:** the phone check passes for `/` signed out and signed in (signed in
with the dev sign-in in the check's profile: `POST /api/admin/login`
`{"credential":"dev"}` before navigating). Desktop unchanged, by eye.

**Built 2026-10-22.** In `SiteHeader.svelte`: the account controls sit in a
`.wide` group (unchanged above 600 px) and a `.narrow` one (under 600 px). The
narrow one is a `<details>` menu, its summary the email's first letter signed
in (email, role, Users for a master, Sign out inside), or "Sign in" on the dev
server signed out (its two dev buttons inside); production's single Sign in
button stays a button. The menu closes on Escape and on a click outside. An
empty `.crew-slot` goes first in `.who` for M33. The 520 px rule became the
600 px one. **Checked** at 390 and 360 px, signed out and in: the header is
one row (38 px; it was two), the menu opens wholly on screen and Escape
closes it; at 1280 px the header is as before; the phone check passes.

### M30.3 — Page-level overflow, page by page (§3.A)

Fix what M30.1 found, one page per commit, until every default path passes.
Likely work, from reading the code: the car page's `.back` row (two links,
no wrap), the crew login row (an input and a button), `Faults`, the gauge
grid's `minmax(170px, 1fr)` (fine at 390, tight at 360: lower it to 150 px
under 600 px), and the course editor, whose layout switches at 900 px.

**The course editor** only has to be usable at phone width, not good: the
map on top at full width, then the sidebar. Editing on a phone is phase 7's
business.

**Check:** the phone check passes for every default path. Run the existing
tests (`npm test && npm run check`) after each page.

### M30.4 — Tables scroll inside their panel (§3.A)

- In `app.css`: `.table-scroll { overflow-x: auto; max-width: 100%;
  -webkit-overflow-scrolling: touch; }`. Its first column gets
  `position: sticky; left: 0; background: var(--panel)`.
- Wrap these tables in it:
  - the session page's laps (replacing `.lapswide`);
  - the race's stints and every-lap tables;
  - the event page's practice tables;
  - the driver page's tables.
- `RaceSection` and `EventPage` drop `overflow-x: auto` from the whole
  `.panel`, so headings and text no longer scroll with the table.
- The sessions list's rows already wrap. Check them in the phone screenshot:
  time and duration on one line, the facts on the next. Fix only if they
  don't.

**Check:** the phone check passes with an event and a session that have 5
sectors and speeds (the replay from M30.1). In the screenshots each table
starts at the panel's left edge, with the lap number visible while scrolling.

### M30.5 — Touch targets and the type floor (§3.A)

In `app.css`, `@media (max-width: 600px)`:
- `button, select, input:not([type=checkbox]):not([type=radio]), a.button,
  summary { min-height: 44px; }`;
- checkbox and radio labels get the same height through `label.check`.

Then, in the components the crew reads, raise anything under 0.95rem at
phone width:
- `CarPage` (`.banner-text`, the session line);
- `StandingPanel`, `LapsPanel`, `MessagePanel`;
- `Faults` (`.name` is 0.85rem).

Also move the four copies of `.button` into `app.css` as one, and delete
them from the components.

**Check:** the phone check also reports every visible button, select or
input under 44 px tall (a function it evaluates in the page through
`Runtime.evaluate`, reading each element's `getBoundingClientRect()`), and
passes only with none on the car page and the session page.
Everything else is reported but not failed, since the editors are phase 7's.

### M30.6 — Bright mode (§3.I, §6.3)

1. **The palette.** In `app.css`, add `:root[data-theme="bright"] { … }` with
   all ten roles and `color-scheme: light`. Suggested starting values:
   - `bg #f7f8fa`, `panel #ffffff`, `line #d5dbe3`, `text #0a0b0d`,
     `muted #4a5560`, `no-data #9aa3ae`;
   - the four logo roles darkened only as far as 3:1 on white needs
     (e.g. accent `#0077b3`, in-range `#00897f`, caution `#c2408f`,
     critical `#d6007f`).

   Tune them until the tests pass.
2. **The theme test** (`lib/theme.test.ts`): give `role()` a palette argument
   (`'dark' | 'bright'`) that reads either `:root { … }` or the bright block.
   - Run the 3:1 contrast checks and the 25-in-Lab check for both palettes.
   - The "four logo colours are exact" check stays dark-only.
   - Add a check that the bright block defines exactly the same ten roles as
     `:root`.
3. **The choice** in `lib/theme.ts`, after `units.ts`:
   - `loadTheme()` and `saveTheme()` with try/catch, key `theme`, default
     `dark`;
   - `lib/themeState.svelte.ts` holding `$state`;
   - `applyTheme(t)`, which sets or clears
     `document.documentElement.dataset.theme`.

   Call it in `main.ts` before `mount`, so there's no flash. Unit tests as
   `units.test.ts` has them: a saved value, nothing saved, storage refused.
4. **The switch:** `widgets/ThemeSwitch.svelte`, copying `UnitsSwitch` (Dark
   | Bright). Show it next to `UnitsSwitch` on the car page and the session
   page, the pages read in the pits. Also show it in the header's account
   menu (M30.2), so every page can change it.
5. **Canvas:** `Chart`, `LapChart`, `DistanceChart`, `SessionMap`,
   `CourseMap` and `GMeter` read `themeState`. Each rebuilds when it changes:
   uPlot is destroyed and re-created with the same data, and Leaflet layers
   are re-styled. The map tiles stay as they are.

**Check:** `npm test` (theme and storage tests) and `npm run check`. On the
dev server with a replay streaming, switch to Bright on the car page: the
banner, gauges, chart and map all change without a reload, and the choice
survives a reload. Run the phone check again in bright mode (it sets
`localStorage.theme` first).

### M30.7 — Deployed, and looked at on a phone

Before deploying, the review's three walk-throughs (§5) are timed on a
phone against the current site, as the baseline M33.6 and M35 compare
with; the times go to JOURNAL. Then deploy as usual: a smoke car streaming across the deploy. Then Sam opens
badnewsbears.live on his phone:
- the landing page;
- the car page, live if a car is out, otherwise the dev car through a
  replay;
- an NHMS session;
- the NHMS event.

He checks there's no sideways scroll, every button can be hit with a thumb,
and Bright mode reads in daylight. Anything he finds goes into this plan's
last step, before the milestone closes. JOURNAL gets the phone check's
before and after.
