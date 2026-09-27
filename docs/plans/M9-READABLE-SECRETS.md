# M9 — Readable tokens and passcodes

> **Tabled** (Sam, 2026-09-27): planned and answered, not to be built until
> Sam says. Nothing below is validated against the code yet beyond the
> checks in "What exists"; each step still is, just before it's built.

Each car's token and crew passcode, readable on the admin page, so Sam can look
one up rather than replace it. **Sam, 2026-09-27:** "make those readable, we
don't need super high security here."

Today the server keeps only their hashes (decision 10): a token as its SHA-256,
a passcode with PBKDF2. Nothing can turn them back, so a lost token means a new
one and a trip to the tablet. After M9 the admin page shows both, in full.

---

## What exists, and what it means for this (checked 2026-09-27)

- **`Car` holds hashes only** (`tokenHash`, `tokenHint`, `passcodeHash`), and
  its Firestore document mirrors it (`FirestoreCarStore.toFields`).
- **The hashes do real work, so they stay:**
  - a tablet is recognised by **looking its token's hash up** in Firestore
    (`findByTokenHash`, a query on `tokenHash`). Looking up the token itself
    would work too, but the hash keeps that path unchanged;
  - a crew cookie carries a **fingerprint of the passcode's hash**
    (`CrewAuth.fingerprint`), which is what signs everyone out when the
    passcode changes (decision 22).
  So M9 **adds a readable copy beside each hash**, and changes nothing about
  signing in, the tablet's or the crew's.
- **Every way a token or passcode is set** goes through `CarRegistry`:
  `addCar` and `rotateToken` (generated), `setToken` (chosen, decision 24),
  `setPasscode`. The CLI and the admin page both call them (`:admin`'s
  `CarAdmin`). One place to record the readable copy.
- **Cars set before M9 can't be recovered**: their hash is all there is. They
  show "not recorded" until set again. **The Outback keeps working**: Sam
  chose its token, so **Replace token → Choose the token myself** with the
  *same* value records it, and the tablet never notices (`setToken` allows a
  car its own token). Its passcode (set 2026-09-27) is recorded the same way: set
  again to the same value.
- **Public responses never serialise a `Car`**; they name their fields (M2.5).
  That rule is what keeps the new fields off the public site, and a test
  should pin it.
- **The admin API sends no `Cache-Control`.** Once it carries secrets, it
  should be `no-store`, so no browser or proxy keeps a copy.
- **The admin page** already shows a generated token in full, once
  ("shown once: copy it now"). That banner goes; the token is simply in the
  car's details from then on.
- **`admin.sh list`** prints each car's token hint. It is run in shared
  terminals, and by Claude, whose rule is never to print a token
  (`CLAUDE.md`). So `list` keeps printing only the hint, and a **separate
  command** prints one car's secrets for Sam.

---

## Decided here (say if any is wrong)

- **Stored readable in Firestore**, beside the hashes: fields `token` and
  `passcode` on the car's document. Only the service's account and the
  project's owner can read Firestore. *Not chosen:* encrypting them with a
  key in Secret Manager. That protects a copied Firestore export, which isn't
  a risk worth the work here.
- **Shown on the admin page in full**, each with a **Copy** button, in the
  monospaced face of the fields (M8's follow-up). Only the allowlisted admin
  (Sam, signed in with Google) ever receives them.
- **Never logged, never on the public site, never in `admin.sh list`**, as now.
- **Hashes stay** as the way in: nothing about a tablet's or a crew member's
  sign-in changes.

## Settled with Sam, 2026-09-27

1. **Shown plainly**, no "Show" button.
2. **The crew login box on a car's page stays a password field.**

---

## The steps

### M9.1 — The registry records them

`Car` gains `token: String?` and `passcode: String?` (null for anything set
before M9). `addCar`, `rotateToken`, `setToken` and `setPasscode` record the
readable value beside its hash; the Firestore mapping reads and writes `token`
and `passcode`, deleting either when it's null on an update, as it does for
`passcodeHash`. `Car`'s own `toString` and `IssuedToken`'s never print them.

**Done when:** tests for each way a token or passcode is set; the Firestore
mapping round-trips both, and an old document without them reads as null;
`scripts/firestore-smoke.sh` extended to check the readable copy through the
real Firestore, on a throwaway car, **compared in the shell and never printed**.

### M9.2 — The admin page shows them

`AdminCar` gains `token` and `passcode`. `/api/admin/*` responses are
`Cache-Control: no-store`. The page shows both in the car's details, with Copy,
or "not recorded — set it again to see it here". The "shown once" banner goes.

**Done when:** route tests: the admin list carries both; **every public
endpoint** (`/api/cars`, a car's live stream, sessions) still carries neither;
the admin API is `no-store`. Looked at in Chrome against the dev server (dev
sign-in): a new car's token visible; a chosen token and a passcode visible
after saving; a car from before M9 saying "not recorded".

### M9.3 — The CLI

`admin.sh show <slug>` prints one car's token and passcode, for Sam. `list`
keeps printing only the hint. `CLAUDE.md`: Claude never runs `show` against
the real registry (it prints secrets), and verifies as today, with files that
are compared, not printed.

**Done when:** tests for `show` and for `list` never printing a token;
`--help` says `show` prints secrets.

### M9.4 — Deploy

Deployed as usual. **Sam**, signed in on https://badnewsbears.live/admin:
- Outback → **Replace token** → **Choose the token myself** → the tablet's
  current token, twice. The page then shows it; the tablet keeps streaming.
- **Change passcode**, typing the current one again (set for the first drive,
  2026-09-27), so it's recorded.

**Done when:** Sam sees both on the page, and the tablet still streams (a
tablet session, as in the road test's dry run, shows Live).

### M9.5 — Record it

A decision amending 10 (hashes, and now a readable copy) and 25 (tokens no
longer shown once); `COMPLETED.md`, `PLAN.md`, README, `CLAUDE.md`. This plan
deleted.

---

## Not in M9

- Changing how a tablet or a crew member signs in.
- Showing secrets anywhere but the admin page and `admin.sh show`.
