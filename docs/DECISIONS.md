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

> **Superseded by 10**, 2026-09-26 (M2): every car has its own token. The
> secret, the `TABLET_API_KEY` variable and `/tablet/ping` are gone.

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

> **Superseded by 15.** The idea survives, two lanes with the log as the source of
> truth, but the telemetry contract carries the archive over **HTTPS chunk uploads**,
> not a second lane on the WebSocket. Line indexes, as argued below, were adopted.

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

> **Refined by 19 (M4):** a browser gets a snapshot on every connect, never a
> replay, so there is no `Last-Event-ID` resume.

**Decision.** Live data reaches browsers as server-sent events, one stream per
car page. Commands (log in, send or clear a message) are ordinary POST/DELETE
requests.

**Why.** Browsers only need to *receive* continuously. SSE reconnects by itself
and resumes with `Last-Event-ID`, which maps onto `seq`. It also works through
proxies and on HTTP/2. A two-way socket would add nothing for sending a message
a few times an hour.

---

## 9. Session data in Cloud Storage; everything small in Firestore

> **Still holds.** Under the contract, chunks are named by **line index** range
> (`{first}-{last}`), and a session is keyed by the tablet's session `id` (a UUID).

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

> **Amended by 15:** the contract calls it a **token**. The tablet keeps one per car
> and picks by VIN, with a default car for sessions without one. That is the
> tablet's business; to the server, the token is still the whole identity.
>
> **Amended by 24:** the owner may choose a car's token instead of a 256-bit one.
>
> **Amended by 25:** "no admin endpoint" no longer holds. The admin page at
> `/admin` does what the CLI does, behind Google sign-in and an allowlist.

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

> **Amended by 15:** states are **queued → received → displayed → cleared | expired**.
> `received` exists because a dashboard without a message widget receives but
> never displays. After every `hello` the server sends the **complete active set**,
> and the tablet takes down anything not in it. One tone on arrival. Text is limited
> to 40 characters.

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
app's real gauges in the browser. It is weighed at M8, for mirrored layouts
only.

---

## 14. The protocol is a document plus fixtures, not shared code

> **Superseded by 15:** the document is the app's `TELEMETRY-CONTRACT.md`, not one
> in this repo. The rest holds: no shared code, and the server parses only what it
> needs.

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

---

## 15. The telemetry contract v1 is the protocol

**Decision.** The protocol between tablet and server is the app's
[`docs/TELEMETRY-CONTRACT.md`](https://github.com/samvoigt/obd2-dashboard/blob/918fa1e/docs/TELEMETRY-CONTRACT.md),
**v1, final, at app commit `918fa1e`** (re-pinned twice on 2026-09-26 with Sam's
agreement, never with a wire change: first for §10, then for the tablet's §15
(GPS) and §16 (laps), confirmed in §17; `PROTOCOL.md` has both). It was written by the tablet side,
reviewed by this side, and agreed by both (its §§12–14). In summary:

- **Live lane:** one WebSocket at `/v1/live`, subprotocol `obd2-telemetry.v1`.
  Every 200 ms, the latest sample per signal plus every structural record.
  Lossy, and never replayed.
- **Archive lane:** HTTPS. `PUT /v1/sessions/{id}`, then
  `POST …/chunks` and `POST …/complete`. Addressed by **line index**, stored
  **byte for byte**, and acknowledged only once durable.
- Crew messages travel on the live socket (decision 12 as amended).
- **A token belongs to a car** (decision 10 as amended).

Supersedes 6 and 14; amends 10 and 12. `docs/PROTOCOL.md` points at the
contract and lists what this side committed to in its §14.5.

**Why.** Only one document can be the contract, and the tablet side knows the
records. HTTPS for the archive is better than the WebSocket lane of decision 6:
each chunk request stands on its own, which suits Cloud Run and ordinary retries.

**Changing it.** A v2, agreed the same way, through Sam. Never an edit on one
side. The pinned commit here moves only when a new version is agreed.

---

## 16. Sessions are kept indefinitely; the VIN is never shown

**Decision.** Nothing expires, and deletion is by the owner with the admin
tool. There is no self-service deletion. The VIN is stored with its session and
appears only in the admin tool and in downloaded session files, **never on a web
page or in a public API response**. GPS position may be shown publicly.

**Why.** Sam, 2026-09-26. Storage costs pennies per session, so there is no
pressure to delete. The VIN identifies a vehicle, and a public page does not
need it. A live map of the car is part of the point.

**Revisit if.** Storage cost matters, or someone asks for their data to be
deleted.

---

## 17. The archive: segments by line range, Firestore as the authority, store then advance

**Decision.**
- Every run of lines the server accepts is written to Cloud Storage as its own
  gzip file, `sessions/{id}/segments/{first}-{last}.jsonl.gz`, verbatim. Line 0
  is segment `0-0`.
- A segment **counts only once it is in the session's Firestore document**,
  appended in a transaction that succeeds only if `ackedThrough` is still what
  the request read.
- `ackedThrough` is answered only after both writes. A completed session is one
  `sessions/{id}/session.jsonl.gz`, and its segments are then deleted.
- Objects are gzip *files* (`application/gzip`), not gzip-*encoded*, so a
  download is exactly the app's own `.jsonl.gz`.

**Why.**
- Contract §6.2 makes an ack a promise of durability. Storing first makes the
  promise true by construction.
- The condition makes two instances racing over one session safe without a
  lock. The loser's object is an orphan nobody names, swept on completion, and
  every answer is a fresh read, so it is always true.
- Firestore rather than the bucket listing is the authority, because a listing
  cannot tell an orphan from a segment.
- `complete` refuses to assemble a list that is not contiguous, or a segment
  that does not hold the lines it claims, rather than trusting the hash alone.

**Revisit if.** Sessions grow past about 12,000 segments, where the document's
segment list nears Firestore's 1 MiB limit (about 16 days at one chunk every
2 minutes). Then move segments to a subcollection.

