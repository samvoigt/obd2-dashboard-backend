# Completed milestones

What each milestone built, and **the claims that have never met a tablet**.
Read before trusting anything the server says. A milestone's plan is deleted
when it closes; its lasting content is here, in `DECISIONS.md` or in
`JOURNAL.md`, and git has the rest.

---

## M0 — Skeleton  ✅

Ktor on Kotlin/JVM 17, one `:server` module, `/health` (not `/healthz`, which
Cloud Run's front end intercepts), a `Dockerfile`, and warnings failing the
build. Decisions 1–3.

## M1 — Deployed  ✅

Cloud Run in `us-east4`, as its own service account, built by Cloud Build
through `cloudbuild.yaml` (`CLOUD_LOGGING_ONLY` is required, JOURNAL
2026-09-26). One shared tablet key in Secret Manager. That key was superseded
in M2 (decision 4).

## M2 — Cars  ✅ 2026-09-26

Registered cars, each with its own tablet token and crew passcode; an admin
tool to manage them; token authentication for every `/v1/*` route to come; the
landing page's data. The shared key is gone.

- **`:registry`, pure Kotlin:**
  - `Slug`: permanent, `[a-z][a-z0-9-]`, 2–32 characters, with reserved words;
  - `Tokens`: `obd2_` plus 256 random bits, stored as SHA-256 with a
    4-character hint;
  - `Passcodes`: PBKDF2-HMAC-SHA256 at 600k, with the parameters in the stored
    string;
  - `CarStore`, `InMemoryCarStore`;
  - `CarRegistry`, which **never caches**, so a rotated or removed token fails
    on the very next request (contract §8).
- **`:registry-firestore`:** one document per car in `cars`, keyed by slug, in
  the `(default)` Native database in `us-east4`. Every "only if" is atomic on
  Firestore's side: `create()`, `update()`, and delete in a transaction (the
  Java client hides `Precondition.exists`).
- **`:tools` (`scripts/admin.sh`):** `add-car`, `rotate-token`, `set-passcode`,
  `rename`, `list`, `remove-car`, run as the owner, so the server has **no admin
  endpoint**.
  - A token is printed exactly once.
  - `list` shows only the hint.
  - A passcode is read without echo, or refused without a terminal.
- **`:server`:**
  - `CarAuthProvider`, a custom provider so a failure answers with the
    contract's §14.2 body;
  - `CarRegistry.principalFor(token)`, the one place a token becomes a car,
    kept for M4's socket;
  - `ApiError`, `PublicCar`;
  - `GET /v1/whoami` (a backend diagnostic, not in the contract);
  - `GET /api/cars` (exactly `{slug, name}`, pinned by a test);
  - a corrupt registry (two cars with one token hash) is an explicit `500`
    naming neither car.
- **Deploy:** `GCP_PROJECT` is passed as an environment variable.
  `--clear-secrets` is passed because **a deploy keeps any setting it does not
  mention**. The runtime account has `roles/datastore.user`.
- **Tests:** 65 across four modules. 13 mutations were killed by failing tests,
  each checked to be a test failure and not a compile error.
- **Verified live, 2026-09-26:**
  - the Firestore smoke test (15 checks);
  - the admin tool against the real project;
  - the deployed service with a throwaway car: `whoami` `200`, a bad token
    `401` with the body, `/api/cars` listing only slug and name,
    `/tablet/ping` `404`, and after removal an empty list and `401`.
  The `tablet-api-key` secret is deleted.

**Never met a tablet.** No real car is registered, and the app has no token
field yet (contract §10.5). Everything above has been exercised only by tests,
by `curl`, and by the admin tool.

**Left for later, on purpose:**
- passcode login and rate limiting (M5, the first place a passcode is checked);
- closing live sockets when a token is rotated (M4, with a Firestore listener);
- refusing `remove-car` while the car has sessions (M3).

## M3 — The archive lane  ✅ 2026-09-26

The server side of contract §6: `PUT /v1/sessions/{id}`, `POST …/chunks` and
`POST …/complete`. Every line is stored byte for byte and acknowledged only once
durable. A replay tool behaves as the tablet does, and admin commands see and
delete sessions. Decisions 17 and 18.

