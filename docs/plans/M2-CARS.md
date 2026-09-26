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
- **Local `gcloud` is 586.0.0** (updated 2026-09-26, and fixed the same day:
  Python 3.13 from Homebrew, with `CLOUDSDK_PYTHON` in `~/.zshrc`). The
  workarounds written for 418 may no longer be needed. `gcp-setup.sh` creates
  the Artifact Registry repo through REST because of a 418 bug; M2.2 can try the
  plain command again.
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

1. ~~Fix `gcloud`~~ ✅ 2026-09-26: Python 3.13, with `CLOUDSDK_PYTHON` set.
2. ~~Admin credentials~~ ✅ 2026-09-26: ADC login, quota project
   `obd2-dashboard-backend`.
3. ~~Firestore API~~ ✅ enabled 2026-09-26; `us-east4` confirmed available. No
   database yet. **Creating one fixes its location permanently** (M2.2).

4. ✅ Sam, 2026-09-26: **`us-east4` for the database is fine**, and **the old
   `tablet-api-key` secret may be deleted** in M2.6, with no need to ask again.

Registering Sam's real cars is **not** part of M2. It is
`scripts/admin.sh add-car` whenever he wants, and a token is only useful once
the tablet has its Cars page (contract §10.5).

---

## The steps

Each is validated against the code again before it is built. **Sam,
2026-09-26:** after each step, check the *next* step's plan against what was
actually built, and write that down, before building it.

### M2.1 — The registry core  `opus`

> ✅ **Done 2026-09-26.** `:registry` (`explicitApi`, coroutines only):
> - `Slug`, with `check` giving the reason for a refusal and `parse` throwing it;
> - `Tokens` (generate, hash, hint, `isWellFormed`);
> - `Passcodes` (PBKDF2, with the parameters read from the stored string; an
>   unreadable string refuses, never throws);
> - `Car` and `IssuedToken`, whose `toString` never prints the token;
> - the sealed `RegistryException`;
> - `CarStore` and `InMemoryCarStore`;
> - `CarRegistry`: add, rotate, set passcode, rename, remove, get, list (by name
>   case-insensitively, then slug), and `authenticate`, which checks the token's
>   shape before asking the store.
>
> **Names:** trimmed, 1–60 characters. **34 tests.** Eight mutations, each
> killed by a failing test and not a compile error, which was checked separately:
> - the three the plan named: swapping the comparison, dropping the salt,
>   reusing the old hash on rotate;
> - the stored iteration count ignored;
> - the passcode length off by one;
> - reserved slugs allowed;
> - `create` overwriting;
> - a duplicate token hash answered instead of refused.

> **Validated against the code 2026-09-26, before building.** No question.
>
> - **Nothing exists to conflict with it.** `:server` has only `/health`,
>   `/tablet/ping` and `TabletAuth`, and M2.1 touches none of them.
> - **`explicitApi()`**, as the app's library modules have: `:registry` is shared
>   by two consumers, so its surface should be chosen deliberately.
> - **`java.time` for time, with a `Clock` passed in**, not `kotlin.time.Instant`.
>   Firestore's `Timestamp` converts from `java.time.Instant` directly (M2.2), and
>   tests set the clock.
> - **`CarStore` is `suspend`.** The server is coroutine-based, and Firestore's
>   futures adapt to it (M2.2). This needs `kotlinx-coroutines-test` in the
>   catalog for `runTest`.
> - **`create` must be atomic** ("refused if the slug exists"), because M2.2 maps
>   it to Firestore's `create()`, which fails on an existing document. It is
>   not a read followed by a write.
> - **The PBKDF2 iteration count is a parameter**, defaulting to 600,000, so tests
>   can use a small count. The stored string carries it, which is also what the
>   "a different iteration count still verifies" test needs.
> - Registry failures are a sealed `RegistryException` with messages meant for
>   a person, because the admin tool (M2.3) shows them as they are.

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

> ✅ **Done 2026-09-26.** `:registry-firestore`:
> - `FirestoreCarStore`, with `connect(projectId)` that refuses a blank project,
>   and a small `ApiFuture.await()`.
> - **One change from the validation:** `delete` is a **transaction**, reading
>   and deleting only if the document exists. The Java client keeps
>   `Precondition.exists` package-private, so it cannot be called.
> - `gcp-setup.sh` enables Firestore and creates `(default)` in `us-east4`
>   (confirmed: `us-east4`, `FIRESTORE_NATIVE`). It also grants
>   `roles/datastore.user` to `obd2-backend-run`, **retried**, because the first
>   run hit "concurrent policy changes" while Google added the Firestore service
>   agent. It then ran twice cleanly.
> - Four unit tests of the field mapping.
> - `scripts/firestore-smoke.sh` passed all 15 checks against the real database.
>   It leaves the `cars` collection empty, which was confirmed through REST.