---

## 18. A hash mismatch resends from line 1, at most twice

**Decision.** If `/complete`'s SHA-256 does not match the stored lines, the
server:
- keeps line 0 (a `PUT` already refuses a different one);
- discards the rest;
- sets `ackedThrough = 0`;
- answers `409 {missingFrom: 1}`.

The tablet resends everything, which is §6.4's ordinary `409`. **After two such
resets**, the next mismatch is `400 bad_record`.

**Why.** A mismatch means the two sides hold different bytes, and the server
cannot know which lines differ. Resending is the only repair within the
contract. The limit stops a tablet and server that disagree about the bytes
themselves (a hashing bug, say) from resending the same session forever. No
contract change was needed.

---

## 19. The live lane: in-memory state, snapshots for browsers, the VIN removed on arrival

**Decision.**
- **Each car's live state is held in memory** by the hub (decision 7):
  - the session header and signals list;
  - the latest sample per signal;
  - `stopped` records and the latest `fault`;
  - five minutes of history, by the server's clock, capped at 100,000 records.
- **A browser gets a snapshot on every connect, then updates, never a replay.**
  The snapshot holds the state and the history. A browser that falls behind is
  given a fresh snapshot rather than waited on, so the tablet never waits for a
  browser.
- **`vin` is removed from every record as it arrives** from a tablet, not only
  from the session record, so nothing derived from it can reach a browser.
- **Freshness** is the server's (live within 2 s of a batch, stale, no session,
  offline). The page counts the seconds itself from the reported age, so a
  silent server still turns "live" into "behind".

**Why.** The live lane is provisional by contract (§5.3, §7). A snapshot is
always right, costs one message, and needs no bookkeeping across reconnects.
The VIN is personal data (decision 16), and removing it at the one door it
comes in by is simpler to prove than guarding every door it could leave by.
A test searches the raw bytes two browsers receive.

**Cost.** Live history lives in one process, so a restart or a new revision
starts it empty and the chart restarts from the reconnect. The archive (M3) is
the record, and M7 draws from it.

---

## 20. Cloud Run settings for the live lane, and draining on deploy

**Decision.**
- `--max-instances 1` (decision 7), `--timeout 3600` and `--concurrency 1000`.
- Every instance checks every 30 s whether its revision still has traffic.
  When it has none, it **drains**: every tablet is closed with `1012`, and every
  browser stream is ended, so both reconnect to the new revision. A failed check
  never drains.
- The runtime account has `roles/run.viewer` on the service, to make that check.

**Why.**
- Cloud Run's defaults would have broken the live lane: a 300 s timeout cuts
  every socket at 5 minutes, and a concurrency of 80 refuses the 81st
  connection.
- **Cloud Run keeps an open WebSocket on the old revision after a deploy** and
  does not send it SIGTERM while that connection is open (found in M4.8). So the
  `1012` that contract §5.3 promises on every deploy never came, and a real
  tablet would have stayed on the old revision, with the site showing offline,
  for up to 55 minutes. Draining keeps the promise.

**Revisit if.** More than one instance is ever needed (decision 7), or Cloud
Run starts signalling old revisions itself.

## 21. Crew messages: stored, one at a time, forward only

**Decision.**
- **Each message is a Firestore document** (`messages`), read from the store
  on every send, sync and report, with no cache in the hub. They are **kept after
  they end**, as a record for the car's sessions (M7), and `admin.sh remove-car`
  deletes them with the car.