- **`:archive`, pure:**
  - `LineBlock` keeps the tablet's bytes;
  - `ChunkBody` inflates under a 1 MiB cap, enforced while inflating (a 1 GiB
    gzip bomb stops in milliseconds);
  - strict UTF-8 JSON-object checks, reading only the v3 session header, and
    keeping unknown types and fields;
  - `Trim.plan`, the contract's line hash;
  - `ArchiveService`: store then advance, with conditional index writes;
    assembly that refuses a corrupt index; the reset of decision 18;
    live-created sessions allowed for M4.
- **`:archive-gcp`:**
  - `GcsSegmentStore` stores gzip files; a writer that throws creates no
    object (proved on real Cloud Storage);
  - `FirestoreSessionIndex`: every conditional change is a transaction.

  Both use Google's `libraries-bom`, so Firestore and Storage agree on Guava
  and gRPC. The bucket is `obd2-dashboard-backend-sessions`: private,
  `us-east4`, uniform access, kept indefinitely, with a 7-day soft delete.
- **`:server`:**
  - the three routes: capped reads, gzip, and §14.2 bodies (a `409` carries
    both its shapes);
  - `503` with `Retry-After` for storage failures only;
  - `SESSIONS_BUCKET` required.
- **`:replay` (`scripts/replay.sh`):**
  - upgrades v1/v2 to v3 by rewriting line 0 only;
  - chunks by log time, lines and 1 MiB;
  - every §6.4 reaction, lost answers and duplicates on demand, positions saved
    and resumed;
  - the token from `OBD2_TOKEN` or a file, never an argument.
- **Admin:** `sessions` (no VIN), `session <id>` (the VIN, only there),
  `delete-session`; `remove-car` refuses while a car has sessions.
- **Tests:** 154 across seven modules, 89 of them new in M3. **46 mutations**
  were killed by failing tests (10 + 12 + 10 + 11 + 3 across M3.1–M3.6). The
  mutation runs found four weak spots, all fixed:
  - untested contiguity;
  - a redundant cleanup (removed, and its rule written into the interface);
  - a `409` handler whose mistake a later `409` hid;
  - server acks that were never exercised.
- **Verified live, 2026-09-26, on the deployed revision `00004`:**
  - two of the app's real sessions (33,091 and 42,477 lines) replayed through
    30% lost answers and 20% duplicates;
  - a third stopped at line 6000 and resumed in a separate run;
  - each download matched its source byte for byte (`cmp`), and lines after
    the header were identical to the app's own files;
  - `wrong_car` came back with the contract's exact body, `401` with no token,
    and re-`PUT` and re-`complete` were idempotent;
  - `remove-car` refused while sessions remained;
  - everything was deleted afterwards.

**Never met a tablet.** No session from the real app has been uploaded: the
tablet's shipper is its M33.6, and its first real upload is its M33.8. Every
v3 session record the server has seen was written by the replay tool or a test,
not by the app, and **signal units** in those records are empty (the replay
cannot know them). M4 needs a real v3 log for units.

**Left for later, on purpose:**
- the live lane, which may create a session before its `PUT` (M4);
- showing sessions on the site, and merging live rows with archive rows (M7);
- `max-instances` 1 (M4).

## M4 — The live lane and the first website  ✅ 2026-09-26

The server side of contract §5.1–5.3. Tablets stream on `/v1/live`, the
server holds each car's live state, and a website shows it. Decisions 19 and 20.

- **`:live`, pure:**
  - every §5.2 frame parsed; **`vin` removed from every record on arrival**;
  - `CarLive` (a car's state and freshness);
  - `InMemoryLiveHub` behind `LiveHub`: the newest socket supersedes; browsers
    get a snapshot then updates; a browser that falls behind is resnapshotted,
    never waited on; `closeAll` ends tablets and browsers.
- **`:server`:**
  - `/v1/live`: the subprotocol, or `unsupported_version`; auth after the
    upgrade; `welcome` and the empty `messages` sync; sessions announced to the
    archive before their `PUT`; `1001` at 55 min, `1012` on shutdown; tokens
    re-checked every 30 s;
  - `/api/cars/{slug}/live` (SSE) and `/api/cars` with each car's state;
  - `RevisionWatcher`, which **drains on deploy**;
  - the site from the jar, by explicit routes that never shadow `/api` or `/v1`;
  - `:server:devServer`, the real module in memory, for work on the site.
- **`web/`:** Svelte 5, Vite 8, TypeScript 5.9, uPlot.
  - The landing page lists cars and who is live.
  - A car's page puts **freshness first**, then the session (no VIN), trouble
    codes, a chart of two chosen signals (gaps never bridged, 24-hour times),
    and a tile per signal by kind.
  - The Dockerfile builds it in a `node:24` stage.
- **`:replay`:** `--live` streams as the tablet does, alongside the archive
  lane:
  - coalesced 200 ms batches; a snapshot on every connect;
  - §5.3 reconnects;
  - `--drop-socket-every`;
  - units from the contract's appendix;
  - pacing from the first record, not line 0.
- **Tests:** 215 Kotlin across eight modules (61 new) and 16 Vitest (new).
  **57 mutations killed**, and one found equivalent. The mutation runs found
  three weak tests, all tightened:
  - a departed browser never removed;
  - a reconnect's snapshot never exercised (now tested with a hub that forgets,
    as a restarted server does);
  - server acks versus the replay's own.
