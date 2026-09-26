# OBD2 Dashboard — Backend

The server side of [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard):
it takes in and logs data sent by the in-car tablet, and serves a website that
shows the car's data live.

## Status

Skeleton only: a health check, plus a tablet-key check at `/tablet/ping`. Next is
M2: registered cars and per-car tokens. See [`docs/PLAN.md`](docs/PLAN.md). The
protocol is the app's [telemetry contract](https://github.com/samvoigt/obd2-dashboard/blob/2210082/docs/TELEMETRY-CONTRACT.md).

## Building

Requires JDK 17. Docker is not needed.

```sh
./gradlew test                 # unit tests
TABLET_API_KEY=dev ./gradlew :server:run   # serve on http://localhost:8080
./gradlew :server:buildFatJar  # server/build/libs/server.jar
```

- `curl localhost:8080/health` → `{"status":"ok"}`
- `curl -H 'Authorization: Bearer dev' localhost:8080/tablet/ping` → the same; without the key, 401.

## Layout

| Module | Contents |
| --- | --- |
| `:server` | Ktor server: tablet ingest, live feed, website |

## Deploying

Google Cloud Run: project `obd2-dashboard-backend`, region `us-east4`, live at
https://obd2-backend-qeppiy7nzq-uk.a.run.app. Cloud
Build builds it from the `Dockerfile`, so Docker is not needed locally.

```sh
scripts/gcp-setup.sh   # once: enable APIs, service account, tablet key secret
scripts/deploy.sh      # build and deploy
scripts/tablet-key.sh  # print the key to paste into the app's settings
```
