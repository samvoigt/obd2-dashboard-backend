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

## M6 — The admin page  ✅ 2026-09-26

`/admin`: cars, tokens, passcodes and sessions, behind Google sign-in limited to
an allowlist. Decision 25 (and decision 10 amended).

- **`:admin`**, a new pure module: `CarAdmin`, the rules both `admin.sh` and the
  page use (adding a car with a chosen token, removing a car, and when a session
  may be deleted).
- **`:server`:**
  - `GoogleIdentity` (Google's `TokenVerifier`, then our own checks, each
    refusal with its reason) and `Allowlist`;
  - `AdminAuth` (a 30-day signed cookie, re-checked against the allowlist on
    every request) and `isSameOrigin`;
  - `AdminConfig`: off by default, from the environment in production, and a
    dev sign-in only in the dev server;
  - the admin API: `config`, `login`, `me`, the cars routes, and the sessions
    routes, every change logged without secrets;
  - `/admin` served unframeable.
- **`:live`:** `CarStatus.sessionId`, the live session, for the page only.
- **`web/`:** `Admin.svelte` and `admin.ts`. Vite's proxy now keeps the page's
  `Host`, as production does.
- **`:tools`:** `delete-session` refuses an upload still going; `add-car
  --choose-token` and `remove-car` go through `CarAdmin`.
- **Setup:** the OAuth client ID in `env.sh` (public by nature); the allowlist
  in Secret Manager (`admin-emails`), since the repo is public.
- **Tests:** 304 Kotlin (44 new) and 38 Vitest (15 new). **79 mutations
  killed**, 4 found equivalent. Google's verification is tested offline against
  a key set of our own, served locally, so the real key-fetching code runs.
- **Verified live, 2026-09-26** (revisions `00013`–`00015`): the admin API
  closed without a sign-in and to a forged cookie, the dev sign-in refused,
  `/admin` unframeable, and Google's button shown. **Sam signed in** and added,
  re-tokened (with a chosen token), set a passcode on, and removed a throwaway
  car. The logs name him on each action and hold no secret. His one note (the
  session count read as the list) changed the page.
- **Found by looking, locally:** Vite's proxy rewrote `Host`, so every change
  was refused; a failed sign-in showed no error; a live session was refused as
  "uploading"; labels split into rows.

## M7 — Past sessions  ✅ 2026-09-26

Every session a car uploads, on the site: a list per car in drives, and a page
per session with full-length charts, laps, a map and what happened; and a
session being driven, whole, merged from both lanes. Decisions 26 and 27.

- **`:archive`:** `SessionReader` and `LineSplitter` (streaming), the summary
  types, `SeriesBuilder` (with `lastSeq`), `SegmentStore.readStream` and
  `readRaw`, `ArchiveService.read`, `summary`, `prepare`, `sessionsOf`,
  `session`; the summary in the Firestore record's mapping.
- **`:server`:** after `complete`, the session is prepared in the background;
  `SessionRoutes` (the list as drives, one session, the series); the admin
  download; `SessionStates` (shared with the admin page); `/cars/{slug}/sessions`
  pages.
- **`:live`:** nothing new beyond M6's `CarStatus.sessionId`.
- **`:replay`:** its v1-to-v3 upgrade gives every record a `wall`, as real v3
  has.
- **`web/`:** `SessionsPage`, `SessionPage`, `SessionMap` (Leaflet 1.9.4),
  `sessions.ts`, `sessionPage.ts`, `merge.ts`; the chart's zoom, range,
  cursor, markers and bands; the live page's "Whole session".
- **Dockerfile:** the JVM gets 75% of the container.
- **Tests:** 349 Kotlin (45 new) and 60 Vitest (22 new). **101
  mutations killed**, 2 found equivalent. Several survivors changed the code:
  gaps placed by `seq` rather than `wall`, numbers JSON can't hold written as
  breaks, a live session listed before its first chunk. Measured: a 3-hour
  synthetic race prepared in 1.5 s, peaking at 62 MiB of a 128 MiB heap.
- **Verified live, 2026-09-26** (revision `00016`, deployed with a drive
  streaming, which drained over as decision 20 says):
  - a session made before M7 got its summary and series on first view;
  - one made after was prepared before anyone looked;
  - the list, a session's page (chart, map on OpenStreetMap, laps, events) and
    the live page's "Whole session" in Chrome on badnewsbears.live;
  - no VIN in any public response; the `ETag` gives a `304`; the download is
    admin-only;
  - everything deleted afterwards.
