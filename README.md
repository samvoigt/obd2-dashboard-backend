# OBD2 Dashboard — Backend

The server side of [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard):
it takes in and logs data sent by the in-car tablet, and serves a website that
shows the car's data live.

## Status

Skeleton only. It builds, tests, and answers a health check. Nothing is
deployed yet. See [`docs/PLAN.md`](docs/PLAN.md).

## Building

Requires JDK 17. Docker is not needed.

```sh
./gradlew test                 # unit tests
./gradlew :server:run          # serve on http://localhost:8080
./gradlew :server:buildFatJar  # server/build/libs/server.jar
```

`curl localhost:8080/healthz` → `{"status":"ok"}`

## Layout

| Module | Contents |
| --- | --- |
| `:server` | Ktor server: tablet ingest, live feed, website |

## Deploying

Google Cloud Run, built from the `Dockerfile` by Cloud Build. There is no GCP
project for this yet — see `docs/PLAN.md`, M1.
