# Plan

The backend for [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard).

**The primary use case:** a car's tablet streams its data live; a website shows
it as live dashboards *and* captures it as a session; and the crew can send the
car messages such as "Pit Now". There can be several cars at once, each with its
own page.

## Status

| Milestone | | |
| --- | --- | --- |
| **M0** | Skeleton: Ktor server, health check, tests, Dockerfile | ✅ |
| **M1** | Deployed to Cloud Run; one shared tablet key | ✅ server side |
| **M2** | Car registry, per-car keys and passcodes, admin CLI | next |
| **M3** | Session capture: the log lane, Cloud Storage, replay tool | |
| **M4** | Live: the live lane, fan-out, first website | |
| **M5** | Messages to the car | |
| **M6** | Past sessions on the site | |
| **M7** | Dashboards: crew views and mirrored tablet layouts | |

App-side work is listed under [The app's half](#the-apps-half). It belongs in the
app's `docs/PLAN.md` when it is scheduled there.

Deployed: https://obd2-backend-qeppiy7nzq-uk.a.run.app. Project
`obd2-dashboard-backend`, region `us-east4`. Deploys go through `cloudbuild.yaml`
(JOURNAL 2026-09-26).

---

## Architecture

```
 TABLET (per car)                     CLOUD RUN — Ktor, one instance           BROWSERS
┌──────────────────────┐            ┌─────────────────────────────┐        ┌──────────────────┐
│ SignalBus ─┬─► live ─┼─ WebSocket ┼─► Live hub (in memory) ─────┼─ SSE ─►│ /  landing:      │
│            │   lane  │            │   latest value per signal,  │        │    cars, who's   │
│            ▼         │            │   last few minutes          │        │    live          │
│ Session log (JSONL)  │            │                             │        │ /cars/{slug}:    │
│   └─► log lane ──────┼────────────┼─► Session writer ───────────┼──┐     │    gauges, charts│
│       (by line no.)  │ ◄── acks ──┤                             │  │     │    message panel │
│                      │            │                             │  │     └────────┬─────────┘
│ Message overlay ◄────┼── messages ┤◄─ Message service ◄─────────┼──┼── POST ──────┘
│                      │ ─displayed►│   (passcode-gated)          │  │   (crew passcode)
└──────────────────────┘            └──────────────┬──────────────┘  │
                                                   ▼                 ▼
                                      Firestore: cars, sessions,  Cloud Storage:
                                      messages, hashed keys       sessions/{car}/{id}/…jsonl.gz
```

**Everything rests on one idea: the stream is the session log** (decision 6).
The tablet already writes a durable, versioned JSONL log. It streams it over one
WebSocket with **two lanes**:

- **The live lane** carries readings straight off the bus, newest first, for
  display. It may drop records. After a dead zone it jumps to *now*; it never
  replays.
- **The log lane** carries lines of the session log *file*, in order, with the
  server acknowledging by line number. It never drops. After a dead zone it
  catches up, and after the session it finishes on paddock Wi-Fi. **This lane is
  the captured session**, and it replaces the post-session upload the app's M8
  planned for.

Two lanes rather than one because of the app's code (measured 2026-09-26):
`SessionLog` flushes the file only when it syncs, at most every 30 s by default
(`LogConfig.fsyncInterval`). Following the file alone would put up to 30 s of lag
on the live view. The file's `seq` can also have holes when the log config
filters kinds, so the file is tracked by line number, not by `seq`.

## Decisions this plan rests on

`docs/DECISIONS.md` 6–14. In short:

| # | Decision |
| --- | --- |
| 6 | The stream is the session log: a live lane for display, and a log lane acked by line number for capture |
| 7 | One Cloud Run instance, with the live hub in memory behind an interface. Several cars still fit |
| 8 | Browsers receive data over SSE, and send commands as plain POSTs |
| 9 | Session data goes to Cloud Storage in gzipped chunks; the index and messages go to Firestore |
| 10 | Cars are registered, and each has its own key and crew passcode (supersedes 4) |
| 11 | Viewing is public; sending messages needs the car's crew passcode (amends 5) |
| 12 | Messages are displayed, never acknowledged by the driver. The crew clears them, or they expire |
| 13 | Website: TypeScript + Svelte + uPlot, built statically and served by Ktor (refines 3) |
| 14 | The protocol is a document plus fixtures, not shared code. The server reads only the envelope |

## URLs

| Path | What |
| --- | --- |
| `/` | Landing: registered cars, and which ones are live |
| `/cars/{slug}` | A car's live page: dashboards, freshness, message panel |
| `/cars/{slug}/sessions`, `…/{id}` | Past sessions (M6) |
| `GET /api/cars` | The landing page's data |
| `GET /api/cars/{slug}/live` | SSE: snapshot, then live records and message states |
| `POST /api/cars/{slug}/login` | Crew passcode → signed cookie for that car |
| `POST /api/cars/{slug}/messages`, `DELETE …/{id}` | Send and clear (crew only) |
| `GET /tablet/ping` | Key check for the app's settings screen (exists) |
| `GET /tablet/stream` | The tablet's WebSocket (`docs/PROTOCOL.md`) |

A tablet never names its car. **The key identifies the car**, so a tablet cannot
write to another car's page by mistake or on purpose.

---

## Milestones

**The replay tool is what lets M3–M5 be built without a car.** It is a CLI that
plays a session log from the app's `test-data/sessions/` into the server at real
speed, pretending to be a tablet, with switches for dropping the connection and
simulating a dead zone. Several copies at once, on different keys, is the
multi-car test.

### M2 — Cars

- A Firestore `cars` collection: slug, display name, SHA-256 of the key,
  PBKDF2 hash of the passcode.
- An admin CLI (`:tools`) run with the owner's own Google credentials (so no
  admin endpoint exists): `add-car`, `rotate-key`, `set-passcode`, `list`. The
  key is printed once, at creation.
