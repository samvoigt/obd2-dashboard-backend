# Architecture decisions

Each entry records what was decided, why, and what would make us revisit it.
**Don't reverse one silently — add a superseding entry.**

---

## 1. Kotlin + Ktor on the server

**Decision.** The backend is Kotlin on the JVM, using Ktor.

**Why.** It has two jobs: take in data from the tablet, and push live data to
browsers. Both are long-lived streaming connections, and Ktor supports WebSockets
and server-sent events with coroutines and `Flow`, which the app already uses for
its reading bus. The client is a Kotlin app, so whatever is sent between the
two — the session-log records, and later a live frame — can be written as one
`@Serializable` type used on both sides, instead of being kept in sync by hand.
Kotlin, kotlinx and JUnit/kotest versions track the app's for that reason.

**Alternatives weighed.** Python/FastAPI is quicker to start but gives up the
shared types, and its async model is a second one to learn next to coroutines.
Node/TypeScript would suit the website, but Node is not installed and the
ingest side gets nothing from it.

**Revisit if.** The website grows into a real single-page app. That affects
*where the frontend lives* (see decision 3), not the server language.

---

## 2. Google Cloud Run, from a Dockerfile

**Decision.** Deploy to Cloud Run. Cloud Build builds the `Dockerfile` using
`gcloud run deploy --source .`.

**Why.** It scales to zero between race weekends, which suits a car that is
driven a few days a month. It supports WebSockets, and it needs no Docker
install on the dev machine. The server reads `PORT` and binds `0.0.0.0`, as
Cloud Run requires.

**Cost.** A Cloud Run request, including a WebSocket, lasts at most 60 minutes, so
live connections must expect to reconnect. Scaling to zero also means live data
cannot be kept in one instance's memory while several instances run. Both matter
when the live feed is designed, not before.

**Revisit if.** A live session needs state shared across instances (use
Memorystore or Pub/Sub), or the cold start after scaling to zero turns out to be
too slow.

---

## 3. The website starts inside the server

> **Refined by 13**, which picks the frontend stack. It still ships from the server.

**Decision.** For now the live website is served by the Ktor server, from the
same deployment.

**Why.** One deploy and one origin, with no CORS and nothing to decide before
there is a page to show. Splitting it out later is a move, not a rewrite.

**Revisit if.** The frontend needs its own build tool (React, Svelte, etc.). Then
pick a framework in a new decision, and decide whether it still ships from
the server.

---

## 4. The tablet authenticates with one shared key held in Secret Manager

> **Superseded by 10** once M2 lands: every car has its own key.

**Decision.** A random key is stored in Secret Manager as `tablet-api-key`.
Cloud Run gives it to the server as `TABLET_API_KEY`. The tablet sends it as
`Authorization: Bearer <key>` on every tablet route, and the server compares it
in constant time. The server will not start without it.

**Why.** There is one tablet and one owner. A shared key is the least that
keeps strangers from writing data, and Secret Manager keeps it out of git, the
image, and the deploy command. The key is entered in the app's settings rather
than built into the APK, so changing it needs no rebuild.

**Rotating.** Add a new secret version, redeploy (`--set-secrets …:latest` is
read when an instance starts), then paste the new key into the tablet.

**Revisit if.** There is more than one tablet or car, or anyone needs to be cut
off without cutting off everyone. That needs a key per device.

---

## 5. The website is public and read-only

> **Amended by 11:** viewing stays public, but sending messages needs a passcode.

**Decision.** The Cloud Run service allows unauthenticated requests. Anyone with
the URL can watch; only the tablet key can write.

**Why.** It is the simplest thing that works for sharing a live view with a
crew. The URL is not advertised.

**Revisit if.** The data should not be seen by just anyone. Identity-Aware
Proxy or a viewer login can go in front later without changing the tablet side.

---

## 6. The stream is the session log

**Decision.** The tablet streams over one WebSocket with two lanes:
- **Live lane:** records taken straight off the bus, newest first, for display
  only. It may drop records.
- **Log lane:** lines of the session log file, sent in order and acknowledged by
  line number. It never drops, and it is what the server stores as the session.

On reconnect the server says which line it has, and the log lane resumes from
there. When the log lane has delivered every line and the tablet has said the
session closed, the server answers `closed` and the tablet marks the archive as
uploaded.

**Why.** The app's session log is already durable, versioned and ordered. That
makes it the right source for capture, and it means live streaming, session
capture and post-session upload are one mechanism instead of three. Dead zones
and the 60-minute Cloud Run connection limit become routine resumes.

**Why two lanes.** Measured in the app on 2026-09-26: `SessionLog` flushes the file
only when it syncs, at most every 30 s by default. Following the file alone would
put up to 30 s on the live view. Resuming by `seq` is wrong too: `seq` is
the *bus* sequence, and a log config that filters kinds leaves holes in it that a
resume cannot tell apart from loss. Line numbers have no holes.

**Revisit if.** The app flushes the log often enough (≤1 s) for the file to be
the live source too. Then the live lane could be dropped.

---

## 7. One Cloud Run instance; the live hub is in memory

**Decision.** `max-instances=1`. Each car's live state (latest value per signal,
the last few minutes, open browser streams) is held in memory, behind a
`LiveHub` interface.

