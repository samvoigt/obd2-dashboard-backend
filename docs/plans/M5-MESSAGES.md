# M5 — Crew messages

**Status: drafted 2026-09-26, written against the code as it stands after M4,
the telemetry contract v1 at app commit `918fa1e` (§5.2 and §5.4), and Sam's
answers (below). Nothing is built.**

**What M5 delivers:**
- **The crew can send the car a message** ("PIT NOW") from the website, and
  **clear** it.
- **They see it go queued → received → displayed**, then cleared, expired or
  replaced, live.
- **Only crew see any of it:** a per-car passcode logs a browser in for 30
  days, and public viewers see the live data but never the messages.
- The server keeps the contract's promises: the complete active set after
  every `hello`, time remaining (never a deadline), never an expired message,
  one message at a time.

**The app built its side already** (its M34: the live lane, crew messages and
the message widget, contract §15's note) and is waiting on this. Where this plan
and the contract disagree, the contract wins.

---

## What exists, and what it means for this

- **The socket half is in place, with messages empty.** `TabletSocket` answers
  `hello` with `welcome` and `ServerFrames.messages()`, which is always
  `{"active":[]}`. `received`/`displayed` are parsed (`TabletFrames`) and then
  ignored (`CarLive.apply`). M5 fills those two places in.
- **The passcode is stored and checkable.** `Car.passcodeHash` (PBKDF2, M2),
  `Passcodes.verify` (constant time) and `admin.sh set-passcode` all exist. **Nothing
  checks a passcode yet**, and nothing issues a login.
- **`admin.sh set-passcode` needs a terminal** (`ConsoleIo`), so it cannot set a
  throwaway car's passcode in a scripted live check (M5.8). A
  `--passcode-file` option is needed for that; see M5.4.
- **Live state is in memory, and a deploy drains to a new, empty revision**
  (decisions 19, 20). If messages were only in memory, a deploy mid-race would
  empty the active set, and the tablet's next sync would **take "PIT NOW" off
  the driver's screen** (§5.4: it takes down anything not in the sync). So
  messages must be stored, and Firestore is already set up for the runtime
  account.
- **The browser stream is public** (`/api/cars/{slug}/live`, M4). Message events
  must reach only crew browsers, so the stream has to know who is asking.
- **`deploy.sh` passes `--clear-secrets`**, noted in M2 as the place a future
  secret would replace. The login cookie's signing key is that secret.
- **The replay does not answer messages.** It has to (with `received`, and
  `displayed` unless it plays a dashboard with no message widget) to test the
  whole path without the tablet.

## Settled with Sam, 2026-09-26

1. **Messages are crew-only.** Viewing a car's page stays public (decision 11),
   but messages and their states are shown only to a browser logged in with
   that car's passcode.
2. **A crew login lasts 30 days** in a browser. **Changing the passcode logs
   everyone out.**
3. **A message stays up until cleared, capped at 30 minutes**, by default. The
   sender may choose a shorter life for a message.
4. **The contract is re-pinned to `918fa1e`.** The tablet's §15 (GPS) and §16
   (laps) are confirmed in the backend's §17. Neither changes anything here.

And from the contract (§5.4, agreed earlier): display-only, with the driver
never acknowledging anything; `received` on arrival and `displayed` only when a
widget draws it; one message on screen at a time, a newer one replacing it;
`ttlMs` as time remaining; the full active set after every `hello`; unknown ids
in `received`/`displayed` ignored; 40 characters of text.

## Decided here, not asked (say if any is wrong)

- **Messages are stored in Firestore** (`messages`, one document each), with a
  write-through cache in the hub, so **a message survives a deploy**. Fields:
  - `car`, `id` (`m_` plus random), `text`, `preset?`;
  - `sentAt`, `expiresAt`;
  - `state`;
  - `receivedAt?`, `displayedAt?`, `clearedAt?`, `expiredAt?`, `replacedBy?`.

  **Kept after they end**, as a record of what the crew told the driver, which
  M6 can show beside a session. Owner deletion follows decision 16.
- **One active message per car.** Sending a new one marks the old one
  **replaced** (a crew-facing state; on the wire the tablet simply replaces it,
  §5.4). The active set in a sync therefore has at most one message.
- **States only move forward**: queued → received → displayed, then cleared,
  expired or replaced. A late `received` after `displayed` changes nothing, and
  anything for a finished or unknown id is ignored (§5.4).
- **Expiry is the server's clock.** A message expires at `expiresAt`: a timer
  marks it and tells crew browsers, and the tablet takes it down on its own
  clock (`ttlMs`). `ttlMs = expiresAt − now` and `ageMs = now − sentAt`, both
  computed when sent. An expired message is never sent.
- **Delivery:** if the car's tablet is attached, `message` goes out at once;
  otherwise it stays **queued** and the next `hello` sync carries it. `clear`
  goes out at once if attached; if not, the next sync leaves it out, which
  takes it down (§5.4).
- **The login is a signed cookie, per car:**
  - `crew_<slug>`: HMAC-SHA256 over slug, expiry, and a fingerprint of the
    passcode's hash (so a new passcode invalidates every cookie);
  - `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/cars/<slug>`,
    `Max-Age` 30 days;
  - endpoints: `POST /api/cars/{slug}/login` (`{passcode}`),
    `DELETE /api/cars/{slug}/login`, and `GET /api/cars/{slug}/crew` →
    `{crew: bool}`.
  - A car with no passcode set cannot be logged into, and says so.
- **The signing key is a Secret Manager secret, `crew-cookie-key`** (32 random
  bytes, never printed), mounted as `CREW_COOKIE_KEY`. `deploy.sh`'s
  `--clear-secrets` becomes `--set-secrets` for exactly that. The runtime
  account may read that one secret. The dev server makes a random key per run.
- **Login attempts are rate-limited per car:** 10 failures in 10 minutes → `429`
  with `Retry-After`. In memory, which is right for one instance
  (decision 7). PBKDF2's cost (~0.3 s an attempt) adds to it.
- **Cross-site requests are refused by construction:** `SameSite=Strict`, and
  every crew call is a JSON `POST`/`DELETE` (`Content-Type: application/json`),
  which a cross-site form cannot send.
- **Crew-only events, on the same stream:** the SSE route checks the cookie
  when it connects, and only a crew stream carries `message` events and message
  states in its snapshot. Public streams are unchanged. A test searches a
  public stream's bytes for a message's text.
- **The crew API:**
  - `POST /api/cars/{slug}/messages` with `{text, preset?, ttlSeconds?}`:
    text trimmed to 1–40 characters (`400` otherwise); `preset` one of the
    contract's (`pit`, `box`, `fuel`, `push`, `slow`) or absent;
    `ttlSeconds` 60–1800, default 1800 ("until cleared", capped);
  - `DELETE /api/cars/{slug}/messages/{id}` to clear;
  - `GET /api/cars/{slug}/messages` for the most recent 20.

  All crew-only (`401` otherwise).
- **The website's message panel** (crew only; a passcode form otherwise):
  - **preset buttons that send at once**: "PIT NOW" (`pit`), "BOX THIS LAP"
    (`box`), "FUEL" (`fuel`), "PUSH" (`push`), "SLOW DOWN" (`slow`). Speed
    matters more than a confirm step, and **Clear** sits right beside them to
    undo;
  - free text with a live 40-character count;
  - a lifetime choice: until cleared (30 min), 10, 5, 2 or 1 min;
  - the current message large, with its state and age counting;
  - the recent list below.
- **The replay answers messages** (`received` on arrival, `displayed` unless
  `--no-widget`), keeps the earlier deadline on a duplicate id, and takes
  messages down on `clear`, a sync, or `ttlMs`, as the contract asks of the
  tablet. It logs each one.
- **`admin.sh set-passcode --passcode-file <file>`**, for scripted checks with
  a throwaway car. The file must be `chmod 600`, or it is refused.
- **Where the code goes:** the message rules in `:live` (pure, with a
  `MessageStore` and an in-memory fake), and Firestore in `:archive-gcp`,
  which already has the client and the BOM, beside `FirestoreSessionIndex`.

---

## The steps

Each is validated against the code again before it is built, and after each,
the *next* step's plan is checked against what was actually built (Sam,
2026-09-26).