> **Validated against what M2.1 built, 2026-09-26, before building.** One
> conflict, resolved here:
>
> - **`FirestoreCarStore` gets its own module, `:registry-firestore`, not
>   `:registry`.** M2.1 made `:registry` pure: coroutines only, with tests that
>   need no cloud. The Firestore client brings gRPC, protobuf and Guava.
>   `:registry-firestore` depends on `:registry` and the client, and both the
>   server and the tool depend on it.
> - **`CarStore`'s contract maps onto Firestore's own preconditions**, not onto
>   read-then-write:
>   - `create` → `DocumentReference.create()`, which fails with
>     `ALREADY_EXISTS` → `false`;
>   - `update` → `update()`, which fails with `NOT_FOUND` on a missing document
>     → `false`. It writes every field, and a null `passcodeHash` becomes
>     `FieldValue.delete()`;
>   - `delete` → `delete(Precondition.exists(true))`, `NOT_FOUND` → `false`.
>     A plain delete succeeds on a missing document, which would break
>     `removeCar`'s "no such car".
> - **`findByTokenHash`** → `whereEqualTo("tokenHash", …).limit(2)`. Two results
>   → `RegistryException.DuplicateToken`, the same as `InMemoryCarStore`.
> - **The slug is the document ID**, not a field, and it is re-checked with
>   `Slug.parse` when read. A document edited by hand into an invalid slug fails
>   loudly.
> - **Time:** `Instant` ↔ `com.google.cloud.Timestamp`. Firestore keeps
>   microseconds, so the round trip is exact for the registry's clock but not
>   for arbitrary nanosecond instants, and the smoke test compares at
>   microsecond precision.
> - **Futures:** a small `ApiFuture.await()` built on
>   `suspendCancellableCoroutine`, rather than another library.
> - **The smoke test is a `main` in `:registry-firestore`'s test sources, run by
>   a `smoke` Gradle task.** It has no `@Test`, so `gradlew test` never runs it.
>   `scripts/firestore-smoke.sh` passes the project from `env.sh`.
> - The Artifact Registry REST workaround in `gcp-setup.sh` stays. The repository
>   already exists, so the plain command could not be tested by running setup
>   again, and replacing a working step with an untested one gains nothing.

- `FirestoreCarStore` in `:registry-firestore` (see validation): the document mapping, and
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

> ✅ **Done 2026-09-26.** `:tools` (`admin`):
> - `add-car`, `rotate-token`, `set-passcode`, `rename`, `list`, `remove-car`,
>   behind a root command that requires `--project`.
> - `AdminIo`, with `ConsoleIo` refusing a passcode without a terminal as a
>   clean error, not a stack trace.
> - `scripts/admin.sh` runs `installDist` and then runs the binary with the
>   project from `env.sh`.
> - **One change from the validation:** a plain `CliktCommand` with
>   `runBlocking`, not `SuspendingCliktCommand`. It keeps to the plainest Clikt
>   API, and the behaviour is the same.
> - A missing car is reported **before** any prompt.
>
> **13 tests** against `InMemoryCarStore`. They check that the token is printed
> exactly once, by `add-car` and `rotate-token` and nowhere else, and cover the
> rest of the plan's list. **Live**, with a throwaway car whose token went to
> `/dev/null`:
> - `add-car` and `list` work;
> - `set-passcode` without a terminal is refused, exit 1;
> - a declined rotation, and a wrong slug typed for `remove-car`, both exit 1
>   and change nothing;
> - `remove-car` works, and the registry is empty again.

> **Validated against what M2.1–M2.2 built, 2026-09-26, before building.** No
> conflict. Now fixed by what exists:
>
> - **`:tools` depends on `:registry-firestore`**, which brings `:registry`.
>   Commands are handed a `CarRegistry`, so tests use `InMemoryCarStore` and only
>   `main` calls `FirestoreCarStore.connect`.
> - **The project is a required `--project` option** on the root command, filled
>   in by `admin.sh` from `env.sh`. `connect` already refuses a blank one.
> - **Passcode entry goes through a small interface.** `System.console()` is
>   null under tests and under Gradle, so the real reader refuses to run without
>   a terminal rather than echoing the passcode. Tests supply their own. The
>   passcode stays a `CharArray` and is cleared after use, as `Passcodes` expects.
> - **Confirmations** (for `rotate-token` and `remove-car`) read from an input
>   the tests can supply.
> - **`RegistryException` messages are shown as they are** (M2.1 wrote them for
>   a person), on stderr, with exit code 1.
> - **`slf4j-nop` in `:tools`:** the Firestore client prints three SLF4J
>   warnings when it finds no logger (seen in the smoke test). They are noise
>   in a tool whose output a person reads. The server has logback, so it is
>   unaffected.
> - **Clikt 5.1.0**, with `SuspendingCliktCommand`, because the registry is
>   `suspend`.

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

