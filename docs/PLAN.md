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
| **M3** | Archive lane (contract §6), and the replay tool | next |
| **M4** | Live lane (contract §5.1–5.3), fan-out, first website | |
| **M5** | Crew messages (contract §5.4) | |
| **M6** | Past sessions on the site | |
| **M7** | Dashboards: crew views and mirrored tablet layouts | |

**How a milestone runs**, as in the app:
- A plan in `docs/plans/` sketches every step.
- Each step is **validated against the code** just before it is built, and the
  validation is written into the plan.
- The step is built, then marked ✅ with what was actually done.
- When the milestone closes, its lasting content moves to
  `docs/plans/COMPLETED.md`, `DECISIONS.md` or `JOURNAL.md`, and the plan is
  deleted. Git keeps the rest.

Deployed: https://obd2-backend-qeppiy7nzq-uk.a.run.app. Project
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
| `GET /api/cars` | Landing page data: slug and name, never secrets | ✅ M2 |
| `GET /v1/whoami` | Which car a token belongs to (backend-only diagnostic, not in the contract) | ✅ M2 |
| `PUT /v1/sessions/{id}`, `POST …/chunks`, `POST …/complete` | Archive lane (contract §6) | M3 |
| `GET /v1/live` | Live lane WebSocket (contract §5) | M4 |
| `/`, `/cars/{slug}` | Landing and live car page | M4 |
| `GET /api/cars/{slug}/live` | SSE: snapshot, live records, message states | M4 |
| `POST /api/cars/{slug}/login` | Crew passcode → signed cookie for that car | M5 |
| `POST /api/cars/{slug}/messages`, `DELETE …/{id}` | Send and clear (crew only) | M5 |
| `/cars/{slug}/sessions`, `…/{id}` | Past sessions | M6 |

---

## Milestones after M2

M3 depends on M2's `CarAuthProvider` (`CAR_AUTH`), `ApiError` (§14.2 bodies) and
`CarRegistry.principalFor`; M4's socket uses `principalFor` after the upgrade.

**The replay tool is what lets M3–M5 be built without a car.** It is a CLI that
plays session logs into the server as a tablet would, through both lanes, with
switches for dropping the connection and simulating a dead zone. Several copies
at once, on different tokens, is the multi-car test. The app's `test-data/`
logs are format v1, so the tool upgrades them to v3 (adding `id`, `device`,
`wall`, a zeroed `session.seq`). Once the tablet writes real v3 logs, ask for
one to be committed as a fixture.

### M3 — Archive lane (contract §6)

- `PUT /v1/sessions/{id}`: stores line 0 verbatim. Idempotent. A session is
  bound to the car whose token opened it; another car's token gets
  `400 wrong_car` (§14.2).
- `POST …/chunks`:
  - gzip body, ≤1 MB uncompressed (`413` over that);
  - idempotent by `(sessionId, index)`;
  - `409 {missingFrom}` for a chunk that starts past the end;
  - `ackedThrough` sent only once the lines are in Cloud Storage.
- `POST …/complete`: checks the record count and the sha256 over the stored
  lines, answers `409 {missingFrom}` if lines are missing, then joins the chunks
  into one `.jsonl.gz`.
- Firestore session index: car, id, started, device, app, line count, complete.
  The VIN is stored but never returned publicly.
- The replay tool (archive half).
- **Done when:** a replay with random dropped responses and repeated chunks
  stores a session whose sha256 matches the source, and one left unfinished
  completes on a later run.

### M4 — Live lane and first website (contract §5.1–5.3)

- `/v1/live`:
  - `hello`, `welcome`, `session`, `snapshot` (including the latest `signals`,
    `fault` and `stopped` records, §14.1), `batch`, `end`;
  - errors `auth`, `superseded`, `bad_message`, `unsupported_version`;
  - the newest socket wins;
  - clean closes: **1001 at about 55 min**, **1012 on SIGTERM**;
  - a Firestore listener on `cars` closes a socket whose token is rotated.
- The in-memory hub, and SSE per car.
- The website's first cut:
  - landing page;
  - car page with **freshness first** ("live" / "last data 40 s ago" /
    "offline"), big numbers, and one uPlot chart;
  - GPS on a map when a session has it.
- Node becomes a build dependency, with a Node stage in the Dockerfile.
- `max-instances=1`.

### M5 — Crew messages (contract §5.4)

- Per-car passcode login → signed, HTTP-only cookie, with rate-limited
  attempts.
- Presets (`pit`, `box`, `fuel`, `push`, `slow`) plus free text, 40 characters
  at most, each with a time-to-live.
- The full `messages` sync after every `hello`; `message`, `clear`.
- States **queued → received → displayed → cleared | expired**, shown live on
  the site. `received`/`displayed` for an unknown `id` are ignored.

### M6 — Past sessions

- A car's session list, grouped into drives by time.
- A session page with full-length charts read from Cloud Storage, and download
  as `.jsonl.gz`.
- Gaps drawn as gaps, never interpolated.
- Live rows replaced by archive rows once the archive covers them (contract §7).

### M7 — Dashboards

- **Crew views:** configurable pages stored per car.
- **Mirrored tablet layouts:** need a layout message, which is a contract v2.
  Reimplement the gauges in Svelte, or build the app's gauges for the web with
  Compose Multiplatform? Decide when M7 starts.

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
the shipper, M4 before the live lane, and M5 before the widget.

**Hardware.** The tablet has no cellular (measured 2026-09-10). A phone hotspot
is enough to test in a car.

## Open questions

- **Mirrored gauges (M7):** see above.
