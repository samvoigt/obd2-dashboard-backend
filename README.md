# OBD2 Dashboard — Backend

The server side of [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard):
it takes in and logs data sent by the in-car tablet, and serves a website that
shows the car's data live.

## Status

M2 done: cars are registered, each with its own tablet token and crew passcode,
managed with an admin tool. The server authenticates tablets by car token and
lists cars for the landing page. The telemetry lanes themselves (archive, live)
come next, in M3 and M4. See [`docs/PLAN.md`](docs/PLAN.md). The protocol is the
app's [telemetry contract](https://github.com/samvoigt/obd2-dashboard/blob/2210082/docs/TELEMETRY-CONTRACT.md).

## Building

Requires JDK 17. Docker is not needed.

```sh
./gradlew test                  # unit tests, all modules
./gradlew :server:buildFatJar   # server/build/libs/server.jar
GCP_PROJECT=obd2-dashboard-backend ./gradlew :server:run   # http://localhost:8080
```

A local server reads the **real** Firestore, as you, through Application Default
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
scripts/admin.sh --help
```

## Layout

| Module | Contents |
| --- | --- |
| `:registry` | Cars, slugs, tokens, passcodes; the `CarStore` interface. Pure Kotlin |
| `:registry-firestore` | `CarStore` on Firestore |
| `:server` | Ktor server: tablet auth, public API; later the telemetry lanes and website |
| `:tools` | The `admin` tool |

## Deploying

Google Cloud Run: project `obd2-dashboard-backend`, region `us-east4`, live at
https://obd2-backend-qeppiy7nzq-uk.a.run.app (also
https://obd2-backend-286164118741.us-east4.run.app). Cloud Build builds it from
the `Dockerfile`, so Docker is not needed locally.

```sh
scripts/gcp-setup.sh        # once, and safe to re-run: APIs, Firestore, service account
scripts/deploy.sh           # build and deploy
scripts/firestore-smoke.sh  # round-trip a throwaway car through the real Firestore
```
