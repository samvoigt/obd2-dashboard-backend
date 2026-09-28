# Plan

The backend for [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard).

**The primary use case:** a car's tablet streams its data live; a website shows
it as live dashboards *and* captures it as a session; and the crew can send the
car messages such as "Pit Now". There can be several cars at once, each with its
own page.

**The protocol is the telemetry contract v1** (decision 15, `docs/PROTOCOL.md`).
Where this plan and the contract disagree, the contract wins. Fix the plan.

## Status

| Milestone | | |
| --- | --- | --- |
| **M0** | Skeleton: Ktor server, health check, tests, Dockerfile | ✅ |
| **M1** | Deployed to Cloud Run; one shared tablet key | ✅ |
| **M2** | Cars: registry, per-car tokens and passcodes, admin tool | ✅ (`plans/COMPLETED.md`) |
| **M3** | Archive lane (contract §6), and the replay tool | ✅ (`plans/COMPLETED.md`) |
| **M4** | Live lane (contract §5.1–5.3), fan-out, first website | ✅ (`plans/COMPLETED.md`) |
| **M5** | Crew messages (contract §5.4) and the crew panel | ✅ (`plans/COMPLETED.md`) |
| **M6** | The admin page: cars, tokens and sessions, behind Google sign-in | ✅ (`plans/COMPLETED.md`) |
| **M7** | Past sessions on the site, and a session being driven, whole | ✅ (`plans/COMPLETED.md`) |
| **M8** | The car's page as a dashboard: one fixed layout, updating live | ✅ (`plans/COMPLETED.md`) |
| **M9** | Tokens and passcodes readable on the admin page | **tabled** (Sam): [`plans/M9-READABLE-SECRETS.md`](plans/M9-READABLE-SECRETS.md) |
| **M10** | The G-meter and map shown before their data | ✅ (`plans/COMPLETED.md`) |
| **M11** | What the first drive found: the charging gauge, tablet and test-data sessions, icons, the tablet's clock | ✅ (`plans/COMPLETED.md`) |
| **M12** | Courses drawn on the website, and sent down to the tablet, which times on them | ✅ (`plans/COMPLETED.md`) |
| **M13** | Re-timing the tablet's own fixes when a line moves | ✅ (`plans/COMPLETED.md`) |
| **M14** | Drivers and events: practice parts and a race, sessions joined by the server's time, who drove, practice results | ✅ (`plans/COMPLETED.md`) |
| **M15** | The race: one timeline through driver changes and restarts, stops, stints, race results, driver pages | ✅ (`plans/COMPLETED.md`) |
| **M16** | Results worth reading: every lap linked, theoretical best, sectors compared, consistency, the lap-time chart, two laps compared | ✅ (`plans/COMPLETED.md`) |
| **M17** | Live: the `timing` frame to the tablet, the car page's driver, stint and race, the event page counting the stint being driven | **planned**: [`plans/M17-LIVE.md`](plans/M17-LIVE.md) |

**How a milestone runs**, as in the app:
- A plan in `docs/plans/` sketches every step.
- Each step is **validated against the code** just before it is built, and the
  validation is written into the plan.
- The step is built, then marked ✅ with what was actually done.
- When the milestone closes, its lasting content moves to
  `docs/plans/COMPLETED.md`, `DECISIONS.md` or `JOURNAL.md`, and the plan is
  deleted. Git keeps the rest.

Deployed: https://badnewsbears.live for people, https://obd2-backend-qeppiy7nzq-uk.a.run.app for tablets. Project
`obd2-dashboard-backend`, region `us-east4`. Deploys go through `cloudbuild.yaml`
(JOURNAL 2026-09-26).

---

## Architecture

```
 TABLET (per car)                       CLOUD RUN — Ktor, one instance            BROWSERS
┌────────────────────────┐            ┌───────────────────────────────┐       ┌──────────────────┐
│ SignalBus ──► live     │ WebSocket  │ /v1/live                      │       │ /  landing:      │
│   coalesced, 200 ms ───┼───────────►│   ─► Live hub (in memory) ────┼─ SSE ►│    cars, who's   │
│                        │◄─ messages─┤      latest per signal,       │       │    live          │
│ Message widget ◄───────┤  received/ │      last few minutes         │       │ /cars/{slug}:    │
│                        │  displayed►│                               │       │    gauges, charts│
│ Session log (JSONL) ──►│  HTTPS     │ /v1/sessions/{id}             │       │    message panel │
│   shipper, by line     ├───────────►│   ─► Archive writer           │       └───────┬──────────┘
│   index, every 2 min   │◄── acks ───┤                               │               │
└────────────────────────┘            │ Message service ◄─────────────┼── POST ───────┘
                                      │   (crew passcode)             │  (passcode cookie)
                                      └───────┬──────────────┬────────┘
                                              ▼              ▼
                                   Firestore: cars,     Cloud Storage: session
                                   sessions, messages   lines, byte for byte
```

- **Two lanes** (contract §2):
  - **Live** is lossy and never replayed. It is for watching.
  - **Archive** is complete, ordered by line index, stored byte for byte, and
    authoritative. It is the captured session.
- The lanes are merged by `(sessionId, seq)` (contract §7).
- **A token identifies a car** (contract §8, decision 10). A car is a page on the
  site, not a VIN.
