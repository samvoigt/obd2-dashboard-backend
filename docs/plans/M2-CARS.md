# M2 — Cars

**Status: drafted 2026-09-26, written against the code as it stands after M1,
and against the telemetry contract v1 (app commit `2210082`). Nothing is built.**

Sam, 2026-09-26: *"there could definitely be more cars at a time, but each
would have its own website, so there might be a landing page, and then you can
click to see registered cars, and go to their sub-page"*, and a *"shared
passcode is fine"* for the crew.

**What M2 delivers:**
- **cars as registered things**, each with its own tablet token and crew passcode;
- **an admin tool** to manage them;
- **token checks** that every `/v1/*` route (M3, M4) will sit behind;
- **the landing page's data.**

It also retires M1's single shared key. No website pages yet (M4), and no
passcode login yet (M5); M2 only *stores* the passcode.

---

## What exists, and what it means for this

- **One shared key.** `TabletAuth.kt` checks a bearer token against
  `TABLET_API_KEY` from Secret Manager (`tablet-api-key`) in constant time. Only
  `GET /tablet/ping` uses it. **Nothing in the app calls it** (grepped
  2026-09-26), so it can go without a transition period.
- **No database.** The runtime service account `obd2-backend-run` can read that
  one secret and nothing else. Firestore is not enabled.
- **One module**, `:server`. Hashing and car rules are needed by the server *and*
  the admin tool, so they need a module of their own.
- **Scripts:** `scripts/env.sh` holds the project, region, service and account
  names. `gcp-setup.sh` is idempotent. `deploy.sh` builds through
  `cloudbuild.yaml`.
- **Local `gcloud` is broken (2026-09-26):** it has been updated to 586.0.0, which
  refuses to run on the Mac's Python 3.9. **This blocks M2.2 and M2.6 until
  fixed** (see Prerequisites).
- **Warnings fail the build.** Versions go in `gradle/libs.versions.toml`.

## Settled with Sam, 2026-09-26

1. **Several cars, each with its own page**, and a landing page listing them.
2. **A token per car, never expiring.** Rotating one revokes the old one at once
   (contract §8).
3. **A shared crew passcode per car** for sending messages. Viewing is public
   (decision 11).
4. **The VIN is never shown** (decision 16). Cars are not identified by VIN
   anyway: a car is whatever its token was issued for.

## Decided here, not asked (say if any is wrong)

- **A car is a Firestore document in `cars`, keyed by its slug:**

  | Field | |
  | --- | --- |
  | `name` | Display name, editable |
  | `tokenHash` | SHA-256 of the token, hex |
  | `tokenHint` | The token's last 4 characters, for "which token is on the tablet?" |
  | `tokenIssued` | When the current token was made |
  | `passcodeHash` | PBKDF2, or absent until set |
  | `created`, `updated` | Timestamps |

- **Slugs are permanent**, because they are URLs: lower-case `a-z`, `0-9` and
  `-`, 2–32 characters, starting with a letter. `api`, `v1`, `cars`, `admin`,
  `health` and `static` are reserved. Names can change; slugs cannot.
- **Token format:** `obd2_` followed by 43 base64url characters (256 random bits
  from `SecureRandom`). The prefix makes a leaked token recognisable to secret
  scanners, and to a person. It is shown **once**, when made or rotated.
- **SHA-256 is right for the token.** 256 random bits cannot be guessed, so a
  slow hash would add nothing. **PBKDF2 is right for the passcode**, which a
  person chooses and can be guessed: PBKDF2-HMAC-SHA256 with 600,000 iterations
  (OWASP's figure) and a 16-byte salt, stored as
  `pbkdf2-sha256$600000$<salt>$<hash>` so the parameters can change later. It
  is built into the JDK, with no dependency. The minimum passcode length is 6:
  it is a crew code, and M5 rate-limits attempts.
- **Token lookup is a Firestore query on every request, with no cache.** The
  contract says rotating revokes the old token *at once*, and a cache would make
  that "within the cache's lifetime". The cost is one indexed read per archive
  chunk (every 2 minutes) and per live connect: nothing. Live sockets that are
  already open are closed on rotation by a listener, in **M4**, where sockets
  exist.
- **The admin tool uses the owner's own Google credentials** (Application
  Default Credentials) and talks to Firestore directly. **The server has no admin
  endpoint**, so there is nothing to attack. The tool always names the project
  explicitly, from `scripts/env.sh`, and **never uses the default project**
  (`microtron-scoreboard`).
