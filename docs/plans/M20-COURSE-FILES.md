# M20 — Courses from a file, and back to one

A course can be opened from a `.geojson` file on the admin page, looked over
and fixed in the editor, and saved as usual. It can also be downloaded as a
file. **Sam, 2026-10-01:** "make a plan for the uploader. we'll test it out
with palmer". Palmer Motorsports Park is the test. **Claude doesn't make
Palmer's file or add the course**: Sam brings the file and uploads it (M20.6).

Today a file gets in only through `scripts/admin.sh import-course`. That
saves at once, so nothing is reviewed on a map first, and it only takes our
own format.

---

## What exists, and what it means for this (checked 2026-10-01)

- **The editor already reads and writes our format.**
  `fromGeoJSON`/`toGeoJSON` in `web/src/lib/courseEdit.ts` turn a course's
  GeoJSON into the editor's model and back. They keep what the editor doesn't
  edit (the collection's `attribution`, a layout's `osm`, a line's note). The
  editor loads a saved course this way (`CourseEditor.svelte`, `show`). So
  opening a file in our format is mostly `fromGeoJSON` on the file's text.
- **`fromGeoJSON` drops what it can't read, silently**: a feature with no
  role or an unknown one, a sector without a layout or index, a "line" that
  isn't 2 points. That's right for a stored course, which the server has
  already checked. For a file it isn't: the editor must **say what it left
  out**.
- **The rules stay the server's, with no copy in the page** (decision 32).
  The editor posts the drawing to `POST /api/admin/courses/check` 400 ms after
  each change, and Save (`PUT /api/admin/courses/{id}`) applies the same
  `CourseRules`: roles, closed layouts, one default, sectors 1…n, lines
  1–200 m, ≤ 256 KB, the name and the id. A file opened into the editor gets
  all of this for free. **No server change is needed**, and nothing reaches a
  tablet until Save, as now.
- **A new course's id follows its name** (`idFrom`) until it's typed. An
  existing course keeps its id; Save is "Save as version n+1", with
  `expected` guarding a save that lands at the same moment as another.
- **There's no way to reverse a line.** Layouts and the pit lane are drawn
  "in the direction cars go", and the arrows (`arrows`) show it. An
  OpenStreetMap way's direction is whatever its mapper drew, so plain lines
  need a **Reverse**.
- **OpenStreetMap circuits are often several ways** meeting end to end, and
  usually aren't closed by the last point repeating the first. The editor
  already has `closed()` (repeat the first point) and `keepClosed()`.
- **Removing a layout an event uses isn't guarded on a course's save**: only
  an event's own save checks its layout exists (`EventRoutes.kt`, the
  "has no layout" check). The editor's **Remove** already allows it.
  Replacing a course from a file makes it easier to do by accident, so the
  editor warns (M20.3).
- **An older version is read-only** in the editor (`viewing`). Opening a
  file there makes no sense, but downloading it does.
- **The admin page's CSP** is `frame-ancestors 'none'` only
  (`WebRoutes.kt`), so a download through a `blob:` link isn't blocked.
  Validate in M20.4 anyway.
- **Tests:** the site's logic is tested with Vitest in `web/src/lib/*.test.ts`
  (`courseEdit.test.ts` exists). There's no browser test suite; the editor is
  proven by hand against `./gradlew :server:devServer`.

---

## Settled with Sam, 2026-10-01

1. **A file opens into the editor**, never saved straight away. It's drawn,
   checked by the server's rules as you go, and saved only by pressing Save.
2. **On new and existing courses.** On an existing course the file replaces
   the drawing, and Save makes it the next version. Old versions, and the laps
   timed on them, are untouched (decision 32).
3. **Our format, and plain lines too**: a file of bare LineStrings with no
   roles (an OpenStreetMap export, say) comes in as lines for you to label.
4. **Download** as well, in the editor.

## Decided here (say if any is wrong)

- **Plain lines are "unassigned", not guessed at.** Every line without a
  role comes in as an unassigned line, drawn in a neutral colour and listed
  in the editor. Each one can be **Make layout** (named, then closed),
  **Make pit lane**, **Reverse** or **Remove**. Unassigned lines are never
  saved, and the editor says how many are left. *Not chosen:* guessing
  layout or pit lane from shape or tags. OSM's tagging of pit lanes isn't
  consistent, and a wrong guess would be harder to spot than an honest list.
- **Ways that meet end to end are joined** on the way in (ends within 5 m,
  reversing a piece if needed), so a circuit mapped as six ways arrives as one
  line. They're joined only where exactly two ends meet. Where three or more
  meet (a junction between layouts), they're left apart for you.
- **What else a file may hold:** a `FeatureCollection`, a single `Feature`
  or a bare geometry. `MultiLineString` is split into its lines. A third
  coordinate (height) is dropped. Points, polygons and anything else are left
  out and **listed** ("left out: 3 points, 1 polygon"). Not GeoJSON at all,
  or over 10 MB before reading (Sam, 2026-10-01: an export drawn around a whole park can run to a few MB, most of it left out on the way in), is refused with a message, and the drawing is
  untouched.
