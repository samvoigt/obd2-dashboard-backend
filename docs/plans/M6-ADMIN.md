# M6 — The admin page

A page at `/admin` for managing cars: add, rename, tokens, crew passcodes,
remove, and their sessions. **Google sign-in, limited to an allowlist of
emails**, so there is no password of our own to build or keep (Sam,
2026-09-26). `admin.sh` keeps working beside it.

Past sessions and dashboards move to M7 and M8.

---

## Settled with Sam, 2026-09-26

1. **Sessions are on the page:** each car's sessions, deleting one by typing
   its id, and a way straight to a car's **live** page when it is live (M6.7).
   Past sessions have no page of their own until M7, which will link them from
   here.
2. **A sign-in lasts 30 days**, like the crew's. Traffic is low, and the
   allowlist is still checked on every request, so removing an email still
   cuts that person off at once.
3. **`ADMIN_EMAILS` is `sam.voigt@gmail.com`.** Sam is the overall admin.
   Everyone on the allowlist can do everything; lesser roles (someone who
   manages only their own car, say) are a later milestone if wanted.
4. **No link to it:** `badnewsbears.live/admin`, typed directly.

---

## What exists, and what it means for this

- **`admin.sh` is the only way to manage cars**, on purpose. Decision 10 says
  "the server has no admin endpoint to attack". This milestone reverses that,
  so it needs its own decision (25), saying why and how it is guarded.
- **The crew login (decision 22) is the model:** an HMAC-signed cookie,
  `HttpOnly`, `Secure`, `SameSite=Strict`, scoped to a path, with JSON-only
  `POST` and `DELETE` requests. The admin cookie follows it.
- **`CarRegistry` already has every rule** (`addCar`, `rotateToken`,
  `setToken`, `setPasscode`, `rename`, `removeCar`), and `admin.sh` is a thin
  shell over them. The page's API is another thin shell over the same ones.
- **`remove-car`'s extra rules live in `:tools`**: refuse while the car has
  sessions, and delete its messages. They have to move somewhere both the
  server and the tool can call, so the two can never disagree.
- **Google's `TokenVerifier`** (`google-auth-library-oauth2-http` 1.53,
  already on the classpath through the Google BOM) checks a Google ID token's
  signature, audience, issuer and expiry. Its keys can be pointed at a local
  file, so tests run offline with a key of our own.
- **The site routes by path** (`web/src/lib/routes.ts`), and the server serves
  `index.html` only on routes it lists (`WebRoutes.kt`). `/admin` is added to
  both.
- **No security headers are set today.** The admin page gets
  `X-Frame-Options: DENY`, since a page that changes tokens must never be
  framed. The rest of the site doesn't need it now.

---

## Decided here, not asked (say if any is wrong)

- **Sign-in is Google Identity Services, "Sign in with Google", on the page.**
  - Google hands the page an **ID token**, a signed statement of who you are.
    The page posts it to `POST /api/admin/login`. The server checks it with
    `TokenVerifier`:
    - the audience is our client ID;
    - the issuer is Google;
    - it hasn't expired;
    - `email_verified` is true, and the email is on the allowlist.
  - If all pass, the server sets the admin cookie. **No client secret is
    involved**, and the server never talks to Google's OAuth endpoints, only
    to its public keys.
  - There is no redirect flow and no refresh token. We ask Google for nothing
    but who you are: `openid` and `email`.
- **The client ID is configuration, not a secret:** `GOOGLE_CLIENT_ID` in
  `deploy.sh`, served to the page by `GET /api/admin/config`.
- **The allowlist is `ADMIN_EMAILS`**, set by `deploy.sh` and compared
  case-insensitively. **Empty means nobody**: a missing setting fails closed.
- **The admin cookie:**
  - `admin`, holding `v1.admin.<email>.<expiry>.<hmac>`;
  - signed with the crew key, under its own `admin` label, so it can never be
    confused with a crew cookie, and there's no second secret to manage;
  - `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/admin`, 30 days;
  - **checked against the allowlist on every request**, so removing an email
    cuts that person off at once, like a rotated token.
- **Admin requests also must come from our own pages:** every `POST`,
  `PATCH`, `PUT` and `DELETE` must carry an `Origin` header matching the host,
  on top of `SameSite=Strict` and JSON-only bodies. It costs one line and
  closes the gap if a browser ever gets `SameSite` wrong.
- ~~Sign-in attempts are rate-limited~~: withdrawn at M6.3 (see there).
- **Every admin action is logged**, with who did it, what, and to which car or
  session. **Never a token or passcode.** Cloud Logging keeps the logs; no new
  store.
