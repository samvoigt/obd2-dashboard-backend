# M3 — The archive lane

**Status: drafted 2026-09-26, written against the code as it stands after M2,
the telemetry contract v1 (app commit `2210082`, §6 above all), and the app's
own archive plan (its M33, `docs/plans/TELEMETRY-ARCHIVE.md`). Nothing is
built.**

**What M3 delivers:** the server side of contract §6.
- `PUT /v1/sessions/{id}`, `POST …/chunks` and `POST …/complete`.
- Every line the tablet sends is stored **byte for byte**, and acknowledged
  only once durable.
- A completed session is **one `.jsonl.gz` in Cloud Storage** whose SHA-256
  matches the tablet's.
- **A replay tool** that behaves like the tablet, faults included, so all of
  this is tested without a car.
- **Admin commands** to see and delete sessions.

Where this plan and the contract disagree, the contract wins, and this plan is
wrong.

---

## What exists, and what it means for this

- **Auth is done (M2).** Under `authenticate(CAR_AUTH)`, a route gets a
  `CarPrincipal(slug, name)`. `ApiError(error, message, skipChunk)` is the
  §14.2 body. `CarRegistry.principalFor` is the one place a token becomes a car.
- **The split that worked for cars works here:** a pure module with in-memory
  fakes, where the rules and tests live, and a Google module with the real
  stores. `:registry` / `:registry-firestore` is the precedent.
- **Firestore is set up** (`(default)`, `us-east4`), and the runtime account
  has `roles/datastore.user`. **There is no bucket yet**, and no storage
  permission.
- **`deploy.sh` runs up to 2 instances** (`--max-instances 2`). Decision 7
  makes it one in M4, for the live hub. **M3 must be correct with several
  instances anyway**: nothing about the archive may live in memory between
  requests.
- **The app now writes format v3** (its M33.2, done). What its encoder does
  decides what the server can expect:
  - `encodeDefaults = true`, so `v` and `seq` are always present;
  - `explicitNulls = false`, so **`vin`, `protocol`, `pids` and `wall` are
    absent, not `null`, when unknown**. The server treats absent as null.
- **The app's `test-data/sessions/` logs are v1 and v2.** There is no v3 file
  committed yet, so the replay tool upgrades old logs (M3.5). Once the tablet
  has streamed a real session, ask the app side to commit one as a fixture.
- **The app's M33.8 tests against "the real backend".** M3 has to be deployed
  (M3.7) before the tablet gets there, and Sam registers a real car for it.
- **The app uploads a growing `.jsonl` every 2 minutes during the drive.**
  Chunks can be small and many: a 6-hour session is about 180. Its shipper
  reads from a saved position, resends after a lost response, and handles every
  status in §6.4.

## Settled (contract v1, and Sam)

1. **Line index addresses everything.** The session record is index 0. Chunks
   are contiguous, idempotent by `(sessionId, index)`, and **acked only once
   durable**. A chunk that starts past the end gets
   `409 {missingFrom: ackedThrough + 1}` (§6.2).
2. **Nothing on the archive path is re-serialised**, and the sha256 covers
   every line plus `\n`, byte for byte (§6.3).
3. **The error body and codes:** `wrong_car` and `bad_record`, each a `400`;
   `skipChunk` is always false for these (§6.4).
4. **Only v3 sessions are uploaded** (§6).
5. **Sessions are kept indefinitely.** Only the owner deletes, with the admin
   tool. The VIN is stored and never shown publicly (decision 16). M3 adds **no
   public session endpoint** (that is M6), so there is nothing public to leak it.

## Decided here, not asked (say if any is wrong)

- **Storage.** One private bucket, `obd2-dashboard-backend-sessions`, in
  `us-east4`: uniform access, public-access prevention enforced, no lifecycle
  rule (kept indefinitely), and Google's default 7-day soft delete kept as a
  safety net. The runtime account gets `roles/storage.objectAdmin` **on that
  bucket only**.
- **Layout.** Every stored run of lines is a **segment object**:
  `sessions/{id}/segments/{first}-{last}.jsonl.gz`, with indexes zero-padded to
  10 digits. Each is the verbatim lines, gzipped by the server. Line 0 from the
  `PUT` is segment `0-0`. A complete session is
  `sessions/{id}/session.jsonl.gz`, and its segments are then deleted.
