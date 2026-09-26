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

- `FirestoreMessageStore` in `:archive-gcp`: a document per message; `active`
  as a query on `car` and `state`.
- A composite index if Firestore asks for one, declared in `gcp-setup.sh`.
- Absent fields stay absent.

**Done when:**
- A mapping test.
- A smoke run against the real database: send, replace, receive, display,
  clear, expire, a query for the active set; then deleted.

### M5.3 — Messages on the tablet's socket  `opus`

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

### M5.8 — Deploy, and prove it live  `sonnet`

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