- **Earlier, against the dev server:** a live session followed through two
  chunk uploads, the shading shrinking to what the archive didn't yet cover.

**Left for Sam:** the admin page's Download button (it needs his sign-in).
**Left for later:** comparing laps (Sam); naming sessions; showing crew
messages beside a session.

## M8 — The dashboard  ✅ 2026-09-27

The car's page as a dashboard: one fixed layout for every car, updating live,
in the Bad News Bears look across the whole site. Decisions 28 and 29.

- **The look (M8.1):** `app.css`'s roles, `theme.ts` for the canvases,
  `speedColor` on colour stops, `web/scripts/make_images.sh`, the logo on the
  landing page and the bear as the tab's icon.
- **The widgets (M8.2):** `units.ts` and `unitsState.svelte.ts`, `readout.ts`,
  `dashboard.ts` (slots, ranges and zones, `timings`, freshness, the G-meter,
  laps); `Gauge`, `Readout`, `Bar`, `Status`, `Faults`, `GMeter`,
  `LapsPanel`, `UnitsSwitch`; `SessionMap`'s `follow`; a dev-only preview at
  `/dev/widgets/{slug}`, left out of the build.
- **The page (M8.3):** the car's page rebuilt in the layout's order, with
  Sam's slots; the chart and the tiles convert units; laps from the archive
  and the live lane (`lapsFrom`).
- **Performance (M8.4):** `web/scripts/measure.mjs` (its own headless Chrome,
  4× slower CPU, phone-sized, a line a minute). The chart's data held as deep
  `$state` cost a quarter of every minute; raw, a whole 34-minute race held
  60 fps with no long tasks and no heap growth. The map redraws only when its
  trace changes.
- **Tests:** 99 Vitest (39 new); Kotlin unchanged. **Mutations:** 25 on the
  new logic (M8.2–M8.4), all killed but one found equivalent. The look's five
  guards each shown to fail when they should: a colour written into a
  component, `rgb()` in one, critical made to look like caution, unreadable
  text, a blue that isn't the logo's.
- **Verified live, 2026-09-27** (revision `00017`, deployed with the synthetic
  race streaming and uploading, which reconnected at once): on
  badnewsbears.live, every section, US units, and the page at 390 px with no
  sideways scroll; then everything deleted.
- **Found by looking**, all fixed: the logo muted by Display P3; two chart
  lines too alike; the preview hanging the browser (an effect rerunning
  itself, and deep proxies); the map drawing nothing before it had a view;
  the chart not converting units; a rarely read signal going stale; "Follow
  the car" stopping after a drag.

**Left for later:** a per-car form for slots and ranges (decision 28's way
on); bars, when Sam wants some; comparing laps.

## M10 — The G-meter and map shown before their data  ✅ 2026-09-27

A car's page always shows the G-meter ("No readings") and the map (the world,
"Waiting for GPS") until their first reading; laps still wait for a lap.
Decision 28 amended.

- **`web/`:** `CarPage` draws both always; `SessionMap`, following with no
  position, opens on the world (set before its move listener, so following
  stays on) and clears its trail if positions drain from the 5-minute
  history; the preview matches; the session map's caption says pink, not red.
- **Tests:** 99 Vitest, unchanged: no new logic to unit-test. Looked at in
  Chrome case by case (nothing streaming, a drive without GPS or G, the race
  arriving on an open page, the race stopping, one session after another, a
  past session, 390 px). **Mutation, by looking:** the first view set after
  the listener left the map on the world when the race came; caught.
- **Deployed**, revision `00019`; the Outback's page on badnewsbears.live
  shows both, empty, while it's offline.

**Left for later:** the map opening on the car's last known position.

## M11 — What the first drive found  ✅ 2026-09-27

Fixes from the first real drive (JOURNAL, 2026-09-27). Decisions 28 (amended)
and 30.

- **The charging gauge takes either voltage** (`control_module.voltage`, then
  `vehicle.system_voltage`): slots are lists, `slotSignal` picks the first the
  session declares.
- **Sessions say what they are**: `source` read into the header and the
  summary (version 2), listed, labelled "Tablet only" and "Test data"; test
  data is a drive of its own. The list keys drives by a session, since two
  can now start together (found by looking: the duplicate key blanked the
  list).
- **The small ones**: the series route answers `204` for a live session with
  nothing uploaded; the bear as `apple-touch-icon.png` (on the site's
  background) and `favicon.ico`, made by `make_images.sh`, served at the root.