### M5.1 — The message rules  `opus`

> ✅ **Done 2026-09-26.** `Messages.kt` in `:live`: `MessageState` (forward-only
> by rank), `Message`, `MessageStore` with its atomic `update`,
> `InMemoryMessageStore`, and `Messages` (send replacing, received, displayed,
> clear, expireDue, active, recent, and the three frames in the app's shapes).
> Text is counted in characters, not UTF-16 units, so 40 flags fit.
>
> **13 tests. 9 mutations killed.** Two survivors are **equivalent**, being
> deliberate double checks:
> - `canBecome`'s `active &&`, since ended states share the top rank;
> - `expireDue`'s outer filter, since the same condition inside the atomic
>   update is the real guard against a stale read.

> **Validated against the code 2026-09-26, before building.** No conflict. Now
> fixed by what exists:
>
> - **The wire shapes are the app's, read from its code** (`LiveFrames.kt`,
>   `ServerFrame.parse`). An `active` item and a `message` frame carry `id`,
>   `text`, `preset?`, `ageMs` and `ttlMs`, and `messages` must have `active`
>   (the app reads it with `!!`). Items go without a `t`. The app shows the
>   newest item (smallest `ageMs`), which agrees with one active per car.
> - **`MessageStore.update(id, change)` is an atomic read-modify-write**
>   (Firestore: a transaction, in M5.2), so a `displayed` on the old revision and
>   a `clear` on the new during a deploy's changeover cannot undo each other. The
>   forward-only rule lives in `change`, not in the store. `send` is serialised
>   per car by a lock in `Messages`: replace the active one, then create the new.
> - **Ids are `m_` and 16 random hex digits** (`SecureRandom`).
> - **Time is a `Clock`**; the `MutableClock` from `:live`'s tests serves here.

