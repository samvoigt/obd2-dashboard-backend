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
  pinned at app commit `2210082`. It is the protocol (decision 15), and
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

```
./gradlew test                 # unit tests
TABLET_API_KEY=dev ./gradlew :server:run   # http://localhost:8080
./gradlew :server:buildFatJar  # server/build/libs/server.jar
```

Warnings fail the build (`allWarningsAsErrors`), as in the app.

## Deploy

Project `obd2-dashboard-backend`, region `us-east4`, service `obd2-backend`.
All three are set in `scripts/env.sh`. Always go through the scripts, or pass
`--project` explicitly: the local `gcloud` default is `microtron-scoreboard`,
which is unrelated.

**Never print, log or commit the tablet key.** It lives in Secret Manager
(decision 4). `scripts/tablet-key.sh` is for the user to run, not for output
that goes into a transcript.
