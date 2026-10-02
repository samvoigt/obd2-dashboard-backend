# M23 — Users: each car, event, course and driver edited by its creator and whoever they add

Today one kind of person edits the site: an admin on the `admin-emails`
allowlist, who can do everything. M23 adds **users**. A user creates cars,
events, courses and drivers. **Whoever creates a thing chooses who else may
edit it**, tokens included. Only its creator, or Sam, may delete it. **Sam,
2026-10-01:** "the creator of each car/event/course can set what users are
allowed to edit them (including tokens and whatnot). we'll leave the crew
code as is. users can create stuff, me as the master admin can delete
anything regular users can only delete things they've created (even if
they're added to another thing, they can edit but not delete)."

> **Planned and answered 2026-10-01, not validated.** "What exists" was read
> from the code on 2026-10-01. Each step is still validated just before it's
> built, and that's written in here.

---

## What exists, and what it means for this (checked 2026-10-01)

- **One sign-in, one check.** Google's ID token → `POST /api/admin/login`
  → the `admin` cookie (`Path=/api/admin`, 30 days). Every admin route calls
  `call.admin(auth, config, change)`, which checks the cookie and the
  allowlist (`ADMIN_EMAILS`, from the `admin-emails` secret) on every
  request, and refuses a change from another origin. `GoogleIdentity`'s
  verify refuses an email not on the allowlist (`Refusal.NotAllowed`). So
  **the allowlist is both "who may sign in" and "who may do what"**, and M23
  splits those apart.
- **24 admin routes in five files**, every one through `call.admin`:
  - `AdminRoutes` (10): cars, tokens, passcodes, a car's sessions, download,
    delete;
  - `CourseRoutes` (7);
  - `EventRoutes` (8): events and drivers;
  - `DriverRoutes` (2): a session's driver and name;
  - `RaceRoutes` (2): flags and stints.
  Each needs a permission check in place of "is admin".
- **Nothing records who made anything.** `Car` has `created` but no
  creator; `Event`, `Driver` and `Course` have neither. Everything that
  exists was made by Sam, through the admin page or `admin.sh`.
- **Firestore stores** for cars (`:registry-firestore`), courses, events and
  drivers. They hold no arrays inside arrays, so a list of editors' emails
  is fine.
- **One server instance** (decision 7), but `admin.sh` writes Firestore
  directly, outside the server (decision 40). Anything cached must allow for
  that.
- **The crew passcode** (decisions 11, 22) is separate, per car, and stays
  exactly as it is.
- **The site already turns edits on from one sign-in store**
  (`lib/signin.ts`, M21). Pages ask it, and every change still goes through
  the server. That store gains the user's role and what they may do.
- **Public endpoints never change with a sign-in**, pinned by
  `PublicWithSignInTest` (M21.5). That holds for users too.
- **Google's sign-in is in Testing mode**: only test users added in the
  Cloud Console can sign in at all, up to 100.

---

## Settled with Sam, 2026-10-01

1. **Users are invited by Sam**, by email, on a Users page. Anyone else who
   signs in with Google is refused, as now. Nobody signs up themselves.
2. **Drivers are like everything else**: any user adds them; the creator
   and whoever they add edit them; the creator or Sam removes them (still
   not one who drove sessions).
3. **A car's sessions are deleted by the car's creator, or Sam.** Editors
   download them, name them and set who drove.
4. **An event may use anyone's car and course.** They're public already.
   Changing the car or course itself still needs access to it.
5. **The crew passcode stays as it is.**

## Decided here (say if any is wrong)

- **Two roles.** **Master admins** are the `admin-emails` allowlist, as now:
  everything, always, and they can't be locked out from the site. **Users**
  are kept in Firestore and managed by a master admin on `/users`.
- **Every car, event, course and driver has an access record**: its
  `creator` (an email) and its `editors` (emails of users). It lives in one
  Firestore collection, `access`, keyed `car:outback`, `event:nhms-october`
  and so on, beside the thing rather than inside it. The models and their
  stores don't change, and one place answers "may this person do this?".
  **Everything that exists today has no record, which reads as made by a
  master admin**, so only Sam can edit or delete it until he adds editors.
