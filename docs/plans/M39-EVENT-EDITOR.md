# M39 — The event editor: see which sessions each part catches before saving

Phase 7 of the UX review (`docs/plans/UX-REVIEW.md` §3.G and §4, "M39"). The
event editor is one page of fields, then a list of parts as rows of
date-time inputs, then (only once saved) the sessions each part caught. The
thing people get wrong is the windows model: a session lands in the wrong
part, or in none, and you only find out after saving. **Sam, 2026-10-22,**
named the admin editors as one of the three places people get lost, and
kept this phase last (§6.5 of the review): phones first, editors after.

> **Planned 2026-10-22**, checked against the code. Built after M33, which
> places a merged session as one unit (`EventRules.sessionsIn(event,
> sessions, merges)`, M33.4).

---

## What exists, and what it means for this (checked 2026-10-22)

- **The page is `web/src/EventEditor.svelte`** (275 lines). Sections, in
  order: the event (name, id for a new one, date, last day, course, layout,
  cars), **Parts** (each a name input, its kind as text, two
  `datetime-local` inputs, Remove), the save row, then **Sessions**, shown
  only when `page` is set, i.e. after the event is saved (`{#if page}`).
- **The draft:** `draft = { name, date, endDate, course, layout, cars, parts }`;
  `changed` compares it with `saved` as JSON. While `changed`, the Sessions
  section warns "Save your changes first" and disables Take out / Add to.
- **Moving a session saves at once:** `move(next)` sets `parts` and calls
  `save()`, so nothing else may be unsaved.
- **A part's kind can't change** after it's added (shown as a `<span>`);
  `newPart(parts, kind, date)` (`lib/events.ts:89`) names and times a new one
  (a practice 1 h after the last part ends, a race 6 h, a second race session
  9:00 the next day).
- **Hand placement:** `addSession` and `removeSession` (`lib/events.ts:111,
  120`) edit a part's `added` and `removed` lists. Tested in
  `lib/events.test.ts` ("an event in the editor (M14.3)").
- **The server's placement:** `GET /api/admin/events/{id}`
  (`EventRoutes.kt`, in `adminEventRoutes`) needs `may(EDIT, event)`. It reads
  the cars' sessions (`archive.sessionsOfCars`), places them with
  `EventRules.sessionsIn(event, heard)`, and offers as `others` the cars'
  unplaced, non-fake sessions heard from a day before the event's date to
  the day after its last day. It answers `AdminEvent(event, sessions by part
  id, others)` with `SessionBrief`s. Tested in `EventRoutesTest`, "an
  event's page lists each part's sessions, and the others around its day to
  add by hand".
- **`EventRules.sessionsIn`** (`events/.../EventRules.kt:79`) is pure: a
  session added by hand goes to that part; otherwise to the part whose
  window overlaps its heard time most (later part on a tie), unless removed
  there. It needs only the event's cars and parts, and each session's car,
  heard times and source. After M33.4 it also takes the cars' merges: a
  merged session is placed whole, by its span, and a member named in
  `added` or `removed` moves them all; it still answers member ids.
- **Saving** is `PUT /api/admin/events/{id}` with `SaveEvent`; `toEvent` gives
  new parts ids `p<n>` in order (`nextPartId`), and `eventProblems` refuses a
  part that doesn't end after it starts, or runs over 30 hours.

**What it means:** the server already does exactly the placement the review
wants previewed. It only does it from the *saved* event. Running the same
code on the *draft* needs one pure function pulled out of the GET route and
one new read-only route. New parts have no id until saved, so the preview
must answer by the parts' position in the request, not by id.

## Decided here

- **A preview route that never writes:** `POST /api/admin/events/{id}/placement`
  with the same body as a save (`SaveEvent`), answering where every session
  would go. POST because it carries a body; `call.may` then checks the
  request comes from our own site, as for any change. Permission as for a
  save: `CREATE` for an id with no event yet, `EDIT` for an existing one.
- **The answer is by position:** `{ parts: [[SessionBrief…], …], others: [...] }`,
  `parts[i]` being the sessions the request's `i`th part would catch. A part
  that can't be placed (it doesn't end after it starts) gets an empty list,
  and the page shows the problem beside it.
- **The page asks as you edit:** 400 ms after the last change, as the course
  editor asks `/courses/check` (`CourseEditor.svelte`, the `checkTimer`
  effect).
- **Take out / Add to work on the draft**, before saving, and no longer save
  at once: they change `parts`, the preview updates, and Save commits it all.
- **Three sections:** *The event*, *The schedule*, *The sessions*, each
  with one plain sentence saying what it's for. Parts are listed in time
  order, grouped by local day.
- **The server's rules and the results don't change.** Nothing about how a
  saved event places sessions changes; the page only learns it sooner.

## The steps

### M39.1 — Placement as one function (server, tested)

