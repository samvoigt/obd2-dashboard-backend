# Proposed contract change: courses and timing to the tablet

**Revision 2, 2026-09-27: the backend's answer to the tablet's reply
(contract §22).** For the tablet side to review. Not part of the contract
until both sides and Sam agree; this side never writes into the app's
`docs/TELEMETRY-CONTRACT.md`. If agreed, the tablet side writes the agreed
version into the contract in place of §22, as §§12–14 were settled.

**Sam, 2026-09-27:** "the tablet's number wins, it is the source of truth for
the location data." That settles the one real disagreement below (§1), and
makes the whole thing simpler than either the first proposal or the reply.

---

## What changed since revision 1

| | Revision 1 (backend) | §22 (tablet) | **Revision 2 (this)** |
| --- | --- | --- | --- |
| Who times laps live | The server | The tablet, the server's number winning where they differ | **The tablet. Its numbers are the results.** |
| The fix's time | `at` | `fixAt` | **`fixAt`**, accepted, and never going backwards (§2) |
| `crossing` frame | Yes | Optional | **Dropped** |
| `timing.lap.startAt` | The lap clock's source | A check, adopted if 50 ms off | **Dropped**: the tablet runs its own clock |
| Courses | From the website | From the website, start/finish editing off the tablet | **As §22.3**, accepted |
| Pit lines | `pit_in`, `pit_out` | Crossing them marks in/out-laps | **Accepted, plus a `pit_line`** where the in-lap ends (§3.2) |
| A `lap` record | Unchanged | Gains `course`, `courseVersion`, layout `id`, `sectors` | **Accepted** (§4) |
| Across sessions | Per session | Per run of the app, on `at`; ages for older things | **Accepted** (§5) |
| `hello.features`, wire v1 | Yes | Yes | **Yes** |

---

## 1. The tablet is the timer; the server keeps the books

**The tablet's numbers win.** It has every fix, the moment it's taken, and
times the lap against the course it holds. So:

- **Live and official, a lap is the tablet's `lap` record.** The server
  stores it as sent and shows it everywhere: the car's page, a session's page,
  an event's results. It **never overrides** a lap the tablet timed on the
  course version that is current.
- **The server times from GPS only where the tablet's lap isn't current:**
  - after a line moves on the website, for laps the tablet timed on an older
    version of the course, re-timed on the new one;
  - for sessions with no `lap` records (logs from before this change, or a
    tablet without the course).

  Even then it times **the tablet's own fixes**, on their `fixAt`, by the same
  rule (§16), so the result is still the tablet's data, measured against the
  new line. Each lap says which it is: timed by the tablet, or re-timed by the
  server on version N.
- **Where both exist for the same version, they should agree to the
  millisecond** (same fixes, same `fixAt`, same rule). The server checks, and
  flags any lap that doesn't, as a bug for one side to find, never by
  replacing the tablet's number.
- **What only the server knows** goes down in `timing` (§6): who's driving,
  the stint, the race's lap count, the time since the stop, and the bests
  across an event's drivers and sessions.

This also removes the problem with the live lane's coalescing (§5.2 sends only
the latest sample of each signal per 200 ms batch): at 10 Hz the server would
see half the fixes live. It no longer needs them live; `lap` records go whole
in the next batch, and the archive has every fix for re-timing.

---

## 2. `gps.position` gains `fixAt` (§22.2, accepted)

As §22.2 has it: the fix's own time on `at`'s clock, present when the receiver
gives one, never later than `at`, and **what both sides time on**, falling back
to `at` where it's absent.

**One addition:** `fixAt` **never goes backwards** from one `gps.position` to
the next within a run of the app. The server orders fixes by it for timing; a
fix whose `fixAt` is earlier than the last one's would be a receiver or
conversion fault, and the server will skip it rather than time on it. (If the
receiver can deliver fixes out of order, say so, and the tablet sorts them
before publishing instead.)

---

## 3. Courses (§22.3, accepted, with pit lines settled)

### 3.1 As agreed

- `GET /v1/courses` with one `ETag` for all courses and `304`; any car's
  token reads them all; the `courses` frame (`{"t":"courses","etag":"…"}`)
  after every `hello` and on any change, to a tablet listing `courses.1`.
- **Courses live only on the website.** The tablet caches the last answer,
  uses it offline, keeps the shipped `nhms.geojson` only until the first
  download, and deletes what the server stops listing.
- Fetched **whenever the tablet has any car's token and a link**.
- The GeoJSON is the app's own format: layouts (with `id`, `name`,
  `default`), `start_finish`, `sector` (`layout`, `index`), `pit_lane`.
  **Every line counts only when crossed in its layout's direction.**
- Layouts are identified by `id`.

### 3.2 Pit lines: in-laps, out-laps and where they end

§22.6's rule, accepted: **a lap is an in-lap when the car crosses `pit_in`
during it, and an out-lap when it crosses `pit_out`**.

