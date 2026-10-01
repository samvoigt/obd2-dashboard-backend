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

### M21.1 — The header, and who's signed in (not validated)

A site header on every page (`App.svelte`): the logo's home link, and **Sign
in** or the signed-in email with **Sign out**. A small store, `lib/signin.ts`,
asks `/api/admin/me` once and is updated on sign-in and sign-out. `whoCanSet`
reads it instead of asking again. Google's button opens on demand. Tests:
the store's states, and that a `401` from any admin call signs the page out.

### M21.2 — The server: every page unframed, the admin API not stored (not validated)

`frame-ancestors 'none'` and `X-Frame-Options: DENY` on the page shell
everywhere. `Cache-Control: no-store` on `/api/admin/*`. The `403` text.
The new page paths `/cars`, `/cars/{slug}/manage`, `/courses/new`,
`/courses/{id}/edit`, `/events/new` and `/events/{id}/edit` served (from
`WebRoutes.kt`'s list). The `/admin…` page paths removed, so they're `404`.
Tests in `:server`, next to today's `/admin` header test, which becomes
"every page".

### M21.3 — Courses and events edited where they're shown (not validated)

The routes `/courses/new`, `/courses/{id}/edit`, `/events/new` and
`/events/{id}/edit` (`routes.ts`, tests). `CourseEditor` and `EventEditor`
move there unchanged. "New …" and "Edit" buttons show when signed in. The
"Sign in on the admin page first" messages become the header's sign-in.
`CoursesAdmin` and `EventsAdmin` go.

### M21.4 — Drivers edited on `/drivers` (not validated)

`DriversAdmin`'s add, rename, code and remove, inline on `DriversPage` when
signed in. `DriversAdmin` goes.

### M21.5 — The Cars page, a car's management, and its sessions (not validated)

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

### M21.6 — The landing page, expanded (not validated)

A section per page, each a summary that links to it, with the same look
(decision 29): **cars** (live state, each one's latest session) → `/cars`;
**events** (date, course, cars, "under way") → `/events`; **drivers** →
`/drivers`; **courses** with a small map each (`CourseMap`) and their
layouts → `/courses`. Measured with `measure.mjs` with a replay streaming, since
it's the page most people open. Phone width checked.

### M21.7 — Proven, deployed, recorded (not validated)

Through the dev server: every former admin task done from its new home,
signed out nothing editable shows, and a public page's raw responses
compared signed in and out. Deployed with a live connection open. Sam signs
in on badnewsbears.live. Decision 25 amended (no admin page; sign-in on
every page), `COMPLETED.md`, `JOURNAL.md`, `PLAN.md`, README, `CLAUDE.md`'s
mentions of `/admin`. Then the plan is deleted.