- **One instance, with the live hub in memory** (decision 7). Several cars fit.

## Decisions this plan rests on

| # | Decision |
| --- | --- |
| 7 | One Cloud Run instance, with the live hub in memory behind an interface |
| 8 | Browsers receive over SSE and send commands as plain POSTs |
| 9 | Session data in Cloud Storage; the index, cars and messages in Firestore |
| 10 | Cars are registered, and each has its own token and crew passcode |
| 11 | Viewing is public; sending messages needs the car's crew passcode |
| 12 | Messages are display-only: queued → received → displayed → cleared \| expired |
| 13 | Website: TypeScript + Svelte + uPlot, built statically and served by Ktor |
| 15 | The telemetry contract v1 is the protocol |
| 16 | Sessions are kept indefinitely; the VIN is never shown |

## URLs

| Path | What | Milestone |
| --- | --- | --- |
| `GET /health` | Liveness | ✅ |
| `GET /api/cars` | Landing page data: slug, name and live state, never secrets | ✅ M2, M4 |
| `GET /v1/whoami` | Which car a token belongs to (backend-only diagnostic, not in the contract) | ✅ M2 |
| `PUT /v1/sessions/{id}`, `POST …/chunks`, `POST …/complete` | Archive lane (contract §6) | ✅ M3 |
| `GET /v1/live` | Live lane WebSocket (contract §5) | ✅ M4 |
| `/`, `/cars/{slug}` | Landing and live car page | ✅ M4 |
| `GET /api/cars/{slug}/live` | SSE: snapshot, then session, records and status; crew streams also messages | ✅ M4, M5 |
| `POST`, `DELETE /api/cars/{slug}/login`; `GET …/crew` | Crew passcode → signed cookie for that car; log out; am I crew | ✅ M5 |
| `POST`, `GET /api/cars/{slug}/messages`, `DELETE …/{id}` | Send, list recent, and clear (crew only) | ✅ M5 |
| `/admin`, `/api/admin/*` | The admin page and its API (Google sign-in, allowlist) | ✅ M6 |
| `/cars/{slug}/sessions`, `…/{id}` | Past sessions: the list in drives, and each session's page | ✅ M7 |
| `GET /api/cars/{slug}/sessions`, `/api/sessions/{id}`, `…/series` | Their data, public, never the VIN; the exact log from `/api/admin/sessions/{id}/download` | ✅ M7 |

---

## Milestones after M7

What M4 builds on:
- M2's `CarRegistry.principalFor`, which the socket uses after the upgrade;
- M3's `SessionIndex`, which already allows a session the live lane creates
  before its `PUT` (`ackedThrough = −1`, no line 0).

**M4 needs a real v3 log** for signal units. The replay's are empty.

**The replay tool is what lets M3–M5 be built without a car.** It is a CLI that
plays session logs into the server as a tablet would, through both lanes, with
switches for dropping the connection and simulating a dead zone. Several copies
at once, on different tokens, is the multi-car test. The app's `test-data/`
logs are format v1, so the tool upgrades them to v3 (adding `id`, `device`,
`wall`, a zeroed `session.seq`). Once the tablet writes real v3 logs, ask for
one to be committed as a fixture.

### M7 — Past sessions

- A car's session list, grouped into drives by time.
- A session page with full-length charts read from Cloud Storage, and download
  as `.jsonl.gz`.
- Gaps drawn as gaps, never interpolated.
- Live rows replaced by archive rows once the archive covers them (contract §7).

### M8 — Dashboards

One fixed layout for every car (Sam, 2026-09-27), updating in real time:
gauges, numbers, bars, the G-meter, the map, laps, status. Not configurable,
and not a mirror of the tablet, so neither storage nor a contract change.
Done (decisions 28 and 29, `plans/COMPLETED.md`).

### Next: race logging (M12–M17)

Courses drawn on the website with start/finish, sectors and pit lines, sent
down to the tablet, **which times laps and sectors: its numbers are the
results** (Sam); the server keeps the books (drivers, practice and an
endurance race tied together from many sessions, results) and re-times the
tablet's own fixes when a line moves. The overall plan is
[`plans/RACE-LOGGING.md`](plans/RACE-LOGGING.md). It needs a contract change,
proposed in [`proposals/COURSES-AND-TIMING-TO-THE-TABLET.md`](proposals/COURSES-AND-TIMING-TO-THE-TABLET.md)
(revision 2, answering the tablet's §22).

### Other candidates

**The first real drive** was 2026-09-27 (`JOURNAL.md`); what it found for the
site was M11.

Candidates, for Sam to choose from: a per-car form on the admin page for the
dashboard's slots and ranges (decision 28's way on); comparing laps; naming
sessions; crew messages shown beside a session.

---

## The tablet's half

The tablet's build order is in contract §10:
1. format v3;
2. the G-meter on the bus;
3. the archive shipper;
4. the live lane;
5. the Telemetry settings page and per-car tokens;
6. the message widget;
7. GPS.

The server is ready for each piece before the tablet needs it. M3 lands before
the shipper, M4 before the live lane, and M5 before the widget. **All three are
ready**; the widget (the app's M34.5) needs a real car registered with a passcode.

**Hardware.** The tablet has no cellular (measured 2026-09-10). A phone hotspot
is enough to test in a car.

## Open questions

None right now; each milestone's plan asks its own.
