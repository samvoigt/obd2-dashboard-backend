# M4 — The live lane and the first website

**Status: drafted 2026-09-26, written against the code as it stands after M3,
the telemetry contract v1 at app commit `4644ab7` (§5.1–5.3 and §7), and the app's own live plan (its
M34, `docs/plans/TELEMETRY-LIVE.md`, which is the app's *next* milestone).
Nothing is built.**

**What M4 delivers:**
- **The server side of contract §5.1–5.3.** Tablets stream on a WebSocket at
  `/v1/live`, and the server holds each car's live state in memory.
- **A first website.** A landing page lists the cars and which are live, and a
  car page shows **how fresh the data is first**, then every signal, and a
  chart of the last few minutes.
- **The replay tool learns the live lane**, so all of this is built and watched
  without a car.

Crew messages are **M5**, but the app's M34.5 checks both lanes together, so M5
follows M4 straight away (see "Order" below). Where this plan and the contract
disagree, the contract wins.

---

## What exists, and what it means for this

- **Auth (M2).** `CarRegistry.principalFor(token)` turns a token into a car and
  was kept for exactly this. The socket must authenticate *after* accepting the
  upgrade, so a refusal can be the contract's `error`/`auth` frame (§5.2).
- **The archive (M3).** `SessionIndex.create` already allows a session the
  **live lane creates before its `PUT`**: `ackedThrough = −1`, no line 0, no
  header (the Firestore mapping stores a header only alongside `line0Sha256`).
  `ArchiveService.open` then fills in line 0.
- **The app now archives only sessions streamed live** (contract §10, changed
  2026-09-26 in app commit `4644ab7`). So the live `session` will usually
  **create** the session, and the `PUT` follows.
- **The app has the server's address built in**, as
  `TELEMETRY_SERVER = "https://obd2-backend-qeppiy7nzq-uk.a.run.app"` (app,
  `TelemetrySettings.kt`). **The service's name, region and project are
  therefore permanent**: recreating or renaming it would strand every tablet
  until an app update.
- **The contract changed after the old pin**, in §10 only. That section is
  orientation, not wire format, and the change is the two facts above. No
  message, field or rule of §5–§8 moved. **Sam re-pinned it to `4644ab7` on
  2026-09-26** (decision 15, `PROTOCOL.md`).
- **Cloud Run as deployed would break the live lane in two ways** (checked
  2026-09-26 on the running service):
  - **`timeoutSeconds: 300`**: every WebSocket and browser stream would be cut
    at 5 minutes, not the 55 the contract promises;
  - **`containerConcurrency: 80`**: on one instance, the 81st open connection
    (tablets plus browser tabs) would be refused.

  Deploying needs `--timeout 3600 --concurrency 1000 --max-instances 1`
  (decision 7).
- **What the app will send** (its M34 plan):
  - the socket connects **whenever STREAM is on, even with no car session
    running**, so a parked car can receive messages. The server must accept a
    socket that sends `hello` and never a `session`;
  - batches every 200 ms, coalesced to the latest sample per signal;
  - `snapshot` after every connect (§5.2, with §14.1's contents);
  - OkHttp, with `permessage-deflate`.
- **Node 24.21.0** is installed (LTS; Homebrew `node@24`, linked). Current
  versions: Svelte 5.57, Vite 8.3, `@sveltejs/vite-plugin-svelte` 7.3,
  uPlot 1.6.32, TypeScript 7.0, Vitest 5.0. Ktor's `websockets` and `sse` are
  3.6.0, matching the server.
- **No real v3 log exists here yet**, so signal **units** are unknown to the
  replay (M3). The one source this side can read is the contract's appendix,
  which lists every signal's unit.

## Settled (contract v1, and Sam)

1. **One socket per tablet, authenticated by the car's token; the newest
   connection wins** and the older one gets `superseded` (§5.2, §8).
2. **Clean closes**: `1001` at about 55 minutes, `1012` on shutdown (§5.3,
   §14.5).
3. **The server refuses only an unknown subprotocol** (`unsupported_version`).
   It accepts `hello.v` ≥ 3 and keeps what it does not understand (§5.2).
4. **Live frames are at most 64 KB** (§5.2).
5. **Viewing is public; the VIN is never shown** (decisions 11, 16). GPS may be
   shown publicly (Sam).
6. **Freshness is shown first** on a car's page (M4's own line in `PLAN.md`).

## Decided here, not asked (say if any is wrong)

- **The VIN is stripped before anything reaches a browser.** The live `session`
  record carries it, and the server removes `vin` from that record, and from
  anything derived from it, before it enters the hub. A test searches every SSE
  byte for it.
