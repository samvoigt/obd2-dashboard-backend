# Tablet protocol — DRAFT

> **Draft.** It is agreed at the start of M3, and fixtures in
> `protocol-fixtures/` then become the contract both repos test against
> (decision 14). Until then, change freely.

One WebSocket at `wss://…/tablet/stream`, authenticated with
`Authorization: Bearer <car key>`. The key identifies the car (decision 10).
Every frame is a JSON text frame with a `"t"` field naming its kind.

## Records are opaque

A **record** is one line of the app's session log (its `LogRecord`), sent as
the JSON **string** of that line, unchanged. Records go as strings, not nested
objects, so the stored file is byte-identical to the tablet's. The server parses
only the envelope:

| Field | Used for |
| --- | --- |
| `type` | Which kind: `session`, `sample`, `stopped`, `fault`, `gap` |
| `seq` | Bus sequence: ordering and dedup in the live lane, SSE `Last-Event-ID` |
| `at` | Elapsed ms: chart x-axis |
| `wall` | Epoch ms: placing the session in time. May be absent |

Everything else is stored and forwarded unchanged.

## Sessions

A session is named by `sid`: the log file's name with `.jsonl` / `.jsonl.gz`
removed (open question in `PLAN.md`). A tablet may have several sessions still
to deliver, one of them live. It delivers them over one connection, oldest first.

## Tablet → server

```jsonc
{"t":"hello","v":1,"app":"0.9.0"}
// For each session it still holds that has not had `closed`:
{"t":"open","sid":"2026-10-04T13-02-11Z"}
// Log lane: consecutive lines of the file, starting at line `from` (0-based).
{"t":"log","sid":"…","from":1200,"lines":["{\"type\":\"sample\",…}", "…"]}
// The file is complete and has `lines` lines in total.
{"t":"close","sid":"…","lines":48211}
// Live lane: newest records, for display only. Not stored, and may skip.
{"t":"live","sid":"…","records":["{\"type\":\"sample\",…}"]}
// A message is on screen now.
{"t":"displayed","id":"m_7f3a"}
```

## Server → tablet

```jsonc
// Reply to `open`: send the log lane from this line. 0 for a new session.
{"t":"resume","sid":"…","from":1200}
// Everything before line `through` is durably stored.
{"t":"ack","sid":"…","through":1450}
// Every line is stored and the session is finalized. The tablet can mark the archive uploaded.
{"t":"closed","sid":"…"}
// Show this. `ttlMs` is time remaining when sent: expire at receipt + ttlMs,
// never by the tablet's wall clock. `ageMs` is how old it already is.
{"t":"message","id":"m_7f3a","text":"PIT NOW","preset":"pit","ageMs":800,"ttlMs":300000}
// Take it down.
{"t":"clear","id":"m_7f3a"}
```

## Rules

- **Acks are for durability, not receipt.** `through` advances only once lines
  are in Cloud Storage, so a tablet may discard nothing it has not seen acked.
- **`log` frames are idempotent.** A resend of lines the server already has is
  ignored. A `from` beyond what the server has is refused with a `resume`.
- **The live lane never blocks the log lane**, and neither blocks messages. The
  tablet interleaves them, with messages and `displayed` first.
- **On every (re)connect** the server sends all active messages. The tablet
  shows them idempotently by `id`, and a newer message replaces an older one.
- **Heartbeat:** WebSocket ping every 15 s. Two misses and either side treats
  the connection as dead.
- **Cloud Run closes every connection within 60 minutes.** The tablet reconnects
  immediately and resumes; this is routine, not an error.