- **Firestore is the authority on what is stored, not the bucket listing.** The
  session document (`sessions/{id}`) holds:
  - `car`, `v`, `started`, `device`, `app`, `vin?`, `protocol?`;
  - `ackedThrough` (an index; −1 until line 0 is stored);
  - the **segment list** (`first`, `last`, `firstSeq`, `lastSeq`, `object`);
  - `complete`, `lineCount`, `sha256`;
  - `created`, `updated`, `hashResets`.

  A segment counts only once it is in that list.
- **Durable, then acknowledged, and safe with several instances.** A chunk:
  1. reads `ackedThrough`;
  2. trims lines already stored;
  3. **writes the new segment object** under its own `{first}-{last}` name;
  4. only then, **in a Firestore transaction**, appends it to the list and
     advances `ackedThrough`, **if and only if `ackedThrough` still equals
     `first − 1`**.

  If two requests race, one loses the transaction. Its object becomes an
  orphan that no list names, and it is deleted when the session completes. It
  answers with whatever `ackedThrough` now is, which is always true.
- **Trimming is a pure decision** on `(ackedThrough, first, count)`:
  - wholly stored → **duplicate**: `200`, nothing written;
  - overlapping → **append the tail**;
  - past the end → **gap**: `409`.
- **What counts as `bad_record`** (the contract's only code for malformed
  content):
  - a line that is not a JSON object, or is not UTF-8;
  - a body that does not end in `\n`;
  - `X-Record-Count` disagreeing with the lines received;
  - a missing or non-numeric `X-First-Index`;
  - a `PUT` whose line is not a v3 `session` record whose `id` matches the URL;
  - a second `PUT` whose line differs from the stored line 0;
  - `/complete` with `recordCount ≠ lastIndex + 1`, or with fewer lines than
    the server holds;
  - new lines after `complete`.

  Each message says which. Unknown fields and record types are **kept, never
  refused** (contract §3.1, §9).
- **Limits:**
  - a chunk's gzip is decoded while counting, refused with `413` beyond
    1 MiB uncompressed (a zip bomb stops at 1 MiB);
  - the raw body is capped at 2 MiB;
  - a `PUT` is capped at 64 KiB.

  Uncompressed chunks are accepted too (no `Content-Encoding`), which the
  contract does not forbid.
- **`/complete`** reads the segments in list order and checks that they are
  contiguous. It streams the lines once through both the hasher and a gzip
  writer that builds `session.jsonl.gz`, checks the count and the hash, and
  only then marks the session complete and deletes the segments. It is
  idempotent: the same body again → `200`; a different one → `bad_record`.
- **A hash mismatch** means the server holds different bytes from the tablet.
  The server **discards every segment after line 0**, sets `ackedThrough = 0`
  and answers `409 {missingFrom: 1}`. The tablet then resends everything, which
  is exactly §6.4's `409`. Line 0 is safe to keep because a `PUT` already
  refuses a different one. **After two such resets**, it answers `bad_record`
  instead, since the two sides disagree about the bytes and a third try will
  not change that. No contract change is needed; it is within §6.4.
- **Storage failures** (Cloud Storage or Firestore unavailable) → `503` with
  `Retry-After: 30`. The tablet already waits and retries (§6.4).
- **Session ids must be UUIDs.** Anything else in the path → `bad_record`.
- **A `PUT` answers `{"ackedThrough": n}` as well**, `201` if new and `200` if
  it already existed. It is extra information: the contract asks only for the
  status, and the tablet ignores fields it does not know.
- **A session belongs to the car whose token first opened it.** Another car's
  token → `wrong_car`. A chunk for a session never opened → `404`, and the
  tablet re-opens it (§6.4).
- **M4's live `session` may create the session first** (§6.1). The document is
  designed for it: a session can exist with `ackedThrough = −1` and no line 0,
  and a later `PUT` fills line 0 and answers `201`.
- **Modules:**
  - `:archive`: pure. Chunk parsing, the rules, `SessionIndex` / `SegmentStore`
    interfaces and in-memory fakes, and `ArchiveService`.
  - `:archive-gcp`: `FirestoreSessionIndex`, `GcsSegmentStore`.
  - The server, the tools and the replay tool depend on whichever they need.
- **The replay tool is its own module and script** (`:replay`,
  `scripts/replay.sh`), using the JDK's `HttpClient` (no new dependency). **It
  reads the token from `OBD2_TOKEN` or a file, never from the command line**,
  where it would land in shell history and transcripts.
- **Admin commands** join `admin.sh`:
  - `sessions [car]` and `session <id>`: the VIN may show here (decision 16:
    the admin tool is its one place);
  - `delete-session <id>`, with the id typed again (decision 16's "deletion is
    by the owner");
  - `remove-car` **refuses while the car has sessions**, as M2 left for here.

---

## The steps

Each is validated against the code again before it is built, and after each,
the *next* step's plan is checked against what was actually built (Sam,
2026-09-26).