- **Verified live, 2026-09-26:**
  - revision `00005` with timeout 3600, concurrency 1000, max 1 instance;
  - the Docker build's Node stage on Cloud Build;
  - a live-only replay **created the session first** ("no record yet"), then
    the archive run completed it byte for byte;
  - the deployed site watched in Chrome with real logs at real speed: live,
    stale, offline, and phone width;
  - socket drops reconnecting;
  - **deploying mid-stream found the old-revision problem** (decision 20).
    After the fix, two deploys (`00008`, `00009`) with no forced drops each
    gave `reconnecting after close 1012, at once`, with the page back on the
    new revision within about 15 s;
  - everything deleted afterwards.

**Never met a tablet.** The app's live lane is its M34, not yet built. Every
live frame the server has seen came from the replay or a test. Units in those
sessions come from the contract's appendix, not from a real v3 log.

**Left for later, on purpose:**
- crew messages (M5): the `messages` sync is empty, and
  `received`/`displayed` are ignored;
- a map (when GPS exists);
- past sessions and merging live with archive (M7);
- configurable dashboards (M8).

## M5 — Crew messages  ✅ 2026-09-26

The server side of contract §5.4, and the crew's panel on the car page.
Decisions 21 and 22.

- **`:live`:** `Messages` and its rules (one active, forward-only states,
  expiry, 1–40 characters, the presets), `MessageStore` with an in-memory fake,
  and the `message`, `clear` and `messages` frames.
- **`:archive-gcp`:** `FirestoreMessageStore` (transactional `update`; the
  `recent` query needs a composite index, created by `gcp-setup.sh`).
- **`:server`:**
  - `/v1/live` answers every `hello` with the active set and takes `received`
    and `displayed`;
  - `CrewMessages` sends, clears, schedules expiry and tells crew browsers;
  - crew login (`CrewAuth`, `LoginLimiter`), `/login`, `/crew`, and the
    message API, all crew-only;
  - crew-only `messages` and `message` events on the browser stream;
  - the dev server sets a generated crew passcode (`server/build/dev-passcode`).
- **`web/`:** the message panel: passcode form and logout, preset buttons that
  send at once, free text with a count, a lifetime choice, the current message
  with its state and age, Clear, and the recent list.
- **`:replay`:** answers messages as the tablet's widget would (`--no-widget`
  stops at received) and logs each one.
- **`:tools`:** `set-passcode --passcode-file` (must be `chmod 600`), and
  `remove-car` deletes the car's messages.
- **Tests:** 260 Kotlin (45 new) and 23 Vitest (7 new). **54 mutations
  killed**, 2 found equivalent. The runs added a test with identical stored
  hashes (a cookie for one car must not pass for another) and two age tests on
  the page.
- **Verified live, 2026-09-26** (revisions `00010`, `00011`), with a throwaway
  car and a real drive replayed at real speed:
  - `CREW_COOKIE_KEY` mounted from the secret; the cookie `Secure` and
    scoped to the car's path;
  - through the API: queued → received → displayed in about a second; clear;
    a 1-minute message expired 15 ms after its time, and the replay took it down;
  - **a deploy while "PIT NOW" was displayed:** the replay reconnected after
    `1012`, the new revision's sync carried it, and it stayed displayed;
  - public streams, 2,724 events across the deploy, and the deployed page in
    Chrome while it was active: no trace of the message;
  - everything deleted afterwards. The page itself was checked against the dev
    server (M5.7).

**Never met a tablet.** The app's widget is its M34.5. Every report so far came
from the replay or a test.

**Left for Sam:** logging in to the crew panel on the deployed site, and a real
car registered with a passcode, for the app's M34.5.