`:live`, pure:
- `Message` and `MessageState`;
- `MessageStore` (create, update, active for a car, recent for a car) and
  `InMemoryMessageStore`;
- **`Messages`**, the rules on a `Clock`:
  - `send(car, text, preset, ttl)`: replaces any active one;
  - `received(id)`, `displayed(id)`: forward only; unknown ids ignored;
  - `clear(id)`;
  - `expireDue(now)`;
  - `active(car, now)`: with `ttlMs` and `ageMs`.
- **Frames:** `message`, `clear`, and `messages {active}` built from the
  active set.

**Done when:**
- Tests on a `MutableClock` cover:
  - replacement leaves one active and marks the old `replaced`;
  - forward-only states, including a late `received` after `displayed`;
  - unknown and finished ids ignored;
  - `ttlMs`/`ageMs` exact at send and at a later sync;
  - an expired message never in the active set, nor sent;
  - the 60–1800 s bounds, the default, and text trimmed and limited to 40;
  - the frames' exact JSON.
- Mutations killed.

### M5.2 — Messages in Firestore  `sonnet`

> ✅ **Done 2026-09-26.** `FirestoreMessageStore` in `:archive-gcp` (`:live` now
> an API dependency there): `create()`, `update` as a transaction, and the two
> queries.
>
> **Found by the smoke run:** `recent` (`car` + `sentAt` descending) needs a
> **composite index**; `active` (`car` + `state in`) does not. `gcp-setup.sh`
> creates it only if missing (READY after about 4 minutes); a rerun was clean,
> with one index.
>
> **3 mapping tests.** `scripts/message-smoke.sh` passed **10 checks** against
> the real database and left nothing.

