# M12 — Courses, and down to the tablet

The first milestone of race logging (`RACE-LOGGING.md`): **courses drawn on
the website**, with layouts, a start/finish, sector lines and pit lines,
**versioned**, and **sent down to the tablet**, which times laps on them. The
server shows the tablet's laps with their sectors. Re-timing is M13; drivers
and events M14.

---

## Where the contract stands (2026-09-27)

The change is proposed (`docs/proposals/COURSES-AND-TIMING-TO-THE-TABLET.md`,
revision 2) and the tablet side hasn't answered revision 2 yet. **M12 builds
only what both sides already agree on** (the tablet's §22.3 and §22.4 accepted
them as proposed):
- `GET /v1/courses` with one `ETag` and `304`, any car's token;
- the `courses` frame after `hello` and on change, to a tablet listing
  `courses.1` in `hello.features`;
- the GeoJSON roles `layout` (with `id`), `start_finish`, `sector`,
  `pit_lane`, `pit_in`, `pit_out`, each line counted only in its layout's
  direction;
- a `lap` record's `course`, `courseVersion`, `layout` as an `id`, and
  `sectors`.

**The one open point M12 touches** is revision 2's `pit_line`: a new role,
which a tablet that doesn't know it ignores (§2.2 of the proposal: unknown
roles are ignored). M12 lets it be drawn and serves it; nothing breaks if the
tablet side changes it. **Anything the tablet side changes in answer** is
folded in before M12's deploy step.

---

## What exists, and what it means (checked 2026-09-27)

- **The pattern for a new kind of thing**: a pure module holds its rules (crew
  messages: `Messages` in `:live`, with a store interface and an in-memory
  store for tests); the Firestore store sits in `:archive-gcp`
  (`FirestoreMessageStore`); `Application.module` wires it; `main()` connects
  the real one.
- **The admin API** (`AdminRoutes.kt`): Google sign-in, an allowlist, a
  same-origin check on every change, and a log line for every change, never
  a secret (decision 25). Courses join it.
- **Tablet routes** sit in `authenticate(CAR_AUTH)` (`/v1/whoami`, the archive
  lane): `GET /v1/courses` goes there.
- **The live lane** answers `hello` with `welcome` and `messages`
  (`LiveRoutes`); `TabletFrames.parse` reads `hello`'s `v`, `device`, `app`,
  `wall`. It gains `features`, and the reply gains `courses` for a tablet that
  lists `courses.1`. A course saved while tablets are connected sends each of
  them `courses` (the hub knows who's connected).
- **The app's NHMS file** (`nhms.geojson`, read-only): three layouts named
  "Road Course", "Road Course Option", "Road Course Option 2" (no `id`s), one
  `start_finish` for all layouts marked as a guess, and the `pit_lane`; no
  sectors, no `pit_in`/`pit_out`. OpenStreetMap-derived, ODbL, with its
  attribution in the file.
- **Leaflet 1.9.4** draws our maps. **Drawing** needs a plugin:
  **Leaflet-Geoman (free edition), MIT**, for Leaflet 1.2+: polylines with
  draggable vertices, which is all a course is.
- **Imagery:** OpenStreetMap as today, and **USGS The National Map's
  orthoimagery**, US public domain, tiles to zoom 16 at NHMS (2.4 m a pixel:
  the grandstands, the pit lane and parked cars visible), enlarged beyond
  that. No key, no terms beyond attribution. US only, which our racing is.
- **The tablet's laps today** (`LapInfo` in `SessionReader`, version 2 of the
  summary) keep `track`, `layout` (a name), lap, time, pit flags and `wall`;
  the session page shows them in a table.

---

## Decided here (say if any is wrong)

- **Only the admin edits courses** (Google sign-in), as cars and tokens.
  Everyone sees them: a public page per course.
- **A course's `id`** is a slug, chosen once (like a car's): `nhms`. Layout
  `id`s too, within a course: NHMS's become `road`, `road-option`,
  `road-option-2`.
- **Every save is a new version**, numbered from 1; old versions are kept
  (M13 re-times against them) and can be viewed, never edited. **A course is
  never deleted while any lap names it**; until then, deletion is allowed.
- **A course is valid** when: at least one layout, `id`s unique, one layout the
  default; a `start_finish` for every layout (its own, or one without `layout`
  for all); sector `index`es 1, 2, … without gaps per layout; every line
  exactly two points, at most 200 m long, every coordinate a real
  longitude/latitude; the whole under 256 KB. The editor says what's wrong
  before saving; the server refuses anything invalid.
