# M21 — One site: a landing page that shows it all, and sign-in where you are

The landing page shows, in short, everything it links to. Signing in, from a
button on every page, turns on the edits on the pages they belong to, and the
admin page goes. **Sam, 2026-10-01:** "the main landing page should show
expanded versions of all the pages that can be clicked to. add sign
in/logout buttons, which will just enable the various edit features. we
probably don't even need the admin page at that point."

> **Planned, answered 2026-10-01, not validated.** The "What exists" section was read from the
> code on 2026-10-01. Each step is still validated against the code just
> before it's built, and that's written in here.

---

## What exists, and what it means for this (checked 2026-10-01)

**The landing page** (`Landing.svelte`, 63 lines) is the logo, the cars
(name and live state, from `GET /api/cars`, polled every 10 s), and one line
of links: Events · Drivers · Courses. Nothing links to `/admin` from it.

**The public pages, and what each could show in short:**

| Page | Data (public) | On the landing page, in short |
| --- | --- | --- |
| A car, `/cars/{slug}` | `/api/cars`, its SSE stream | Name, live state (as now) |
| A car's sessions, `/cars/{slug}/sessions` | `/api/cars/{slug}/sessions` | Its latest session: when, how long, laps, best lap |
| Events, `/events` and `/events/{id}` | `/api/events` (name, date, course and layout, cars, parts with times) | Each event: date, course, cars; "under way" while a part is |
| Drivers, `/drivers` and `/drivers/{id}` | `/api/drivers` (name, code) | Each driver's name and code |
| Courses, `/courses` and `/courses/{id}` | `/api/courses` (layouts, sectors, version) | Each course and its layouts; a small map would need each course's GeoJSON |
| Compare, `/compare` | laps in the query | Not on the landing page: it's reached from two laps |

So the cars, events, drivers and courses can each be a section of the landing
page from endpoints that exist. The per-car latest session is one request per
car. That's fine for a few cars, but not every 10 s. A small course map is one
`/api/courses/{id}` per course.

**The admin page** (`/admin`, `Admin.svelte`, 439 lines) does, after Google
sign-in:
- **Cars:** add (generated or chosen token, the generated one shown once),
  rename, replace the token, set or change the crew passcode, remove (its
  slug typed). Plus a car's **sessions**: list with state, download, delete
  (the first 8 characters of its id typed; refused while live or uploading).
- **Links** to `/admin/courses` (the list and `CourseEditor`), `/admin/events`
  (the list and `EventEditor`) and `/admin/drivers` (add, rename, change
  code, remove).

**Edits already on public pages:** a session's **name** and **who drove**
(`SessionPage`) and a race's **flags and stints** (`RaceSection` on the event
page) are set "by the admin or the car's crew". `lib/events.ts` `whoCanSet`
asks `GET /api/admin/me` first, then `GET /api/cars/{slug}/crew`. So public
pages **already see the admin sign-in**. The admin cookie is
`Path=/api/admin`, so it's sent to every `/api/admin/*` call from any page.
**No cookie change is needed** for edits to move onto public pages.

**Two sign-ins, kept apart on the server:**
- **Admin** (decision 25): Google ID token → `POST /api/admin/login` →
  cookie `admin`, `adm1.…`, `HttpOnly`, `Secure`, `SameSite=Strict`,
  `Path=/api/admin`, 30 days. The allowlist is checked on every request.
  Every change goes through `call.admin(…, change = true)`, which refuses a
  missing or foreign `Origin` (`403`) and no sign-in (`401`).
- **Crew** (decisions 11, 22): a passcode per car → `POST
  /api/cars/{slug}/login` → cookie `crew_<slug>`, `Path=/api/cars/<slug>`.
  It allows messages, a session's name and driver, and race flags and stints,
  for that car only. Rate-limited.