- **Tokens are shown exactly as the command line shows them:**
  - a **generated** token appears once, in the response and on the page, with
    a copy button, and never again;
  - a **chosen** one is typed twice in password fields, and only its hint is
    shown back;
  - the list shows each car's hint (`…-15`) and when its token was issued.
- **Destructive actions ask you to type the name:** removing a car (its slug),
  deleting a session (its id), and replacing a token ("Replace").
- **The API**, all under `/api/admin`, and all `401` without the cookie
  except `config` and `login`:

  | | |
  | --- | --- |
  | `GET config` | `{googleClientId}` |
  | `POST login` | `{credential}` → sets the cookie; `DELETE login` signs out |
  | `GET me` | `{email}`, or `401` |
  | `GET cars` | every car: slug, name, token hint, token issued, passcode set, live state (with its session), session count |
  | `POST cars` | `{slug, name, token?}` → the generated token once, or the chosen one's hint |
  | `PATCH cars/{slug}` | `{name}` |
  | `POST cars/{slug}/token` | `{token?}`: generate a new one, or set yours |
  | `PUT cars/{slug}/passcode` | `{passcode}` (logs the crew out, as the command line does) |
  | `DELETE cars/{slug}` | refused while it has sessions; its messages go too |
  | `GET cars/{slug}/sessions`, `DELETE sessions/{id}` | a car's sessions, which one is live, and deleting one |

- **Local work needs no Google:** the dev server takes a **dev sign-in**, a
  button that appears only when the server says so, and signs in as
  `dev@localhost`. **Only `DevServer` can switch it on**, through a `module`
  parameter; `main` has no way to, and a test proves the deployed module
  refuses a dev sign-in.
- **Where the code goes:**
  - the removal rule moves to one function both the server and `:tools` call
    (which module holds it is settled when M6.1 is validated);
  - `AdminAuth` (verify, cookie, allowlist) and `AdminRoutes` in `:server`;
  - `web/src/Admin.svelte` and `web/src/lib/admin.ts`.

---

## The steps

### M6.1 — One removal rule

Move `remove-car`'s rules (refuse while it has sessions; delete its messages)
out of `:tools`, into a function both the tool and the server call. `admin.sh`
behaves exactly as before.

**Done when:** the tool's tests pass unchanged; the rule has its own tests; and
mutations are checked.

