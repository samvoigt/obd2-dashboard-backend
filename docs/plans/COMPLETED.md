# Completed milestones

What each milestone built, and **the claims that have never met a tablet**.
Read before trusting anything the server says. A milestone's plan is deleted
when it closes; its lasting content is here, in `DECISIONS.md` or in
`JOURNAL.md`, and git has the rest.

---

## M0 — Skeleton  ✅

Ktor on Kotlin/JVM 17, one `:server` module, `/health` (not `/healthz`, which
Cloud Run's front end intercepts), a `Dockerfile`, and warnings failing the
build. Decisions 1–3.

## M1 — Deployed  ✅

Cloud Run in `us-east4`, as its own service account, built by Cloud Build
through `cloudbuild.yaml` (`CLOUD_LOGGING_ONLY` is required, JOURNAL
2026-09-26). One shared tablet key in Secret Manager. That key was superseded
in M2 (decision 4).

## M2 — Cars  ✅ 2026-09-26

Registered cars, each with its own tablet token and crew passcode; an admin
tool to manage them; token authentication for every `/v1/*` route to come; the
landing page's data. The shared key is gone.

- **`:registry`, pure Kotlin:**
  - `Slug`: permanent, `[a-z][a-z0-9-]`, 2–32 characters, with reserved words;
  - `Tokens`: `obd2_` plus 256 random bits, stored as SHA-256 with a
    4-character hint;
  - `Passcodes`: PBKDF2-HMAC-SHA256 at 600k, with the parameters in the stored
    string;
  - `CarStore`, `InMemoryCarStore`;
  - `CarRegistry`, which **never caches**, so a rotated or removed token fails
    on the very next request (contract §8).
- **`:registry-firestore`:** one document per car in `cars`, keyed by slug, in
  the `(default)` Native database in `us-east4`. Every "only if" is atomic on
  Firestore's side: `create()`, `update()`, and delete in a transaction (the
  Java client hides `Precondition.exists`).
- **`:tools` (`scripts/admin.sh`):** `add-car`, `rotate-token`, `set-passcode`,
  `rename`, `list`, `remove-car`, run as the owner, so the server has **no admin
  endpoint**.
  - A token is printed exactly once.
  - `list` shows only the hint.
  - A passcode is read without echo, or refused without a terminal.
- **`:server`:**
  - `CarAuthProvider`, a custom provider so a failure answers with the
    contract's §14.2 body;
  - `CarRegistry.principalFor(token)`, the one place a token becomes a car,
    kept for M4's socket;
  - `ApiError`, `PublicCar`;
  - `GET /v1/whoami` (a backend diagnostic, not in the contract);
  - `GET /api/cars` (exactly `{slug, name}`, pinned by a test);
  - a corrupt registry (two cars with one token hash) is an explicit `500`
    naming neither car.
- **Deploy:** `GCP_PROJECT` is passed as an environment variable.
  `--clear-secrets` is passed because **a deploy keeps any setting it does not
  mention**. The runtime account has `roles/datastore.user`.
- **Tests:** 65 across four modules. 13 mutations were killed by failing tests,
  each checked to be a test failure and not a compile error.
- **Verified live, 2026-09-26:**
  - the Firestore smoke test (15 checks);
  - the admin tool against the real project;
  - the deployed service with a throwaway car: `whoami` `200`, a bad token
    `401` with the body, `/api/cars` listing only slug and name,
    `/tablet/ping` `404`, and after removal an empty list and `401`.
  The `tablet-api-key` secret is deleted.

**Never met a tablet.** No real car is registered, and the app has no token
field yet (contract §10.5). Everything above has been exercised only by tests,
by `curl`, and by the admin tool.

**Left for later, on purpose:**
- passcode login and rate limiting (M5, the first place a passcode is checked);
- closing live sockets when a token is rotated (M4, with a Firestore listener);
- refusing `remove-car` while the car has sessions (M3).