**Headers:** only the `/admin…` page paths send `X-Frame-Options: DENY` and
`frame-ancestors 'none'` (`WebRoutes.kt`). Every page is the same shell,
served `no-cache`, and the data comes from the API. **The admin API sends no
`Cache-Control`** (also noted in M9).

**What never reaches a public page today:** tokens (only their hint, and
only from `/api/admin/cars`), passcodes (only `passcodeSet`), a session's VIN.
The public endpoints don't change with a sign-in. Admin-only facts come only
from `/api/admin/*`, and that must stay so.

**`admin.sh`** is untouched by any of this.

---

## Decided here (say if any is wrong)

- **Sign in / Sign out is the admin's Google sign-in**, in a header on every
  page. The server side doesn't change: same cookie, same allowlist, same
  `call.admin` check on every change. *Not chosen:* merging it with the crew
  passcode. A crew member isn't an admin, and the passcode is per car and
  shared. **The crew login stays where it is**, on the car's page.
- **Signed in, each page shows its own edits**; signed out, it's exactly
  today's public page. The page asks `GET /api/admin/me` once, and the
  header holds the answer for every page (one small store, not each page
  asking).
- **Where each admin task goes:**
  - **Courses:** "New course" on `/courses`. The editor moves to
    `/courses/new` and `/courses/{id}/edit`, linked from the course page.
  - **Events:** "New event" on `/events`. The editor moves to `/events/new`
    and `/events/{id}/edit`, linked from the event page.
  - **Drivers:** add, rename, change code and remove inline on `/drivers`.
  - **Cars:** a new **Cars page, `/cars`** (Sam, below), listing every car.
    Each one links to its **live feed** (`/cars/{slug}`, as now), its
    **sessions** (`/cars/{slug}/sessions`, as now) and, when signed in, its
    **management**, `/cars/{slug}/manage`: rename, the token (replace, its hint
    shown), the crew passcode, and remove. "Add a car" is on `/cars` when
    signed in.
  - **Sessions:** download and delete on the car's sessions page, beside each
    session, when signed in: that's where they're listed.
- **The old `/admin…` URLs are removed**, not redirected (Sam, below): the
  server stops serving them, so they're a `404`. The page's own router
  already shows the landing page for a path it doesn't know.
- **Google's script loads only when "Sign in" is pressed**, not on every
  page view. Locally, the dev server's dev sign-in shows in its place, as now.
- **Every page gets `frame-ancestors 'none'` and `X-Frame-Options: DENY`**,
  since every page can now carry edits. The site isn't framed anywhere today.
- **`/api/admin/*` answers `Cache-Control: no-store`**, so no browser or proxy
  keeps what a signed-in page was sent.
- **The admin `403` message** "Changes must come from the admin page" becomes
  "…from this site".
- **The landing page's summaries come from existing endpoints**: cars every
  10 s as now; each car's latest session, events, drivers and courses once on
  load and then every minute. A single `/api/overview` is added only if
  measuring says the page is slow.

## Settled with Sam, 2026-10-01

1. **A summary per page**, its key facts and a link, as in the table above.
2. **A Cars page** "which can link to its management, its sessions, its live
   feed": `/cars`, with `/cars/{slug}/manage` new. The landing page's cars
   section is a summary that links to it, like every other section.
3. **Small course maps on the landing page**, one per course.
4. **`/admin` removed**, every path under it, with no redirects.

---

## The steps

### M21.1 — The header, and who's signed in ✅

A site header on every page (`App.svelte`): the logo's home link, and **Sign
in** or the signed-in email with **Sign out**. A small store, `lib/signin.ts`,
asks `/api/admin/me` once and is updated on sign-in and sign-out. `whoCanSet`
reads it instead of asking again. Google's button opens on demand. Tests:
the store's states, and that a `401` from any admin call signs the page out.