- **The tablet's clock** measured by the live lane and flagged on the admin
  page past 2 minutes.
- **Tests:** Vitest 106 (7 new); Kotlin 11 new across `:archive`,
  `:archive-gcp`, `:live` and `:server`, including a session stored before
  M11 gaining its source on rebuild. **Mutations: 24, all killed** (one only
  after a test was added: snapshots counted toward the clock, harmless under
  the minimum except before any batch).
- **Verified live, 2026-09-27** (revision `00020`, deployed with a drive
  streaming, which reconnected at once): the Outback's seven sessions rebuilt
  in 2.5 s on first view, labelled, the test-data one alone; the icons served
  at the root. The throwaway car and its session deleted.

**Left for Sam:** "Add to Home Screen" on the iPhone; the tablet's clock
(automatic date and time), then the admin page's clock note should go;
ticking `fuel.system_1_status` as an extra on the tablet, for the fuel light.
**Left for the app:** the G-meter's offset at a cruise (A17), the live link's
second socket, the fake session's `protocol` (JOURNAL).

## M12 — Courses, and down to the tablet  ✅ 2026-09-27

The first milestone of race logging (`plans/RACE-LOGGING.md`). Decisions 31
and 32; contract §22, proposed here, agreed by Sam and the tablet side, and
confirmed here.