### M3.1 — Lines, chunks and the session record  `opus`

> ✅ **Done 2026-09-26.** `:archive`:
> - `LineBlock`: byte ranges; the final `\n` required; `\r` kept; written back
>   byte for byte.
> - `ChunkBody`: inflates while counting, stops at 1 MiB, handles multi-member
>   gzip, and a non-gzip body says so.
> - `Records.parseObject`: strict UTF-8, one JSON object.
> - `SessionHeader`: v3 or later, the id matching the URL (case-insensitively,
>   keeping the URL's spelling), absent fields null, and a `toString` without
>   the VIN.
> - `SessionIds`, `Trim.plan`, `LineHash`.
>
> A synthetic 47-line v3 fixture, whose hash came from `shasum`. **29 tests**,
> among them a 1 GiB gzip bomb refused in well under two seconds. **Ten
> mutations**, all killed by failing tests: every `Trim` boundary, the limit
> checked after writing, no final newline, UTF-8 patched rather than refused,
> v2 accepted, the id unchecked, the hash without newlines, the uncompressed
> limit.

> **Validated against the code 2026-09-26, before building.** No question.
>
> - **Nothing exists to conflict with it.** `:archive` is new and pure, as
>   `:registry` is: `explicitApi()`, and no Google or Ktor dependency.
> - **`kotlinx-serialization-json` as a library only** (no compiler plugin),
>   for `parseToJsonElement`. Nothing is decoded into a type, so nothing can be
>   re-encoded: "never re-serialised" holds by construction.
> - **UTF-8 is checked strictly** with a `CharsetDecoder` set to report, not
>   replace. `String(bytes)` would quietly turn bad bytes into U+FFFD and
>   pass them.
> - **Indexes and counts are `Long`** everywhere a session is addressed. Lines
>   in one chunk are an `Int`, because a chunk is capped at 1 MiB.
> - **Session-id checking (UUID) lives here too**, since it is pure and M3.4
>   needs it before anything else.
> - **The hash fixture is synthetic and committed to this repo**, with its
>   `shasum -a 256` written into the test. Real logs stay in the app's
>   `test-data/`, where the vehicle facts live (the app's decision 33).

`:archive`, pure Kotlin:
- **`Lines.split(bytes)`**: byte ranges on `\n`, without copying or decoding.
  It refuses a body that does not end in `\n`.
- **`Chunks.decode(body, gzipped, limit)`**: inflates while counting, and stops
  at 1 MiB with a "too large" outcome. Handles multi-member gzip.
- **Each line checked as a JSON object**, parsed from UTF-8, with the original
  bytes kept for storing.
- **`SessionHeader.parse(line0, expectedId)`**: `type` = `session`, `v ≥ 3`,
  `id` = the URL's id, and the index fields (`started`, `device`, `app`, `vin?`,
  `protocol?`), with absent meaning null.
- **`Trim.plan(ackedThrough, first, count)`** → `Duplicate` | `Append(from)` |
  `Gap(missingFrom)`.
- **`LineHash`**: streaming SHA-256 over lines plus `\n`.

**Done when:**
- Tests cover:
  - bodies with and without the final newline, CRLF (kept as bytes, not
    refused), empty lines, non-UTF-8, and a non-object line;
  - a 1 MiB + 1 byte body refused, and a gzip bomb stopped at the limit
    without inflating further;
  - multi-member gzip;
  - v2 and v3 headers, a mismatched id, and absent optional fields;
  - every boundary of `Trim.plan` (whole duplicate, one-line overlap, exact
    continuation, one past the end);
  - the hash against an independent `shasum` of a fixture.
- Mutations killed.

### M3.2 — The archive rules  `opus`

> ✅ **Done 2026-09-26.**
> - `Stores.kt`: `Segment`, `SessionRecord`, the conditional `SessionIndex`,
>   `SegmentStore` (plain bytes in and out; `write` streams, and **creates no
>   object if its body throws**), and fakes with hooks for interleaving
>   (`afterPut`) and failure (`failNextPut`, `failNextAppend`).
> - `ArchiveService`: `open`, `append`, `complete`, `delete`, all store then
>   advance. `complete` assembles through `assemble`, which **refuses a corrupt
>   index** (segments not contiguous, or not holding the lines they claim)
>   rather than trust the hash alone. A `PUT` body may carry its newline or not,
>   but only one line.
>
> **21 service tests**, 50 in the module:
> - 20 random chunkings, each byte for byte;
> - the race, with one recorded, a true answer, and the orphan swept;
> - failures between storing and recording, and of storing itself;
> - the reset, and the limit on resets;
> - a live-created session;
> - both kinds of corruption.
>
> **12 mutations killed.** The mutation run also found two things:
> - **the contiguity check was untested**, so corruption tests were added;
> - **the "delete a half-written session" step was redundant**: neither the
>   fake nor Cloud Storage creates an object whose writer threw. It was
>   removed, and the rule was written into `SegmentStore.write` instead, for
>   M3.3 to prove.

> **Validated against what M3.1 built, 2026-09-26, before building.** No
> conflict. Now fixed by what exists:
>
> - **Compression is the store's, not the rules'.** `ArchiveService` hands a
>   `SegmentStore` plain line bytes (`LineBlock.bytesFrom`), and gets them back
>   plain. Cloud Storage gzips them (M3.3); the fake keeps them raw. The finished
>   session is written through an `OutputStream` (`writeSession`), so a long
>   session is never held whole in memory.
> - **A segment's `firstSeq`/`lastSeq` come from its own lines**, which are
>   parsed as objects anyway, not from `X-First-Seq`/`X-Last-Seq`. After
>   trimming, those headers describe lines that were not stored.
> - **Line 0 is compared by its SHA-256**, kept in the index as `line0Sha256`,
>   so a repeated `PUT` reads no object.
> - **Object keys are the service's** (`sessions/{id}/segments/{first}-{last}`
>   zero-padded, and `sessions/{id}/session.jsonl.gz`). The stores only store.
> - **The race is made deterministic** by a hook on the fake store that runs the
>   rival append in the middle of a `put`, not by timing.
> - **A lost race answers with a fresh read of `ackedThrough`**, which is always
>   true, even when the rival stored fewer lines than this request brought.

