# OBD2 Dashboard Backend — working notes for Claude

The server side of the in-car race dashboard at `../obd2-dashboard` (an Android
app on a tablet). The server **captures and logs** what the tablet sends, and
**serves a website** with the car's data live. Kotlin + Ktor, deployed to
Google Cloud Run.

## Read these first

- `docs/PLAN.md` — the roadmap and what state each milestone is in. **Check
  before starting work.**
- `docs/DECISIONS.md` — what was decided and *why*. Add to it; don't silently
  reverse one.
- `docs/JOURNAL.md` — measurements from real deployments and process lessons.
- The app's `CLAUDE.md` and `docs/` in `../obd2-dashboard`. The server's
  conventions follow the app's. When the two repos share a format, the app's
  session log (its milestone M8) is where it comes from.

## The server never learns what car it is talking to

The app keeps a rule (its decision 33) that facts about a specific vehicle belong
in `test-data/` beside the capture that proves them, and never in code, types or
copy. The same rule applies here. Readings arrive as the app describes them, and
the server stores and forwards them without knowing which car sent them.

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
