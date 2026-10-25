# M40 — The course editor: a toolbar on the map, the four things a course needs, and undo

Phase 7 of the UX review (`docs/plans/UX-REVIEW.md` §3.G and §4, "M40"). The
course editor is a map and one tall sidebar of twenty-odd controls: name,
imagery, file, layouts, timing lines, pit, save, re-timing, a session's
route, editors, remove, versions. Drawing modes are announced only by a hint
at the bottom of the map; Save scrolls out of sight. **Sam, 2026-10-22,**
named the admin editors as one of the three places people get lost, and
kept this phase last (§6.5 of the review).

> **Planned 2026-10-22**, checked against the code.

---

## What exists, and what it means for this (checked 2026-10-22)

- **The page is `web/src/CourseEditor.svelte`** (660 lines). Leaflet with
  Geoman (`@geoman-io/leaflet-geoman-free`) for drawing lines. The map is
  made in `mount()`; a click calls `clicked(p)`, a finished Geoman line calls
  `created(layer)`.
- **Tools:** `type Tool = 'layout' | 'pit_lane' | 'start_finish' | 'sector' |
  'pit_in' | 'pit_out' | 'pit_line'`. `start(t)` checks what's needed first
  (a layout name, a selected layout, a pit lane) and says so in `message`;
  `layout` and `pit_lane` turn on Geoman's line drawing. `stop()` turns it
  off. A one-click timing line (M22) is `across(...)` in `lib/courseEdit.ts`;
  a sector tool stays on for the next line, the others stop after one.