- **Browsers get a snapshot on every connect, never a replay.** A new or
  reconnecting browser receives:
  - the car's status;
  - the session's header (no VIN) and signals list;
  - the latest value of each signal;
  - **the last 5 minutes** of samples, for the chart;

  then live batches. No `Last-Event-ID` resume: the live lane is provisional
  anyway (§5.3), and a snapshot is always correct. This refines decision 8.
- **A rotated or removed token closes its socket within 30 s.** Each open socket
  re-checks its token every 30 s through `principalFor`, and gets `error`/`auth`
  if refused. It is one Firestore read per socket per 30 s. A Firestore
  listener was considered and dropped: on request-based Cloud Run the CPU is
  throttled outside requests, and a re-check inside the open request needs no
  background work.
- **The `messages` sync is sent in M4, empty.** §5.4 has the server send
  `messages` after every `hello`, and the tablet takes down anything not in it.
  In M4 there are no messages, so `{"t":"messages","active":[]}` is exactly
  right, and M5 fills it. `received`/`displayed` are accepted and ignored until
  M5.
- **A live `session` creates the index entry** if there is none
  (`SessionIndex.create` with `ackedThrough = −1`, no header). If it belongs to
  another car, the frame gets `bad_message` (non-fatal) and that session is
  ignored. There is no `wrong_car` on the live lane.
- **History is 5 minutes per car, capped at 100,000 records.** At the
  contract's worst case, about 100 records a second, that is 30,000 records,
  a few MB per car. Several cars fit on one instance (decision 7).
- **Freshness states, from the server's clock:**
  - **live**: a batch within 2 s;
  - **stale**: connected, but no batch for over 2 s; shown as "last data 40 s
    ago";
  - **connected, no session**: `hello` but no `session`, as a parked car in
    the pits;
  - **offline**: no socket.

  The server sends status changes, and the page counts the seconds itself.
- **Frames:**
  - Ktor's `maxFrameSize` is 256 KB, and the server itself refuses over 64 KB
    with `bad_message`, not by closing, so an oversize frame costs one frame,
    not the socket;
  - the server pings every 15 s and gives up after 30 s of silence, matching
    the tablet (§5.1);
  - `permessage-deflate` is installed, and optional: the JDK client the replay
    uses does not offer it.
- **The website:**
  - **Svelte 5 + TypeScript + Vite**, in `web/`; **uPlot** for the chart;
  - built into the server jar (decision 13). Gradle builds `web/` with npm
    into the server's resources, and the Dockerfile gains a `node:24` stage;
  - dark by default (a pit wall at night), usable on a phone;
  - Vitest for the page's logic. What it looks like is checked in a browser
    against the replay.
- **The landing page's data grows a live state.** `GET /api/cars` becomes
  `[{slug, name, state}]`. M2's test pinned `/api/cars` to exactly
  `{slug, name}` so that adding a field would be a decision, and **this is that
  decision**: the test changes to `{slug, name, state}`. `/v1/whoami` keeps
  `PublicCar`.
- **No map in M4.** GPS is planned on the tablet but not built (contract §4.3),
  so there is nothing to draw or test. A `position` sample is shown as numbers.
  The map comes when GPS does.
- **The replay fills units from the contract's appendix** (`--units-from` the
  app's `TELEMETRY-CONTRACT.md`) when it upgrades an old log, so the site can be
  seen with real units before a real v3 log exists. Signals missing from the
  appendix keep an empty unit.
- **A new pure module, `:live`**, for frames, per-car state and the hub, as
  `:archive` is for the archive: the rules and most tests live there.

## Order, with the app

The app's M34 is next on its side. Its M34.1–M34.4 test against a fake
server; **its M34.5 needs this server live, and M5's messages too**. So M4
then M5, back to back, with M4.8's deploy early enough for the app to point
at it.

---

## The steps

Each is validated against the code again before it is built, and after each,
the *next* step's plan is checked against what was actually built (Sam,
2026-09-26).

### M4.1 — Frames and a car's live state  `opus`

`:live`, pure Kotlin:
- **Tablet frames parsed:**
  - `hello` (`v`, `device`, `app`, `wall`);
  - `session` (the record; the id checked as a UUID; `v` ≥ 3);
  - `snapshot`, `batch`, `end`;
  - `received`, `displayed`, **parsed and ignored until M5**;
  - unknown `t` → ignored (§5.2);
  - malformed → `bad_message`.

  Records inside stay JSON objects. The server reads `type`, `seq`, `at`,
  `wall`, `signal` and nothing else.
- **Server frames encoded:** `welcome {serverWall}`, `messages {active: []}`,
  `error {code, message, fatal}`.