- **What each person may do:**

  | | Master admin | Creator | Editor | Other users |
  | --- | --- | --- | --- | --- |
  | Create a car, event, course, driver | ✓ | (any user) ✓ | | ✓ |
  | Edit it: rename, token, passcode, course versions, event, race flags and stints, a driver's name, a car's sessions' names and drivers, download | ✓ | ✓ | ✓ | |
  | Choose its editors | ✓ | ✓ | | |
  | Delete it (and a car's sessions) | ✓ | ✓ | | |
  | Invite and remove users | ✓ | | | |

  The rules that stand still stand: a car with sessions isn't removed, a
  course with laps isn't, and a driver who drove isn't.
- **Editors are chosen from the users Sam invited**, by email, on each
  thing's edit page: the car's Manage, the course and event editors, and a
  driver's row.
- **A removed user loses access at once** (checked on every request, as the
  allowlist is). What they created stays, with them as its creator, so Sam
  still can delete it, and its other editors still can edit it.
- **Lists signed in show what you can act on.** `GET /api/admin/cars` and
  similar return only what you may edit, plus flags for whether you may
  delete it or share it. Public pages are unchanged.
- **Checks read Firestore on every change**, since changes are few and
  `admin.sh` writes there too. Lists read the access records in one query.
- **`admin.sh` acts as a master admin**, as now. It gains `users`,
  `add-user`, `remove-user`, `share <kind> <id> <email>` and `unshare`, and
  `list` shows each car's creator.
- **The dev server's dev sign-in** gets a second button, "Dev sign-in as a
  user" (`dev-user@localhost`, invited at start), so both roles can be
  tried locally.

## Settled with Sam, 2026-10-01 (second round)

6. **Google's sign-in goes to production** when the first user is invited,
   so an invited user signs in without being a Cloud Console test user.
7. **A new user sees "New" buttons** on Cars, Events, Courses and Drivers,
   and nothing else editable until they create something or are added.

---

## The steps

### The API every step builds against (fixed 2026-10-01, before the parallel steps)

- **`GET /api/admin/me`**: `{ email, role: "master" | "user" }`.
- **`GET /api/admin/config`** gains `devUser: true` on the dev server only.
  There, `POST /api/admin/login` with credential `dev-user` signs in
  `dev-user@localhost`, a user invited at start (`dev` stays the master).
- **Lists** (`GET /api/admin/cars`, `/courses`, `/events`, `/drivers`)
  return only what the caller may edit. Each item gains
  `access: { creator: string | null, editors: string[], canShare: boolean, canDelete: boolean }`.
  A `null` creator means made before M23, by a master admin.
- **Creating** (`POST /api/admin/cars`, `POST /api/admin/drivers`, and
  `PUT` of a course or event id that doesn't exist yet) asks `CREATE`, then
  `gate.created(thing, who)`. `PUT` of an existing one asks `EDIT`.
  **Deleting** asks `DELETE`, then `gate.deleted(thing)`.
- **A car's sessions** (list, download, name, driver) ask `EDIT` of the
  car; deleting one asks `DELETE` of the car. **The race routes** ask
  `EDIT` of the event. **`POST /api/admin/courses/check`** only needs a
  sign-in.
- **Sharing:**
  - `GET /api/admin/access/{kind}/{id}` (`SHARE`) →
    `{ creator, editors, invitable: string[] }`, where `invitable` is every
    invited user but the creator.
  - `PUT /api/admin/access/{kind}/{id}` `{ editors: string[] }` (`SHARE`),
    each an invited user; refused `400` otherwise. A thing with no record
    gets one, its creator `null`.
  - `kind` is `car`, `event`, `course` or `driver`.
- **Users** (`INVITE`, master admins only):
  - `GET /api/admin/users` → `[{ email, invitedBy, invited (epoch ms), created: string[] }]`,
    where `created` holds thing keys like `car:outback`;
  - `POST /api/admin/users` `{ email }` → `201`, or `409` if already a user,
    or `400` if it's a master admin;
  - `DELETE /api/admin/users/{email}` → `204`.
- **In the server**, a route asks `call.may(Action.X, Thing(Kind.Y, id))`
  (or `null` to create), or `call.signedIn(change)`, from `Gate.kt`, in
  place of `call.admin(...)`. Both return `Who` or answer `401`/`403`
  themselves.

### M23.1 — Who may do what (not validated)

Pure Kotlin in `:admin` (the owner's rules, shared with `admin.sh`): `Role`
(master, user), `Thing` (car, event, course, driver, and its id), `Access`
(creator, editors), `Action` (create, edit, share, delete, invite), and
`may(who, action, thing, access)`, following the table above. A missing
record means a master admin made it. Tests for every cell of the table, a
removed user, and a missing record.

### M23.2 — Users and access records, stored (not validated)

`UserStore` (email, invited by, when) and `AccessStore` (one record per
thing: creator, editors), each in memory (tests, dev server) and in
Firestore. `scripts/user-smoke.sh` through the real Firestore with
throwaway users and records, deleted after.

### M23.3 — Signing in as a user (not validated)

The login lets in the allowlist **or** a stored user (`GoogleIdentity` no
longer refuses by the allowlist alone). The cookie is unchanged, and every
request checks the role again. `GET /api/admin/me` returns `{ email, role }`.
Tests: a master, a user, a removed user (refused at once), a stranger.

### M23.4 — Every change checks its thing (not validated)

All 24 routes move from "is admin" to `may(...)`:
- **Creating** stores the access record, with the creator.
- **Deleting** removes it.
- **Lists** are filtered, with `canDelete` and `canShare` on each item.
- **An event's** cars and course can be anyone's (Sam, 4).
- **A car's sessions** follow the car (Sam, 3).
- **The race routes** follow the event, and the session name and driver
  routes follow the car.
- The crew's routes are untouched.

Tests per route file: a user can't touch another's thing; an editor edits
but can't delete or share; the creator does all three; a master admin does
anything; a thing with no record is the master admin's. `PublicWithSignInTest`
gains a user's cookie.

### M23.5 — Sharing and users, over the API (not validated)

- **Sharing:** `GET` and `PUT /api/admin/access/{kind}/{id}` (editors;
  creator or master admin). Only invited users can be added, and the creator
  can't be removed.
- **Users:** `GET`, `POST` and `DELETE /api/admin/users` (master admin
  only).
Every change is logged with who made it, as now.

### M23.6 — The site (not validated)

- **The sign-in store** holds the role.
- **Pages show what you can act on:** "New …" for any user; Edit, Delete
  and **Editors** per thing, from the list flags. Editors is a small panel
  that adds or removes an invited user by email, on the car's Manage, the
  course and event editors, and a driver's row.
- **`/users`**, linked in the header for a master admin: invite by email,
  see each user and what they created, and remove them.
- Tests for the store and the flags. Proven by hand on the dev server with
  both dev sign-ins.

### M23.7 — `admin.sh` (not validated)

`users`, `add-user`, `remove-user`, `share`, `unshare`, and the creator in
`list`. Tests in `:tools`, as the other commands have.

### M23.8 — Proven, deployed, recorded (not validated)

- **On the dev server**, as the dev user: create a car, an event, a course
  and a driver; add the master as an editor and back; fail to edit or delete
  the master's things; get nothing from another user's. As the master:
  everything.
- **Deployed** with a live connection open.
- **Sam** invites the first user (`henxing@gmail.com`, asked 2026-10-01)
  and, per question 1, switches the Google sign-in to production or adds them
  as a test user.
- **Recorded:** decision 25 amended (master admins and users), a new
  decision for access, `COMPLETED`, `JOURNAL`, `PLAN`, README, `CLAUDE.md`.
  Then the plan is deleted.