> **Validated against what M5.1 built, 2026-09-26, before building.** No
> conflict. Now fixed by what exists:
>
> - **`FirestoreMessageStore` in `:archive-gcp`**, beside
>   `FirestoreSessionIndex`, with the same patterns: `update` as a
>   `runTransaction`, absent fields left absent, time as `Timestamp`.
> - **Two queries, and they may need composite indexes:** `active(car)`
>   (`car ==`, `state in [queued, received, displayed]`) and `recent(car)`
>   (`car ==`, `sentAt` descending). The smoke run shows whether Firestore asks
>   for them. If it does, `gcp-setup.sh` creates them, idempotently.
> - **`create` uses Firestore's `create()`**, which refuses an existing id, as
>   the in-memory store does.

- `FirestoreMessageStore` in `:archive-gcp`: a document per message; `active`
  as a query on `car` and `state`.
- A composite index if Firestore asks for one, declared in `gcp-setup.sh`.
- Absent fields stay absent.

**Done when:**
- A mapping test.
- A smoke run against the real database: send, replace, receive, display,
  clear, expire, a query for the active set; then deleted.

### M5.3 — Messages on the tablet's socket  `opus`

> ✅ **Done 2026-09-26.**
> - `CrewMessages` joins `Messages` and the hub: send pushes `message`, clear
>   pushes `clear`, `sync` expires then builds the active set, the reports are
>   applied, and every change is published as `MessageChanged`. Expiry is
>   scheduled at send; the scheduler is a parameter, so a test runs it at once.
> - The socket sends the real sync after `hello`.
> - `TabletHandle.send`, `LiveHub.toTablet` and `publish`.
> - `Messages.received`/`displayed`/`clear` take the car, and ignore another
>   car's message (a test).
> - The public encoder drops `MessageChanged`, and `ServerFrames.messages()` is
>   gone.
>
> **7 socket tests**:
> - queued while away → in the next sync, with `ttlMs`;
> - pushed while attached;
> - a clear pushed, or while away left out of the next sync;
> - reports moving the state, repeats and unknown ids with no error;
> - **after a restart (a new hub, same store) the sync still carries the
>   message**;
> - the expiry timer publishing `expired`;
> - the public encoder dropping it.
>
> **7 mutations killed.**

> **Validated against what M5.1–M5.2 built, 2026-09-26, before building.** One
> gap in M5.1, fixed here:
>
> - **`received`/`displayed` must check the car.** As built, they would accept a
>   report for **any** car's message id. Ids are random, so this is unlikely,
>   but a tablet must only ever move its own car's messages. They become
>   `received(car, id)` and `displayed(car, id)`, and another car's message is
>   ignored like an unknown id. M5.1 gains a test.
> - **The crew side needs to reach the tablet's socket.** `TabletHandle` gains
>   `send(frame)`, and the hub gains `toTablet(car, frame)`, false if no tablet
>   is attached (the message then stays queued for the next sync).
> - **Expiry both ways:** a timer set at send, publishing `expired` to crew
>   browsers, plus a lazy `expireDue` on every sync, send and crew snapshot, since
>   timers do not survive a restart or drain.
> - **A `CrewMessages` service in `:server`** joins `Messages` and the hub: send,
>   clear, sync, the reports, and publishing each change as a
>   `LiveUpdate.MessageChanged` update.
> - **Public browser streams must not carry it from this step on.** M4's
>   `encode` drops `MessageChanged` for now, with a test; crew streams are M5.5.
> - **`ServerFrames.messages()` (always empty) is removed**, and the socket sends
>   `Messages.syncFrame`.

`:server`:
- after `hello`, `messages` with the car's real active set;
- `message` and `clear` pushed to the attached tablet;
- `received`/`displayed` applied;
- an expiry timer;
- every state change published through the hub as a `LiveUpdate.Message` for
  crew browsers.

