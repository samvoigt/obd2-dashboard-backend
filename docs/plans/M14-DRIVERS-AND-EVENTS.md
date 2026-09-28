# M14 — Drivers and events

The third milestone of race logging (`RACE-LOGGING.md`): **drivers, and
events with practice parts and a race**; sessions joining a part by car and
the server's time, or by hand; **who drove** each session; and **practice
results**, public. The race itself (one timeline, stints, stops) is M15; here
a race part is only created, with its window, and lists its sessions.

Nothing here changes the contract or touches the tablet.

---

## What exists, and what it means (checked 2026-09-27)

- **Sessions** (`SessionRecord`): the car, `created` and `updated` (the
  **server's** times: first heard, last heard), and the summary (the tablet's
  `wall` times, `started` and `ended`, which are only as right as the tablet's
  clock: 10 h 58 min slow on the first drive).
  - A car's session streamed live is **created when it starts** (the live lane
    announces it, M4); a tablet-only session is **created when it uploads**, at
    its end (M11); a session uploaded after a dead zone is created late.
  - The measured clock offset lives only in the live lane's memory, is shown on
    the admin page, and is **never used to correct times** (decision 30).
- **Laps as they stand** (M13): `RetimingJobs.lapsOf(car, id)` picks the course
  a session's laps name, else the first its fixes touch. An event names **its
  course and layout**, so results need the laps on **that** course.
- **The admin sign-in** (Google, decision 25) and **the crew's** (a car's
  passcode, a per-host cookie, M5): the crew can already act for their own car
  (messages).
- **The courses pattern** to follow: a pure module (`:courses`), a Firestore
  store in `:archive-gcp` (a document per course, a transaction per save),
  admin routes, public routes, `admin.sh` commands, pages on both sides.

---

## Decided here (say if any is wrong)

- **A driver** is a name and a **code** of 2–4 capital letters (`SAM`), unique;
  drivers are one list for every event and car.
- **An event**: a name, a date, a **course** and **layout**, the **cars**
  entered (any of ours), and its **parts**:
  - **practice** parts, any number, each a name ("Practice 1") and a window;
  - **at most one race**, a window.
  Windows are entered in the browser's time zone and stored as instants;
  parts may not overlap.
- **Which sessions are in a part**: a car entered in the event, whose session
  the **server heard during the part's window** (`created` to `updated`
  overlapping it); **plus sessions added by hand, minus sessions removed by
  hand** (the admin, on the event's page). The server's times, not the
  tablet's, since the tablet's clock can be hours out; by hand for anything
  the server heard late (a tablet-only session uploaded after the window, a
  dead zone).
  - Only the car's own sessions and the tablet's (§20) join; **fake data
    (§21) never does**, even by hand.
  - A session is in **at most one part**.
- **Who drove**: each session has **a driver**, set on its page by the admin
  **or its car's crew** (the passcode they already use for messages), every
  change logged with who made it. In M15 a race session's driver becomes its
  first stint's, and a stint can be split.
- **Practice results** (public): per part, and for the whole event's practice,
  **each driver's best lap** (on track, never an in- or out-lap, §18), their
  laps, and the **best of each sector**; each session with its car, driver and
  laps, linked to its page. Laps as they stand on **the event's course and
  layout**: the tablet's where current, re-timed where not (decision 33).
- **Pages:** `/events` (public, newest first), `/events/{id}` (the event, its
  parts, practice results); on the admin page, **Drivers** and **Events**
  (create, edit, a part's sessions with add and remove). A session's page
  shows its event and part, and its driver (with a picker for the admin or the
  crew).
- **Storage:** Firestore, `drivers` (a document each) and `events` (a document
  each, parts and hand-made changes inside; a save is a transaction on an
  `expected` revision, as courses are). A session's driver is a field on the
  session's index record, set conditionally, touching nothing else.
- **Deleting:** a driver who drove any session can't be deleted (rename
  instead); an event can be deleted (its sessions are untouched).

---

## The steps

Each validated against the code just before it's built, the validation
written here.

### M14.1 — The rules (`:events`)

A pure module: `Driver`, `Event`, `Part` (practice or race); the rules
(names, codes, windows, one race, no overlaps, cars and course named);
**which sessions a part holds** (a function of the event, the sessions' cars,
server times and sources, and the hand-made changes); a store interface and
an in-memory store.

**Done when:** tests for every rule and its reason; a part picking a session
heard inside its window, one overlapping its edge, not one before or after,
not another car's, never fake data, one added by hand though heard late, one
removed by hand though inside; a session never in two parts.

> **Validated against the code, 2026-09-27, before building.**
> - **Pure, like `:courses`**, and depending on nothing of ours: membership
>   takes a small `SessionHeard` (id, car, the server's first and last heard,
>   source), which the server maps from `SessionRecord` (`created`, `updated`,
>   and `header.source`, else the summary's).
> - **Ids:** an event's id is chosen like a course's (`CourseRules`' id rule,
>   suggested from the name); a driver's is made by the server, so a code can
>   change; a part's is `p1`, `p2`… in the order made, never reused.
> - **Two cases the plan left open, decided:** a session **straddling two
>   parts** joins the one it overlaps most (the earlier on a tie); **by hand
>   wins**: a session added to a part is in that part only, and one removed
>   from a part is in no part by the windows. Added to two parts is refused.
> - **Windows** are half open, `[start, end)`; a session is heard in one if
>   `created < end` and `updated ≥ start`.
> - **Stores** as `CourseStore`: suspend functions, conditional saves
>   (`expected` revision), in-memory versions for tests.

> **✅ Done, 2026-09-27.** `:events`: `Driver`, `Event`, `Part`,
> `SessionHeard`; `EventRules` (every problem said; `sessionsIn`);
> `DriverStore`, `EventStore` and their in-memory versions.
> - **Added while building:** a part is at most 30 hours (a day and a night;
>   a mistyped year is refused, not a week-long window catching everything).
> - **Tests: 7** (a valid event; every problem with an event; drivers and
>   codes; a part's sessions by window, edges, cars, sources; by hand, and a
>   tie; part ids; the in-memory stores). **Mutations: 19, all killed.**

### M14.2 — Storage

`FirestoreDriverStore`, `FirestoreEventStore` (transactions, `expected`
revisions), the session's driver on the index (`setDriver`, conditional);
their mappings tested; `scripts/event-smoke.sh` through the real Firestore
(a throwaway driver and event, removed).

**Done when:** mapping round trips; a stale save refused; the smoke script
passes.

> **Validated against the code, 2026-09-27, before building.**
> - **`FirestoreDriverStore` and `FirestoreEventStore`** in `:archive-gcp`,
>   beside `FirestoreCourseStore`, whose shape they follow (`connect`, fields
>   in and out tested without Firestore, `await` on the API futures).
> - **A driver's code stays unique** by a transaction that reads every driver
>   and writes only if no other has the code (a handful of drivers: reading
>   them all is cheap). **An event's save** is a transaction on its
>   `revision`; parts are a list of maps inside the event's document (no
>   arrays inside arrays).
> - **A session's driver** is `SessionRecord.driver` (a driver's id), set by
>   `SessionIndex.setDriver` through the index's existing `conditional`
>   transaction, as `setSummary` is; every other conditional change writes
>   back the record it read, so the driver survives them.
> - **The smoke test** as `CourseSmoke`: `EventSmoke` and
>   `scripts/event-smoke.sh`, a throwaway driver and event, removed.