- **The hint** is `<p class="hint">` absolutely placed at the bottom of the
  map, with the tool's text from `hint[tool]` and a Stop button. **There's no
  keyboard handling**: no `keydown` listener, so Esc does nothing (Geoman's
  own Esc behaviour, if any, isn't relied on).
- **Every edit goes through `change(next)`**, which sets `course` and `dirty`.
  `course` is `$state.raw` and every helper in `lib/courseEdit.ts` returns a
  new `EditCourse` (`addSector`, `removeSector`, `moveSector`, `removeLayout`,
  `reverse`, `makeDefault`, `assignLayout`…). **So an undo stack is cheap**:
  keep the previous `course` on each `change`.
- **The course's state** (`EditCourse`, `lib/courseEdit.ts:29`): `layouts`,
  `startFinish` (each `layout: string | null`, null meaning every layout
  without its own), `sectors` (by layout and index), `pitLane`, `pitIn`,
  `pitOut`, `pitLine`, and `unassigned` lines from a file (M20).
- **Problems as you draw:** an effect posts to `/courses/check` 400 ms after
  each change and shows the server's problems; Save is disabled while any
  stand (`problems.length > 0 || !changed`).
- **Removes are immediate, in memory:** a layout, a sector, the pit lane, pit
  in/out/line. Nothing is stored until Save, so a remove is undone by not
  saving, which throws away everything else too. **Remove course** (stored)
  already asks you to type the id.
- **Save** is `Save as version N` mid-sidebar; `unsaved(dirty, name,
  savedName)` says whether there's anything to save.
- **The layout** is a grid, `1fr 340px`, one column under 900 px; the map is
  78vh.
- **Tests:** `lib/courseEdit.test.ts`, `courseFile.test.ts` (M20),
  `courseLines.test.ts` (M22). The component has none; its logic is in
  `lib/`.

**What it means:** the review's ideas fit without touching how a course is
stored or checked. The one change from the review: removes inside the
drawing get **Undo**, not a confirm dialog, since they're not stored until
Save and undo is cheap here. Remove course keeps its typed confirmation.

## Decided here

- **A toolbar over the map**, top left beside the zoom control: the current
  mode as a pill ("Drawing the pit lane · click to add points · Esc to
  stop"), Undo, Redo, Stop. With no tool on, it shows "Choose what to draw"
  and Undo/Redo.
- **Esc stops a tool** (and Geoman's drawing); **Ctrl/Cmd+Z** undoes,
  **Shift+Ctrl/Cmd+Z** redoes, except while typing in an input.
- **Undo history:** up to 50 drawings back, cleared on load, on viewing
  another version, and on save.
- **The sidebar in groups**, each with a done mark derived from the course,
  in this order: *Layouts*, *Start/finish*, *Sectors* (of the chosen
  layout), *Pit*. Then the Save bar. Then *More*: name and id, imagery, open
  file / download, a session's route, editors, versions, remove course.
- **Done means:** Layouts, at least one; Start/finish, the chosen layout has
  one (its own or the shared one); Sectors, a count, never "not done"
  (optional); Pit, the pit lane with pit in and pit out (the pit line is
  optional and said so). Not a rule: the server's check stays the rule.
- **A sticky Save bar** at the bottom of the sidebar: "Version 7 · unsaved
  changes · [Save as version 8]", and the problems' count linking to them.
- **Labels by name, not id:** "only for Road Course", not "only for road".

## The steps

### M40.1 — The checklist (pure, tested)

`courseChecklist(course: EditCourse, selected: string | null)` in
`lib/courseEdit.ts`, returning
`{ layouts: number; startFinish: boolean; sectors: number; pit: { lane: boolean; in: boolean; out: boolean; line: boolean } }`
with the rules in "Decided here".

**Tests** (`courseEdit.test.ts`): empty course; one layout without a
start/finish; a shared start/finish (layout null) counts for every layout; a
layout's own start/finish counts only for it; sectors counted for the
chosen layout only; pit lane without in/out; all of it.

### M40.2 — Undo and redo (pure, tested; then wired)

`lib/history.ts`: `History<T>` with `push(previous)`, `undo(current)`,
`redo(current)`, `canUndo`, `canRedo`, `clear()`, a 50-deep limit, redo
dropped on a new push.

In `CourseEditor.svelte`: `change(next)` pushes the previous `course`; Undo
and Redo set `course` (and `dirty` as `unsaved` would have it) without
pushing; `show()`, `view()` and a successful `save()` clear it.

**Tests:** `lib/history.test.ts` (order, the limit, redo dropped). On the dev
server: remove a sector, Undo brings it back, Redo takes it away again;
after Save, Undo is disabled.

### M40.3 — The toolbar and the keyboard (web)

A `MapToolbar.svelte` placed in `.mapwrap`, absolutely at top left (beside
Leaflet's zoom, `z-index` above the map as the hint's 1000): the mode pill
(the tool's hint text, short), Stop (only with a tool on), Undo, Redo. The
old `<p class="hint">` goes; its texts move into the pill, shortened.

A `keydown` listener on `window` while the editor is shown: Esc calls
`stop()`; Ctrl/Cmd+Z undo; Shift+Ctrl/Cmd+Z redo; ignored when the event's
target is an `input`, `select` or `textarea`.

**Check** on the dev server: start "Add sector lines", press Esc, the pill
says "Choose what to draw" and a click on the map adds nothing; start
drawing the pit lane with Geoman, Esc stops it with no line made.

### M40.4 — The sidebar in four groups with done marks (web)

Rearrange the sidebar's existing controls, no new behaviour:
- **Layouts** ✓ (count): the layout list, make default, remove, reverse, move
  points, new layout and Draw; the "Lines to label" block from a file (M20)
  above it while there are any.
- **Start/finish** ✓/–: draw or redraw; "only for {layout name}".
- **Sectors** (n): the chosen layout's lines, ↑ ↓ remove, Add sector lines.
- **Pit** ✓/–: draw/redraw lane, pit in, pit out, pit line, move points,
  reverse, removes; a line under it says which of the three are still to
  draw.

Each group's heading shows its mark from `courseChecklist`. Removes stay one
click (Undo covers them) and keep the `danger` style.

**Check** on the dev server, on a new course: the four marks go from "–" to
"✓" as each is drawn; on NHMS, all four show done.

### M40.5 — The Save bar and "More" (web)

- A Save bar fixed at the bottom of the sidebar (`position: sticky;
  bottom: 0` inside `aside`; under 900 px, fixed to the viewport's bottom):
  version, "unsaved changes", the problems' count (a link scrolling to the
  list), Save.
- *More*, a `<details>` after the groups: name and id, aerial imagery, open
  file / download, a session's route, editors, versions, remove course. The
  name field moves here for an existing course; **a new course keeps Name
  and Id at the top**, since it can't be saved without them.
- An older version being viewed: the bar says "Viewing version 5 · Back to
  the latest", and the groups are read-only as now.

**Check** on the dev server: scroll the sidebar to the bottom, the Save bar
stays in view; with a problem (a layout with no start/finish) the bar says
"1 problem" and Save is disabled; a new course shows Name and Id first.

### M40.6 — Deployed

Deployed with a live stream across it. On the live site, Sam opens NHMS's
editor, removes and undoes a sector, and leaves without saving; the course
stays at its version.

## Not in this milestone

- Changing how lines are drawn (M22's one click stays) or how files are read
  (M20 stays).
- Snapping, measuring, or any new drawing tool.
- The editor on a phone beyond what M31 makes fit.