- **`:courses`** (pure): `Course`, `CourseRules` (every rule, every reason),
  `coursesEtag`, `CourseStore`; NHMS seeded from the app's file
  (`courses/seed/`, its `pit_line` the tablet's own coordinates).
- **Storage and the admin:** `FirestoreCourseStore` (versions under each
  course, a save a transaction); the admin courses API and
  `POST …/courses/check`; `admin.sh import-course`.
- **The editor** (`/admin/courses`): layouts drawn with Leaflet-Geoman (MIT),
  timing lines with two clicks, points moved one line at a time, sectors kept
  1…n, closed layouts kept closed, OpenStreetMap or USGS imagery, a session's
  route to trace, the rules as you draw, version history.
- **Public:** `/courses`, `/courses/{id}` and their API; a session's page
  links to its course.
- **Down to the tablet:** `hello.features`; `GET /v1/courses`; the `courses`
  frame after `hello` and on every save; the replay tool's `--courses`.
- **The tablet's laps with sectors:** the session page's lap table, a column
  per sector, the best of each (an in-lap's last and an out-lap's first
  never counting, §22.6), the course's and layout's names.
- **Tests:** Vitest 119 (13 new); Kotlin 28 new across `:courses`,
  `:archive-gcp`, `:server`, `:tools`, `:replay` (and `:live`'s hello test
  extended). **Mutations: 40, all killed but two shown equivalent**; tests
  were added after three survivors (the seed not re-tested when it changed, a
  closed tablet never forgotten, an arrow's direction).
- **Proven:** the real Firestore (`scripts/course-smoke.sh`); the editor in
  Chrome (NHMS edited through three versions, an invalid line refused, a new
  course traced over a replayed route); a replay taking courses end to end;
  **deployed as `00021`** with a drive streaming across it, NHMS imported,
  `/v1/courses` answering with the tablet's pit line, `304` and `401`.

**Owed:** Sam drawing a course on the live site; the tablet timing on a
downloaded course, when the app's half (its M44) lands.
**Left for later:** re-timing (M13); drivers and events (M14 on);
`import-course` telling connected tablets (they hear at their next `hello`).

## M13 — Re-timing  ✅ 2026-09-27

The second milestone of race logging. Decision 33 (decision 31 made exact);
contract §22.1, §22.3, §22.5, §22.6, §22.8.

- **`:timing`** (pure): `LapRule`, the tablet's `LapTimer` ported line by
  line (moves, forward crossings interpolated, arming at 150 m or a quarter
  of the layout, only the next sector, the pit line and its fallback);
  `SessionTrace` (a log's fixes on `fixAt`, else `at`, a backward one left
  out, its `lap` records, `wall − at`); `runs()` (§22.8);
  `Retiming.retime` (what stands, the 2 ms check) and `Retimer` (stored per
  run and course version, read back while current); `courseBounds`,
  `touches`, `runsAt`, `runOf`; `removeTimings`. Test fixtures: a box course
  and logs of a car round it, shared with the server's tests.
- **The summary, version 3:** the device, the first and last `at`, the fixes'
  bounds; a lap's `course` ahead of its `track`.
- **The server:** `RetimingJobs` (after a session is prepared; every run a
  course touches when it's saved, one at a time; on view; a removed course's
  re-timings deleted); `GET /api/admin/courses/{id}/retiming`;
  `GET /api/sessions/{id}/laps`.
- **The site:** the course editor follows a save's re-timing ("Version 2:
  re-timed 1 session."); a session's lap table shows the laps as they stand,
  "Re-timed on version N", and a flag's "Re-timing found 1:10.000".
- **`admin.sh remove-course`**: refuses a course a tablet timed laps at, asks
  for the id again, and deletes its re-timings.
- **Tests:** Kotlin 43 new (`:timing` 33, `:server` 9, `:archive` and
  `:tools` 1 each, `:archive-gcp`'s mapping extended); Vitest 123 (4 new).
  **Mutations: 70, 69 killed, 1 shown equivalent**; tests were added or
  tightened after ten survivors, each a gap in the test and none a fault in
  the code (a sector line cutting the track twice, the pit gate's width, a
  device-less run, a layout named by its name, another course's laps, the
  pit lane in a course's bounds, noisy positions for the rounding, a tablet
  number that differs from the run's, the course the laps name, the removal's
  route).
- **Found while building:** a tablet lap a millisecond early knocked out the
  re-timed lap before it (now: more than half shared); the summary read a
  lap's `track`, not its `course`; a disagreement logged on every page view;
  the best-sector highlight broken by re-timing's 7th decimal.
- **Proven:** on the dev server (a drive with no laps re-timed; the tablet's
  laps with one flagged; all re-timed after the line moved 100 m, S1 2.5 s
  shorter and the last sector 2.5 s longer). **Deployed as `00022` and
  `00023`**, the second with a stream across the cutover (`1012`, reconnected
  at once). **The first real drive, re-timed:** a test course from its own
  loop, and the drive shows a lap it never had, **434.997 s on version 1 and
  417.025 s on version 2** (the line 100 m on), the GPS series saying 434.998
  and 417.026; the course and its re-timings then removed.

**Left for later:** re-timing while a session is driven (the tablet's laps
are the live ones); a course save's re-timing surviving a restart (the
progress is in memory; a missing re-timing is built on view); `import-course`
and `remove-course` starting the server's job (they write Firestore
directly, so re-timing happens on view).

## M14 — Drivers and events  ✅ 2026-09-27

The third milestone of race logging. Decision 34.

- **`:events`** (pure): `Driver`, `Event`, `Part`, `SessionHeard`;
  `EventRules` (every problem said; which sessions each part holds);
  `DriverStore`, `EventStore`, in memory.
- **Storage:** `FirestoreDriverStore` (a code unique by a transaction),
  `FirestoreEventStore` (a save a transaction on its revision), a session's
  driver on its index record; `scripts/event-smoke.sh`.
- **The admin page:** Drivers, Events and the event editor (course, layout,
  cars, parts and their windows, a part's sessions taken out or added by
  hand); a refused save shows every problem.
- **Who drove:** set on a session's page by the admin or the car's crew,
  public to read.
- **Public:** `/events` and `/events/{id}` (each driver's best per practice
  and over all practice, the best of each sector, every session, re-timed laps
  marked); a session's page and its car's list name its event and part; the
  landing page links Events and Courses.
- **`admin.sh`:** `drivers`, `events`, `add-driver`, `remove-driver`,
  `import-event`, `remove-event`.
- **Batched in:** the course editor's rename fix (a new name alone can be
  saved).
- **Tests:** Kotlin 29 new (`:events` 7, `:archive` 1, `:archive-gcp` 4,
  `:server` 14, `:tools` 3); Vitest 139 (14 new). **Mutations: 78, 76 killed,
  2 shown equivalent**; tests were added or tightened after seven survivors.
- **Found while building:** a new event's date defaulting to UTC's day; a
  part's window heading-sized; the theoretical best creeping in (M16's).
  **In production:** an empty driver id answered with a 500 (Firestore throws
  on it; the in-memory store just finds nothing); fixed and redeployed.
- **Proven:** the real Firestore (`scripts/event-smoke.sh`); Chrome against
  the dev server (every page, the admin's and the public's); **deployed as
  `00024` and `00025`**, each with a stream across the cutover; in
  production, a test event ranking two drivers set through the crew's
  passcode, then removed.

**Left for later:** the race as one timeline, stints, stops (M15); the
theoretical best, comparisons (M16); a part's sessions changed while other
edits are unsaved (the editor asks to save first).