Move the body of `GET /api/admin/events/{id}` after its permission check into
`fun placement(event: Event, sessions: List<SessionRecord>, merges:
List<MergedSession>): AdminPlacement` in `EventRoutes.kt`, where
`AdminPlacement(byPart: Map<String, List<SessionBrief>>, others:
List<SessionBrief>)`. It passes the merges to `sessionsIn` (as M33.4 already
made this route do) and folds a merged session's members into one
`SessionBrief` (the merge's id and name, first heard to last heard, and
`members: List<String>`, a new optional field), in placed lists and
`others` alike. The GET route calls it and answers `AdminEvent(event.view(),
byPart, others)` as before.

**Check:** `EventRoutesTest`'s "an event's page lists each part's sessions…"
passes unchanged; a new case: two sessions merged list as one brief with
both members.

### M39.2 — `POST /api/admin/events/{id}/placement` (server, tested)

In `adminEventRoutes`:
1. `current = events.get(id)`; `call.may(if (current == null) CREATE else
   EDIT, …)`, exactly as the PUT.
2. `request = call.receive<SaveEvent>()`; build the draft with the same
   `toEvent(id, current)` the PUT uses, so new parts get the ids they'd get.
3. Keep only cars the registry has (unknown ones would place nothing anyway,
   and must not be looked up).
4. `placement(draft, archive.sessionsOfCars(cars), the cars' merges)`; answer
   `{ parts: draft.parts.map { byPart[it.id].orEmpty() }, others }`, in the
   request's order.
5. Never call `events.save`, `onChanged` or the gate's `created`.

**Tests** (`EventRoutesTest`, using its `session(...)`, `event(...)` and
`practice(...)` helpers):
- an unsaved new event (no PUT): two parts, sessions placed by position;
- an existing event with one part's window moved in the body: the session
  moves in the answer, and a GET afterwards still shows the saved placement;
- a new part with no id, after a saved one: placed at its position;
- a part that ends before it starts: empty list, the rest still placed;
- `added` in the body moves a session; `removed` sends it to `others`;
- a merged session is one brief, placed whole; a window moved so it
  straddles two parts keeps it whole;
- not signed in: 401; from another site: 403; an existing event someone
  else made, a user not its editor: 403 (as the M23 test does);
- the event store's revision is unchanged after any number of previews.

### M39.3 — The page asks as you edit (web)

In `lib/events.ts`: `placementBody(draft, revision)` (the same shape
`saveBody` builds) and an `AdminPlacement` type. In `EventEditor.svelte`: an
effect on `draft` that, 400 ms after the last change, posts it and keeps
the answer in `placement`; errors are ignored (Save will say them). A new
event asks with the id it would get (`newId`).

**Check** (`lib/events.test.ts`): `placementBody` matches `saveBody` for the
same draft. On the dev server: changing a part's end updates its sessions
within a second.

### M39.4 — Sessions shown before saving, and moved on the draft (web)

The Sessions section shows always (a new event included), reading
`placement` instead of `page`:
- each part's sessions under its name, by position;
- the others, with "Add to [part]";
- **Take out** and **Add to** change `parts` with `removeSession` /
  `addSession` and do **not** save. Since a new part has no id yet, they
  need it: extend both helpers to take a part's index as well as an id
  (`partAt: number`), and test them. On a merged session's row they write
  every member's id (`brief.members`), so an unmerge later leaves each
  member where it was.
- the warning "Save your changes first" goes; "Unsaved changes" by Save
  covers it.

**Check:** vitest for the index form of both helpers and for a merged
brief (every member written); on the dev server, a new event with two
practices shows the dev car's sessions in each before it's saved (a merged
one as one row), a session taken out moves to "others", and one Save stores
it all (the event page then agrees).

### M39.5 — Three sections, and the schedule by day (web)

- **The event:** the fields as now, under "The event".
- **The schedule:** the parts sorted by start, grouped under a heading per
  local day ("Sat 17 Oct"). Each row: name, kind as a pill, from–to, the
  number of sessions it would catch ("3 sessions", from `placement`),
  Remove. One sentence above: "Each part catches the sessions the server
  heard during its window. Practice parts give each driver's best; race
  parts are one race, its laps adding up across them." The timezone note
  stays, shorter.
- **The sessions:** as in M39.4.
- Remove the event stays where it is, at the bottom, `may.delete` only.

Keep the existing `.panel` and `.row` styles; no new colours (decision 29).

**Check:** on the dev server with the M29 synthetic event (three parts over
three days): three day headings, counts that match the Sessions section.

### M39.6 — Deployed

Deployed with a live stream across it, as usual (JOURNAL: M4, M13). On the
live site, Sam opens NHMS Lemons 2026's editor, moves a window by an hour
without saving, sees the sessions move, and leaves without saving; the event
page is unchanged.

## Not in this milestone

- Changing a part's kind after it's made.
- Dragging parts on a timeline. A list by day is enough for two or three
  days.
- Phone layout of the editor: phase 1 (M31) makes every page fit; this page
  is used at a desk.
