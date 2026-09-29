# M18 — The candidates, and what earlier milestones left

After race logging, the candidates Sam had waiting (`PLAN.md`) and the small
things M13–M17 left for later. **The dashboard shows every signal the car
sends**; **sessions get names**; **crew messages show beside a session**; and
three leftovers: **each session's clock offset stored**, **a course's
re-timing that survives a restart** (and starts after `import-course`), and two
stale lines in `PLAN.md`.

Not here: the neighbourhood lap-timing drive (Sam: planned later, on its own),
and M9 (readable secrets, tabled by Sam).

---

## Settled with Sam, 2026-09-28

- **The dashboard:** "just display all the signals being sent up by the car",
  in place of a per-car form for slots and ranges (decision 28's "way on").
- **Names:** the admin and the car's crew (its passcode) name a session, as
  they set who drove it.
- **Crew messages beside a session:** marked on its chart where each was sent,
  and listed with when it was sent, received and shown.

---

## What exists, and what it means (checked 2026-09-28)

- **The dashboard** (decision 28, amended by M10 and M11) is one layout in
  code: `SLOTS` in `web/src/lib/dashboard.ts` (four gauges, one number, two
  status lights, each a list of signal names), then the G-meter and the map,
  laps, the crew panel, the chart, and **every other signal as a tile**. So
  every signal already shows, but only Sam's picks get a gauge, and a tablet
  sending something else shows it only as a tile. Ranges are by unit
  (`BY_UNIT`) and zones by signal name, generic (the app's decision 33).
- **A session's `signals`** (its record, and `signals` records) are what the
  tablet declares, each with `name`, `unit` and `kind`, in the tablet's order.
- **A session has no name.** Its record (`SessionRecord`) has a driver (M14),
  set by the admin or the crew through two routes, logged.
- **Crew messages** are stored per car with `sentAt`, `receivedAt`,
  `displayedAt` and `endedAt` on the server's clock (M5). Only the most recent
  are listed (`recent(car, limit)`). A session's chart is on the tablet's
  `wall`.
- **The tablet's clock against ours** is measured live (`CarLive.clockOffset`,
  M11) and **kept nowhere**. The race's flags use the smallest
  `created − started` over its sessions (M15), good to seconds.
- **A course save's re-timing** runs in the background (`RetimingJobs`), its
  progress in memory. A restart mid-job leaves the rest to be built on first
  view. **`admin.sh import-course`** writes Firestore directly, so the running
  server never starts the job; again, built on view (the M13 record).
- **`PLAN.md`** still lists "comparing laps" as a candidate (M16 built it) and
  says the tablet has no cellular (the tablet's §23: it has a working SIM).

---

## Decided here (say if any is wrong)

- **The dashboard, from what the car declares:** every signal the session
  declares gets a place, in the tablet's order, **drawn by its kind and unit**:
  - a number whose unit has a range (`BY_UNIT`: rpm, km/h, °C, V, %, kPa…) is
    a **gauge**, with its generic zones if it has any;
  - any other number is a **number**;
  - a state, flag or flag set is a **status light**;
  - positions go to the **map**, the three accelerations to the **G-meter**
    (both stay, as M10 has them), and laps to the laps panel.
  
  `SLOTS` and the tiles section go: nothing is picked by name any more, so a
  car sending something new shows it without a deploy. A signal with a reading
  and no declaration (a session record not come yet) still shows, as a
  number. Ranges and zones stay generic; a per-car form isn't needed.
- **A name** is up to 60 characters, optional, set and cleared by the admin
  (the admin page and the session page) or the car's crew (the session page),
  logged like a driver. It shows first wherever a session is listed (a car's
  sessions, the admin page, event results), with the time beside it.
- **Messages beside a session:** those sent while it ran (from when the server
  first heard it to its last data), listed with their sent, received and shown
  times, and each marked on the chart at the moment it was sent, placed on the
  tablet's `wall` by the session's stored offset. Public, as the session page
  is: the text is what the tablet showed the driver.
- **The clock offset per session:** the live lane's measured offset (its
  smallest over the session), stored on the session's record when the session
  ends or the car disconnects; the race's flags and the messages use it, and
  fall back to `created − started` for a session streamed before this.
- **Re-timing that survives:** the server keeps, per course, the version its
  last finished re-timing was for (Firestore, beside the course); on start,
  and every minute, it starts the job for any course whose current version
  isn't that one. So a restart resumes, and `import-course` is picked up
  within a minute. Runs already re-timed on that version are read back, not
  redone (the `Retimer` stores each).

---

## The steps

Each validated against the code just before it's built, the validation
written here.

### M18.1 — Each session's clock offset, stored

`CarLive`'s measured offset, kept per session (its smallest), written to the
session's record at its end (or the car's disconnect); `SessionRecord` and the
Firestore index gain it; the race's flags use it before `created − started`.

