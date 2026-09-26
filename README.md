# OBD2 Dashboard — Backend

The server side of [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard):
it takes in and logs data sent by the in-car tablet, and serves a website that
shows the car's data live.

## Status

M5 done. The crew can log in on a car's page with its passcode and send the
driver messages ("PIT NOW"), and see them reach the tablet's screen (contract
§5.4). Tablets stream live on a WebSocket (M4), and the website shows each car
live. Sessions upload in chunks and are kept byte for byte (M3), and each car
has its own token (M2). Past sessions come next, in M6. See
[`docs/PLAN.md`](docs/PLAN.md). The protocol is the app's
[telemetry contract](https://github.com/samvoigt/obd2-dashboard/blob/918fa1e/docs/TELEMETRY-CONTRACT.md).

The site: https://obd2-backend-qeppiy7nzq-uk.a.run.app

## Building

Requires JDK 17 and Node 24 (for `web/`). Docker is not needed.

```sh
./gradlew test                  # unit tests, all modules
./gradlew :server:buildFatJar   # server/build/libs/server.jar, the site included
./gradlew :server:devServer     # in-memory server + site on :8080, one car (dev-car)
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
# both lanes at real speed, units from the contract, as a tablet streams:
scripts/replay.sh --server … --token-file car.token --live --speed 1 \
  --units-from ../obd2-dashboard/docs/TELEMETRY-CONTRACT.md session.jsonl.gz
```

## Layout

| Module | Contents |
| --- | --- |
| `:registry` | Cars, slugs, tokens, passcodes; the `CarStore` interface. Pure Kotlin |
| `:registry-firestore` | `CarStore` on Firestore |
| `:archive` | The archive lane's rules: lines, chunks, store then advance. Pure Kotlin |
| `:archive-gcp` | The archive on Cloud Storage and Firestore |
| `:live` | The live lane's rules: frames, a car's live state, the hub, crew messages. Pure Kotlin |
| `:server` | Ktor server: tablet auth, both lanes, the browser stream, the website |
| `web/` | The website: Svelte, Vite, TypeScript, uPlot |
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
scripts/message-smoke.sh    # throwaway messages through the real Firestore
```