`:archive`:
- `SessionIndex`: get, create-if-absent, and a transactional append that
  succeeds only if `ackedThrough` equals an expected value. Also set complete,
  reset, list by car, and delete.
- `SegmentStore`: put, read, and delete objects.
- In-memory fakes for both. The index fake can be made to **interleave two
  appends**, for the race test.
- **`ArchiveService`**:
  - `open(car, id, line0)`: `Created` / `Existing` / `WrongCar` / `BadRecord`;
  - `append(car, id, first, lines, seqs)`: `Acked(n)` / `Gap(missingFrom)` /
    `NotOpen` / `WrongCar` / `BadRecord`;
  - `complete(car, id, lastIndex, recordCount, sha256)`: `Complete` /
    `Gap(missingFrom)` / `WrongCar` / `BadRecord`;
  - in each, the order is **store, then advance**.

**Done when:**
- Tests cover:
  - a session in random chunk sizes completes, and its assembled bytes and hash
    equal the source's;
  - every duplicate and overlap case returns the right `ackedThrough` and writes
    nothing twice;
  - a gap gives `409` and writes nothing;
  - **two racing appends of the same range**: exactly one is recorded, the
    other's object is an orphan, and both answers are true;
  - **a failure between writing an object and recording it**, then a resend,
    ends consistent;
  - a hash mismatch resets to line 0 and then succeeds on the resend;
  - a second mismatch gives `bad_record`;
  - `complete` is idempotent;
  - lines after `complete` are refused;
  - `wrong_car` on every operation;
  - a session created by "live" (no line 0) is then opened by `PUT`.
- Mutations killed: advancing before storing, dropping the transaction's
  condition, an off-by-one in the trim, skipping the contiguity check.

### M3.3 — Cloud Storage and Firestore  `sonnet`

> ✅ **Done 2026-09-26.** `:archive-gcp`:
> - `GcsSegmentStore` stores gzip files (`application/gzip`, no
>   content-encoding) and abandons the channel if a writer throws.
> - `FirestoreSessionIndex`: every conditional change is a transaction, header
>   fields are flat, and absent means null.
> - `libraries-bom` 26.89.0 for both Google modules (Firestore 3.48.0, Storage
>   2.74.0, one Guava, one gRPC).
> - `gcp-setup.sh` creates `gs://obd2-dashboard-backend-sessions` and grants
>   `roles/storage.objectAdmin` **on that bucket only**, retried. It ran twice
>   cleanly. The bucket reads back as `US-EAST4`, uniform access, public access
>   prevention `enforced`, soft delete 7 days, and no lifecycle rule.
> - `env.sh`: `BUCKET`.
>
> **4 mapping tests** (round trip, absent VIN, a live-created session).
> `scripts/archive-smoke.sh` passed **16 checks** against the real services,
> among them:
> - the raw download is the fixture byte for byte;
> - the object is `application/gzip` with no content-encoding;
> - **a write whose body throws creates no object**, which is M3.2's rule,
>   proved on real Cloud Storage.
>
> The bucket and the `sessions` collection were empty afterwards.