A message sent while no tablet is attached stays queued, and reaches the tablet
in its next sync.

**Done when:**
- WebSocket tests cover:
  - a message queued while away arrives in the next `hello`'s sync;
  - one sent while attached arrives as `message`, with the right `ttlMs`;
  - a `clear` while attached → `clear`; while away → left out of the next sync;
  - `received` then `displayed` move the state, a duplicate changes nothing,
    and an unknown id is ignored with no `bad_message`;
  - **after a simulated restart (a new hub over the same store), the sync still
    carries the active message**.
- Mutations killed.

### M5.4 — Crew login  `opus`

> ✅ **Done 2026-09-26.**
> - `CrewAuth` (issue, verify in constant time, 30 days, the passcode
>   fingerprint) and `LoginLimiter` (10 in 10 minutes, per car).
> - `CrewRoutes`: login (PBKDF2 on `Dispatchers.Default`), logout, `crew`, with
>   `pathCar` and `isCrew` for M5.5.
> - `module(…, crewKey)` is required; `main` reads `CREW_COOKIE_KEY`.
> - `gcp-setup.sh`: the Secret Manager API back, `crew-cookie-key` generated and
>   never printed, and access for the runtime account only. It ran twice cleanly.
> - `deploy.sh` mounts it with `--set-secrets`.
> - `admin.sh set-passcode --passcode-file`, which refuses a file others can
>   read.
>
> **11 login tests** and **1 admin test**. **9 mutations killed.** The one that
> first survived was the slug check, caught only by accident through the
> salted fingerprint. It now has a test of its own, with two cars given
> identical stored hashes.

> **Validated against what M5.1–M5.3 built, 2026-09-26, before building.** One
> thing M2 removed, back now:
>
> - **The Secret Manager API goes back into `gcp-setup.sh`.** M2.6 dropped it
>   along with the old tablet key; `crew-cookie-key` needs it. The runtime
>   account may read that one secret, and `deploy.sh` swaps `--clear-secrets`
>   for `--set-secrets CREW_COOKIE_KEY=crew-cookie-key:latest`.
> - **`module(…, crewKey = …)` is required**, with no default: a random default
>   would quietly log every crew member out at each restart if production ever
>   forgot it. `main` reads `CREW_COOKIE_KEY` (base64url, at least 32 bytes) and
>   refuses to start without it. Tests pass a fixed key, and the dev server a
>   random one.
> - **PBKDF2 (about 0.3 s) runs on `Dispatchers.Default`**, never on the thread
>   that handles requests. Tests keep registries at 1,000 iterations; `verify`
>   reads the count from the stored string.
> - **Cookie:** `v1.<slug>.<expiry>.<fingerprint>.<hmac>`. A slug cannot hold
>   `.`, so it splits cleanly; the fingerprint is the first 16 hex digits of
>   SHA-256 over the stored passcode hash. `Path=/api/cars/<slug>` covers the
>   car's stream and its message endpoints, and nothing else.
> - **Answers:** a wrong passcode → `401 auth`; a car with none set →
>   `409 no_passcode`; rate-limited → `429` with `Retry-After`; not JSON →
>   `415`, from content negotiation.

- `CrewAuth`: sign, verify (constant time), expiry, the passcode fingerprint;
- login, logout and `crew`;
- the rate limit;
- `CREW_COOKIE_KEY` required in production and random in the dev server;
- `gcp-setup.sh` creates `crew-cookie-key` and grants access to that secret
  only; `deploy.sh` uses `--set-secrets` for it;
- **`admin.sh set-passcode --passcode-file`**.

**Done when:**
- Tests cover:
  - a right passcode → the cookie's exact attributes;
  - a wrong one → `401`;
  - no passcode set → a clear refusal;
  - a cookie for another car, a tampered one, an expired one, and **one from
    before a passcode change** are all refused;
  - the 11th failure in 10 minutes → `429` with `Retry-After`;
  - a form-encoded `POST` is refused;
  - the `--passcode-file` permission check.