- **NHMS is seeded from the app's file**, given `id`s, and its `pit_line`
  placed where the tablet draws one today: across the pit lane, level with the
  start/finish. Imported by `admin.sh import-course`, as version 1. Its
  start/finish stays marked a guess until someone checks it at the track.
- **The editor can show a session's route** under the map, faintly, to trace a
  course from a real drive (the loop near home).

---

## The steps

Each validated against the code just before it's built, the validation
written here.

### M12.1 — The course, as rules (`:courses`)

A pure module: `Course` (id, name, version, the GeoJSON), parsing and
validating the GeoJSON by the rules above, layout `id`s, the default layout,
the lines of a layout (its start/finish, its sectors in order, its pit lines),
the collection's `ETag` (from every course's id and version); `CourseStore`
(get, list, the current version of each, a new version, delete) and an
in-memory store.

**Done when:** tests for every rule (and each refusal's reason), the ETag
changing exactly when a course changes, versions counting up.

### M12.2 — NHMS, seeded

`courses/nhms.geojson` in this repo, made from the app's file by a script kept
here (it reads the app's file, writes only here): `id`s added,
the `pit_line` placed, the attribution kept. `admin.sh import-course <file>`
validates it and stores it as a new version.

**Done when:** the seed passes `:courses`' rules; the script is repeatable;
the `pit_line` is looked at on a map against the imagery.

### M12.3 — Stored, and the admin API

`FirestoreCourseStore` in `:archive-gcp` (a course document, its versions
beside it); wired in `main()`; admin routes: list, get (any version), save (a
new version), rename, delete (refused while a lap names the course), all with
the same sign-in, origin check and change log as cars.

**Done when:** route tests (signed out, wrong origin, invalid course, a save
making version N+1, delete refused when named); a Firestore smoke test with a
throwaway course, removed after.

### M12.4 — The editor

`/admin/courses`: the list; a course's page with the map (OpenStreetMap or
USGS imagery), where you draw a **layout** (click points; drag, add or remove
vertices; its direction shown with arrows), and **lines** (two clicks) as the
start/finish, sectors (numbered in order), `pit_in`, `pit_out`, `pit_line`,
and the pit lane; the rules checked as you go; **Save** makes a new version;
earlier versions viewable. "Show a session's route" draws one faintly.

**Done when:** looked at in Chrome against the dev server: drawing a course
from nothing around a replayed route, NHMS opened and a sector added, a line
dragged and saved as version 2, an invalid course refused with its reason,
phone width for viewing (drawing is for a desktop).

### M12.5 — Courses on the public site

`/courses` and `/courses/{id}`: each course on its map, its layouts, lines and
sectors, and its version history. A session whose laps name a course links to
it.

**Done when:** looked at in Chrome; tests for the public API (no secrets in
it, which it has none of, and every version readable).

### M12.6 — Down to the tablet

`GET /v1/courses` (car token; `ETag` and `304`); `hello.features` read; the
`courses` frame after `hello` to a tablet listing `courses.1`, and to every
connected one when a course is saved.

**Done when:** route tests (no token, a token, `304`, the ETag moving on a
save); live tests (a tablet with and without `courses.1` after `hello`, and
after a save); **the replay tool** speaking `courses.1` and fetching courses,
as a stand-in tablet.

### M12.7 — The tablet's laps, with their sectors

`SessionReader` reads a `lap`'s `course`, `courseVersion`, `layout` (an `id`
now, a name in older records) and `sectors` (and `startAt`/`endAt` if the
contract settles them); the summary's version goes to 3, so older sessions
rebuild; the session page's lap table gains a column per sector (the best of
each marked) and the course's name, linked.

**Done when:** tests for old and new lap records side by side; a replay whose
laps carry the new fields (made synthetic, as the tablet will send them), seen
in Chrome.

### M12.8 — Deploy, and prove it

Deployed with a drive streaming; NHMS imported; Sam draws the loop near home
on the website (his sign-in) over the first drive's route; `/v1/courses`
fetched with a throwaway car's token (compared, never printed); the courses
pages looked at on badnewsbears.live. **The tablet timing on it** comes when
the app's half lands, and is proven then (recorded as owed, like the app's
own A-rows).

### M12.9 — Record it

Decisions (courses on the website, versions, validity, imagery); `COMPLETED`,
`JOURNAL`, `PLAN`, `PROTOCOL` (what this side built of the proposal), README,
`CLAUDE.md`; this plan deleted.

---

## Not in M12

- Re-timing (M13), drivers and events (M14 on).
- The `timing` frame (M17).
- Editing courses on a phone (viewing works; drawing wants a mouse).