> **Validated against what M3.1–M3.2 built, 2026-09-26, before building.** One
> flaw in this plan, corrected here:
>
> - **Objects are gzip *files*, not gzip-*encoded*.** The plan said
>   `Content-Encoding: gzip`. With that, Cloud Storage decompresses on download
>   (decompressive transcoding), so `session.jsonl.gz` would download as plain
>   JSONL under a `.gz` name. Instead: `Content-Type: application/gzip`, no
>   content-encoding. The store gzips on `put`/`write` and gunzips on `read`, so
>   a download is exactly the `.jsonl.gz` the app itself writes.
> - **`write` must create no object when its body throws** (M3.2's rule). With
>   `storage.writer`, the object exists only once the channel is closed, so on
>   an exception the channel is abandoned, never closed. The smoke test proves
>   it.
> - **Blocking Google clients run on `Dispatchers.IO`.**
> - **Firestore and Storage share Guava and gRPC**, so both `:archive-gcp` and
>   `:registry-firestore` take their versions from Google's `libraries-bom`.
> - **Header fields are flat document fields** (`v`, `started`, `device`, `app`,
>   `vin`, `protocol`), absent when null. The header is present exactly when
>   `line0Sha256` is. `car` is a field, for `listByCar`.
> - **The smoke test gives the fixture a fresh UUID** (rewriting line 0's `id`),
>   so runs never collide, and hashes what it actually sent.

`:archive-gcp`:
- `GcsSegmentStore`: objects gzip-encoded, `Content-Type: application/x-ndjson`.
- `FirestoreSessionIndex`: the append as a `runTransaction` that reads
  `ackedThrough` and writes only if it matches.
- `gcp-setup.sh` gains the bucket (uniform access, public-access prevention,
  `us-east4`) and `roles/storage.objectAdmin` **on the bucket** for the runtime
  account, retried against the policy race (JOURNAL).
- `env.sh` gains `BUCKET`.
- `scripts/archive-smoke.sh` runs the service against the real stores: a
  throwaway session, a chunked upload, a duplicate, a gap, completion. It
  downloads `session.jsonl.gz`, compares its sha256 with the source, and
  deletes it all.

**Done when:**
- The setup script runs twice cleanly.
- The bucket reads back as private, in `us-east4`, with uniform access.
- The smoke test passes and leaves nothing behind.
- A mapping test covers the document fields, including absent `vin`.

### M3.4 — The routes  `opus`

> ✅ **Done 2026-09-26.** `ArchiveRoutes.kt`:
> - `PUT`, `/chunks` and `/complete` inside `authenticate(CAR_AUTH)`;
> - `Acked`, `Completed`, and `Missing` (the `409` with both shapes);
> - capped reads (`readBuffer`: Ktor 3.6 deprecates `readRemaining`);
> - Google and I/O failures → `503` with `Retry-After: 30`, anything else a
>   `500`.
>
> `main` requires `SESSIONS_BUCKET`. **18 route tests**, section by section,
> through the real routes:
> - 201/200, a different record, `wrong_car` with the contract's exact body,
>   v2 and a foreign id, a non-UUID, the upper-case id;
> - chunks acked and deduplicated, the `409` with both shapes, `404 not_open`,
>   uncompressed accepted, eight malformed chunks each saying why, `413` over
>   1 MiB, and over 64 KiB for a `PUT`;
> - `complete` byte for byte and repeated, its `409`, malformed bodies;
> - `401` on every route, and `503` with nothing acked, then a recovery.
>
> **Ten mutations killed.**

> **Validated against what M3.1–M3.3 built, 2026-09-26, before building.** One
> point where the contract speaks twice, resolved here:
>
> - **The `409` body carries both shapes.** §6.4 says every archive `4xx` has
>   `{error, message, skipChunk}`, while §6.2–6.3 show `{"missingFrom": n}`. So
>   a `409` is `{"error":"missing","message":…,"skipChunk":false,"missingFrom":n}`,
>   and satisfies either reading. `404` is `error: "not_open"`, `413` is
>   `error: "too_large"`.
> - **`:server` depends on `:archive-gcp`**, and `module(registry, archive)`
>   takes an `ArchiveService`, with no default: production must name its stores.
>   `main` requires `SESSIONS_BUCKET` as well as `GCP_PROJECT`.
> - **The id is checked (`SessionIds`) and lower-cased** before `ArchiveService`
>   sees it. `SessionHeader` compares ids ignoring case, so an upper-case tablet
>   id still matches its own line 0.
> - **Only Google-client and I/O failures become `503` with `Retry-After: 30`**
>   (`BaseServiceException`, gax `ApiException`, `IOException`). The corruption
>   checks' `IllegalStateException` stays a `500`: a fault to look into, not a
>   busy service. The tablet backs off on both (§6.4). The `503` test uses a
>   store that throws `IOException`, since the fakes' simulated failures are
>   `IllegalStateException`.
> - **An unsupported `Content-Encoding` is `bad_record`.** `415` is not in
>   §6.4's table, so the tablet has no defined reaction to it.
> - **Bodies are read with a cap** (`readBuffer(limit + 1)`), so an oversize
>   body is refused without being held whole.

In `:server`, under `authenticate(CAR_AUTH)`:
- **Route plumbing:** headers parsed (`X-First-Index`, `X-Record-Count`,
  `X-First-Seq`, `X-Last-Seq`); body caps; `Content-Encoding: gzip`.
- **Results to statuses:** each `ArchiveService` outcome becomes its status and
  `ApiError`, and any storage exception becomes a `503` with `Retry-After`.
- **Configuration:** `main` builds the archive from `GCP_PROJECT` and
  `SESSIONS_BUCKET`, and fails fast without them.
- **The contract's own examples** (§6.1–6.4, §14.2) become test fixtures.

**Done when:**
- `testApplication` tests, against the fakes, follow the contract section by
  section:
  - `201` then `200` on a repeated `PUT`;
  - chunk `200` with `ackedThrough`;
  - an overlap deduplicated;
  - `409 {missingFrom}` for a gap and for an incomplete `complete`;
  - `413` over 1 MiB;
  - `404` for a chunk on an unopened session;
  - `400 wrong_car` and `400 bad_record`, each with the exact §14.2 body and
    `skipChunk: false`;
  - `401` without a token;
  - `503` with `Retry-After` when a store throws;
  - `complete` `200 {"complete": true}`.
- Every `4xx` body parses as `ApiError`.
- Mutations killed.

### M3.5 — The replay tool  `opus`

> ✅ **Done 2026-09-26.** `:replay`:
> - `SessionFile` loads `.jsonl`/`.gz`, drops a cut-short last line, and
>   upgrades v1/v2 by rewriting line 0 alone. The id is stable per
>   (token, source).
> - `Replayer` handles every §6.4 status; chunks by log time, lines and 1 MiB;
>   injects lost answers and duplicates; saves its position after every ack.
> - `Replay` is the Clikt CLI. The token comes from `OBD2_TOKEN` or
>   `--token-file`, and it refuses cleanly without one.
> - `scripts/replay.sh`.
>
> **One change from the plan:** there is no `--resume`. Positions are always
> saved and a rerun always resumes, as on the tablet; `--fresh` forgets them.
>
> **14 tests against the real server module over real HTTP** (Netty on a free
> port, in-memory stores, a 30-second timeout on every test):
> - v1 in six 2-minute chunks; v2 gzipped;
> - 30% lost answers with 30% duplicates;
> - stop, then resume from the saved position;
> - **a lost position that trusts the server's `ackedThrough`**;
> - a session lost mid-run → `404`, re-open, then `409` from line 1;
> - a 2 MB backlog split with no `413`, and a real `413` halved;
> - two cars at once, a wrong token, and storage outages waited out;
> - the upgrade's fields, the partial line, a stable id.
>
> Every stored session was compared byte for byte. **11 mutations killed.** Two
> first survived, and they showed weak tests, since tightened:
> - a `409` that ignored `missingFrom` was rescued later by `/complete`'s own
>   `409`;
> - trusting its own chunk end over `ackedThrough` was never exercised.

> **Validated against what M3.1–M3.4 built, 2026-09-26, before building.** One
> conflict with an earlier rule, resolved here:
>
> - **Its tests use synthetic v1 and v2 fixtures, not the app's `test-data/`
>   files.** Those carry real VINs. Copying them here would put vehicle facts
>   outside the app's `test-data/` (the app's decision 33) and put VINs in this
>   repo's git (decision 16). The synthetic ones copy the real shapes, read on
>   2026-09-26 with the VIN masked: a `session` record (v1 and v2 differ only in
>   `v`) and `sample` lines carrying `value`, `code`/`text`, `flag` or `flags`,
>   with no `wall`. **The real files are used in place, from
>   `../obd2-dashboard`, only in M3.7's live run**, and never committed.
> - **The replay is a separate client, not the server's code.** It computes its
>   own SHA-256 with `MessageDigest`, and does not reuse `:archive`'s
>   `LineHash`, so it checks the server rather than agreeing with it by
>   construction.
> - **The upgrade to v3 is the replay's own writing**: it is the tablet, so it
>   may encode line 0 however it likes. Only the server must never re-encode.
>   Every other line is copied as bytes.
> - **"Several files at once, on different tokens"** is a `Replayer` per
>   (token, file). The CLI takes one token and any number of files; the
>   multi-car test runs two `Replayer`s concurrently.
> - **Tests start the real server module** (`module(registry, archive)` with
>   fakes) under `embeddedServer` on a free port, so the replay talks real HTTP
>   through the JDK `HttpClient`.
> - **Waits are scaled in tests** (`Retry-After`, backoff), so a `503` test does
>   not sleep 30 seconds.

`:replay`, run by `scripts/replay.sh`. It **behaves as the tablet does**:
- `PUT`, then gzipped chunks from its saved position, then `complete`.
- Every §6.4 status handled:
  - `409` → resend from `missingFrom`;
  - `413` → halve the chunk;
  - `404` → re-`PUT`;
  - `429`/`503` → honour `Retry-After`;
  - `5xx` → back off;
  - `401`/`400` → stop and say why.

It also:
- **Upgrades v1/v2 logs to v3**: it adds `id` (a UUID fixed per source file, so
  a rerun resumes), a replay `device`, `app` = `replay`, and `signals` derived
  from the samples (the kind from each sample's fields, the unit left empty:
  the server does not read units in M3, and M4 revisits this). It writes the
  upgraded file beside its state, so the hash has a source.
- **Chunks by log time**, every 2 minutes of `at`, as the tablet does, or by
  size. `--speed` sets real time or as fast as possible.
- **Injects faults**: `--lose-responses p` (sends, discards the answer, resends),
  `--duplicate p`, `--stop-after n` (exits mid-session, keeping its position),
  `--resume`.
- **Several files at once, on different tokens**: the multi-car test.
- Reads the token from `OBD2_TOKEN` or `--token-file`. **Never an argument**,
  and never printed.

**Done when:**
- Tests run it against the server in-process (`embeddedServer` on a free port,
  with fakes):
  - a v1 and a v2 fixture from the app's `test-data/` each upgrade and upload
    whole, and the server's final hash equals the upgraded file's;
  - the same with `--lose-responses 0.3 --duplicate 0.3`;
  - `--stop-after` then `--resume` completes;
  - two files on two cars' tokens at once, each ending as its own car's
    session;
  - a wrong token stops with the `401` explained.

### M3.6 — Sessions in the admin tool  `sonnet`

> ✅ **Done 2026-09-26.**
> - `admin.sh` gains `sessions [car]` (no VIN), `session <id>` (the VIN, only
>   here) and `delete-session <id>` (the id typed again; objects, then the
>   index entry).
> - `remove-car` refuses while the car has sessions, and says how many, before
>   any prompt.
> - The factory builds registry, index and archive from `--project` and
>   `--bucket`.
>
> **16 admin tests** (3 new). **3 mutations killed**: the guard, the VIN in the
> list, and deleting without confirmation. Run against the real project, with no
> sessions there yet.

> **Validated against what M3.1–M3.5 built, 2026-09-26, before building.** No
> conflict. Now fixed by what exists:
>
> - **The tool builds the archive as well as the registry.** Its factory takes
>   `(project, bucket)` and returns both. `admin.sh` passes `--bucket` from
>   `env.sh`. Tests hand it the fakes.
> - **`delete-session` is `ArchiveService.delete`**: objects first, then the
>   index entry, so nothing is left that the index no longer names.
> - **The "size" column is dropped.** `SegmentStore` has no size call, and
>   adding one to Cloud Storage for a listing is not worth it. Lines
>   (`ackedThrough + 1`) say more.
> - **The VIN appears only in `session <id>`**, never in the `sessions` list
>   (decision 16: the admin tool is its one place, and there, only on request).

`admin.sh` gains:
- `sessions [car]`: id, car, started, lines, complete, size;
- `session <id>`: every index field, the VIN included, and the segment count;
- `delete-session <id>`: the id typed again, then the objects and the document
  deleted.

`remove-car` now **refuses while the car has sessions**, and says how many.

**Done when:**
- Tests against the fakes cover:
  - listing and showing;
  - the VIN appearing only in `session`;
  - delete confirmation and a wrong id;
  - `remove-car` refused with a session, then allowed once it is deleted.
- Each command runs once against the real project.

### M3.7 — Deploy, and prove it live  `sonnet`

> ✅ **Done 2026-09-26.** Revision `00004`, with `GCP_PROJECT` and
> `SESSIONS_BUCKET`. Two throwaway cars, tokens in `chmod 600` scratch files.
> - The app's `outback-2026-09-24-evening-drive.jsonl` (33,091 lines) and
>   `…-cold-start-drive.jsonl.gz` (42,477 lines) were replayed with
>   `--lose-responses 0.3 --duplicate 0.2`: both complete in 23 s.
> - Each `session.jsonl.gz` was downloaded with `gcloud storage cp`: a real gzip
>   file, whose `shasum` equals the upgraded source's, `cmp` identical, and
>   whose lines 1… are identical to the app's own file.
> - `…-unplug-and-drive.jsonl` was stopped with `--stop-after 3` (6,001 lines,
>   "uploading"), then a separate run resumed at `chunk 6001..` and completed,
>   byte for byte.
> - Car B's token got the contract's exact `wrong_car` body on `PUT` and on a
>   chunk; no token got `401`; car A's re-`PUT` got `200 {"ackedThrough":33090}`
>   and re-`complete` got `{"complete":true}`.
> - `remove-car` was refused ("has 2 session(s)"); each session was deleted,
>   then each car.
> - The bucket and `sessions` were empty, and the scratch tokens were deleted.
>
> `admin.sh session` was not run on these sessions, since they carry a real VIN.

> **Validated against what M3.1–M3.6 built, 2026-09-26, before building.** No
> conflict. Four points:
>
> - **`deploy.sh` sets both variables in one `--set-env-vars`**
>   (`GCP_PROJECT`, `SESSIONS_BUCKET`). The flag replaces the whole set, and a
>   deploy keeps anything it does not mention (JOURNAL: M2 deploy).
> - **The app's real logs carry real VINs.** Uploading them into this private
>   project and deleting them after is fine. But `admin.sh session <id>` prints
>   the VIN, so **it is not run on these sessions**; checks use `sessions`,
>   which never shows one.
> - **Throwaway tokens** go into a `chmod 600` scratch file, are passed with
>   `--token-file`, never printed, and are deleted with the cars.
> - **`max-instances` stays 2.** The archive is correct with several instances
>   (M3.2), so the live run exercises that too.

1. Deploy with `SESSIONS_BUCKET` (and still `--clear-secrets`).
2. A throwaway car. Replay the app's largest committed session (about 3 MB of
   JSONL) and a gzipped one against the **deployed** service, with
   `--lose-responses 0.3 --duplicate 0.2`.
