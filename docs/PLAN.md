# Plan

The backend for [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard).
It has two jobs:

1. **Capture and log** what the tablet sends. At first that is finished sessions
   uploaded over Wi-Fi; once the car has a cell connection, it is a live stream.
2. **Serve a website** that shows the car's data in real time.

## Status

| Milestone | | |
| --- | --- | --- |
| **M0** | Skeleton: Ktor server, health check, tests, Dockerfile | ✅ |
| **M1** | Deployed to Cloud Run; tablet key (decisions 4, 5) | in progress |
| — | Everything after M1 | not planned yet |

## Carried over from the app

These were written in the app's `docs/PLAN.md` when streaming was moved out to
this project (2026-09-10). They still apply:

> **The tablet has no cellular** — measured 2026-09-10: `gsm.sim.state` is
> `ABSENT`, and Wi-Fi is the only link. Cell service is planned but not here yet.
>
> **The session log is already most of an upload.** App milestone M8 writes
> versioned JSONL to disk, and M8.3 left a `LogUploader` seam for exactly this.

In short: post-session upload can be built today. The live stream needs hardware
the car does not have yet.

## M1 — Deploy

- **Project** `obd2-dashboard-backend`, **region** `us-east4` (Northern
  Virginia: there is no New England region, and this is the nearest in the US).
- Service `obd2-backend`, running as its own service account that can read only
  the tablet key. Scales to zero, max 2 instances.
- `scripts/gcp-setup.sh` (one-time, can be re-run), `scripts/deploy.sh`,
  `scripts/tablet-key.sh`.
- `GET /tablet/ping` requires the key. It is there so the app's settings screen
  can check a pasted key. **App side, not done:** a settings field for the key
  and the server URL, and a "test connection" button that calls the ping.

## Later — not yet planned

- Session upload endpoint, and where sessions are stored (Cloud Storage is the
  obvious choice).
- The shared wire types between app and server: a published library, a git
  submodule, or a copy — decide when the first type is shared.
- Live ingest and fan-out to browsers (WebSocket or SSE). See decision 2 for the
  Cloud Run limits this has to work within.
- Website frontend (decision 3).
