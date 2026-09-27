# M6 — The admin page

A page at `/admin` for managing cars: add, rename, tokens, crew passcodes,
remove, and their sessions. **Google sign-in, limited to an allowlist of
emails**, so there is no password of our own to build or keep (Sam,
2026-09-26). `admin.sh` keeps working beside it.

Past sessions and dashboards move to M7 and M8.

---

## Questions for Sam

1. **Sessions on the page:** listing a car's sessions, and deleting one (you
   type its id to confirm, as `admin.sh` asks), is step M6.7. Keep it, or leave
   sessions to `admin.sh` for now?
2. **How long a sign-in lasts:** proposed **12 hours**. That is short, because
   this page can replace every token; signing in again is one click.
3. **Who is on the allowlist:** just `sam.voigt@gmail.com` to start?
4. **A link to it:** proposed **none**. You go to `badnewsbears.live/admin`
   directly, and the public pages don't advertise it.

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
  - `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/admin`, 12 hours;
  - **checked against the allowlist on every request**, so removing an email
    cuts that person off at once, like a rotated token.
- **Admin requests also must come from our own pages:** every `POST`,
  `PATCH`, `PUT` and `DELETE` must carry an `Origin` header matching the host,
  on top of `SameSite=Strict` and JSON-only bodies. It costs one line and
  closes the gap if a browser ever gets `SameSite` wrong.
- **Sign-in attempts are rate-limited** like the crew's: 10 failures in 10
  minutes → `429`.
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
  | `GET cars` | every car: slug, name, token hint, token issued, passcode set, live state, session count |
  | `POST cars` | `{slug, name, token?}` → the generated token once, or the chosen one's hint |
  | `PATCH cars/{slug}` | `{name}` |
  | `POST cars/{slug}/token` | `{token?}`: generate a new one, or set yours |
  | `PUT cars/{slug}/passcode` | `{passcode}` (logs the crew out, as the command line does) |
  | `DELETE cars/{slug}` | refused while it has sessions; its messages go too |
  | `GET cars/{slug}/sessions`, `DELETE sessions/{id}` | if question 1 keeps them |

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

### M6.7 — Sessions (if question 1 keeps it)

A car's sessions on the admin page (started, lines, state), and deleting one by
typing its id. The same rule as `admin.sh delete-session`.

**Done when:** tested as M6.4; looked at in Chrome; mutations are checked.

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