- **One active message per car.** A new one marks the old **replaced** (a
  crew-facing state; the tablet simply shows the new one, §5.4).
- **States only move forward**: queued → received → displayed, then cleared,
  expired or replaced. A late or repeated report, or one for another car's or
  an unknown id, changes nothing. `update` is a Firestore transaction.
- **Expiry is the server's clock**: a timer marks the message expired at
  `expiresAt` and tells crew browsers. The tablet takes it down on its own
  clock from `ttlMs`. Lifetimes are 60–1800 s, default 1800 ("until cleared",
  capped; Sam, 2026-09-26).
- **Delivery:** `message` and `clear` go to an attached tablet at once. Every
  `hello` is answered with the full active set, so a message sent while the car
  was away arrives, and one cleared meanwhile comes down.
- Text is 1–40 characters (code points, trimmed). Presets are the contract's
  `pit`, `box`, `fuel`, `push`, `slow`.

**Why.**
- The live lane's state is in memory (decision 19), but a message must not be:
  a deploy drains every tablet (decision 20), and an empty sync from a fresh
  revision would take "PIT NOW" off the driver's screen. Verified in M5.8 by
  deploying while a message was displayed.
- One at a time is what the tablet can show (§5.4), and forward-only states
  make every report safe to repeat or reorder, which the contract's
  reconnects produce.

**Revisit if.** Messages ever need to reach more than one screen, or queue
behind each other.

## 22. The crew login: a signed per-car cookie; crew-only events on the same stream

**Decision.**
- `POST /api/cars/{slug}/login` with `{passcode}` sets `crew_<slug>`:
  `v1.<slug>.<expiry>.<fingerprint>.<hmac>`, HMAC-SHA256 with a key from
  Secret Manager (`crew-cookie-key`, mounted as `CREW_COOKIE_KEY`). The
  fingerprint is of the passcode's stored hash, so **a new passcode logs
  everyone out**. 30 days (Sam, 2026-09-26).
- `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/cars/<slug>`. Every crew
  call is a JSON `POST` or `DELETE`, which a cross-site form cannot send; a form
  body gets `415`.
- 10 failed logins in 10 minutes per car → `429` with `Retry-After`, in memory
  (one instance, decision 7). PBKDF2 runs off the request threads.
- **Viewing stays public; messages are crew-only.** The browser stream decides
  crew or public when it connects: a crew stream also carries `messages` after
  its snapshot and `message` on each change; a public one never does. The page
  reopens its stream after a login or logout.

**Why.**
- A shared passcode per car was Sam's choice (M2). A signed cookie needs no
  session store, survives deploys, and is revoked by changing the passcode.
- One stream with crew-only events keeps a single SSE path, and a test reads a
  public stream's raw bytes for a message's text. In M5.8, 2,724 public events
  across a deploy held none.

**Revisit if.** Crew members need their own logins, or a message must be
revoked from one person without changing the passcode.

## 23. The website at badnewsbears.live, by a Cloud Run domain mapping

**Decision.**
- `badnewsbears.live` and `www.badnewsbears.live` are Cloud Run **domain
  mappings** onto `obd2-backend` (`gcp-setup.sh`, from `DOMAINS` in `env.sh`).
  DNS is at Namecheap: four A and four AAAA records on `@`, and a CNAME from
  `www` to `ghs.googlehosted.com`. Google issues and renews the certificate.
- The domain is verified in Search Console by Sam's Google account (a TXT
  record on `@`, which must stay).
- **Tablets keep the `run.app` URL**, which the app has built in. Both reach
  the same service; nothing in the server or site names a host.

**Why.**
- Free, with the certificate managed, and it passes WebSockets and SSE through.
  Firebase Hosting in front would cut the browser stream at 60 s; a global load
  balancer costs about $18 a month for the same result; Cloudflare's proxy
  would need a Worker to set the host.
- Domain mapping is a preview feature, which is acceptable here.

**Revisit if.** Mappings leave preview in a way that changes them, the
service moves to a region without them, or the crew trips over logging in
separately on `www` (then redirect `www` to the bare domain).

## 24. The owner may choose a car's token

**Decision.**
- `admin.sh set-token <car>` and `add-car … --choose-token` take a token the
  owner types (twice, without echo) or reads from a `chmod 600` file
  (`--token-file`). It replaces the old one at once, as a rotation does.
- A token is 8 to 128 characters from `A–Z a–z 0–9 . _ ~ -`: what a bearer
  token carries unchanged, and what a person can type into the tablet. Generated
  tokens (`obd2_` and 43 characters) still exist and fit the same rule.