- **`CarLive`**, one car's state:
  - the session header **with `vin` removed**, and its signals list, replaced
    by any `signals` record;
  - the latest sample per signal;
  - `stopped` signals, the latest `fault`, and a gap count;
  - a 5-minute history ring (by the server's receive time, capped);
  - `lastDataAt`.

  `apply(frame)` returns the updates to forward.
- **`Freshness.of(now, state)`** → live / stale(seconds) / no session / offline.

**Done when:**
- Tests cover:
  - each frame kind and each malformed shape;
  - a `session` with a VIN comes out without one, in every derived value;
  - a `snapshot` replaces the latest values and `stopped`;
  - a `signals` record replaces the list;
  - history drops what is older than 5 minutes, and anything beyond the cap;
  - each freshness boundary (2 s exactly, just over).
- Mutations killed.

### M4.2 — The hub  `opus`

`:live`:
- a `LiveHub` interface (decision 7: replaceable by Pub/Sub or Redis), and
  `InMemoryLiveHub`;
- **tablets:** `attach(car, socket)` supersedes any earlier socket for that car
  (it is told why, so it can send `superseded`); `detach`;
- **browsers:** `subscribe(car)` returns a `Flow` that starts with the snapshot
  (status, header, signals, latest, history), then updates;
- **a slow browser is never allowed to slow the tablet.** Its buffer is
  bounded; if it overflows, that browser is sent a fresh snapshot and carries
  on;
- `closeAll(code)` for shutdown;
- a lock per car; no global lock.

**Done when:**
- Tests on virtual time cover:
  - superseding;
  - a subscriber that joins mid-session gets the snapshot, then updates, in
    order;
  - a stalled subscriber overflows into a resnapshot while another keeps up,
    and the tablet side never waits;
  - status changes on connect, `session`, silence and disconnect;
  - two cars never see each other's data;
  - `closeAll`.
- Mutations killed.

### M4.3 — The tablet's socket  `opus`

`:server`, `GET /v1/live`:
- Ktor `WebSockets` (deflate extension, `pingPeriod` 15 s, timeout 30 s,
  `maxFrameSize` 256 KB); the subprotocol `obd2-telemetry.v1` negotiated. A
  socket without it gets `error`/`unsupported_version`, fatal.
- **Authenticate after the upgrade**: no or bad token → `error`/`auth`, fatal,
  close `1008`.
- `hello` → `welcome` and `messages []`. Frames before `hello` →
  `bad_message`.
- A `session` creates the index entry if needed; another car's session →
  `bad_message`.
- `snapshot`, `batch` and `end` go to the hub.
- **Closes:**
  - superseded → `error`/`superseded`, close `1008`;
  - **`1001` at 55 minutes** (configurable, so tests use seconds);
  - **`1012` on shutdown** (Ktor's `ApplicationStopping` → `closeAll(1012)`);
  - **the token re-checked every 30 s** (configurable), and a rotated one gets
    `auth`.

**Done when:** tests with Ktor's WebSocket test client cover:
- the connect sequence;
- no token, a bad token, and no subprotocol, each with its exact frame and
  close code;
- a second socket superseding the first;
- the age limit closing with `1001`;
- a server stop closing with `1012`;
- rotation closing with `auth`;
- a 70 KB frame → `bad_message`, with the socket still up;
- `hello` with no `session` staying connected ("connected, no session");
- a live-created session, then its `PUT` through M3's route.

Mutations killed.

### M4.4 — The browser's stream  `opus`

`:server`:
- **`GET /api/cars/{slug}/live`, SSE, public:**
  - on connect, a `snapshot` event; then `batch`, `status` and `session`
    events;
  - a comment every 15 s, so proxies keep it open;
  - an unknown car → `404`.
- **`GET /api/cars`** → `[{slug, name, state}]`; the M2 test becomes this shape
  (see above).

**Done when:**
- Tests read the SSE stream through `testApplication`:
  - snapshot, then batches, in order;
  - a second browser mid-session gets the same state;
  - the status sequence;
  - **a session record with a VIN streamed through the tablet socket, with
    every SSE byte searched and no VIN found**;
  - `/api/cars` states for an offline and a live car, and still no secret
    field.
- Mutations killed (above all: the VIN strip).

### M4.5 — The replay learns the live lane  `opus`

`:replay`:
- **`--live`**: the JDK WebSocket client, the subprotocol, the token header.
  It sends:
  - `hello`, then `session`, then a `snapshot` built from the log up to "now";
  - batches **every 200 ms of log time, coalesced as the tablet does** (the
    latest sample per signal, other records in full);
  - `end` at the log's end.

  `--speed` paces it: 1 is real time.
- **Reactions:**
  - reconnects **at once** after a clean `1001`/`1012`, unless one came in the
    last 10 s;
  - otherwise backs off 1, 2, 5, 10 s;
  - on every reconnect, `hello`, `session`, `snapshot` again;
  - `auth` or `superseded` → stops and says so.
- **Faults:** `--drop-socket-every N` seconds.
- **Both lanes at once**, as the app does (`--live` with the archive upload
  running beside it).
- **`--units-from <contract.md>`**: units from the appendix when upgrading.

**Done when:**
- Tests against the real server module, in-process, cover:
  - a short log replayed live at high speed: the hub's latest values equal the
    log's last sample per signal, and the history holds what 5 minutes allow;
  - a dropped socket reconnects and resnapshots;
  - `1001` from the age limit reconnects at once, and a second within 10 s backs
    off;
  - superseded stops;
  - units are filled from a copy of the appendix.
- Mutations killed.

### M4.6 — The website's frame  `sonnet`

- **`web/`**: Vite, Svelte 5, TypeScript, uPlot, Vitest; `npm ci` from a
  committed lockfile.
- **Built into the server:**
  - Gradle `:server:processResources` depends on a `buildWeb` task (`npm ci`,
    `npm run build`) that puts `web/dist` on the classpath as `web/`;
    `-PskipWeb` for Kotlin-only work;
  - Ktor serves it, with a fallback to `index.html` for `/` and `/cars/*`;
  - `/api` and `/v1` are never shadowed.
- **The Dockerfile gains a `node:24` stage** that builds `web/dist`; the JDK
  stage copies it in and skips `buildWeb`.
- **The landing page**: cars and their state from `/api/cars`, refreshed every
  10 s; each links to `/cars/{slug}`.
- **`vite dev` proxies `/api` and `/v1`** to a local server, for work on the
  page.

**Done when:**
- `./gradlew :server:buildFatJar` includes the site.
- A route test gets `index.html` for `/` and `/cars/x`, and JSON for
  `/api/cars`.
- Vitest runs one test.
- The Docker build is not run locally (there is no Docker); M4.8 proves it on
  Cloud Build.

### M4.7 — A car's live page  `opus`

- **The SSE client:** reconnects with backoff, and takes each snapshot as the
  whole truth.
- **The freshness banner first and largest**: live / "last data 40 s ago" /
  "connected, no session" / offline, counted locally each second from the
  server's status.
- **The session line:** started, app, device. **No VIN.**
- **A tile per signal, by kind:**
  - `number`: the value and its unit;
  - `state`: the text, the code on hover;
  - `flag`: on or off;
  - `flags`: those set;
  - `position`: latitude and longitude as numbers;
  - `stopped` signals greyed, with their reason.
- **One uPlot chart:** the last 5 minutes, one or two chosen signals
  (`engine.rpm` and `vehicle.speed` by default when present), a second axis
  when their units differ, and `gap` records shown as breaks, never bridged.
- **A pit-wall theme**: dark, large type, fine on a phone.

**Done when:**
- Vitest covers the pure parts: applying a snapshot and batches, freshness
  arithmetic, series for uPlot with gaps as nulls, and formatting per kind and
  unit.
- **Looked at in a browser** (Claude in Chrome) against a local server with the
  replay running live at real speed, with screenshots of:
  - live;
  - stale (replay paused);
  - offline (replay stopped);
  - a phone-width view.

### M4.8 — Deploy, and watch it live  `sonnet`

1. **`deploy.sh`:** `--max-instances 1`, `--timeout 3600`,
   `--concurrency 1000`. Check them on the service afterwards. Deploy.
2. **A throwaway car:**
   - the replay live at real speed against the **deployed** service, with the
     archive lane beside it;
   - the site open on the deployed URL, and screenshots;
   - `--drop-socket-every 60`, reconnecting cleanly;
   - **a deploy while it runs** → `1012`, and the replay reconnecting at once.
3. **After the replay:** the archived session completes and matches byte for
   byte (M3), and was created by the live lane first.
4. **Clean up:** the session, then the car.
5. **Tell Sam** the live lane is up for the app's M34.

**Done when:** steps 1–4 pass against the deployed service, with screenshots
kept for Sam.

### M4.9 — Record it

- **Decisions:**
  - 19: the live state, its snapshot-not-replay browser stream, and the VIN
    strip;
  - 20: the Cloud Run settings the live lane needs;
  - decision 8 amended.
- `COMPLETED.md`, `JOURNAL.md`, `PLAN.md`, README and CLAUDE.md. CLAUDE.md gets
  **"never rename or recreate the service"**.
- This plan deleted, and pushed.

---

## Not in M4

- **Crew messages** (M5): the `messages` sync is empty until then, and
  `received`/`displayed` are ignored.
- **A map** (when GPS exists).
- **Past sessions on the site, and merging live with archive** (M6).
- **Configurable dashboards** (M7).
