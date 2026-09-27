# Protocol

**The contract is the app's `docs/TELEMETRY-CONTRACT.md`, v1, final, at app
commit `918fa1e`** (decision 15):

- On GitHub: https://github.com/samvoigt/obd2-dashboard/blob/918fa1e/docs/TELEMETRY-CONTRACT.md
- Locally: `../obd2-dashboard/docs/TELEMETRY-CONTRACT.md`. Check it is still at
  that version with `git -C ../obd2-dashboard log -1 --format=%h -- docs/TELEMETRY-CONTRACT.md`.

**Re-pinned 2026-09-26**, twice, both times with Sam's agreement, and neither
changing anything on the wire:
- from `2210082` to `4644ab7`: §10 only (the server address built into the app;
  only streamed sessions archived);
- from `4644ab7` to `918fa1e`: the tablet's §15 (GPS as built: an `m` unit, no
  fixed rate, absent fields left out) and §16 (a new `lap` record), both
  confirmed by this side in §17.

Read the contract's body. Its §§12–14 are the record of how it was agreed, and
everything in them is already folded into the body.

**Don't edit the contract from this side.** A change is a v2, agreed through Sam.
When one is agreed, move the pinned commit here and in decision 15 in the same
change.

## What this side committed to (contract §14.5)

None of these is in the contract's body, and each has to be true of the server:

- **The server closes live sockets itself:** with **1001** at about 55 minutes,
  before Cloud Run's 60-minute cut, and with **1012** on SIGTERM, within Cloud
  Run's 10 s grace period. That way the tablet sees a clean close, not a drop.
- **Versions:** refuse only an unknown **subprotocol** (`unsupported_version`).
  Accept any `hello.v` / `session.v` ≥ 3, and keep records not understood.
- **Message text:** at most 40 characters, enforced by the website and the API.
- **The VIN is never shown** on a web page or in a public API response
  (decision 16).
- **`received` / `displayed` for an unknown or finished message `id`** are
  ignored, not treated as errors.
- **No path ends in `z`** (JOURNAL 2026-09-26).

## Where each part is built

| Contract | Milestone |
| --- | --- |
| §8 tokens per car | M2 |
| §6 archive lane | M3 |
| §5.1–5.3 live lane | M4 |
| §5.4 crew messages | M5 ✅ |

## Agreed since the pin: courses and timing (contract §22)

- **Contract §22** (the app's repo, 2026-09-27): the tablet times laps and
  sectors, and its numbers are the results; the server keeps the books.
  Proposed by this side
  ([`proposals/COURSES-AND-TIMING-TO-THE-TABLET.md`](proposals/COURSES-AND-TIMING-TO-THE-TABLET.md),
  revision 2), written into the contract by the tablet side, agreed by Sam and
  the tablet side, and **confirmed by this side** (the proposal file's last
  section).
- **Built here (M12):** `GET /v1/courses` and the `courses` frame to a tablet
  listing `courses.1` (§22.2, §22.4); courses drawn and versioned on the
  website, closed layouts, NHMS's `pit_line` from the tablet's coordinates
  (§22.5); the tablet's `lap` records with `course`, `courseVersion`, layout
  `id`, `sectors`, `startAt`, `endAt` (§22.6) stored whole and shown.
- **To come:** re-timing on `fixAt` (§22.1, §22.3, §22.8; M13), `timing`
  (§22.7; M17).