3. Download each `session.jsonl.gz`, and check that its sha256 equals the
   upgraded source's.
4. A `--stop-after` run left overnight is not needed: `--stop-after`, then a
   separate `--resume` run, then compare.
5. Check that a second car's token gets `wrong_car` on the first car's session,
   and that no token gets `401`.
6. Clean up: `delete-session` for each, then `remove-car` (refused first, while
   sessions remain, as it should be).
7. Tell Sam the backend is ready for the app's M33.8, and that it needs a real
   car registered (`admin.sh add-car`) with its token pasted into the tablet.

**Done when:** steps 2–6 pass against the deployed service, and the bucket and
`sessions` collection are empty afterwards.

### M3.8 — Record it

> ✅ **Done 2026-09-26.** Decisions 17 and 18. `COMPLETED.md` has M3 with its
> exact counts: 154 tests, 89 new; 46 mutations. There are JOURNAL entries, and
> PLAN, README and CLAUDE.md are updated. This plan is deleted in the next
> commit.

- `COMPLETED.md` entry, including what has never met a tablet.
- `JOURNAL.md`: anything learned.
- `DECISIONS.md`:
  - **17**, the archive's storage (segments named by range, Firestore as the
    authority, store then advance);
  - **18**, the hash-mismatch reset.
- `PLAN.md` row; README and CLAUDE.md commands.
- This plan deleted, and pushed.

---

## Not in M3

- **The live lane** and anything it creates (M4). The session document leaves
  room for it.
- **Showing sessions on the site** (M6), and merging live rows with archive rows
  (contract §7, M6).
- **Setting `max-instances` to 1** (M4, decision 7). M3 is correct with any
  number of instances.