- **The admin tool is a Gradle-installed binary run through `scripts/admin.sh`**,
  not `gradlew run`. Reading a passcode without echoing it needs a real
  terminal, and `gradlew run` does not give it one. Commands are built with
  Clikt.
- **Removing a car needs the slug typed again.** From M3 on, it refuses while the
  car has sessions (decision 16: nothing is deleted by accident).
- **`GET /v1/whoami` replaces `/tablet/ping`:** it returns the car's slug and
  name for a valid token. It is a backend diagnostic for deploys and support, and
  **not in the contract**. The tablet may use it only if a contract v2 adds it.
- **Error bodies follow contract §14.2 everywhere**:
  `{"error":"auth","message":"…","skipChunk":false}`, with `401` for a missing
  or unknown token.
- **Firestore in Native mode, database `(default)`, location `us-east4`**, the
  same region as the service.

## Prerequisites (Sam)

1. **Fix `gcloud`**: install a supported Python (`brew install python@3.13`)
   and point gcloud at it. Either add
   `export CLOUDSDK_PYTHON=/opt/homebrew/bin/python3.13` to `~/.zshrc`, or run
   `gcloud config virtualenv create`. Claude can do this if asked. It changes
   the machine, so it is Sam's call.
2. **Admin credentials, once:**
   ```
   ! gcloud auth application-default login
   ! gcloud auth application-default set-quota-project obd2-dashboard-backend
   ```
3. **The first car's slug and name**, and its passcode, which Sam types into the
   tool (M2.6).

---

## The steps

Each is validated against the code again before it is built.

### M2.1 — The registry core  `opus`

A new pure-Kotlin module, `:registry`, shared by the server and the tool:
- `Car`, and the slug rules (`Slug.parse`, with reasons for refusal);
- `Tokens`: generate, hash, hint;
- `Passcodes`: hash and verify, in constant time, reading the parameters from
  the stored string;
- a `CarStore` interface (get, find by token hash, list, create, update,
  delete), with an `InMemoryCarStore` for tests;
- `CarRegistry`, the operations: `addCar` (returns the token), `rotateToken`
  (returns the new one), `setPasscode`, `rename`, `removeCar`, `list`,
  `authenticate(token)`.

**Done when:**
- Unit tests cover:
  - slug rules, including reserved words, case, and length limits;
  - token format, and that two tokens differ;
  - the stored hash is not the token;
  - rotation makes the old token fail and the new one pass;
  - a passcode round-trips, and a wrong one fails;
  - a stored hash with a different iteration count still verifies;
  - adding a duplicate slug is refused;
  - `authenticate` on a malformed token returns nothing and does not throw.
- Mutations killed: swap the comparison, drop the salt, reuse the old hash
  on rotate.

### M2.2 — Firestore  `sonnet`

- `FirestoreCarStore` in `:registry`: the document mapping, and
  `findByTokenHash` as a `whereEqualTo` query, limit 2 (two results is a
  corruption, reported, never a coin toss).
- `gcp-setup.sh` gains:
  - enabling `firestore.googleapis.com`;
  - creating the `(default)` database, Native mode, in `us-east4` (idempotent;
    through REST if gcloud cannot);
  - `roles/datastore.user` for `obd2-backend-run`.
- **The project ID is always passed in explicitly.** The client is never left to
  guess it.

**Done when:**
- The setup script runs twice cleanly.
- A smoke test (`scripts/firestore-smoke.sh`, run by hand, not in `gradlew
  test`) creates a car named `smoke-<random>` against the real database, finds
  it by token, rotates it, confirms the old token fails, and deletes it.
- The database's location reads `us-east4`.

*No Firestore emulator:* it needs a gcloud component, and Docker is not
installed. The in-memory store covers the logic, and the smoke test covers the
mapping.

### M2.3 — The admin tool  `sonnet`

