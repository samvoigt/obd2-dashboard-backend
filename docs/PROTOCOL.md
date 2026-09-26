# Protocol

**The contract is the app's `docs/TELEMETRY-CONTRACT.md`, v1, final, at app
commit `4644ab7`** (decision 15):

- On GitHub: https://github.com/samvoigt/obd2-dashboard/blob/4644ab7/docs/TELEMETRY-CONTRACT.md
- Locally: `../obd2-dashboard/docs/TELEMETRY-CONTRACT.md`. Check it is still at
  that version with `git -C ../obd2-dashboard log -1 --format=%h -- docs/TELEMETRY-CONTRACT.md`.

**Re-pinned 2026-09-26** from `2210082` to `4644ab7` (Sam). The difference is
§10 only: the server address is built into the app, and only sessions streamed
live are archived. Nothing on the wire changed.

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
| §5.4 crew messages | M5 |