- Two cars cannot share a token; the second is refused.
- Still stored as a plain SHA-256, and the stored hint is 4 characters, or a
  quarter of a short token.

**Why.** Sam wanted a token that is easy to set on both the backend and the
tablet, and does not need it to be very secure (2026-09-26). The contract
leaves the format open: a token is "issued by the backend's owner" (§8).

**What it costs.**
- A chosen token can be guessed, and **tablet requests are not rate-limited**,
  so a short or obvious one could be found by trying. A guesser could send data
  as that car and read its crew messages; they could not see the crew's side
  or anything of another car's.
- A lookup by hash cannot use a salted, slow hash, so someone holding a copy of
  the Firestore data could test guesses offline.

**Revisit if.** The site or its data become worth protecting more: then
rate-limit failed tablet logins, or go back to generated tokens only.

## 25. The admin page, behind Google sign-in and an allowlist

**Decision.**
- `/admin` manages cars (add, rename, replace a token, set the crew passcode,
  remove) and their sessions (list, which is live, delete). `admin.sh` keeps
  working, and **both call the same rules** in `:admin` (`CarAdmin`).
- **Sign-in is "Sign in with Google"**: the page gets an ID token, and the
  server checks it with Google's `TokenVerifier` (signature, expiry), then the
  audience (our client ID), the issuer, `email_verified`, and the allowlist.
  There's no client secret and no redirect; we ask only for `openid` and
  `email`. The OAuth consent screen stays in Testing with Sam as its test user,
  so Google itself refuses anyone else.
- **The allowlist is `ADMIN_EMAILS`**, from the Secret Manager secret
  `admin-emails` (kept out of the public repo, not secret in itself). Empty
  means nobody. Changing it: add a version, then deploy. It can't be changed
  from the page, so a stolen sign-in can't add itself.
- **The sign-in is a cookie**, `adm1.<email>.<expiry>.<hmac>`, with the crew
  key under its own tag: `HttpOnly`, `Secure`, `SameSite=Strict`,
  `Path=/api/admin`, 30 days (Sam). **The allowlist is checked on every
  request**, so removing an email ends that sign-in at once.
- **Every change must come from our own page** (its `Origin` must name the
  host), on top of `SameSite=Strict` and JSON-only bodies. `/admin` can't be
  framed (`X-Frame-Options: DENY`, `frame-ancestors 'none'`).
- **Every change is logged** with who made it, and never a token or passcode.
- **Generated tokens are shown once**; chosen ones are typed twice, and
  chosen tokens and passcodes are **typed in the open**, not in password fields
  (Sam, 2026-09-27): the owner types them to hand on, so seeing them beats
  hiding them. The server still keeps only their hashes. Replacing a
  token asks for "Replace" to be typed, removing a car its slug, and deleting a
  session the first 8 characters of its id.
- **A session can't be deleted while its tablet is live on it, or while its
  upload is incomplete and was active in the last 5 minutes**: deleting it then
  wouldn't stick, since the next chunk gets `not_open` and the tablet re-sends
  the session from line 0. `admin.sh delete-session` shares the upload rule.
- **No rate limit on sign-in:** a Google ID token can't be guessed, and one
  shared limit would let anyone lock Sam out.
- **Off unless configured:** without `GOOGLE_CLIENT_ID` the page says it isn't
  set up. Locally, only the dev server offers a dev sign-in, which production
  refuses (tested).

**Why.** Sam wanted to manage cars and tokens from a page, without a password
of our own to build or keep (2026-09-26). Google sign-in brings his account's
own protections, and the allowlist keeps it to him.

**Revisit if.** More people need access with less than everything (roles), or
the page ever needs to act without a Google account.

## 26. Past sessions: prepared once, as a summary and a series

**Decision.**
- **Each session is read once**, streamed (never held whole), into:
  - **a summary** in its Firestore record (times, signals, track and laps,
    the best lap never a pit lap, faults, gaps), for the list. Part of the
    record's mapping, so every conditional write keeps it;
  - **a prepared series**, `sessions/{id}/series-v{N}.json.gz` beside the
    log: every signal as columns (times after `t0`, values), states and flag
    sets as their changes, positions, events (stopped, fault, gap, lap), and
    the last `seq` it covers.
- **Both are derived, never the record.** The log is never changed, and both
  can be rebuilt from it; each carries a version (the series in its file
  name), and an older one rebuilds itself. A complete session is prepared
  once, after `complete` is answered (the tablet never waits) or on first
  view. One still uploading is prepared per `ackedThrough`, and older files
  are deleted.
- **Gaps are drawn as gaps**, as an explicit `null` in the series: where two
  samples of a signal are more than 5× its median interval apart (never under a
  second), or where a `gap` record's `seq` falls between theirs. Never
  interpolated.