A new module, `:tools`, run by `scripts/admin.sh`, which builds the tool with
`installDist` if it is out of date, then runs it with the project from
`env.sh`. Commands:

| Command | Does |
| --- | --- |
| `add-car <slug> --name "…"` | Creates the car and prints its token **once**, with a line saying it will not be shown again |
| `rotate-token <slug>` | Asks for confirmation; prints the new token once; the old one fails immediately |
| `set-passcode <slug>` | Reads the passcode twice, without echo; refuses a mismatch or fewer than 6 characters |
| `rename <slug> --name "…"` | |
| `list` | Slug, name, token hint, when the token was issued, whether a passcode is set. **Never a hash** |
| `remove-car <slug>` | Asks for the slug to be typed again |

**Done when:**
- Tests run the commands against `InMemoryCarStore`, with output captured:
  - a token appears in the output of `add-car` and `rotate-token`, and nowhere
    else;
  - `list` output contains no hash;
  - a mismatched passcode is refused;
  - `remove-car` with the wrong slug typed changes nothing.
- `scripts/admin.sh list` runs against the real project.

### M2.4 — Car tokens on the server  `opus`

- `TabletAuth` becomes `CarAuth`: a Ktor bearer provider that resolves a
  `CarPrincipal(slug, name)` through `CarRegistry.authenticate`.
- A plain function `authenticateCar(token)` exists beside it, because the M4
  WebSocket must authenticate *after* accepting the upgrade, so it can send an
  `error`/`auth` frame (contract §5.2). The design has to allow for that now.
- `401` with the contract §14.2 body.
- `GET /v1/whoami`.
- The server builds its `CarStore` from configuration: Firestore in production,
  in-memory in tests. `main` fails fast if the project is not set.

**Done when:**
- Tests cover:
  - a valid token → `200` and the right car;
  - an unknown token → `401` with the §14.2 body;
  - no token → `401`;
  - **a rotated token fails on the very next request** (no cache);
  - two cars' tokens resolve to their own cars;
  - a malformed `Authorization` header → `401`, not `500`.
- Mutations killed: always-true auth, and returning the first car.

### M2.5 — The landing page's data  `sonnet`

- `GET /api/cars`, public: `[{"slug","name"}]`, sorted by name.
- Served from the registry. A 30-second cache is fine here: it is public, and
  holds no secret.

**Done when:**
- A test serialises a car that has every field set, and asserts the response
  contains **only** `slug` and `name`. That test fails if a future field leaks.
- The response is empty (`[]`) when there are no cars, not a `404`.

### M2.6 — Retire the shared key, deploy, and register the first car  `sonnet`

1. Remove `TABLET_API_KEY`, `TabletAuth.kt`, `/tablet/ping`, and
   `scripts/tablet-key.sh`. Remove the secret lines from `gcp-setup.sh` and
   `--set-secrets` from `deploy.sh`. Pass the project ID to the service as an
   environment variable instead.
2. Deploy.
3. **With Sam:** `scripts/admin.sh add-car <slug> --name "…"`, then
   `set-passcode`. The token goes into the tablet's Cars page when the tablet
   has one (contract §10.5). Until then, Sam keeps it.
4. Verify against the live service:
   - `/v1/whoami` with the token → `200`, the car;
   - with a wrong token → `401`, the §14.2 body;
   - `/api/cars` lists the car, and contains no other fields;
   - `/tablet/ping` → `404`.
   The token is read into a shell variable, never printed.
5. **Ask Sam, then** delete the `tablet-api-key` secret, and remove the runtime
   account's access to it.
6. Close M2:
   - entry in `docs/plans/COMPLETED.md` (a new file);
   - anything learned in `JOURNAL.md`;
   - decision 4 marked **superseded** (from "once M2 lands");
   - README and CLAUDE.md commands updated;
   - this plan deleted.

**Done when:** all of step 4 passes against the deployed service, and the old
secret is gone.

---

## Not in M2

- Passcode login, cookies, and rate limiting: **M5**, which is the first place a
  passcode is checked.
- Closing live sockets on rotation: **M4**, with a Firestore listener on `cars`.
- A website page: **M4**. M2 only serves `/api/cars`.
- Refusing `remove-car` while the car has sessions: **M3**, when sessions exist.