> **Validated against the code, 2026-09-26, before building.**
> - **No existing module sees cars, sessions and messages together** without a
>   wrong dependency (`:registry` and `:archive` see nothing else, and `:live`
>   sees only `:archive`). So there's **a new pure module, `:admin`**, on
>   `:registry` and `:live` (which brings `:archive`), used by `:tools` and
>   `:server`.
> - `CarAdmin.removeCar(slug)`: no such car → `NoSuchCar`; any sessions →
>   `CarHasSessions(count)`; otherwise it removes the car and then its messages,
>   returning how many. `checkRemovable` lets the tool refuse **before** asking
>   for the slug to be typed, as it does now. Each caller words the refusal its
>   own way (the tool names `admin.sh`; the page won't).
> - **Deleting a session stays `ArchiveService.delete`**, one call already
>   shared. The page's "not while live" rule needs the hub, which only the
>   server has, so it is M6.7's, and `admin.sh delete-session` is unchanged.

> **✅ Done, 2026-09-26.** `:admin` with `CarAdmin` (`checkRemovable`,
> `removeCar`) and `CarHasSessions`; `admin.sh remove-car` calls it and keeps its
> wording and its "type the slug" prompt. 3 new tests; the tool's tests are
> unchanged and pass. 6 mutations, all killed.


### M6.2 — Verifying a Google sign-in

`GoogleIdentity`: `TokenVerifier` behind a small interface, returning the
verified email or a reason for refusal:
- wrong audience;
- wrong issuer;
- expired;
- bad signature;
- email not verified;
- email not allowed.

Tests sign ID tokens with **our own RSA key** and point the verifier at a local
key file, so the real verification code runs offline. Also: a token for another
client ID; an `accounts.google.com` issuer with and without `https://` (Google
uses both).

**Done when:** every refusal is tested, and mutations are checked.

> **Validated against the code, 2026-09-26, before building.**
> - **`TokenVerifier` throws one exception type for every failure**, so it can't
>   say why. It is built with **no audience or issuer**, and checks only the
>   signature (by the token's `kid`, from Google's key set) and expiry. Our code
>   then checks the audience, the issuer (`accounts.google.com` with or without
>   `https://`), `email_verified`, and the allowlist, each with its own reason.
>   A token that fails the library's check is `Expired` if its `exp` has passed,
>   and `BadSignature` otherwise. Unparsable is `Malformed`.
> - **Fetching Google's keys blocks**, so verification runs on `Dispatchers.IO`.
>   The library caches the keys.
> - **The allowlist is its own small class** (`Allowlist`), parsed from
>   `ADMIN_EMAILS` (comma-separated, trimmed, lower-cased, empty = nobody), and
>   shared with M6.3's per-request check.
> - `:server` declares `google-auth-library-oauth2-http` itself (from the BOM
>   already in use), rather than relying on `:archive-gcp` exporting it.
> - **Tests sign with our own RSA key** (`JsonWebSignature.signUsingRsaSha256`,
>   `GsonFactory`, both already on the classpath), and serve its key set from a
>   local JDK HTTP server via `setCertificatesLocation`, so the code that fetches
>   keys runs too. Expiry uses the verifier's clock.

> **✅ Done, 2026-09-26.** `GoogleIdentity`, `IdentityVerifier`, `SignIn`,
> `Refusal`, `Allowlist`. 5 tests, including a payload swapped after signing.
> Google's library refuses a token from the second it expires, with no
> leeway; a test pins that. 13 mutations, all killed; the expiry boundary
> survived at first and got its test.

### M6.3 — The admin cookie and sign-in routes

`AdminAuth`, and `config`, `login` (`POST`, `DELETE`), `me`. The rate limit,
the `Origin` check, and the dev sign-in (dev server only).

**Done when:**
- the cookie is refused if tampered with, expired, a crew cookie, or for an
  email no longer allowed;
- the `Origin` check refuses other and missing origins on changes, and allows
  `GET`;
- a test proves `main`'s module refuses a dev sign-in;
- mutations are checked.

> **Validated against the code, 2026-09-26, before building.** Three changes
> from the plan:
> - **The cookie can't be the crew's dotted format with an email in it**
>   (emails have dots). It is `adm1.<email, base64url>.<expiry>.<hmac>`: four
>   parts and its own tag, where a crew cookie is five parts starting `v1`. So
>   neither can pass as the other, even for a car whose slug is `admin`. Same
>   key and HMAC as the crew's.
> - **No sign-in rate limit.** A Google ID token can't be guessed, so a limit
>   protects nothing. And one shared limit (there's no car to key it by) would
>   let anyone lock Sam out by posting junk. Verifying costs one RSA check with
>   cached keys. The "Decided here" bullet is withdrawn.
> - **Until M6.6, there is no client ID**, and `main` must still start: the
>   config says admin is not set up, the page says so, and sign-in is `503`.
>   `module` takes an `AdminConfig`, **disabled by default**, so every existing
>   call site and every test stays as it is. Only `DevServer` builds one with
>   the dev sign-in.
> - **The `Origin` check compares the header's host and port with `Host`**,
>   ignoring the scheme. TLS ends in front of Cloud Run, so the server sees
>   `http`. Locally, Vite's proxy keeps `Host: localhost:5173`, which matches
>   the page's origin.
> - **The dev sign-in is a verifier, not a switch:** `DevServer` passes one
>   that accepts the credential `dev` as `dev@localhost`, with that address as
>   the allowlist. `main` builds its `AdminConfig` in a function of its own
>   (from the environment), which a test calls to prove that `dev` is refused
>   there.

> **✅ Done, 2026-09-26.** `AdminConfig` (off by default; `fromEnvironment`),
> `AdminAuth`, `isSameOrigin`, and `config`, `login`, `me`. `DevServer` has the
> dev sign-in. 11 tests. Found while building:
> - **`admin` is already a reserved slug**, so a crew cookie for a car named
>   `admin` can't exist; the test uses an ordinary car.
> - **Ktor's test client sends no `Host`**, where browsers always do; the tests
>   set it.
>
> Mutations: 14, 12 killed. **Equivalent:** the tag check (nothing is ever
> signed with three dotted parts and another tag, so no test can build such a
> cookie; it stays as a second line of defence). **Left to M6.4:** `admin()`'s
> own origin check, which no route uses yet.

### M6.4 — The cars API

Every car action from the table, each a thin call to `CarRegistry` and M6.1's
rule, each logged.

**Done when:**
- each route is tested with and without a sign-in;
- a token or passcode never appears in a log line or in `GET cars` (tested by
  capturing the log);
- a generated token appears exactly once;
- `DELETE` is refused while the car has sessions;
- mutations are checked.

> **Validated against the code, 2026-09-26, before building.**
> - **Adding a car with a chosen token** (create, set the token, remove the car
>   if that fails) is inline in `admin.sh add-car` today. It moves into
>   `CarAdmin.addCar(slug, name, chosenToken?)`, and the tool calls it, so the
>   two can't drift apart.
> - **The hub's status doesn't name the live session**, only whether there is
>   one. `GET cars` gives the live state (as the landing page does) and a
>   session count; the live session's id is M6.7's to add.
> - **Refusals map to statuses:** a bad slug, name, token or passcode → `400`;
>   an unknown car → `404`; a slug that's taken, a token that's another car's,
>   or a car with sessions → `409`. Each keeps the registry's plain words,
>   except for sessions, which the page words itself.
> - A passcode hash is PBKDF2 (about 0.3 s), so `PUT passcode` hashes on
>   `Dispatchers.Default`, as crew login does.
> - **The log test** attaches a Logback appender to the `admin` logger and
>   checks that no token or passcode appears in any line.

> **✅ Done, 2026-09-26.** `adminCarRoutes`, and `CarAdmin.addCar`, which
> `admin.sh add-car` now calls too. `ArchiveService.sessionsOf` and
> `Messages.deleteCar` pass through, so `CarAdmin` takes the services, not
> their stores. 7 API tests and 3 more in `:admin`. Mutations: 12, 10 killed,
> including M6.3's `admin()` origin check. **Equivalent:** logging the passcode
> after it's wiped logs only blanks (the wipe is the protection, and the log
> test catches the real thing); and `addCar`'s early token check only saves a
> write, since a bad token is refused and the car removed either way.

### M6.5 — The page

`/admin` (server route and site route):
- sign in (Google's button, or the dev button locally), and sign out;
- the car list;
- add a car, choosing its token or letting one be generated (shown once, with
  copy);
- rename; replace the token, generated or chosen;
- set the crew passcode;
- remove, by typing the slug.

Every error says what happened in plain words. Pure logic in `admin.ts`, with
Vitest.

**Done when:** type check and Vitest; **looked at in Chrome against the dev
server** (dev sign-in):
- every action;
- a wrong confirmation;
- a token shown once and gone on reload;
- phone width.

> **Validated against the code, 2026-09-26, before building.**
> - `routes.ts` gains `{ page: 'admin' }` for `/admin`; `WebRoutes` serves
>   `index.html` there, **with `X-Frame-Options: DENY`** and
>   `Content-Security-Policy: frame-ancestors 'none'`, so no other site can put
>   the page inside its own. Tested in `WebRoutesTest`.
> - **Google's script (`accounts.google.com/gsi/client`) is loaded only when
>   `config` sends a client ID**: never locally, where the dev button shows. The
>   page's code is in the same bundle as the rest of the site, which is fine:
>   every action is behind the API.
> - **Pure logic in `admin.ts`**, with Vitest: the slug and token rules (a
>   mirror of the server's, for instant hints; the server still decides), the
>   confirmation check, the state labels, dates, and error text.
> - The cookie's `Path=/api/admin` covers every call the page makes; Vite's
>   proxy passes it through locally.
> - **Correction found in Chrome:** Vite's shorthand proxy (`'/api': url`) sets
>   `changeOrigin`, which rewrites `Host` to `localhost:8080`, so every change
>   from the page was a `403`. M6.3's note was wrong about that. `/api` now keeps
>   the page's `Host`, as production does.

> **✅ Done, 2026-09-26.** `/admin` on the server (framing refused) and in the
> router; `Admin.svelte`; `admin.ts` (11 Vitest tests here, 34 in all; type check
> clean). Looked at in Chrome against the dev server:
> - the dev sign-in; the car list;
> - adding a car with a generated token, shown once with Copy, and gone after a
>   reload (no full token anywhere in the page);
> - a bad slug hinted at once;
> - rename; replacing with a chosen token (a mismatch and a wrong "Replace"
>   both blocked), and the new one authenticates while the mismatched one
>   doesn't;
> - setting a passcode (a short one hinted);
> - removing (a wrong slug blocked), and a car with a session refused;
> - 390 px wide: no sideways scroll; sign out: back to sign-in, the API `401`;
> - the dev server's log names each action and holds no token or passcode.
>
> **Found by looking:**
> - **Vite's proxy** (above).
> - **The error line showed only once signed in**, so a failed sign-in looked
>   like nothing happening. It now shows everywhere.
> - **Labels with bold words split into rows** (a grid label makes each text
>   piece a row); each label's text is now one element.
>
> Mutations: 19 in `admin.ts` and `routes.ts`, all killed. **M6.7 is built next,
> before M6.6**, so that Sam's Google setup and the deploy come once, with
> everything in.

### M6.6 — Google setup, and deploy

**Sam, about 10 minutes in the Cloud console**, guided step by step:
1. the OAuth consent screen: External, an app name, your email; the
   `openid` and `email` scopes only, which need no Google review;
2. a **Web application** OAuth client ID, with authorized JavaScript origins
   `https://badnewsbears.live`, `https://www.badnewsbears.live` and
   `http://localhost:5173`.

Then `deploy.sh` sets `GOOGLE_CLIENT_ID` and `ADMIN_EMAILS`, and it's deployed.

**Done when:**
- the deployed `/admin` shows Google's button;
- `/api/admin/*` is `401` without a sign-in, and a forged cookie is refused;
- **you sign in** (only you can; I never enter a Google password) and manage a
  throwaway car from the page, which I check from the command line and the
  logs;
- everything is cleaned up.

### M6.7 — Sessions

On the admin page:
- each car's sessions: started, lines, state, and **which one is live now**;
- a **"Watch live"** link to the car's page (`/cars/{slug}`) on a live car and
  its live session;
- deleting a session by typing its id, with the same rule as
  `admin.sh delete-session`. **A live session can't be deleted** while its
  tablet is still sending: the page says so, and the server refuses it.

**Done when:** tested as M6.4; looked at in Chrome with a replay streaming live
(the link, the live marker, a delete refused while live, a delete after it
ends); mutations are checked.

> **Validated against the code, 2026-09-26, before building** (moved ahead of
> M6.6, so that there's one deploy).
> - **"Not while live" is too narrow.** A session deleted while its archive
>   upload is still going gets `not_open` on its next chunk, and the tablet then
>   re-sends it from line 0 (contract §6), so the delete doesn't stick. The rule
>   is **no delete while the tablet is live on that session, or while its upload
>   is incomplete and was active in the last 5 minutes**. The page says which.
> - **The upload rule needs no live state**, so it goes in `CarAdmin`
>   (`deleteSession`, with a clock), and **`admin.sh delete-session` gains it
>   too**. The live rule needs the hub, so the server adds it, passing whether
>   the session is live.
> - **The hub knows the live session's id** (`CarLive.sessionId`) but doesn't
>   pass it out. `CarStatus` gains `sessionId`, set only while in a session.
>   `GET cars` gives `liveSession`.
> - `GET cars/{slug}/sessions`: id, started (the header's, else when the record
>   was made), lines (`ackedThrough + 1`), and state: `live`, `uploading`,
>   `complete` or `incomplete`. Newest first.
> - **The confirmation is the id's first 8 characters**, not all 36: typing a
>   UUID on a phone is unreasonable, and 8 hex characters can't be hit by
>   accident.

> **✅ Done, 2026-09-26.** `CarStatus.sessionId`; `ArchiveService.session`;
> `CarAdmin.checkDeletable` and `deleteSession`, which `admin.sh
> delete-session` now uses too; `GET cars/{slug}/sessions`, `DELETE
> sessions/{id}`, and `liveSession` in the car list; the page's Sessions panel
> and "Watch live". Tests: 2 API, 2 `:admin`, 1 `:live`, 1 tool, 4 Vitest.
> Looked at in Chrome with a real drive streaming live into the dev server:
> - the live session marked "Live now", with "Watch live" on the car and on
>   the session; an archived one listed as Complete;
> - deleting the live one blocked with its reason and no field; the complete
>   one deleted after typing 8 characters (7 blocked);
> - the replay stopped: the car offline and the session "Uploading", still
>   blocked.
>
> **Found by looking:** the server gave "still uploading" for a live session,
> because the upload check ran first; a live session is usually uploading too.
> Live is now checked first, and the test's live session is a realistic one
> (incomplete, just updated), which the old order fails. Also "1 lines".
>
> Mutations: 15 server/`:admin`/tool (an id with no letters hid the
> lower-casing until the test's id got some), and 5 in the page, all killed
> but one equivalent (the 24-hour option, which `en-GB` gives anyway).
>
> One `check` run failed while the dev server was being stopped, and the log
> was lost; three full reruns with nothing cached all passed. Noted, not
> explained.

### M6.8 — Record it

- Decision 25 (the admin page, and how it is guarded); decision 10 amended.
- `COMPLETED.md`, `JOURNAL.md`, `PLAN.md` (M6 done; past sessions become M7,
  dashboards M8), README and `CLAUDE.md`.
- This plan deleted, and pushed.

---

## Not in M6

- More than one role: everyone on the allowlist can do everything.
- Managing the allowlist from the page. It's a deploy setting, on purpose, so
  a stolen sign-in can't add itself.
- Rate-limiting failed tablet tokens (decision 24's "revisit if").
- Viewing session data. That is M7.