- Tablet auth looks up the car by key hash. The single `TABLET_API_KEY` and its
  secret are retired once the tablet has its per-car key.
- `GET /api/cars`.

### M3 — Session capture

- `docs/PROTOCOL.md` agreed: `open`/`resume`, `log` batches, `ack`, `close`/`closed`.
- `/tablet/stream`: the log lane only. Chunks go to Cloud Storage every ~30 s
  and are joined into one `.jsonl.gz` on `closed`. The session index goes to
  Firestore.
- The replay tool.
- **Done when:** a replay with random disconnects produces a stored session that
  decompresses to the source file byte for byte, and one never closed can be
  finished by reconnecting later.

### M4 — Live

- The live lane; the in-memory hub keeping each car's latest value per signal
  and the last few minutes; SSE per car.
- The website's first cut: landing page, and a car page with **freshness shown
  first** ("live" / "last data 40 s ago" / "offline"), big numbers for a few
  signals, and one uPlot chart.
- `max-instances=1` (decision 7).

### M5 — Messages

- Crew passcode login per car → signed, HTTP-only cookie.
- Presets ("Pit Now", "Box this lap", "Fuel", "Push", "Slow — yellow") plus free
  text; a time-to-live per message.
- States: **queued → delivered → cleared | expired**. *Delivered* means the tablet
  has put it on screen. The site shows each state live.
- One message on screen at a time; a new one replaces it.

### M6 — Past sessions

- A car's session list; a session page with full-length charts read from Cloud
  Storage; download as `.jsonl.gz`; gaps drawn as gaps, never interpolated.

### M7 — Dashboards

- **Crew views:** configurable pages of charts and numbers, stored per car in
  Firestore.
- **Mirrored tablet layouts:** the tablet sends its current dashboard profile.
  The site draws the same gauges. Depends on the app's profile format (app M12)
  and on how much gauge drawing can be shared. Decide when it starts.

---

## The app's half

| | | Needs |
| --- | --- | --- |
| **A1** | Settings: server URL and car key; a test-connection button calling `/tablet/ping` | M1 |
| **A2** | The streamer: a bus subscriber for the live lane and a cursor over the log file for the log lane, in the connection service; marks an archive sent on `closed`, through the existing `LogUploader` retention path | M3, M4 |
| **A3** | Message overlay: large, glanceable, shows the message's age, no tap to dismiss | M5 |
| **A4** | Publish the active dashboard layout | M7 |

**Hardware.** The tablet has no cellular (measured 2026-09-10). A phone hotspot is
enough to test A2 in a car; a proper link is a purchase to make before racing
with it.

## Open questions

- **Session identity.** The draft protocol uses the log file's name without its
  extensions, because an archive is renamed `.jsonl` → `.jsonl.gz` when it
  closes. Confirm that name is stable in the app before M3.
- **How long to keep sessions.** Nothing expires for now; storage is pennies a
  session.
- **Mirrored gauges (M7).** Reimplement them in Svelte, or move the app's gauges
  to Compose Multiplatform and build them for the web? Decide at M7, not before.
