# OBD2 Dashboard — Backend

The server side of [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard):
it takes in and logs data sent by the in-car tablet, and serves a website that
shows the car's data live.

## Status

M3 done: the archive lane. A tablet uploads each session in chunks
(contract §6). Every line is stored byte for byte in Cloud Storage and
acknowledged only once durable, and a completed session is one `.jsonl.gz`
whose SHA-256 matches the tablet's. Cars (M2) each have their own token. The
live lane and website come next, in M4. See [`docs/PLAN.md`](docs/PLAN.md). The
protocol is the app's [telemetry contract](https://github.com/samvoigt/obd2-dashboard/blob/2210082/docs/TELEMETRY-CONTRACT.md).

## Building

Requires JDK 17. Docker is not needed.

```sh
./gradlew test                  # unit tests, all modules
./gradlew :server:buildFatJar   # server/build/libs/server.jar
GCP_PROJECT=obd2-dashboard-backend SESSIONS_BUCKET=obd2-dashboard-backend-sessions \
  ./gradlew :server:run         # http://localhost:8080
```

A local server uses the **real** Firestore and bucket, as you, through Application Default
Credentials (`gcloud auth application-default login`).

- `curl localhost:8080/health` → `{"status":"ok"}`
- `curl localhost:8080/api/cars` → `[{"slug":…,"name":…}]`
- `curl -H 'Authorization: Bearer <car token>' localhost:8080/v1/whoami` → that car; a bad token gets `401`.

## Cars

```sh
scripts/admin.sh list
scripts/admin.sh add-car yaris --name "Yaris"   # prints the car's token, once
scripts/admin.sh set-passcode yaris             # crew passcode, typed without echo
scripts/admin.sh rotate-token yaris             # old token stops working at once
scripts/admin.sh sessions [car]                 # a car's sessions (never a VIN)
scripts/admin.sh session <id>                   # one session in full, its VIN included
scripts/admin.sh delete-session <id>            # the owner's deletion (decision 16)
scripts/admin.sh --help
```

## Replaying sessions

`scripts/replay.sh` uploads session logs exactly as the tablet does, faults
included. The token comes from `OBD2_TOKEN` or `--token-file`, never an argument.

```sh
scripts/replay.sh --server https://obd2-backend-qeppiy7nzq-uk.a.run.app \
  --token-file car.token --lose-responses 0.3 --duplicate 0.2 session.jsonl.gz
```

## Layout

| Module | Contents |
| --- | --- |
| `:registry` | Cars, slugs, tokens, passcodes; the `CarStore` interface. Pure Kotlin |
| `:registry-firestore` | `CarStore` on Firestore |
| `:archive` | The archive lane's rules: lines, chunks, store then advance. Pure Kotlin |
| `:archive-gcp` | The archive on Cloud Storage and Firestore |
| `:server` | Ktor server: tablet auth, the archive lane, public API; later the live lane and website |
| `:tools` | The `admin` tool |
| `:replay` | Uploads session logs as the tablet does |

## Deploying

Google Cloud Run: project `obd2-dashboard-backend`, region `us-east4`, live at
https://obd2-backend-qeppiy7nzq-uk.a.run.app (also
https://obd2-backend-286164118741.us-east4.run.app). Cloud Build builds it from
the `Dockerfile`, so Docker is not needed locally.

```sh
scripts/gcp-setup.sh        # once, and safe to re-run: APIs, Firestore, bucket, service account
scripts/deploy.sh           # build and deploy
scripts/firestore-smoke.sh  # a throwaway car through the real Firestore
scripts/archive-smoke.sh    # a throwaway session through the real bucket and Firestore
```