- Mutations killed.

### M5.5 — The crew API and crew-only events  `opus`

> ✅ **Done 2026-09-26.**
> - `MessageRoutes.kt`: `POST`/`DELETE`/`GET` messages, all crew-only, with
>   `MessageView`.
> - The SSE route decides crew or public at connect. A crew stream gets a
>   `messages` event after its snapshot and `message` events after that; a
>   public one, neither.
>
> **4 tests**:
> - `401`s, and another car's login not counting;
> - a send with its lifetime and default, five bad ones, and a form-encoded
>   one;
> - clear and the recent list;
> - **the whole path on real Netty**: a crew `POST` → the tablet's socket →
>   `received`/`displayed` → the crew stream, **while a public stream's raw
>   bytes never held the text, the id, or a `message` event**.
>
> **6 mutations killed**, "everyone is crew" among them. **Found:** Ktor writes
> SSE fields as `event: name`, with a space. My test first looked for
> `event:name`.

> **Validated against what M5.1–M5.4 built, 2026-09-26, before building.** One
> gap, resolved here:
>
> - **The hub's snapshot knows nothing of messages**, so a crew stream sends a
>   **`messages` event** (the recent 20, with states) straight after its
>   snapshot. It is fetched *after* subscribing, so a change in between arrives
>   as an update as well; the page merges by id.
> - **`encode(event, clock, crew)`**: a public stream drops `MessageChanged` as
>   before; a crew stream sends it as `message`. The crew check is made once,
>   at connect (`isCrew`); a logout takes effect on the next connect.
> - **The crew's view of a message** (`MessageView`) adds the state, every
>   timestamp and `replacedBy`, which the tablet's wire form does not need.
> - **`DELETE` carries no body.** It is still safe, because a `SameSite=Strict`
>   cookie is never sent on a cross-site request.
> - **The whole-path and leak tests run on real Netty** (M4.4's finding), with
>   the JDK's HTTP client sending the cookie and its WebSocket playing the
>   tablet.

- `POST`/`DELETE`/`GET` messages as above, all crew-only.
- The SSE stream checks the cookie at connect; a crew stream's snapshot
  carries the active and recent messages, and `message` events follow.

**Done when:**
- Tests cover:
  - each endpoint's validation and `401` without a login;
  - the whole path, from a crew `POST` to the tablet's socket and back to the
    crew stream as `received` then `displayed`;
  - **a public stream's raw bytes never contain a message's text or id**, while
    a crew stream's do.
- Mutations killed.

### M5.6 — The replay answers messages  `sonnet`

> ✅ **Done 2026-09-26.**
> - `LiveReplayer`'s `Screen` keeps one message, the earlier deadline on a
>   duplicate, and takes it down on `clear`, on a sync that leaves it out, or on
>   `ttlMs`. `received` and `displayed` are sent once each, `displayed` unless
>   `--no-widget`.
> - A `Mutex` means batches and replies never overlap on the JDK socket.
>
> **3 tests** on real Netty, with crew sends through the real API:
> - received then displayed;
> - `--no-widget` stopping at received;
> - a clear the replay never heard, taken down at its next sync.
>
> **4 mutations killed.**

> **Validated against what M5.1–M5.5 built, 2026-09-26, before building.** One
> hazard in the JDK's client, designed out here:
>
> - **The JDK's `WebSocket` allows one send in flight.** The main loop sends
>   batches, and replying `received`/`displayed` from the listener's callback
>   would collide with them. So every send goes through a `Mutex` in `Socket`,
>   and the listener's replies are launched into the replay's own coroutine
>   scope.
> - **It behaves as the app's `CrewMessages` does**, read from its code: one
>   message at a time; `received` once per id on arrival; `displayed` once per id
>   unless `--no-widget`; the earlier deadline kept on a duplicate; taken down by
>   `clear`, by a sync that leaves it out, or by `ttlMs`.
> - **Its log lines** (`message …`, `clear …`, `taken down: …`) are what the
>   tests read. Crew sends in the tests go through the real API, with a login.

`:replay`, `--live`:
- `received` at once, `displayed` unless `--no-widget`;
- the earlier deadline kept on a duplicate;
- taken down on `clear`, on a sync that leaves it out, or when `ttlMs` runs
  out;
- each logged.

**Done when:** tests against the real server module over real HTTP cover:
- a crew-sent message → the replay logs it → the state on the crew stream
  reaches `displayed`;
- `--no-widget` stops at `received`;
- a clear while the replay is disconnected takes the message down on its next
  sync.

### M5.7 — The message panel  `sonnet`

> **Validated against what M5.1–M5.6 built, 2026-09-26, before building.**
> Two points that change the page, and one about checking it:
>
> - **Crew or public is decided when the stream connects** (M5.5), so after
>   logging in or out **the page reopens its stream**.
> - **The cookie reaches the stream with nothing extra**: same origin, the path
>   `/api/cars/<slug>`, and `SameSite=Strict`, which still allows same-site
>   requests, so `EventSource` sends it. Chrome treats `http://localhost` as
>   secure, so the dev server's `Secure` cookie works.
> - **The dev server sets a generated passcode** for `dev-car`, in
>   `server/build/dev-passcode` (`chmod 600`, git-ignored), as it does the token.
> - **Typing a passcode into a page is done only against the local dev server**,
>   with that generated test value. The deployed site's crew view is not logged
>   into from the browser here: M5.8 checks the crew path there through the API
>   and the replay, and the public view in the browser. **Seeing the crew panel
>   on the deployed site is Sam's.**
> - **Pure logic in `messages.ts`** (merging the `messages` and `message`
>   events by id, the current message, its age, character counting, the
>   lifetime choices), with Vitest.

`web/`:
- the passcode form (and logout);
- preset buttons, free text with its count, the lifetime choice;
- the current message large, with its state and age;
- Clear;
- the recent list;
- everything live from the crew stream.

Pure logic (message state from events, age counting, text limits) in a `.ts`
file with Vitest.

**Done when:**
- Vitest tests, and a type check.
- **Looked at in Chrome** against the dev server with the replay answering:
  - logged out (the passcode form, and no message anywhere on the page);
  - logging in;
  - sending a preset → received → displayed;
  - a clear;
  - an expiry at a 1-minute lifetime;
  - `--no-widget` stopping at received;
  - phone width.

> **✅ Done, 2026-09-26.** `web/src/MessagePanel.svelte` below the banner, and
> `web/src/lib/messages.ts` (7 Vitest tests here, 23 in all; type check clean).
> The page asks `/crew` and reopens its stream after a login or logout.
> Looked at in Chrome at `localhost:5173` against the dev server, with a real
> drive (`outback-2026-09-24-evening-drive`) replayed live at real speed:
> - logged out: only the passcode form; a wrong passcode says so and empties
>   the field;
> - login (kept across a reload); PIT NOW → "On the driver's screen" in 3 s;
> - Clear, and the replay's `taken down: … (clear)`;
> - free text with an emoji counted as one (21/40), 1-minute lifetime: the
>   replay took it down at the minute (`its time ran out`), the page said Expired;
> - BOX THIS LAP then FUEL: the first shows Replaced;
> - logout: the form again, `/crew` false, a send is 401;
> - `--no-widget`: "On the tablet, not on screen". FUEL, still active, was
>   in the new replay's sync;
> - 500 px wide (Chrome's narrowest): no sideways scroll;
> - a cookie-less stream of the same page over 8 s: 55 events, no text, id or
>   `message` event.
>
> Found by looking: the panel lacked the page's box (`.panel` is scoped to
> `CarPage`), so it now has its own, when logged in. Mutations: 16 in
> `messages.ts`, all killed after two tests were added (age at 60 s; age with a
> clock offset). The password field opens the browser's password manager over
> the page, so in Chrome the passcode was filled by script, not typed.

### M5.8 — Deploy, and prove it live  `sonnet`

> **Validated against what M5.1–M5.7 built, 2026-09-26, before building.**
> - **Step 2 is through the API, not the deployed page** (as M5.7 settled):
>   `curl` logs in with the passcode from a `chmod 600` file, keeps the cookie in
>   a `chmod 600` jar in the scratchpad, sends and clears, and follows the crew
>   stream for the states. The deployed page is looked at in Chrome **logged
>   out**, for step 4, with screenshots.
> - **Cleanup had no tool for messages**, and `remove-car` would have left
>   them, to turn up in the recent list of a later car with the same slug. Now
>   `MessageStore.deleteCar`, called by `remove-car` after the car goes
>   (tested; the live smoke checks it on Firestore; 3 mutations killed).
>   Sessions still must be deleted first, one by one, as before.
> - **Step 3 relies on the replay reconnecting after `1012`** (M3) and the
>   message coming back in its `hello` sync: the log should show no
>   `taken down` for it, and the crew stream should still say displayed.
> - `gcp-setup.sh` is idempotent. It was run for M5.1–M5.4 (the composite
>   index is READY), and runs again here for the secret.

1. `gcp-setup.sh` (the secret), then deploy. Check `CREW_COOKIE_KEY` is
   mounted from the secret and never printed.
2. A throwaway car and passcode (`--passcode-file`), and the replay live at
   real speed. From the **deployed** site:
   - log in, send, and watch the states;
   - clear;
   - send with a 1-minute lifetime and watch it expire.
3. **A message across a deploy:** send one, deploy while it is displayed, and
   check it is still active after the drain, is in the replay's next sync, and
   stays on its "screen".
4. A public browser's stream, while a message is active, holds none of it.
5. Clean up: messages, sessions, the car.
6. Tell Sam the server is ready for the app's M34.5, and that it needs a real
   car registered, **with a passcode set**.

**Done when:** steps 2–5 pass against the deployed service, with screenshots
kept.

> **✅ Done, 2026-09-26.** Revision `00010` with `CREW_COOKIE_KEY` from the
> secret. Car `m58-throwaway`, and the evening drive live at real speed:
> - login: a wrong passcode 401; the cookie `Secure`, path `/api/cars/m58-throwaway`;
>   `/crew` true with it, false without;
> - PIT NOW: queued → received → displayed on the crew stream, about 1 s;
> - **deployed (`00011`) while it was displayed**: the replay logged
>   `reconnecting after close 1012, at once` and nothing taken down; stored
>   and in the new revision's crew sync, still displayed;
> - public streams, before and after the deploy, 2,724 events, and the page in
>   Chrome logged out (screenshot kept): no trace of it;
> - clear: `taken down: … (clear)`; a 60 s message expired 15 ms after its
>   time on the server, and the replay took it down;
> - cleanup: the live session deleted, `remove-car` removed the car and its 2
>   messages, and the token, passcode and cookie jar were deleted.

### M5.9 — Record it

- **Decisions:** 21 (crew messages: stored, one active, forward-only, the
  delivery rules) and 22 (crew login: the cookie, the key, the limit, crew-only
  events).
- `COMPLETED.md` and `JOURNAL.md`.
- `PLAN.md`, README and CLAUDE.md.
- This plan deleted, and pushed.

---

## Not in M5

- **Individual crew accounts, and who sent what.** The passcode is shared
  (decision 11); a message has no sender.
- **Showing messages beside a past session** (M6), though they are kept for it.
- **A map, laps on the page, dashboards** (M6, M7, and when wanted).
