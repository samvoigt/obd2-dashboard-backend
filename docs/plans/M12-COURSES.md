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

> **Validated against the code, 2026-09-27, before building.**
> - **The shape** follows `Messages` in `:live`: rules plus a store
>   interface and an in-memory store, in a module whose build is `:live`'s
>   (`explicitApi`, JUnit and Kotest), depending only on kotlinx
>   serialization's JSON (a course is GeoJSON, kept as a `JsonObject` so
>   nothing the editor or the tablet adds is lost in a round trip).
> - **Ids** take the car slug's shape (`Slug` in `:registry`: lower case,
>   letters, digits and hyphens, starting with a letter, 2–32), without its
>   reserved words, since a course only ever sits under `/courses/`. Its own
>   rule here, so `:courses` doesn't depend on `:registry`.
> - **Roles** are exactly the proposal's seven; **an unknown role is
>   refused** on the server (the tablet ignores unknown ones, but the website is
>   the only source, so nothing unknown should ever be saved).
> - **The rules, precisely:** a `FeatureCollection`; ≥ 1 `layout`
>   (`LineString`, ≥ 2 points, a unique `id`, a `name`), exactly one with
>   `default: true` (or the only one); for each layout, exactly one
>   `start_finish` that applies (its own `layout`, or one without `layout`,
>   never both); `sector`s with a `layout` that exists and `index`es 1…n
>   without gaps per layout; at most one each of `pit_lane` (≥ 2 points),
>   `pit_in`, `pit_out`, `pit_line`; every line 2 points, 1–200 m long;
>   every coordinate `[lon, lat]` in range; the whole ≤ 256 KB. A name 1–60
>   characters. Every breach reported, not just the first.
> - **The collection's ETag** is from each course's id and version (sorted),
>   so it moves exactly when a course is saved, renamed or removed.