**Done when:** tests (the smallest over a session kept; written at the end
and at a disconnect; a new session starts afresh; the flags placed by it, and
by `created − started` without it); the real Firestore round trip
(`firestore-smoke.sh` or a new line in it).

> **Validated against the code, 2026-09-28, before building.**
> - **`CarLive.noteClock`** keeps the smallest of the last 50 batches' offsets
>   (`clockOffset`, for the admin page's clock note), across sessions. The
>   session's own smallest is a second value, reset when a new session is
>   announced and kept after its `end`, so it can be written then.
> - **When to write**: the socket sees the `end` frame, a new `session` frame
>   (the previous one is over, or it's the same one again after a reconnect),
>   and its own close (`finally`). It writes the session's smallest then,
>   through `ArchiveService`, **keeping the smaller of what's stored and what
>   came** (a session can span reconnects, and a restart of the server).
> - **`SessionRecord`** gains `clockOffsetMs`, kept through every
>   whole-document write as `driver` is (`toFields`, `recordFrom`), set
>   conditionally (`setClockOffset`).
> - **The race's flags** (`publicEventRoutes`) take the car's smallest stored
>   offset over its race sessions, else `created − started` (M15), else the
>   live lane's (M17.6).

> **✅ Done, 2026-09-28.** `CarLive.sessionOffset` (the session's smallest,
> reset on a new one, kept after its end), `LiveHub.sessionOffset`,
> `SessionRecord.clockOffsetMs` (in memory and Firestore; the smaller kept),
> the socket storing it at an `end`, a new session and its close (the close's
> write not cancelled with the socket); the race's flags by it first.
> - **Found:** the archive smoke still expected M17.1's repeated `complete`
>   to answer `Done`, and its "a later write keeps the summary" relied on that
>   write, which no longer happens; the offset's write now plays that part.
> - **Tests:** `:live` 1, `:server` 2 and one extended (`ClockOffsetTest`: at
>   the end, the smallest; at the next session and a disconnect; the smaller
>   kept; the race's green flag by the stored offset), `:archive-gcp` mapping
>   extended. **The real Firestore:** `archive-smoke.sh` passed (stored, the
>   smaller kept, the summary kept through it).
> - **Mutations: 14, all killed** (one rewritten after it didn't compile).

### M18.2 — Re-timing that survives a restart, and follows `import-course`

The last finished re-timing's version per course, stored; a watcher on start
and every minute starting the job for any course behind; the admin page's
progress as today.

**Done when:** tests (a course saved and the server restarted: the job runs
again and reads back what was done; a course saved outside the server:
re-timed within a tick; a course up to date: nothing runs); proven against
the real Firestore with a throwaway course.

### M18.3 — Names for sessions

`SessionRecord.name`; the admin's and the crew's routes (as the driver's);
the name shown in a car's sessions, the session page (with its editor), the
admin page and event results; `admin.sh sessions` showing it.

**Done when:** tests (set and cleared by each; refused over 60 characters,
for another car's crew, for a fake session; shown in each list); looked at in
Chrome.

### M18.4 — Crew messages beside a session

A store query for a car's messages sent in a window; `GET
/api/sessions/{id}/messages`; the session page's list and its chart's marks.

**Done when:** tests (messages inside the window and none outside; placed by
the stored offset, else `created − started`; the marks where they were sent);
looked at in Chrome with messages sent during a replay.

### M18.5 — The dashboard shows every signal the car sends

`lib/dashboard.ts` gains the layout from the declared signals (by kind and
unit); `CarPage` draws it; `SLOTS` and the tiles go; decision 28 amended.

**Done when:** tests (each kind and unit to its widget; the tablet's order;
the map and the G-meter's signals not repeated; a signal with a reading and
no declaration shown; nothing picked by name); looked at in Chrome with the
first drive's signals and a fake session's; measured with `measure.mjs` as M8
was (a page of more gauges must still hold its frame rate).

### M18.6 — Deploy, and prove it

Deployed with a stream across it (a second throwaway car's, under
`caffeinate -i`). **Proof in production:** a throwaway car streaming a drive
with messages sent to it: its dashboard with every signal, its offset stored
at the end, the messages on its session's chart and list, a name set by the
crew; a throwaway course imported with `admin.sh` and re-timed by the server.
All removed after.

### M18.7 — Record it

Decisions for names, messages beside a session, the stored offset and
re-timing's watcher, and decision 28 amended; `COMPLETED`, `JOURNAL`, `PLAN`
(its two stale lines fixed), `README`; this plan deleted.

---

## Not in M18

- The neighbourhood lap-timing drive (planned on its own, later).
- M9, readable secrets (tabled).
- A per-car dashboard form, or per-car ranges and zones.
- Re-timing a session before it completes; positions per lap or between cars.