- **Where it came from is kept.** A plain line's OSM id (`@id` or `id`
  like `way/123`, as Overpass Turbo exports) is kept as `osm` on the layout or
  pit lane it becomes, as NHMS's layouts have. A file with OSM ids and no
  `attribution` gets the ODbL attribution NHMS uses, which you can see before
  saving. A file's own `attribution` is kept as it is.
- **The name:** on a new course, the file's top-level `name` (as
  `import-course` reads it) fills the name, and the id follows. On an existing
  course the name stays the course's own.
- **Opening over unsaved changes asks first**, as switching versions does
  (`confirm('Leave your unsaved changes?')`).
- **Download is what's on the screen**: `toGeoJSON` of the drawing, named
  `{id}-v{version}.geojson` for a saved version (the latest or an older one
  being viewed), or `{id}-draft.geojson` with unsaved changes. It's done in
  the page; the server isn't asked. *Not chosen:* a download on the public
  `/courses` page. Courses are public there, but nobody asked for it.
- **Nothing changes for `admin.sh import-course`**: it still takes our
  format only, and saves at once.

---

## The steps

### M20.1 — Reading a file ✅

A pure function in `web/src/lib/courseFile.ts`:
`readCourseFile(text) → { course, name, unassigned, leftOut } | { refused }`.
- Our roles go through `fromGeoJSON`'s reading, unchanged. What it would drop
  is counted into `leftOut` with a reason ("a sector without an index", "a
  start/finish that isn't 2 points").
- Lines without a role become `unassigned` (`{ path, extra }`), joined end to
  end as above. Their properties are kept in `extra`, their OSM id as `osm`.
- The attribution rule, the 10 MB limit, the other shapes and heights as
  above.

**Validated, 2026-10-01.** `fromGeoJSON` read layouts and the pit lane
without checking their geometry, which is fine for a stored course but not a
file. **Done:** `fromGeoJSON` is now `readCourse(geojson, leftOut)`, which
refuses a role feature that isn't a `LineString` and says why for every
drop. `fromGeoJSON` calls it and ignores the reasons, so stored courses read
as before. `courseFile.ts` has `readCourseFile`, `join`, `contents` and
`length`. **Changed from the sketch:**
- A plain line keeps only its OSM id (`osm`) and its `name`, offered as the
  layout's name. Not all its tags: an Overpass export carries every OSM tag,
  which would be saved into the course for nothing.
- Joined pieces' ids are kept as one `osm`, `"way/1, way/2"`.
- A loop of pieces starts where the file's first piece of it starts.
- An end's partner is mutual (each the only other end near the other), so a
  chain can't fork.
- Unassigned lines are sorted longest first.
- Repeated reasons are counted: "2 × point".
Tests: `courseFile.test.ts`, 9 for M20.1.

Tests in `courseFile.test.ts`:
- `courses/seed/nhms.geojson` reads with nothing left out and nothing
  unassigned, and `toGeoJSON` of it equals the file.
- A circuit as three ways, one of them backwards, joins into one line. A
  junction of three ends doesn't join.
- Points, a polygon, a MultiLineString, heights, a bare `Feature`, a bare
  geometry, not-JSON, an empty collection, an over-size file.

### M20.2 — Unassigned lines in the model ✅

`EditCourse` gains `unassigned: { path: Pt[]; extra }[]`, which `toGeoJSON`
never writes. New pure functions in `courseEdit.ts`:
- `assignLayout(course, i, name)`: a layout with an id from `idFrom(name,
  taken)`, closed with `closed()`, default if it's the first;
- `assignPitLane(course, i)`: replacing any pit lane;
- `removeUnassigned(course, i)`;
- `reverse`, for an unassigned line, a layout or the pit lane. Reversing a
  layout keeps it closed and leaves its start/finish and sectors where they
  are. Drawn courses get it too.

Tests for each, and that `toGeoJSON` never includes an unassigned line.

**Validated, 2026-10-01.** The server wants a layout closed exactly (last
point equal to the first, `CourseRules`). `closed()` adds exactly the first
point, and leaves an already closed path alone, so a line whose ends are
apart becomes a layout closed by a straight line between them. The editor
shows that gap (M20.3). **Done** as sketched, with `reverse` taking
`{ unassigned: i }`, `{ layout: id }` or `'pit_lane'`. Tests: 4 in
`courseFile.test.ts`.

### M20.3 — Open file in the editor ✅

In `CourseEditor.svelte`:
- **Open file…**, a file input taking `.geojson,.json`, beside Save. A file
  dropped on the map does the same. It's hidden while viewing an older
  version.
- Unsaved changes are asked about first. Then the drawing is replaced by
  what's read (`dirty = true`), the default layout is selected, and the map
  fits it (`fit()`). On a new course the name is filled from the file.
- **A "From the file" panel** says what was read ("2 layouts, a
  start/finish, 4 sectors, a pit lane"), what was left out and why, and lists
  the unassigned lines with their actions. Hovering one highlights it on the
  map. Unassigned lines are drawn dashed in a neutral role colour
  (`app.css`, decision 29).
- **Layouts the current version has and the file doesn't** are named in a
  warning: "Events at this course use layouts by id. Save only if that's
  meant."
- Save stays as now: disabled while the rules find problems. It also says
  "N unassigned lines won't be saved" while any are left.

**Validated, 2026-10-01.** The editor redraws everything from the model on
each change (one `$effect`), so unassigned lines are drawn there. The check
`$effect` posts `toGeoJSON(course)`, which never holds them. **Done**, with
these differences:
- Unassigned lines are drawn dashed in `caution`, with direction arrows (to
  decide on Reverse), thicker when their row is hovered. `caution` rather than
  a neutral colour: they're work still to do, and `muted` is the pit lane's.
- Each row shows its length, and whether it's closed or how far apart its
  ends are. **Remove all** clears a messy export at once.
- **Reverse** is on every layout and the pit lane too, not just lines from a
  file.
- The warning about layouts gone compares against the latest saved version
  (`savedLayouts`) and shows whenever one is missing, including after
  **Remove**. It's the same risk.
- `fit()` also takes in the pit lane and unassigned lines, so a file of
  plain lines is framed.
- An over-size file is refused by its size before it's read.

### M20.4 — Download ✅

**Download** in the editor, beside Open file, as decided above. A round trip
(download, then open the file into a new course) gives the same drawing. That
is tested at the function level in M20.1, and tried once by hand here.

**Done:** a `Blob` link in the page, written with one-space indents as the
NHMS seed is. It's disabled on a new course with nothing drawn. Tried in
M20.5.

### M20.5 — Proven locally ✅

Against `./gradlew :server:devServer`, through the browser (the dev sign-in):
- open `courses/seed/nhms.geojson` into a new course and save it. The
  versions list, `/courses` and `GET /v1/courses` show it;
- open a plain-lines file (a made-up loop in three ways, one backwards, a
  pit lane way, a point and a building; written for the check, not kept,
  since `courseFile.test.ts` covers the same). Join, Make layout, Make
  pit lane, Reverse, draw a start/finish, then save;
- open a file over an existing course: the warning for a layout gone, then
  Save as the next version;
- open a broken file: refused, and the drawing untouched;
- download, and open the download again.

`(cd web && npm test && npm run check)` and `./gradlew test` pass.

**Done, 2026-10-01**, through Chrome against the dev server:
- NHMS's seed opened into a new course with nothing left out. The name
  filled in and the id followed it. Saved as version 1, and listed on
  `/api/courses`.
- The plain-lines file opened over that course. Its three ways joined into
  one closed 797 m line, with "Loop" offered from the way's `name`; the point
  and the building were listed as left out. Make layout and Make pit lane
  worked. The warning named the three NHMS layouts gone, and the server's
  rules asked for a start/finish. Reverse turned the arrows round, a
  start/finish was drawn, and it saved as version 2. `GET /v1/courses` (the
  dev car's token, read into a variable, never printed) sent the tablet
  layout, start/finish and pit lane.
- The broken file was refused ("The file isn't JSON") with the drawing and
  version untouched.
- Viewing version 1 hid Open file. Download named the file
  `…-v1.geojson`, with its three layouts.
- Round trip: that download, dropped on a new course's map (a `drop` event
  carrying the file), read back and downloaded identically.

**Found and fixed here:**
- A layout's **Reverse** in its row squeezed the names to three lines. It
  moved beside "Move the points of …" as **Reverse it**, for the chosen
  layout.
- A file opened over a course kept its own `name` in the GeoJSON ("Made-up
  loop") under a course called something else. Download now writes the
  course's name, which is what Open file and `import-course` read.

Downloads were checked by catching the link in the page and reading its
blob, so nothing was saved to disk.

### M20.6 — Deployed, and Palmer by Sam

Deploy (a live connection open, as always). **Sam** opens Palmer's file on
`/admin/courses/new`, from wherever he gets it (an Overpass Turbo export of
OpenStreetMap is the likely path, plain lines). He labels and places the
lines, and saves. What it took, and what got in the way, goes in `JOURNAL.md`.
Anything the file showed the editor needs is fixed here, before the milestone
closes.

**Deployed, 2026-10-01**, as revision `00034` (`cb5f292`), with a live
stream open across it: a throwaway `smoke-m20` car replaying the 2026-09-28
morning drive at real time, `--live --no-archive --courses`, under
`caffeinate -i`. The old revision closed it with `1012`; it reconnected at
once and took "courses: unchanged" from the new one. The deployed
`CourseEditor` chunk carries Open file. The car, and the session the live
lane recorded for it even with `--no-archive`, were removed after.
**Found:** a replay of a log already archived for another car is refused
frame by frame (`bad_message`, "a batch for a session not announced": its
id belongs to the Outback, `Announce.WrongCar`). The smoke run streamed a
scratch copy with a fresh session id and no VIN. **Waiting on:** Sam's
Palmer file.

### M20.7 — Recorded

A decision (courses from files: into the editor, plain lines unassigned,
download in the page), `COMPLETED.md`, `JOURNAL.md`, `PLAN.md`, the
README's admin section. Then the plan is deleted.