**Done when:** tests for every rule (and each refusal's reason), the ETag
changing exactly when a course changes, versions counting up.

> **✅ Done, 2026-09-27.** `:courses`: `Course`, `CourseShape` (layouts with
> their start/finish and ordered sectors, the pit lines), `CourseRules`,
> `coursesEtag`, `CourseStore` and `InMemoryCourseStore`.
> - **Tests: 11**, every rule and its reason, several reported at once.
> - **Mutations: 9, all killed**: an unknown role allowed, defaults
>   unchecked, sectors in the order drawn, lines to 2 km, a layout with both
>   its own and the shared start/finish, no size limit, coordinates
>   unchecked, the ETag ignoring versions, a save over someone else's.
> - **Found by the tests:** my own one-letter layout ids in them, which the
>   2–32 rule rightly refused.

### M12.2 — NHMS, seeded

`courses/nhms.geojson` in this repo, made from the app's file by a script kept
here (it reads the app's file, writes only here): `id`s added,
the `pit_line` placed, the attribution kept. `admin.sh import-course <file>`
validates it and stores it as a new version.

> **Validated against the code, 2026-09-27, before building.**
> - **The app's pit line** (read in its `core/laps`, `Venues.kt`, `pitGate`):
>   the point on the pit lane nearest the middle of the start/finish, and a
>   line square to the lane there, 8 m each side (16 m, "a pit lane's width
>   and a GPS error, short of a track beside it"), in a local flat frame. The
>   seed script does the same, so NHMS times as the tablet does today.
> - **The app's file:** layouts named but without `id`s ("Road Course" is the
>   default), one `start_finish` for every layout (24 m, marked a guess, with
>   a note: kept, since unknown properties are kept), the `pit_lane`; the
>   attribution at the top. `id`s from the names: `road`, `road-option`,
>   `road-option-2`.
> - **Order:** storing it needs the Firestore store, so `admin.sh
>   import-course` moves to M12.3; this step makes the file, and a test that it
>   passes the rules.

**Done when:** the seed passes `:courses`' rules; the script is repeatable;
the `pit_line` is looked at on a map against the imagery.

> **✅ Done, 2026-09-27.** `courses/seed/make_nhms.py` reads the app's file
> and writes `courses/seed/nhms.geojson`: layouts `road` (the default),
> `road-option`, `road-option-2`; the start/finish and its "guess" note kept;
> a `pit_line` of 16 m, 22 m from the start/finish's middle.
> - **Tests: 2**: the seed passes the rules, its start/finish crosses every
>   layout, its pit line crosses the pit lane and no layout.
> - **Repeatable:** run twice, the same file.
> - **Looked at** over the USGS imagery: the start/finish across the front
>   straight by the grandstand, the pit line beside it across the pit lane,
>   short of the track.
> - **Mutations: 3, all killed** (a 40 m pit line, one along the lane, ids
>   not shortened), **but only after a fix**: Gradle didn't know the test
>   reads the seed, so a changed seed left the test "up to date" and every
>   mutant survived. The seed directory is now the test task's input.

### M12.3 — Stored, and the admin API

`FirestoreCourseStore` in `:archive-gcp` (a course document, its versions
beside it); wired in `main()`; admin routes: list, get (any version), save (a
new version), rename, delete (refused while a lap names the course), all with
the same sign-in, origin check and change log as cars.

Also `admin.sh import-course <file>` (from M12.2): validates a GeoJSON file
and stores it as the next version.

> **Validated against the code, 2026-09-27, before building.**
> - **Firestore:** `courses/{id}` holds the latest version's number, name and
>   time; `courses/{id}/versions/{n}` each version. **The GeoJSON is stored
>   as its JSON text**, since Firestore can't hold arrays inside arrays, which
>   GeoJSON coordinates are. **A save is a transaction**: read the course,
>   check its version is the one expected, write the course and the new
>   version together, as `FirestoreMessageStore.update` does. Delete removes
>   the versions in batches, then the course.
> - **A mapping test** (as `MessageMappingTest`) and **a smoke test** against
>   the real Firestore (as `MessageSmoke`, run by `scripts/course-smoke.sh`,
>   never by `test`), a throwaway course removed after.
> - **The admin API** uses `call.admin(auth, config, change)` (sign-in, and
>   the same-origin check on a change) and `adminLog` for every change:
>   `GET /api/admin/courses`, `GET …/{id}` (`?version=`), `GET
>   …/{id}/versions`, `PUT …/{id}` (`expected`, `name`, `geojson`: 400 with
>   every problem, 409 if someone saved first), `DELETE …/{id}`.
> - **"Refused while a lap names it":** today's tablet laps name their track
>   (`track: "nhms"`), and a session's summary keeps its most-used track
>   (`SessionSummary.track`). A course is refused deletion while any session's
>   summary names it. (M12.7 adds `course` to the same place.)
> - **`admin.sh import-course <file> [--id] [--name]`** in `:tools`: reads
>   the file, applies `CourseRules`, saves over the current version; the id
>   defaults to the file's name, the name to the GeoJSON's.

**Done when:** route tests (signed out, wrong origin, invalid course, a save
making version N+1, delete refused when named); a Firestore smoke test with a
throwaway course, removed after.

> **✅ Done, 2026-09-27.** `FirestoreCourseStore` (versions under each
> course, the GeoJSON as text, a save a transaction); the admin courses API
> (`CourseRoutes.kt`); the module takes a `CourseStore` with no default (as
> messages), `main()` the Firestore one, the dev server an in-memory one
> seeded with NHMS; `admin.sh import-course`.
> - **Tests:** 2 mapping (a version round-trips exactly; nothing stored as
>   arrays), 4 routes (signed out and cross-origin change nothing; versions
>   count up, a stale save 409, history newest first, layouts listed; an
>   invalid course 400 with every problem; a course in use kept, an unused one
>   deleted), 2 for `import-course`. The module's 23 test call sites pass a
>   store.
> - **The real Firestore** (`scripts/course-smoke.sh`): saved, read back
>   exactly, a stale save refused, version 2, version 1 kept, both listed,
>   deleted with its versions. PASSED.
> - **Mutations: 6, all killed**: an invalid course saved, a course in use
>   deleted, no origin check on a save, history oldest first, import ignoring
>   problems, import always over version 0.

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