**What it leaves open:** where an in-lap *ends*. A start/finish drawn across
the track (NHMS's spans 12 m either side of the centreline) isn't crossed in
the pit lane, so an in-lap would run through the whole stop until the car next
crosses it on track. The tablet avoids that today with its own line across the
pit lane, level with the start/finish, and §22 drops it.

**Proposed:** keep it, drawn on the website. A new role:

| `role` | Geometry | Meaning |
| --- | --- | --- |
| `pit_line` | `LineString`, 2 points | Across the pit lane, usually level with the start/finish. **Crossing it ends the lap under way (an in-lap) and begins the next (an out-lap)**, as the start/finish does on track. |

- A course **with** `pit_line`: in-laps end there, out-laps start there;
  the time between is lost to neither lap, since the car crosses one line.
- A course **without** it but with `pit_in` and `pit_out`: the tablet's
  current behaviour is the fallback (a line across the pit lane level with
  the start/finish, made by the tablet), so NHMS keeps working until one is
  drawn. **Suggested:** the website seeds NHMS's `pit_line` from where the
  tablet makes it today, so the fallback is never needed there.
- **A stop's length** is from `pit_in` to `pit_out`, for the race's results.
- A course with no pit lines at all times as §16 does today.

---

## 4. The `lap` record (§22.4, accepted)

```json
{"type":"lap","track":"nhms","course":"nhms","courseVersion":7,"layout":"road","lap":3,"time":94.532,"sectors":[31.298,32.990,30.244],"seq":61022,"at":4410233,"wall":1758719710456}
```

As §22.4: `course`, `courseVersion`, `layout` as the layout's `id`, and
`sectors`; `track` kept for older readers; no `courseVersion` and a layout
*name* before the first download. **Plus, suggested:** the lap's start and end
crossings on `fixAt`'s clock (`startAt`, `endAt`), so the server can place a
lap exactly on the session's chart and check it against its own timing
without re-deriving the crossings. Optional; say if it's awkward.

---

## 5. Timing is per run of the app (§22.5, accepted)

As §22.5: `at` is one clock across every session in a run; the server
identifies a run by `device` and each session record's `at` rising; it joins
a run's sessions on `at`, and runs only on `wall`. Anything that may predate
the run is sent as an age (`…AgeMs`, as old as it was when sent), never an
`at`.

---

## 6. `timing` (revised): only what the tablet can't know

Sent to a tablet listing `timing.1`, **after every `hello`** once a session is
running, and **whenever anything in it changes**. The complete state, like
`messages` (§5.4): the tablet shows exactly what it says.

```json
{
  "t": "timing",
  "session": "5ace0000-1111-4111-8111-000000000035",
  "course": {"id": "nhms", "version": 7, "layout": "road"},
  "best": {"time": 94.532, "sectors": [31.298, 32.990, 30.244], "driver": "SAM", "session": "5ace…0031", "lap": 7},
  "bestSectors": [31.298, 32.874, 30.101],
  "driver": {"name": "Sam", "code": "SAM", "stintAgeMs": 2412000},
  "race": {"lap": 58, "sinceStopAgeMs": 1290000}
}
```

| Field | Meaning |
| --- | --- |
| `session` | The tablet's current session; the tablet ignores a `timing` for any other (§5). |
| `course` | The course, version and layout the server knows the car to be at. **If the version is newer than the tablet's cached one, the tablet fetches courses** (a line moved) and times on the new one from then on, re-timing the lap under way from its own fixes if it likes. |
| `best` | The best lap **of the event** (every driver and session of this car), or of this car on this layout if there's no event; never an in- or out-lap. Who set it, and where, so the tablet can tell whether it holds that lap's fixes (§22.6: it uses the server's best as its delta's reference only then). |
| `bestSectors` | The event's best of each sector; their sum is the theoretical best. |
| `driver` | Who's driving, as the crew set it on the website, and how long since the stint began (an age). |
| `race` | During a race only: the lap count through the race, and how long since the car left the pits (an age). |

Every field is optional; the tablet shows what's there. **No lap clock, no
last lap, no splits**: those are the tablet's own.

**Dropped from revision 1:** `crossing`, and `timing`'s `lap` and `last`.

---

## 7. Still for the tablet side

1. **`fixAt` never going backwards** (§2): can the tablet promise it?
2. **`pit_line`** (§3.2): agreed, including the fallback and seeding NHMS's
   from where the tablet draws it today?
3. **`startAt` and `endAt` on a `lap`** (§4): easy to add?
4. **§21's fake session** carries `protocol` and `pids`, which §21 says are
   absent (found in the first drive's logs, 2026-09-27; the backend's
   JOURNAL). Harmless to the server, but one side's text is wrong.

---

## What each side commits to, if agreed

**The tablet:** `fixAt` on every `gps.position` with a receiver time, never
going backwards; courses from `GET /v1/courses`, cached, the shipped file only
before the first download, no start/finish editing on the tablet; laps and
sectors timed against the cached course, in-laps and out-laps by `pit_in`,
`pit_out` and `pit_line`; every `lap` naming its course, version and layout
`id`, with its sectors (and `startAt`, `endAt` if agreed); `timing` shown for
the driver, the race and the event's bests; `hello.features` listing
`courses.1` and `timing.1`.

**The server:** `GET /v1/courses` and the `courses` frame as §3.1; the
tablet's `lap` records as the results, never overridden for the current
course version; re-timing from the tablet's own fixes on `fixAt` only for
older course versions and laps the tablet didn't time, each marked as such;
flagging, never replacing, a lap where the two disagree; `timing` as §6, only
to a tablet that lists `timing.1`; no existing frame or record changed.