- **Drives:** a car's sessions less than 10 minutes apart, end to start.
- **Public like the live pages** (decision 11), never the VIN. The exact log
  (VIN and all) downloads **from the admin page only** (Sam). The series is sent
  as stored, gzipped, with its file name as the `ETag`.
- **The map is Leaflet on OpenStreetMap's tiles** (Sam), coloured by speed.
- **The server's JVM gets 75% of the container** (`-XX:MaxRAMPercentage=75`),
  not Java's default quarter (128 MiB of 512).

**Why.** A race with the G-meter and GPS is about 3,000 lines a minute: a
3-hour one is 540,000 lines, 55 MB raw. A page can't parse that. Prepared, it
is a 5.9 MB file that a chart draws directly, and preparing it takes 1.5 s
within 128 MiB (measured).

**Revisit if.** Sessions routinely run many hours at high rates, where one
file per session gets too big for a phone: then split the series by signal or
by time.

## 27. A session being driven: the archive and the live lane, merged by seq

**Decision.**
- The page (the session page, and the live page's "Whole session") takes the
  prepared series up to its `lastSeq`, then the live records with a higher
  `seq`, deduplicated, in `seq` order (contract §7). **Archive rows win**
  wherever both have a `seq`.
- **Live rows are provisional**, and shaded on the chart until the archive
  covers them. The page re-checks the series each minute (usually a `304`)
  and redraws at most once a second.
- A live session has a page before its first chunk arrives, from the live
  stream alone.

**Why.** The live lane keeps only 5 minutes (decision 19), and the archive lags
by up to a chunk (2 minutes) and sometimes more. Together they make the whole
session, and `seq` is the only key both lanes share exactly.

## 28. The car's page is one fixed dashboard, the same for every car

**Decision.**
- **One layout, in code** (Sam, 2026-09-27): the freshness banner; up to four
  gauges, six numbers and four bars; the G-meter beside the map; laps; status
  lights and trouble codes; then the crew panel, the chart ("Last 5 minutes |
  Whole session") and every other signal as a tile. On a phone, the same
  order in one column. Nothing is stored and nothing is edited on the site.
- **The slots are a list in `web/src/lib/dashboard.ts`** (`SLOTS`), chosen by
  Sam from what his car sends: gauges `engine.rpm`, `vehicle.speed`,
  `engine.coolant_temperature`, `control_module.voltage` (charging); the
  number `gps.speed`; no bars yet; status `diagnostics.mil`,
  `fuel.system_1_status`. A signal in a slot isn't repeated as a tile.
  Changing one is a line and a deploy.

  > **Amended by M11:** **a slot is a list of signals, and shows the first
  > the session declares** (else the first with a reading). A tablet sends
  > only what its own dashboard shows plus chosen extras, so the first drive
  > carried `vehicle.system_voltage` and not `control_module.voltage`, and the
  > charging gauge was blank. Charging is now either, with the same zones.
- **Ranges come from the unit, zones from the signal**, and both are generic
  engine knowledge, never one car's (the app's decision 33): coolant caution
  over 105 °C, critical over 115; voltage caution under 12.0 V, critical
  under 11.5; engine speed over 6,000 and 7,000. Colour only; nothing
  notifies anyone.
- **Sections appear with their data**: the G-meter once both
  `motion.acceleration.*` axes have come, the map once a `gps.position` has,
  laps once there's one. A car that sends none gets no empty boxes; a slot's
  signal a car doesn't send reads "—".

  > **Amended by M10** (Sam, 2026-09-27): **the G-meter and the map are always
  > shown**, the G-meter saying "No readings" and the map the whole world under
  > "Waiting for GPS", until their first reading. An empty box says nothing
  > has come, where a hidden one says nothing; found when the tablet's first
  > real connections sent neither. **Laps still wait** for a lap: they mean
  > something only at a track.
- **Every reading shows when it's out of date**: stale once its last reading
  is 5 times its usual interval old (the history's median gap, never under
  2 s), "stopped" if the tablet said so. A signal read fewer than twice in the
  5 minutes isn't judged by its own pace; the banner covers a quiet car.
- **Units are the viewer's**, metric or US, kept in their browser (per host):
  every reading, gauge scale, zone, tile and chart axis converts. The
  G-meter stays in g.
- **Laps while live** come from the archive's series plus the live lap
  events after it, by `seq` (decision 27), re-checked each minute.
- **The server doesn't change**: the page reads the live stream (decision 19)
  as it did.

**Why.** Sam wants one dashboard per car, and a fixed one is most of the value
for a fraction of the work: no storage, no editor, no contract change, and
nothing the tablet has to mirror. A per-car form on the admin page (slots,
ranges, redlines) is the way on, if the generic ranges ever bother him.

**Revisit if.** Cars with very different engines share the site (a diesel's
redline, an EV's lack of coolant), or Sam wants a different layout per car.

## 29. The site takes the Bad News Bears look, as roles

**Decision.**
- **The team's logo** (the app's `docs/branding/bnb-logo.pdf`, only read)
  gives the colours, as the tablet has them (its decision 100): accent sky
  blue `#01B7F9`; in range and "live" mint `#67EFE6`; caution light pink
  `#FFA9DE`; critical, errors and trouble codes hot pink `#FF0099`; offline
  and no data grey; the dark neutrals for background, panels, lines and text.
  **No amber and no red.** The same values as the tablet, so a colour means the
  same on both.
- **Colours are roles in `web/src/app.css` and nowhere else.** The chart
  (uPlot) and the map (Leaflet) draw on canvas, so `theme.ts` reads the
  variables at run time. A test fails on any colour written elsewhere in
  `src/`.
- **Legibility is a test:** text, caution, critical and in range at 3:1 or
  more on the background and on a panel; caution, critical, in range and the
  accent at least 25 apart in CIE Lab.
- **The logo and the tab's bear** are made from the PDF into `web/src/assets/`
  by `web/scripts/make_images.sh`, converted from Display P3 to sRGB first.

**Why.** Sam asked for the logo as the site's colour scheme. As roles, the
look changes in one file, and a colour always means one thing.

## 30. What a session is: a car's, the tablet's alone, or test data

**Decision.**
- **The session record's `source` is read and shown** (contract §20, §21):
  absent for a car's session; `tablet` shown as **"Tablet only"**; `fake`
  shown as **"Test data"** in the caution colour; any other value shown as
  sent, never guessed at. On the car page's session line, the session list
  and a session's page.
- **Test data is never part of a drive**: each fake session is a drive of its
  own and doesn't bridge the real sessions either side. The site keeps no
  bests or peaks across sessions, so a drive is what "never count it" means
  here. **Tablet sessions still group**: they are the tablet's real signals,
  usually the minutes around a drive.
- **Kept in the header and the summary** (version 2), so a session stored
  before this gains it on its first view.
- **The tablet's clock is measured, flagged, never corrected**: the live lane
  keeps the smallest (arrival minus newest `wall`) over the last 50 batches,
  and the admin page says "Tablet clock 10 h 58 min slow" past 2 minutes. The
  times on the site stay the tablet's (contract §3); the fix is the tablet's
  clock. Not on the public pages.

**Why.** The first real drive (JOURNAL, 2026-09-27) listed six setup sessions,
one of them invented readings, as part of the drive, and was dated 1:27 AM by
a tablet 11 hours slow, with nothing on the site to say either.

## 31. Laps: the tablet times them, the server keeps the books

**Decision.** (Contract §22, agreed 2026-09-27.)
- **The tablet times laps and sectors, and its numbers are the results**
  (Sam: "the tablet's number wins, it is the source of truth for the location
  data"). The server stores its `lap` records whole and shows them, and
  **never overrides** one timed on the course version that is current.
- **The server re-times** only laps timed on an older version of a course
  (after a line moves) and sessions with no laps, from the tablet's own fixes
  on `fixAt`, by the tablet's rule, each marked as re-timed; where both exist,
  it flags a disagreement and never replaces the tablet's number (M13).
- **What only the server knows goes down to the tablet** in `timing`: the
  driver, the stint, the race, the event's bests (M17).

**Why.** The tablet has every fix the moment it's taken, times offline, and
must keep the live delta itself; the server sees coalesced fixes live and
the rest only later. So the tablet is the timer, and the server is where
courses, drivers, events and results live.

## 32. Courses live on the website, versioned, and go down to the tablet

**Decision.**
- **A course is drawn on the admin page**, and only there: layouts (closed,
  in the direction cars go), a start/finish, sector lines, the pit lane,
  `pit_in`/`pit_out` (a stop's length) and `pit_line` (where an in-lap ends).
  Only the admin edits; **everyone sees** courses, on `/courses`.
- **Every save is a new version**, and old ones are kept: a lap names the
  version it was timed on. A course is never deleted once laps were timed at
  it.
- **The server's `CourseRules` are the only rules**: the editor asks them as
  you draw (`POST /api/admin/courses/check`), the save and `admin.sh
  import-course` apply them. Lines 1–200 m, sectors 1…n per layout, one
  start/finish per layout, one default layout, closed layouts, ≤ 256 KB.
- **Down to the tablet**: `GET /v1/courses` (any car's token, one `ETag`,
  `304`) and the `courses` frame after `hello` and on every save, to a tablet
  listing `courses.1`. Stored in Firestore with the GeoJSON as text (no arrays
  inside arrays there).
- **Imagery**: OpenStreetMap, and USGS The National Map's orthoimagery
  (public domain, US only) for placing lines.

**Why.** One source for every line, so the tablet and the results agree; a
line moved at the track reaches every tablet and re-times the past, visibly.

## 33. Re-timing: per run of the app, what stands, and a 2 ms check

**Decision.** (M13; decision 31's "the server re-times", made exact.)
- **The server re-times with the tablet's own rule**, ported line by line
  (`:timing`'s `LapRule`: each move between fixes, a line counted only
  forwards and interpolated along the move, the arming, only the next
  sector, the pit line ending an in-lap), on the tablet's own fixes at
  `fixAt`, else `at`.
- **Per run of the app, not per session** (§22.8): one car's sessions from
  one `device`, back to back, `at` rising, none more than 12 h apart. A lap
  across an OBD drop is one lap, belonging to the session it ended in, and is
  numbered through the run, as the tablet numbers it.
- **What stands**, lap by lap: every tablet lap on the course's current
  version (and layout), as sent; and every re-timed lap that doesn't share
  more than half its time with one of those. A tablet lap on the current
  version **agrees** if a re-timed lap starts and ends within **2 ms** of it
  (the tablet times on nanoseconds, the log keeps milliseconds); otherwise
  it's **flagged**, on the session page and once in the service's log, and
  still stands.
- **Which course:** the one a session's laps name, else the first its fixes
  touch (its fixes' bounds against the course's lines, with 50 m round).
- **When:** after a session is prepared, and for every run a course touches
  when it's saved, in the background, one run at a time; on view if missing.
  A removed course takes its re-timings with it.
- **Stored** beside the run's first session,
  `timing-v{rule}-{course}-{version}.json.gz`, naming the run's sessions and
  each one's `wall − at`; rebuilt when the rule, the course's version or the
  run changes; only the newest of a course kept.
- **Shown to the millisecond**, as a `lap` record has it: re-timing's doubles
  differ in the 7th place, and equal sectors must be equal.

**Why.** The same fixes by the same rule give the same laps, so agreement
is a check on both sides, and a line moved at the track re-times the past
without anyone re-driving it. Per run, because the tablet's timing carries
across its sessions; to the millisecond, because that's what the tablet's
records carry.

## 34. Events, parts and who drove

**Decision.** (M14.)
- **Drivers** are one list for every event and car: a name and a code of
  2–4 capitals, unique; a driver who drove stays (rename instead).
- **An event** is at one course and layout, with the cars entered, **any
  number of practice parts and at most one race**, each a window of time,
  never overlapping, none over 30 hours.
- **A session is in a part by the server's time**: its car entered, the
  server hearing it (`created` to `updated`) during the window; the part it
  overlaps most if two; **plus by hand, minus by hand**, and by hand wins.
  Never test data (§21). The tablet's own clock never places a session: it
  was 11 hours out on the first drive.
- **Who drove** is set per session on its page, by the admin or **the car's
  crew** (the passcode they use for messages), each change logged with who;
  public to read. Two routes, one rule, because each sign-in's cookie reaches
  only its own paths.
- **Practice results are public and computed on view**: the laps as they
  stand on the event's course and layout (decision 33), each driver's best on
  track (§18), the best of each sector (§22.6's in- and out-lap rule), per
  part and over all practice. Laps on another layout are shown, never counted.
- **`admin.sh`** can do what the admin page does for drivers and events
  (`add-driver`, `remove-driver`, `import-event`, `remove-event`), so a race
  weekend can be set up from a file, and a proof needs no Google sign-in.

**Why.** Endurance racing means many sessions, and a crew that knows who's in
the car; the server's clock is the only one to trust, and a person can put
right what it heard late. Results on view stay right when a line moves.

## 35. The race: one timeline, stops, stints, flags as annotations

**Decision.** (M15.)
- **A car's race is its race sessions' runs, made one timeline**, laps
  numbered from the first its race sessions timed. The tablet keeps running
  through a power cut (its own battery), so a driver change is normally
  inside one run and its timing carries on; **across a restart of the app**
  the gap between the last crossing and the next is **one lap, marked**,
  timed on `wall` (an out-lap after the pit line, an in-lap before it).
- **Everything stays on the tablet's clock**, however far out: laps, stops,
  stints and stored stint boundaries. A constant offset cancels in every
  difference. The server's clock orders runs.
- **The green flag and the chequered flag are annotations, never a cut-off**
  (Sam): entered by the admin or the crew as real times, placed on laps with
  the tablet's offset (the smallest `created − started` over the race's
  sessions, good to seconds), and every lap counts either side of them.
- **A stop is the time in the pit lane**, from drawn `pit_in` and `pit_out`,
  **else lines made where the lane comes clear of every layout by 16 m**: a
  pit lane begins and ends on the track (NHMS's does), where a line would be
  crossed by cars that never pit. A stop is on its in-lap.
- **Stints split at every stop by default**, each stint's driver the one set
  on the session its first lap ended in; **the admin or the car's crew**
  merges, splits at a lap, and names drivers; once edited, the car's stints
  are stored whole and replace the default. A stint holds the laps that end
  after it starts: a split where lap N begins holds lap N.
- **Race results and driver pages are public and computed on view**, from
  each run's stored re-timing (decision 33).

**Why.** Endurance racing means the power off at every change, and the
server must never lose count because the car did; the crew knows who drove,
and must be able to say so without the admin's sign-in. Flags as annotations
keep every lap the tablet timed, which is the tablet's to give (decision 31).

## 36. Results worth reading: laps linked, what they add up to, two compared

**Decision.** (M16.)
- **Every lap links to its moment**: its session's page with the lap's
  `?from=&to=` on `wall`, the chart zoomed and the map drawing only that
  stretch; choosing a lap on the page writes it back, so a view can be shared.
  **A session in an event is timed on the event's course** first, not the
  first course its fixes touch.
- **What the laps add up to**, over laps on track only (no in- or out-laps, no
  lap across a restart; another layout's never): the **theoretical best** (the
  best of each sector added up, by §22.6's rule, only when every sector has
  one); **sectors driver by driver** with the gap to the best of all; and
  **consistency** (`:timing`'s `Consistency`: the laps, best, median, the
  standard deviation, and how many within 1% of the best). Practice has them
  per driver, the race per car and per stint.
- **The race's lap chart is lap times by lap number** (Sam), a line per car,
  stints shaded, stops and flags marked; a lap slower than 130% of the car's
  best on track is drawn at the top edge, so racing laps keep the scale.
- **Two laps compared by distance along the course's own line**, at
  `/compare` (public, both laps in the address), in the browser from the two
  sessions' series: positions projected onto the layout (looked for from 20 m
  behind the last fix to 250 m ahead, since a course passes near itself); a
  lap's ends where its positions cross the line **on their own clock**
  (`wall` of `at`, later than the lap's `fixAt` crossings by the tablet's
  delay); signals resampled every metre; the running delta from each lap's own
  start; speed from the laps where a drive has no speed signal. The first pick
  is held in the viewer's browser only.
- **A session's series ETag is its key and the log's hash**, so a replaced
  file is never served from a cache.

**Why.** A lap time alone says little: the crew wants where the time went, who
is quick where, and who is steady, and to go straight to the moment in the
data. Distance is the only axis two laps share whatever line each took, and
it's the course's own line that makes it the same for every car.

## 37. Live: what the server knows, while the car is being driven

**Decision.** (M17.)
- **Sessions not complete yet count.** Every session a car streamed and that
  isn't complete is held from the live lane (its `lap` records whole, its
  fixes), joined into runs of the app and **re-timed as a complete run is**:
  a provisional run, never stored, let go once the session completes (its run
  is then re-timed whole). A session first seen mid-drive is read from its
  partial archive first; after a reconnect, the archive is read again for the
  laps sent while the link was down.
- **`timing` (contract §22.7)** goes to a tablet listing `timing.1` on every
  `session` frame, and when where the car stands changes (compared without
  ages): the course (the event's, else its laps'), at its current version;
  this car's best and best sectors in the event (else on the layout); who's
  driving and since when; in a race, its lap count and when the car left the
  pits. Ages are from the tablet's `wall` now, by the live lane's measured
  offset.
- **Who's driving now**: stints the crew edited say who; else the session's
  driver as set (the car page's picker sets it); else the stint's. A stint
  outside a race runs from the last pit exit.
- **The car page** shows the same (moments on the server's clock, counted on
  by the page), each lap's sectors with each sector's best marked, and, for
  the crew, who's driving. **The event page** counts the sessions being
  driven, their laps marked live, and asks again every 30 s while a part is
  on; results are held 10 s, emptied by any edit.
- **`complete` answers a repeat at once** (contract §23), one at a time per
  session, reading segments ahead.

**Why.** Race day is live: the pit wall needs the lap count, who's in and for
how long, and the tablet needs what only the server knows, while the car is
out, not after the session is uploaded. Holding the live run as a provisional
run means one set of rules, the same as for complete sessions, and results
that settle into the complete ones without a lap lost or doubled.