**Validated and built, 2026-10-01.** No page has a fixed full-screen layout,
so the header sits above each page's `main`. `lib/signin.ts` is a Svelte
store (`createSignIn(fetcher)`, tested with a fake server): it asks
`/api/admin/config` and `/me` once, however often it's checked. `api()` in
`lib/admin.ts` calls `signin.lapsed()` on any `401`. `whoCanSet` takes the
store (tests pass their own), and `SessionPage` and `RaceSection` ask it
again whenever the sign-in changes, so signing in on a page turns its edits
on without a reload. `SiteHeader.svelte` has home, Cars · Events · Drivers ·
Courses (the current one underlined), and Sign in / the email and Sign out.
On the dev server, Sign in is the dev sign-in. In production it shows
Google's button, whose script loads on the first press. Nothing shows if
sign-in isn't set up. The router lost every `/admin…` route and gained
`cars`, `manage`, `course-edit` and `event-edit` (`new` for one not yet
saved, as before). App renders placeholders for the Cars and Manage pages,
which M21.5 fills.

### M21.2 — The server: every page unframed, the admin API not stored ✅

`frame-ancestors 'none'` and `X-Frame-Options: DENY` on the page shell
everywhere. `Cache-Control: no-store` on `/api/admin/*`. The `403` text.
The new page paths `/cars`, `/cars/{slug}/manage`, `/courses/new`,
`/courses/{id}/edit`, `/events/new` and `/events/{id}/edit` served (from
`WebRoutes.kt`'s list). The `/admin…` page paths removed, so they're `404`.
Tests in `:server`, next to today's `/admin` header test, which becomes
"every page".

**Validated and built, 2026-10-01.** The frame headers moved into
`page()`, so every page sends them. `/api/admin/*` gets `no-store` from a
small application plugin (`AdminNoStore`), not per route, since the admin
API spans five route files. The admin page's paths are gone and are `404`.
"…from the admin page" became "…from this site", and "The admin page is not
set up yet" became "Signing in is not set up yet". `WebRoutesTest` covers the
new paths, the headers on every page, the `404`s and `no-store`, and that the
public API is unchanged.

### M21.3 — Courses and events edited where they're shown ✅

The routes `/courses/new`, `/courses/{id}/edit`, `/events/new` and
`/events/{id}/edit` (`routes.ts`, tests). `CourseEditor` and `EventEditor`
move there unchanged. "New …" and "Edit" buttons show when signed in. The
"Sign in on the admin page first" messages become the header's sign-in.
`CoursesAdmin` and `EventsAdmin` go.

**Validated and built, 2026-10-01** (by a subagent). Both editors asked
`/me` once in `onMount` and said "Sign in on the admin page first". Their
links and redirects went to `/admin/courses…` and `/admin/events…`.
`CourseEditor` built its Leaflet map in `onMount`, on a `div` that doesn't
exist while the page is signed out. **Done:**
- **Public pages:** signed in, **New course** on `/courses` and **New
  event** on `/events`, and **Edit** on a course's and an event's page. They
  follow `$signin`.
- **The editors:** signed out they say "Sign in (top right) to edit." Back
  goes to the list for a new one, else to the course's or event's page. A
  first save goes to `…/{id}/edit`, and removing an event goes to `/events`.
  A `401` signs out through `api()`.
- **`CourseEditor`'s map** is built by an `$effect` on its `div`, so it
  appears when you sign in. M20's file features are unchanged.
- **`EventEditor`** loads once when the sign-in turns `in`.
- **Deleted:** `CoursesAdmin` and `EventsAdmin`.
**Changed:** signing out mid-edit unmounts the editor, and unsaved changes
go with it.

### M21.4 — Drivers edited on `/drivers` ✅

`DriversAdmin`'s add, rename, code and remove, inline on `DriversPage` when
signed in. `DriversAdmin` goes.

**Validated and built, 2026-10-01** (by a subagent, in parallel with M21.3,
.5 and .6). `DriversAdmin` used `POST`, `PUT` and `DELETE
/api/admin/drivers[/{id}]`. Remove is refused `409` for a driver who drove
("…so stays. Rename instead.") and `404` for an unknown one. The public
`/api/drivers` has the same id, name and code, so the page lists from it and
reloads it after each change. Signed in, `DriversPage` shows the add form (the
code follows the name until typed) and Rename / Remove on each driver, with
the refusal shown above the list. Signed out it's today's list, and signing
out mid-edit closes the edit. It follows `$signin` without a reload. No new
helpers were needed (`codeFrom`, `api`). `DriversAdmin.svelte` is deleted.

### M21.5 — The Cars page, a car's management, and its sessions ✅

- **`/cars`** (`CarsPage`, new route): every car from `/api/cars`, its live
  state, and links to Live, Sessions and, signed in, Manage. Signed in:
  **Add a car**, the generated token shown once, as now (or readable, if M9
  is ever built).
- **`/cars/{slug}/manage`** (new route): rename, replace the token, set the
  crew passcode, remove (its slug typed), from `Admin.svelte`'s car panel.
  Signed out, it says to sign in, and shows nothing of the car's.
- **Sessions:** download and delete on `/cars/{slug}/sessions`, signed in.
- Admin-only facts (token hint, passcode set, a session's state for deleting)
  come from `/api/admin/cars` and `/api/admin/cars/{slug}/sessions`, fetched
  only when signed in. A test pins that **no public endpoint's answer changes
  with an admin cookie**. `Admin.svelte` goes.

**Validated and built, 2026-10-01** (by a subagent). Read against
`Admin.svelte` before deleting it:
- **No single-car `GET`**, so Manage finds the car in `GET
  /api/admin/cars`.
- **`POST /cars` returns the token only when the server made it**, as before.
- **Removing a car is refused while it has sessions.**
- **`GET /api/admin/sessions/{id}/download`** existed (and logs who) but the
  admin page never linked it.
- **The public session list carries a state**, but deleting goes by the
  admin list's, which knows from the live hub what's live.

**Built:**
- **`/cars`:** every car with its state, polled every 10 s. Each links to
  Live, Sessions, and (signed in) Manage, plus **Add a car** with the
  show-once banner.
- **`/cars/{slug}/manage`:** state, the token's hint and date, passcode set
  or not, the clock note. Rename, replace token (generated, or chosen and
  typed twice), passcode, and remove (slug typed, then `/cars`; refused with
  a link while sessions remain). It follows `$signin`. Signed out it shows
  nothing of the car's.
- **`SessionsPage`:** signed in, each session has its admin state,
  **Download** (new UI on the existing endpoint) and **Delete** (the old
  rules: `deleteBlocked`, the id's first 8 characters typed), plus a Manage
  link. The admin list is fetched only when signed in.
- **`CarPage`:** "← Cars" goes to `/cars`.
- **`TokenOnce.svelte`** (new, shared by Cars and Manage) is the only place
  a token is ever shown.
- **`PublicWithSignInTest`:** for a car with a chosen token, a passcode and
  a session, `/api/cars`, its sessions, a session, `/api/courses`,
  `/api/events` and `/api/drivers` answer the same status and body with and
  without a real admin cookie, and none holds the token, the passcode,
  `tokenHint` or `passcode`.

### M21.6 — The landing page, expanded ✅

A section per page, each a summary that links to it, with the same look
(decision 29): **cars** (live state, each one's latest session) → `/cars`;
**events** (date, course, cars, "under way") → `/events`; **drivers** →
`/drivers`; **courses** with a small map each (`CourseMap`) and their
layouts → `/courses`. Measured with `measure.mjs` with a replay streaming, since
it's the page most people open. Phone width checked.

**Validated and built, 2026-10-01** (by a subagent; measured by me). It
reads only the public endpoints the other pages use, with their helpers
(`fetchCars`, `dayOf`, `clockOf`, `duration`, `lapTime`, `badge`). An
event's "under way" was nowhere yet: it's new in `lib/landing.ts`, with
`latestSession`, `sessionLine`, `eventsShown` and `layoutsLine`, tested in
`landing.test.ts`. Sections:
- **Cars:** state, and each car's latest session line.
- **Events:** the newest five, "Under way" in mint, "All N events →".
- **Drivers:** name and code.
- **Courses:** a 160 px `CourseMap` of the default layout, and the layouts.
Cars are polled every 10 s, and everything else (each car's latest session
included) once a minute. A course map is fetched again only when its version
changes. A section that fails says so in one line. The small maps don't move
(`pointer-events: none`, the card is the link), and their zoom buttons are
hidden.

**Measured, 2026-10-01:** `measure.mjs` on `/` for 3 minutes, CPU 4× slower,
390×844, with the dev car live (a drive replayed at real time): 60 fps
every minute, no long tasks, heap 2.0 → 2.3 MB, 298 nodes.

**Phone width:** every page (`/`, `/cars`, `/courses`, `/events`,
`/drivers`, a car's sessions and its Manage) at 390 px, by the DevTools
protocol's mobile emulation: the scroll width equals the screen's. The
same-host 390 px frame used before can't work now that no page may be
framed. Chrome's `--window-size` screenshots crop wrongly (the window, not
the viewport), so they aren't a test. The header's links wrapped at 390 px,
so they're tighter there.

### M21.7 — Proven, deployed, recorded

Through the dev server: every former admin task done from its new home,
signed out nothing editable shows, and a public page's raw responses
compared signed in and out. Deployed with a live connection open. Sam signs
in on badnewsbears.live. Decision 25 amended (no admin page; sign-in on
every page), `COMPLETED.md`, `JOURNAL.md`, `PLAN.md`, README, `CLAUDE.md`'s
mentions of `/admin`. Then the plan is deleted.

**Proven locally, 2026-10-01**, through Chrome against the dev server, after
`./gradlew test` (all modules, the site built in) and 204 site tests passed:
- **Header sign-in and sign-out.** Signed out, `/cars` shows only Live and
  Sessions, `/courses` has no New, `/drivers` is the list, and a car's
  Manage shows only "Sign in (top right) to manage this car." Signing out on
  Manage cleared it without a reload, and signing in on `/drivers` turned on
  its add form the same way.
- **Signed in:** a throwaway `smoke-local` car added on `/cars` (the
  show-once banner appeared; its text was never read), then removed from its
  Manage page, which went back to `/cars`. The dev car renamed and named
  back.
- **Editors:** a course's page links Edit to `/courses/nhms/edit`, whose
  editor and map loaded, with back going to the course. `/events/new` loaded
  its editor, with back going to `/events`.
- **Back links:** "← Cars" on Courses, Events and Drivers went to `/`, which
  isn't the cars list any more, so it reads "← Home".
- **Docs:** README, `CLAUDE.md`, and `env.sh`'s and `gcp-setup.sh`'s
  comments no longer mention an admin page.
**Deployed, 2026-10-01**, as revision `00035` (`9bf18d3`), with a live
stream open across it: a throwaway `smoke-m21` car replaying a scratch copy
of the 2026-09-28 morning drive (fresh session id, no VIN), `--live
--no-archive --courses`, under `caffeinate -i`. The old revision closed it
with `1012`; it reconnected at once and took "courses: unchanged". Checked
from outside:
- `/`, `/cars`, `/cars/outback/manage` and `/courses/new` answer `200`, and
  every page sends `X-Frame-Options: DENY` and `frame-ancestors 'none'`.
- `/admin` and `/admin/courses/new` are `404`.
- `/api/admin/me` is `401` with `no-store`, and `/api/cars` is unchanged.
The car and the session its live lane recorded were removed after.
(`curl -I` sends `HEAD`, which these routes don't answer, so headers are
read from a `GET`.)

**Still to do:** Sam signs in on badnewsbears.live, decision 25 amended,
`COMPLETED`, `JOURNAL`, `PLAN`, and the plan deleted.