> **✅ Done, 2026-09-27.** `FirestoreDriverStore`, `FirestoreEventStore`;
> `SessionRecord.driver`, `SessionIndex.setDriver` (Firestore and in memory),
> `ArchiveService.setDriver`; `EventSmoke`, `scripts/event-smoke.sh`.
> - **Tests: 4** (an event's round trip, parts and hand-made changes and all;
>   a part's kind in words and no list inside a list; a driver's round trip; a
>   session's driver set, kept through other changes, cleared) and the session
>   mapping extended. **Mutations: 7, all killed**, one after the in-memory
>   `setDriver` got its own test.
> - **The real Firestore** (`scripts/event-smoke.sh`): a code kept unique
>   inside a transaction (reading the collection there works), a taken id and
>   a stale save refused, all removed. 13 checks, passed.

### M14.3 — The admin: drivers and events

Admin API (list, create, edit, delete; a part's sessions, add, remove), every
change logged; the admin page's **Drivers** and **Events**, the event editor
(course and layout from the courses, cars, parts and windows), a part's
sessions with add and remove. `admin.sh` gains `drivers` and `events` (list
only).

**Done when:** API tests (sign-in, origin, validation, conflicts, a driver in
use not deleted); looked at in Chrome against the dev server: an event made,
a practice part whose window catches a replayed session, one added by hand.

### M14.4 — Who drove

`PUT /api/sessions/{id}/driver` for the admin or the car's crew; the session
page's driver line and picker; logged.

**Done when:** tests (admin, own crew, another car's crew refused, no sign-in
refused, an unknown driver refused, fake data refused); looked at in Chrome.

### M14.5 — Practice results, public

`GET /api/events`, `GET /api/events/{id}` (the event, parts, sessions with
car and driver, and practice results: laps on the event's course and layout,
best per driver, best of each sector); `/events` and `/events/{id}` pages; the
session page's event and part; the car's sessions list says which event.

**Done when:** tests for the results (bests never a pit lap, the event's
course only, a re-timed lap marked, a session with no driver shown as such);
looked at in Chrome with two drivers' sessions in two practice parts.

### M14.6 — Deploy, and prove it

Deployed with a drive streaming (a stream longer than the build, the Mac kept
awake; JOURNAL: M13), with the course editor's rename fix batched in. **Proof:**
a throwaway `smoke-*` car, a test course, a test event with a practice part
whose window catches two replayed drives, two test drivers set through the
crew's passcode; the event's public page shows each driver's best. All of it
removed after.

### M14.7 — Record it

A decision for events, parts and who drove; `COMPLETED`, `JOURNAL`, `PLAN`,
`README`, `CLAUDE.md`; this plan deleted.

---

## Not in M14

- The race as one timeline, stints, stops, laps numbered through the race,
  race results, driver pages (M15).
- The lap chart, theoretical best, comparisons, consistency (M16).
- Anything live, or to the tablet (M17).