> ✅ **Done 2026-09-26.**
> - `CarAuth.kt`:
>   - `CarRegistry.principalFor(token)`, the one place a token becomes a car;
>   - `bearerToken(call)`, parsed by hand, so a malformed header is simply no
>     token;
>   - `CarAuthProvider`, a small custom `AuthenticationProvider`, because
>     Ktor's bearer provider cannot change its `401` body.
> - `ApiTypes.kt`: `ApiError`, `PublicCar`.
> - `TabletAuth` is now a provider registration beside `carTokens`, in one
>   `install(Authentication)`. It is still retired in M2.6.
> - **`GCP_PROJECT` is required** by `main`.
> - **Corruption (`DuplicateToken`) is handled explicitly**: logged, and a `500`
>   with `{"error":"server"}` naming neither car. Unhandled, it was only a `500`
>   by accident.
>
> **14 server tests** (10 new):
> - a valid token, and two cars' tokens;
> - an unknown token, and no token, each with the §14.2 body and
>   `WWW-Authenticate`;
> - **a rotated token fails on the very next request**, and a removed car's
>   token fails;
> - seven malformed headers give `401`, never `500`;
> - the scheme is case-insensitive;
> - the old key and car tokens do not cross routes;
> - corruption.
>
> Mutations killed by failing tests: always-true auth (in a form that compiles),
> returning the first car, a case-sensitive scheme, and the wrong message.
> **Live:** the real server run locally against the real Firestore, with a
> throwaway car. `whoami` gave `200` with the car, `401` with the body when
> there was no token, and `401` once the car was removed.

> **Validated against what M2.1–M2.3 built, 2026-09-26, before building.** One
> overlap with M2.6, resolved here:
>
> - **`CarAuth` is added *beside* `TabletAuth`, not in place of it.** M2.6
>   removes `TabletAuth`, `/tablet/ping` and `TABLET_API_KEY` in the same change
>   that deploys. Removing them here would leave the tree ahead of the deployed
>   service for two steps. Until M2.6, `main` needs both `TABLET_API_KEY` and
>   `GCP_PROJECT`.
> - **`:server` depends on `:registry-firestore`.** `main` calls
>   `FirestoreCarStore.connect(GCP_PROJECT)`, and fails fast if it is blank
>   (`connect` already refuses a blank one). `Application.module` is given a
>   `CarRegistry`, so tests use `InMemoryCarStore`.
> - **The shared function is `CarRegistry.principalFor(token)`** in `:server`,
>   which returns a `CarPrincipal(slug, name)` or null. The bearer provider
>   calls it, and so will M4's WebSocket after the upgrade.
> - **The §14.2 body is one serializable `ApiError(error, message, skipChunk)`**,
>   used by M3 as well. The bearer provider's challenge responds with it and a
>   `401`.
> - **`RegistryException.DuplicateToken` during authentication is a `500`**,
>   logged. It means the store is corrupt, and neither car may be assumed.
> - **`/v1/whoami` answers `{"slug","name"}`**: the same shape M2.5's
>   `/api/cars` lists, so it is one `PublicCar` type.

- `CarAuth`, beside `TabletAuth` until M2.6 (see validation): a Ktor bearer provider that resolves a
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

> **Validated against what M2.1–M2.4 built, 2026-09-26, before building.** No
> conflict.
>
> - **`PublicCar` exists already** (M2.4, shared with `/v1/whoami`), and
>   `CarRegistry.list()` already sorts by name, then slug. M2.5 is one route
>   that maps one to the other.
> - **No cache.** The plan allowed a 30-second one and did not require it. It
>   is one Firestore read per page view for a handful of viewers, and a cache
>   would only let a removed car linger on the landing page. Add one if load
>   ever says so.
> - **The leak test goes through the real route and parses the JSON**, so it
>   checks what a browser would receive, not what a function returns. It uses a
>   car with every field set, including a passcode.

- `GET /api/cars`, public: `[{"slug","name"}]`, sorted by name.
- Served from the registry, uncached (see validation).

**Done when:**
- A test serialises a car that has every field set, and asserts the response
  contains **only** `slug` and `name`. That test fails if a future field leaks.
- The response is empty (`[]`) when there are no cars, not a `404`.

### M2.6 — Retire the shared key, and deploy  `sonnet`

1. Remove `TABLET_API_KEY`, `TabletAuth.kt`, `/tablet/ping`, and
   `scripts/tablet-key.sh`. Remove the secret lines from `gcp-setup.sh` and
   `--set-secrets` from `deploy.sh`. Pass the project ID to the service as an
   environment variable instead.
2. Deploy.
3. Register a throwaway car, `smoke-<random>`, with the admin tool.
4. Verify against the live service, using that car:
   - `/v1/whoami` with the token → `200`, the car;
   - with a wrong token → `401`, the §14.2 body;
   - `/api/cars` lists the car, and contains no other fields;
   - `/tablet/ping` → `404`.
   The token is read into a shell variable, never printed. Then remove the
   throwaway car, check that `/api/cars` no longer lists it, and check that its
   token now gets `401`.
5. Delete the `tablet-api-key` secret, and with it the runtime account's access
   (approved by Sam, 2026-09-26).
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
