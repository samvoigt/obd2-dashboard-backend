# OBD2 Dashboard — Backend

The server side of [obd2-dashboard](https://github.com/samvoigt/obd2-dashboard):
it takes in and logs data sent by the in-car tablet, and serves a website that
shows the car's data live.

## Status

M16 done: results worth reading. Every lap links to its moment in its
session; practice and the race show the theoretical best, sectors driver by
driver and how consistent each driver was; the race has a lap-time chart; and
any two laps can be compared by distance round the course, the time gained or
lost metre by metre. M15: the race as one timeline, through driver changes (the car's power
off, the tablet's timing carrying on) and restarts of the app; stops timed in
the pit lane; stints split at stops, edited by the admin or the crew; the green
and chequered flags marked; race results and driver pages, public. M14:
drivers and events, public at https://badnewsbears.live/events:
practice sessions and a race, sessions placed by the server's clock (or by
hand), who drove each set by the admin or the car's crew, and each driver's
best lap and sectors. M13: the server re-times the tablet's own GPS fixes, by the tablet's
own rule, wherever its laps aren't current (a line moved, or a drive with no
laps), and checks the tablet's laps to 2 ms; a session's page shows the laps
as they stand, marked. M12: courses are drawn on the website (layouts,
start/finish, sectors, pit lines, every save a version, public at
https://badnewsbears.live/courses) and sent down to the tablet, which times
laps on them. Race logging goes on from here (drivers, events, the race;
`docs/plans/RACE-LOGGING.md`). A car's page is a live dashboard, the same for every car: gauges,
GPS speed, a G-meter, a map following the car, laps, status lights and
trouble codes, in metric or US units, in the Bad News Bears look. Every
session a car uploads is on the site too, with full-length charts, laps and a
map (M7). Cars, tokens and sessions are managed at
https://badnewsbears.live/admin (M6). The crew can send the driver messages
(M5). See
[`docs/PLAN.md`](docs/PLAN.md). The protocol is the app's
[telemetry contract](https://github.com/samvoigt/obd2-dashboard/blob/918fa1e/docs/TELEMETRY-CONTRACT.md).

The site: https://badnewsbears.live

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

At https://badnewsbears.live/admin (Google sign-in; the allowlist is the
`admin-emails` secret), or from the command line; both follow the same rules:

```sh
scripts/admin.sh list
scripts/admin.sh add-car yaris --name "Yaris"   # prints a generated token, once
scripts/admin.sh add-car yaris --name "Yaris" --choose-token   # or type your own (decision 24)
scripts/admin.sh set-token yaris                # change it to one you choose
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
| `:archive` | The archive lane's rules, and reading a session into its summary and series. Pure Kotlin |
| `:archive-gcp` | The archive on Cloud Storage and Firestore |
| `:live` | The live lane's rules: frames, a car's live state, the hub, crew messages. Pure Kotlin |
| `:admin` | The owner's rules shared by `admin.sh` and the admin page. Pure Kotlin |
| `:server` | Ktor server: tablet auth, both lanes, the browser stream, the website |
| `web/` | The website: Svelte, Vite, TypeScript, uPlot, Leaflet |
| `:tools` | The `admin` tool |
| `:replay` | Uploads session logs as the tablet does |

## Deploying

Google Cloud Run: project `obd2-dashboard-backend`, region `us-east4`. The site
is https://badnewsbears.live (and `www.`), mapped by `gcp-setup.sh` with DNS at
Namecheap (decision 23). Tablets use https://obd2-backend-qeppiy7nzq-uk.a.run.app
(also https://obd2-backend-286164118741.us-east4.run.app). Cloud Build builds it from
the `Dockerfile`, so Docker is not needed locally.

```sh
scripts/gcp-setup.sh        # once, and safe to re-run: APIs, Firestore, bucket, service account
scripts/deploy.sh           # build and deploy
scripts/firestore-smoke.sh  # a throwaway car through the real Firestore
scripts/archive-smoke.sh    # a throwaway session through the real bucket and Firestore
scripts/message-smoke.sh    # throwaway messages through the real Firestore
```
