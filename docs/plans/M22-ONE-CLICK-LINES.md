# M22 — Timing lines in one click

A start/finish, a sector line, or a pit line, made with **one click on the
path it crosses**: the editor builds the line square across it there, and its
ends can be dragged after. **Sam, 2026-10-01:** "why does the start/finish
have to be two separate points?" Then, on making them from a single click:
"yes, make a plan to do it that way".

> **Planned and answered 2026-10-01, not validated.** "What exists" was read from the code on
> 2026-10-01. Each step is still validated just before it's built, and that's
> written in here.

**Nothing changes but the editor.** A line is still exactly two points,
1–200 m apart (contract §22, `CourseRules`). The tablet, the server, the
stored format and `admin.sh import-course` are untouched. Only the way a
line is drawn changes.

---

## What exists, and what it means for this (checked 2026-10-01)

- **Every two-point line is two clicks today** (`CourseEditor.svelte`,
  `clicked`): the first click is held in `firstPoint`, the second makes the
  line. That covers the start/finish (shared, or "only for" the chosen
  layout), sector lines (one after another until Stop), pit in, pit out and
  the pit line. The hints say "Click one side of the track, then the other."
- **The ends can already be dragged** (`drawLine`'s handles), so a made line
  can be adjusted without new UI.
- **Which path each line crosses** is already implied by the editor's
  model: the start/finish and sectors cross a layout (the chosen one,
  `selected`); pit in, pit out and the pit line cross the pit lane
  (`course.pitLane`). With no pit lane drawn, a pit line has nothing to be
  square to.
- **NHMS gives the widths.** Its start/finish is 24 m, "12 m each side of
  the centreline", and its pit line 16 m, "8 m each side"
  (`courses/seed/nhms.geojson`, made by `make_nhms.py`, which already builds
  a line square to a path).
- **A line can cross more than one path.** JOURNAL, M15: "A pit lane starts
  and ends on the track… a line made at an end would be crossed by cars that
  never pit." A pit line too long reaches the track. A start/finish too long
  can reach the pit lane, or another stretch of a layout that passes close.
  The server doesn't check what a line crosses (`CourseRules` checks only
  length and shape), so the editor must.
- **Paths are drawn by hand or come from OpenStreetMap**, so a path can kink
  over a few metres. A direction taken from one segment could be skewed, so
  it's averaged along the path either side of the click.
- **Pure geometry is tested in Vitest** (`courseEdit.test.ts`, `metres`,
  `arrows`). The map clicks aren't, and are proven by hand on the dev server.

---

## Decided here (say if any is wrong)

- **One click on the path makes the line**, square to it there: the nearest
  point on the path to the click, the path's direction averaged over 10 m
  each way, and the line through that point at right angles.
- **Widths:** **12 m each side** for the start/finish and sector lines,
  **8 m each side** for pit in, pit out and the pit line, as NHMS's are.
- **A made line stops short of any other path.** Each half is shortened to
  1 m before the first other path it would cross: another layout, another
  stretch of the same layout, or the pit lane (for a track line), or a layout
  (for a pit line). The editor says when it shortened one ("Shortened: it
  would have crossed the pit lane"). If a half ends up under 1 m, so the line
  can't be made there, the editor says "Too close to the pit lane here",
  and nothing is drawn.
- **A click too far from the path is refused:** over 30 m from it, the hint
  says "Click on the track" (or "on the pit lane"), and nothing is drawn.
- **The ends stay draggable**, as now, for anything the defaults get wrong
  (a wide pit straight, say).
- **The hints change** to "Click on the track where the start/finish
  goes", "…each sector line, in order", "Click on the pit lane where it
  begins" and so on.

## Settled with Sam, 2026-10-01

1. **One click only.** No two-click drawing; the ends are dragged for
   anything odd.
2. **12 m each side** for the start/finish and sector lines, **8 m** for pit
   lines.
3. **Shortened** short of any other path it would cross.

---

## The steps

### M22.1 — A line square across a path (not validated)

Pure functions in `web/src/lib/courseEdit.ts`:
- `nearestOn(path, p)`: the nearest point on a path to `p`, its distance,
  and where along the path it is.
- `across(path, p, half, others)`: the line square to `path` at its nearest
  point to `p`, `half` metres each side, each half cut 1 m before the first
  of `others` (paths) it crosses. It returns `{ a, b, shortened: string[] }`,
  `{ tooFar }` or `{ tooTight: string }`.

Tests:
- a straight path, a bend, and a kinked hand-drawn path (the direction is
  averaged, not taken from one segment);
- the nearest point at a path's ends;
- a pit lane beside a straight: the start/finish is shortened, the pit line
  is shortened from the other side, and too close is refused;
- a layout that passes close to itself;
- a click 31 m away is refused.

### M22.2 — The editor makes lines in one click (not validated)

`clicked` in `CourseEditor.svelte`: for each line tool, the path it crosses
(the chosen layout, or the pit lane) and what it must stop short of. A pit
line tool with no pit lane says "Draw the pit lane first". Sectors still go
one after another until Stop. A start/finish "only for" a layout is made on
that layout. Messages for "shortened", "too far" and "too tight" go in the
existing `message` line, and the hints change. `firstPoint` and its marker
go.

### M22.3 — Proven, deployed, recorded (not validated)

Through the dev server:
- NHMS: a start/finish on the front straight (shortened short of the pit
  lane, if it reaches), a sector on each layout, and pit in, out and line on
  the pit lane;
- the made lines compared with NHMS's own;
- saved, and `GET /v1/courses` sends them;
- a click off the track refused.

Then deployed with a live connection open, and Sam draws Palmer's
start/finish. Then a decision (lines made square to their path in one click,
the widths, shortening), `COMPLETED.md`, `JOURNAL.md`, `PLAN.md`, and the
plan deleted.
