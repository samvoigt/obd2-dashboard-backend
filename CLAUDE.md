# OBD2 Dashboard Backend — working notes for Claude

The server side of the in-car race dashboard at `../obd2-dashboard` (an Android
app on a tablet). Each registered car's tablet **streams live** to the server.
The server **captures the stream as a session** and **serves a website** per car
with live dashboards. The crew can **send the car messages** ("Pit Now").
There can be several cars at once. Kotlin + Ktor, deployed to
Google Cloud Run.

## Read these first

- `docs/PLAN.md` — the roadmap and what state each milestone is in. **Check
  before starting work.**
- `docs/DECISIONS.md` — what was decided and *why*. Add to it; don't silently
  reverse one.
- **The telemetry contract**, the app's `docs/TELEMETRY-CONTRACT.md`, v1, final,
  pinned at app commit `918fa1e`. It is the protocol (decision 15), and
  `docs/PROTOCOL.md` says where to find it and what this side committed to.
  **Never edit it from here**: a change is a v2, agreed through Sam.
- `docs/plans/` — the plan for the current milestone. Each step is **validated
  against the code before it is built**, and that validation is written into
  the plan. When a milestone closes, its lasting content goes to
  `docs/plans/COMPLETED.md`, `DECISIONS.md` or `JOURNAL.md`, and the plan is
  deleted.
- `docs/JOURNAL.md` — measurements from real deployments and process lessons.
- The app's `CLAUDE.md` and `docs/` in `../obd2-dashboard`. The server's
  conventions follow the app's. When the two repos share a format, the app's
  session log (its milestone M8) is where it comes from.

## The server never learns what car it is talking to

The app keeps a rule (its decision 33) that facts about a specific vehicle belong
in `test-data/` beside the capture that proves them, and never in code, types or
copy. The same rule applies here. Readings arrive as the app describes them, and
the server stores and forwards them without knowing which vehicle sent them.
It reads only a record's envelope (`type`, `seq`, `at`, `wall`), per decision 14.

## Build

JDK 17 (Homebrew, `java` on PATH). Gradle wrapper, versions pinned in
`gradle/libs.versions.toml`. Kotlin and kotlinx versions track the app's.
Modules: `:registry`, `:archive` and `:live` (pure, where the rules and most
tests live), `:registry-firestore` and `:archive-gcp` (Google), `:server`,
`:tools`, `:replay`. The website is `web/` (Svelte, Vite, TypeScript 5.9, uPlot;
Node 24 from Homebrew `node@24`), built by Gradle into the server jar.

```
./gradlew test                  # unit tests, all modules
./gradlew :server:buildFatJar   # server/build/libs/server.jar
GCP_PROJECT=obd2-dashboard-backend SESSIONS_BUCKET=obd2-dashboard-backend-sessions \
  CREW_COOKIE_KEY=… ./gradlew :server:run   # real Firestore and bucket; any 32+ byte base64url key
scripts/admin.sh list           # the admin tool (cars, tokens, passcodes, sessions)
scripts/replay.sh --help        # the tablet's lanes (--live), faults included
./gradlew :server:devServer     # the real module in memory + the real site, car dev-car
                                #   (token and crew passcode in server/build/dev-token,
                                #   dev-passcode, never printed); replay into it
(cd web && npm test && npm run check)   # the site's logic and types
./gradlew test -PskipWeb        # Kotlin only, without building the site
scripts/firestore-smoke.sh      # throwaway car through the real Firestore
scripts/archive-smoke.sh        # throwaway session through the real bucket
scripts/message-smoke.sh        # throwaway messages through the real Firestore
```

Warnings fail the build (`allWarningsAsErrors`), as in the app. `gcloud` needs
`CLOUDSDK_PYTHON` pointing at Python 3.10+ (JOURNAL 2026-09-26).

## Deploy

Project `obd2-dashboard-backend`, region `us-east4`, service `obd2-backend`.
**Never rename or recreate the service.** The app has its URL built in
(`https://obd2-backend-qeppiy7nzq-uk.a.run.app`), and a new service would strand
every tablet until an app update. Deploy settings (one instance, timeout 3600,
concurrency 1000) are decision 20; **test deploys with a live connection
open** (JOURNAL: M4).
All three are set in `scripts/env.sh`. Always go through the scripts, or pass
`--project` explicitly: the local `gcloud` default is `microtron-scoreboard`,
which is unrelated. **A deploy keeps any setting it does not mention**, so
removing one (a secret, an env var) needs an explicit flag.

**Never print, log or commit a car token.** A token is printed once, by
`admin.sh add-car` or `rotate-token`, for the user. When verifying, create a
throwaway `smoke-*` car, capture its token into a shell variable or a scratch
file that is deleted, never into output, and remove the car afterwards.
**The same for crew passcodes and cookies:** generate a passcode into a
`chmod 600` file (`set-passcode --passcode-file`), keep the cookie in a
`chmod 600` jar, and delete both. Typing a passcode into a page is only for the
dev server's generated one, on localhost.