**Why.** A tablet's WebSocket and its viewers' streams have to meet in one
process. With a handful of cars and tens of viewers, one instance does that
without trouble. Sharing state across instances (Memorystore, Pub/Sub) would
cost money and moving parts to solve a scale that does not exist. Several cars
does not change this. Each car is just another key in the hub.

**Cost.** A deploy or instance restart drops every connection. Tablets resume
(decision 6) and browsers reconnect (decision 8), and the live buffer is rebuilt
from the next few seconds of data. Nothing is lost that the log lane does not
carry.

**Revisit if.** One instance runs out of CPU or connections, or a deploy mid-race
turns out to be disruptive in practice. Then replace `LiveHub` with Pub/Sub or
Redis.

---

## 8. Browsers receive over SSE and send with POST

**Decision.** Live data reaches browsers as server-sent events, one stream per
car page. Commands (log in, send or clear a message) are ordinary POST/DELETE
requests.

**Why.** Browsers only need to *receive* continuously. SSE reconnects by itself
and resumes with `Last-Event-ID`, which maps onto `seq`. It also works through
proxies and on HTTP/2. A two-way socket would add nothing for sending a message
a few times an hour.

---

## 9. Session data in Cloud Storage; everything small in Firestore

**Decision.**
- Log-lane lines are written to Cloud Storage as gzipped chunks named by line
  range, and joined into one `.jsonl.gz` when the session closes.
- Cars, the session index, and messages with their states go in Firestore.

**Why.** Session data is large, append-only and read whole, which is what object
storage is for. Stored as gzipped JSONL, it is the same format as the app's
archives, so the same tools read both. Firestore costs nothing when idle and has
no instance to run.

**Rejected.** Firestore per record (~475k writes a session). Cloud SQL (a bill
whether or not anyone races). BigQuery, until there is a question that spans
sessions.

---

## 10. Cars are registered, and each has its own key

**Decision.**
- A car is a Firestore document: slug, name, key hash, passcode hash.
- A key is 256 random bits, stored as its SHA-256. A fast hash is enough for a
  secret that cannot be guessed.
- The passcode is human-chosen and guessable, so it is stored with PBKDF2.
- Cars are added and keys rotated with an admin CLI that runs on the owner's own
  Google credentials, so the server has no admin endpoint to attack.
- The key alone identifies the car. A tablet never says which car it is.

**Why.** There can be several cars at once, each with its own page. One shared
key would let any tablet write to any car, and rotating it would take every car
offline. Supersedes decision 4.

**Note.** A registered car is a *page*, not a vehicle. Which vehicle a session
came from is whatever the session log says (its `vin`), and the server does not
interpret it (the app's decision 33).

---

## 11. Viewing is public; sending needs the car's crew passcode

**Decision.** Anyone can open the landing page and any car's page. Sending or
clearing a message needs that car's passcode. It is exchanged at
`POST /api/cars/{slug}/login` for a signed, HTTP-only cookie scoped to that car.
Login attempts are rate-limited.

**Why.** It is not a public site, and a shared passcode per crew is the whole
requirement. Per car rather than global, so one crew cannot message another
car. Amends decision 5.

**Revisit if.** The passcode needs revoking from one person. Then individual
accounts are needed.

---

## 12. Messages are displayed, never acknowledged

**Decision.** The driver does nothing with a message. A message goes **queued →
delivered → cleared | expired**. *Delivered* is reported by the tablet once the
message is *on screen*. It stays there until the crew clears it, a newer message
replaces it, or its time-to-live runs out.

- A reconnecting tablet is sent every active message, and it shows them
  idempotently by id.
- Expiry is sent as time *remaining*, not as a wall-clock instant, so a tablet
  with a wrong clock still expires it on time.

**Why.** The driver is driving. Nothing on that screen can ask for a tap. Given
that, the one thing the crew needs to know is whether the message is up on the
screen, and *delivered* answers exactly that. The time-to-live exists because a
"Pit Now" that arrives four minutes late, after a dead zone, is worse than one
that never arrives.

---

## 13. Website: TypeScript + Svelte + uPlot, served by Ktor

**Decision.** The site is a Svelte app built with Vite into static files, which
the Ktor server serves. Time-series charts use uPlot.

**Why.** Many small values change about 20 times a second. Svelte compiles
components to direct DOM updates, without the overhead of a virtual DOM. uPlot
is built for streaming time series and draws tens of thousands of points per
frame. Served by Ktor, so it is still one deploy on one origin (decision 3).

**Cost.** Node becomes a build dependency (it is not installed yet). The
Dockerfile gains a Node build stage.

**Alternative kept open.** Compose Multiplatform for the web could draw the
app's real gauges in the browser. It is weighed at M7, for mirrored layouts
only.

---

## 14. The protocol is a document plus fixtures, not shared code

**Decision.**
- `docs/PROTOCOL.md` specifies the tablet connection.
- `protocol-fixtures/` holds example frames. Both repos test against the same
  files.
- The server reads only each record's envelope (`type`, `seq`, `at`, `wall`),
  and stores and forwards the rest unchanged.
- The website's TypeScript types for records are written against the fixtures.

**Why.** Sharing Kotlin between the repos would tie the two builds together
(composite builds, published artifacts) for a protocol of about eight frame
types. Leaving records unparsed keeps the server car-agnostic by construction
(the app's decision 33). The server cannot misread a field it never reads, and
new signals need no server change.
