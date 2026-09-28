# Journal

Measurements from real deployments, bugs found in the field that tests could
not catch, and lessons about process. Short on purpose.

---

## 2026-09-26 — First deploy

- **`/healthz` never reaches the server on Cloud Run.** Google's front end
  answers it with its own HTML 404. Cloud Run reserves some paths that end in
  `z`. Renamed to `/health`. The local tests could not have caught this.
- **`gcloud run deploy --source` is refused with a bare `PERMISSION_DENIED`**
  on this project. The real cause only showed up when the Cloud Build API was
  called directly: builds run as the compute default SA, which cannot write the
  legacy logs bucket. Fixed with `cloudbuild.yaml` and `CLOUD_LOGGING_ONLY`.
- Local `gcloud` is 418.0 (2023), and `artifacts repositories create` fails on
  it. The setup script calls the REST API for that step instead.

## 2026-09-26 — gcloud 586 needs Python 3.10+

- After an update to 586.0.0, `gcloud` would not start at all: *"You are
  running gcloud with Python 3.9, which is no longer supported"*. The Mac had
  only 3.9 (both system and Homebrew). Fixed by installing Homebrew
  `python@3.13` and setting `CLOUDSDK_PYTHON=/opt/homebrew/bin/python3.13` in
  `~/.zshrc`. If gcloud breaks the same way after a Homebrew cleanup, check
  that path first.

## 2026-09-26 — M2.2, Firestore

- **Google's policy race, again.** A project IAM binding made right after
  creating the Firestore database failed with "concurrent policy changes":
  Google was adding its own Firestore service agent at that moment. This is the
  same race as the first deploy. `gcp-setup.sh` now retries the binding.
  **Expect it after enabling any API.**
- **`Precondition.exists` is package-private** in the Firestore Java client, so
  "delete only if it exists" is a transaction instead.

## 2026-09-26 — M2 deploy

- **A Cloud Run deploy keeps any setting it does not mention.** Dropping
  `--set-secrets` from `deploy.sh` would have left `TABLET_API_KEY` mounted, and
  after the secret was deleted the next revision would have failed to start.
  This was caught while validating the M2.6 plan, not in production. Removing a
  setting needs an explicit flag (`--clear-secrets`, `--remove-env-vars`).
- **The service has two URLs.** gcloud 586 prints the newer form,
  `obd2-backend-286164118741.us-east4.run.app`. The older
  `obd2-backend-qeppiy7nzq-uk.a.run.app`, which the contract names, still
  serves. Both answer.
- **Validating each step's plan against the code just built paid off** (Sam's
  instruction). It found five conflicts, each before it could cost anything:
  - Firestore needed its own module, to keep `:registry` pure;
  - `CarAuth` had to sit beside `TabletAuth` until the deploy;
  - `--clear-secrets` (above);
  - the Ktor bearer provider cannot change its `401` body;
  - `Precondition.exists` is not public.

## 2026-09-26 — M3, the archive lane

- **`Content-Encoding: gzip` on a stored object means Cloud Storage decompresses
  it on download.** A `.jsonl.gz` would arrive as plain JSONL under a `.gz`
  name. Objects are stored as gzip files (`application/gzip`) instead. Found
  while validating the M3.3 plan, before any object was written.
- **The contract gives a `409` two shapes** (`{missingFrom}` in §6.2–6.3, and
  the §14.2 error body in §6.4). The server sends both at once.
- **Mutation runs earn their keep.** Four of 46 mutations first
  survived, and each showed a real gap in the tests, not in the code. One
  survivor came from a mistake that a *later* step quietly repaired: a replay
  that ignored `409`'s `missingFrom` still finished, because `/complete`'s own
  `409` put it right. **A test that only checks the end state can miss a wrong
  path to it.**
- **The mutation runner confused "compiled and passed" with "compile error"**,
  because Ktor's log line "413 Payload Too Larg**e:**" matched `e: `. Fixed by
  deciding on the exit code first.
- **JUnit 4 needs `void` tests.** `= runBlocking { … }` returns its last
  expression, and the class fails to initialise. Use `runBlocking<Unit>`.
- **zsh does not split an unquoted variable into words**, so `$CMD args` runs a
  program named after the whole string. Use a function.
- **Deleted objects stay 7 days in soft delete.** The live check uploaded two of
  the app's real logs, with a real VIN, to the private bucket, and deleted
  them; they are recoverable until the soft-delete window passes.

## 2026-09-26 — M4, the live lane

- **Cloud Run keeps an open WebSocket on the old revision after a deploy.** It
  moves only new connections, and does not SIGTERM an instance while it holds
  one. So the promised `1012` on deploy never came, and the site showed offline
  until the tablet's own socket dropped: up to 55 minutes for a real tablet.
  Found only by deploying while a replay streamed. Fixed by draining on a
  revision check (decision 20). **Test a deploy with a live connection open.**
- **Cloud Run's defaults would have broken it before that:** a 300 s timeout
  (sockets cut at 5 minutes) and a concurrency of 80 (the 81st connection
  refused). Read the running service's settings before planning.
- **Ktor's test engine buffers a response until it ends**, so an SSE stream
  hangs it. SSE tests run on real Netty with the JDK's HTTP client.
- **Resolving Ktor's port inside a test's outer `runBlocking` hung**, only
  when the whole class ran. A thread dump found it: the test parked in its own
  setup, not in the code under test. Use the shared `start()` helper.
- **Looking at the page found a replay bug that tests had not:** both lanes
  paced from line 0's `at = 0`, while old logs' samples carry the app's uptime,
  so real speed waited 67 minutes for the first batch. **A test at speed 0
  cannot see pacing.**
- **After a drain the chart starts empty**, since the new revision's history
  is empty (decision 19's cost).
- **Sparse data flickers between live and behind.** An engine-off stretch sends
  a batch every few seconds, across the 2-second line. That is right for such
  data; if real idle data looks the same, revisit the threshold.
- `svelte-check` supports TypeScript 5 and 6, not 7; the site pins 5.9.3.

## 2026-09-26 — M5, crew messages

- **Firestore wants a composite index** for `car ==` with `sentAt`
  descending. The in-memory tests could not know; the live smoke run found it.
  It took about 4 minutes to build. `gcp-setup.sh` creates it.
- **The JDK's WebSocket allows one send at a time.** Once the replay answered
  messages while streaming batches, its sends overlapped; a mutex fixed it.
- **A salted hash hid a mutation.** The cookie's fingerprint made a slug check
  look tested when it was not. A test with identical stored hashes for two cars
  caught it.
- **The browser's password manager takes over a clicked password field**, and
  then Chrome automation can neither see nor click the page. On localhost the
  passcode was filled by script instead. On the deployed site, the crew path
  was checked through the API.
- **A Svelte component's styles are scoped**, so a class borrowed from the
  parent page (`.panel`) did nothing. Found only by looking at the page.
- **Deploying with a message on screen worked first time**, because decision 20's
  drain and the stored messages were designed together.

## 2026-09-26 — The domain

- **Create the domain mapping after the DNS records, or expect a wait.** The
  mappings were made first, so Google's first checks saw Namecheap's parking
  records and failed with "challenge data was not visible through the public
  internet", although every public resolver already had the new records. It
  retries every 20 minutes; the certificate came on the retry at 23:09 UTC,
  57 minutes after the mapping. Nothing needed changing.
- Domain mappings need the `gcloud` beta component, installed for this.

## 2026-09-26 — M6, the admin page

- **Vite's shorthand proxy (`'/api': url`) sets `changeOrigin`**, which rewrites
  `Host` to the target. Any same-origin check then fails locally although it
  works in production. `/api` keeps the page's `Host` now.
- **Ktor's test client sends no `Host` header**, which every browser does.
  Tests of anything that reads it must set it.
- **Google's `TokenVerifier` throws one exception for every failure**, and
  refuses a token from the second it expires, with no leeway. Checking the
  audience, issuer and email ourselves gives each refusal its reason.
- **A test id with no letters hid a `lowercase()`**, which a mutation found.
  Test ids should have letters in them.
- **Deleting a session mid-upload doesn't stick**: the tablet re-sends it from
  line 0 after `not_open`. Found while validating M6.7, before building it.
- **Two things with one name confuse**: a "Sessions: 0" line beside a
  "Sessions" button, and Sam read the line as the list. The count is on the
  button now.
- **The browser automation hides fields named like secrets** ("token",
  "sessions") in script results; name them otherwise when checking a page.

## 2026-09-26 — M7, past sessions

- **Java takes a quarter of the container by default**: on Cloud Run's 512
  MiB, the server had a 128 MiB heap for everything. Found by measuring with
  the heap capped as production's is. It's 75% now.
- **A Firestore index that rewrites whole documents erases any field outside
  its mapping.** The session summary had to be part of the record's mapping,
  or the next chunk's write would have removed it.
- **The replay's upgrade gave only line 0 a `wall`**, so every replayed
  session lasted "0 s" on the page. Real v3 has `wall` on every record; the
  replay now does too. A tool that stands in for the tablet has to be as
  faithful as the contract.
- **`seq`, not time, places a `gap`**: one written in the same millisecond as
  a sample fell on the wrong side of it. A mutation found this.
- **A number JSON can't hold** (`1e999` parses as infinity) would have made
  the prepared file invalid; it's written as a break.
- **The map drew once**, and stopped growing while the chart went on. Found
  only by watching a live session in Chrome.
- **uPlot bridges `undefined` and breaks at `null`**, which is exactly what a
  join of signals with their own gaps needs.
- **Vite and Svelte's type check:** `let x: T | null = $state(null)` narrows to
  `never` inside derived values; `$state<T | null>(null)` doesn't.

## 2026-09-27 — M8, the dashboard

- **Svelte 5's deep `$state` is the cost to watch.** Anything large goes in
  `$state.raw` and is replaced, never changed in place: the live state, the
  chart's columns, a session's series. As deep state, the chart alone cost a
  quarter of every minute on a slowed phone (uPlot read every point through
  the proxy), and the preview hung the browser. A CPU profile found it; the
  suspects I'd listed (the passes over the history) were about 1 ms each.
- **Measure, don't guess, and keep the measuring honest.** `measure.mjs` gives
  fps, long tasks and heap a minute. It lied three ways before it didn't: the
  Mac's sleep froze a "minute" into twenty (run under `caffeinate -s`; `-i`
  lets a closed lid's maintenance sleep through); an edit under `web/src`
  hot-reloaded the page and reset its counters; and Photos' and Spotlight's
  night work made a clean page look slow. A second run, the Mac's load logged
  beside it, settled that.
- **`sips` renders a PDF in Display P3.** Stripping the profile without
  converting made the logo's pink `#EA3396` instead of `#FF0099`.
- **Leaflet fires a `moveend` of its own inside `setView`**, when it stops a
  drag's inertia. A flag cleared on `moveend` then called the page's own pan
  the viewer's, and "Follow the car" stopped following. Unanimated pans end
  inside the call, so the flag is set around it.
- **Leaflet can't draw a line on a map with no view**; set the view first.
- **Chrome's automation couldn't resize a window** that reported the
  screen's width, so phone width was checked in a 390 px frame on the same
  host, where media queries follow the frame.
- **Deployed with a drive streaming** (revision `00017`): the replay
  reconnected at once on the `1012`, and an open page stayed "Live".

## 2026-09-27 — The first real drive (the road test)

Sam's Outback around the neighbourhood, the tablet streaming over a phone
hotspot, 11:49 AM–12:36 PM EDT. Read afterwards from the service's logs and
the stored sessions (read in a private scratch folder, deleted after; the VIN
never printed). The plan was `plans/ROAD-TEST.md`, closed with this entry.

**What worked**
- **Seven sessions, all complete**, every `complete` accepted, nothing
  missing: one **car session** of 11 min (24,768 records), five **tablet
  sessions** of 36–80 s (the app's M40, at the desk and in the driveway), and
  one **fake-data session** of 41 s (§21).
- **The car session is clean**: no `seq` missing, the tablet's clock never
  stepping back, steady rates (rpm and speed every 177 ms, the G-meter every
  102 ms, GPS every 1,000 ms, coolant and the diagnostics every 4 s, voltage
  every 5 s).
- **GPS speed agrees with the car's** to 0.1 km/h (median above 20 km/h,
  n = 317); accuracy 3.8 m typical, 40 m at the first fix.
- **Every session carried GPS and the G-meter** (the empty map and G-meter
  seen before the drive were connections that sent nothing, 8 s and 50 s).
- **Crew messages**: a login, a message sent and cleared, at 15:37–15:41.
- **Nothing failed on the server**: every tablet request 200, 201 or 101.

**What didn't, the tablet's (passed to the app in chat, not written there)**
- **The tablet's clock is 10 h 58 min slow.** Every `wall` and `started` is,
  so the site lists the drive at 1:27 AM. The fix is the tablet's automatic
  date and time.
- **The G-meter reads high.** At a steady cruise the longitudinal reading sat
  at +0.1 to +0.3 g (30-second means) while the car's speed was flat; the
  car's speed gives at most 0.19 g accelerating and 0.27 g braking; the two
  correlate at r = 0.35 over 1-second means. Stopped, it averages near zero
  but spikes to 1.25 g (the tablet being handled, likely). The app's A17.
- **The live link:** a second socket opened at 16:27 while the first was
  still open (closed by the server at 16:30); none after 16:35:16, about a
  minute before the drive ended. Chunk uploads paused 16:31–16:35, then 79 at
  once: a dead zone, most likely, to be matched against the tablet's strip.
- **The fake session carries `protocol` and `pids`**, which §21 says it
  doesn't.

**What didn't, the site's (planned as M11)**
- **The charging gauge was blank**: its slot is `control_module.voltage`, and
  the tablet sent `vehicle.system_voltage`. **The tablet sends only what its
  own dashboard shows plus ticked extras**, so the site's slots can name
  signals a drive never carries; `fuel.system_1_status` wasn't sent either.
- **Tablet and fake sessions look like drives**: the list grouped all six
  afternoon sessions, fake data included, into one. §20 and §21 ask the site
  to say so, and never to count fake data.
- **Small:** no `apple-touch-icon.png` or `favicon.ico` (an iPhone asked);
  the car page's minute check for the live session's series answers 404,
  logged as a warning, for the whole of a tablet session, which uploads only
  at its end.

**Not reported, from the plan's checklist:** the hotspot switched off on
purpose, "Whole session" while driving, the page on a phone through the
whole drive, the admin page's Download.

## 2026-09-27 — M12, courses

- **A contract change can be built in step with the other side** when both
  have agreed the parts being built: M12 built only what §22 already had from
  both sides, and folded in the rest (closed layouts, the pit line's exact
  coordinates, in-lap sectors) the moment the tablet side wrote §22 up.
- **Gradle doesn't know what a test reads from outside its source set.** The
  NHMS seed test stayed "up to date" after the seed changed, so every mutant
  of the seed script survived. Every test task that reads `courses/seed/` now
  declares it as an input.
- **A drawing tool's defaults can bury the drawing.** Geoman's vertex
  handles on a 153-point layout covered the course; editing points is a
  switch, one line at a time.
- **Two labels 22 m apart overlap at a normal zoom**: the start/finish's hid
  under the pit line's until they were put either side.
- **USGS The National Map's orthoimagery** is public domain, tiles to zoom 16
  at NHMS (2.4 m a pixel), enough to place a line within a few metres.
- **Firestore holds no arrays inside arrays**, so GeoJSON is stored as text.
- **Deployed with a drive streaming** (`00021`): the replay reconnected on the
  `1012` and took `courses` from the new revision straight away.

## 2026-09-27 — M13, re-timing

- **The first real drive, re-timed** (read from its public series in a
  private scratch folder, deleted after). It's one 3.3 km loop, parked at
  both ends, and passes nowhere twice the same way (out south by one street,
  home south by another), so **no single line gives it a lap**. The pit line
  also ends a lap, so the test course had a start/finish on the street out
  and a pit line on the street home: one in-lap, **434.997 s** re-timed on
  version 1, **417.025 s** on version 2 (the line 100 m on), against 434.998
  and 417.026 from the series' own positions. The start moved by exactly the
  expected millisecond.
- **The tablet's sessions and the car's that afternoon are one run of the
  app** (one device, `at` rising): the re-timing was stored beside a desk
  session from before the drive, as §22.8 says it should be.
- **A stream across a deploy needs the Mac awake.** Two Ping timeouts (1006)
  in a live replay were the Mac's idle sleep (20:43:52 and 20:52:26 local, the
  server's timeout 35 s after each), not the server; in a dark wake between,
  the cutover went as designed (`1012`, reconnected at once). Run long streams
  under `caffeinate -i`. A replay that must cross a deploy needs to last longer
  than the Cloud Build (about 6 minutes): the first, 5 minutes, ended before
  the cutover.
- **Floats and equality:** re-timing's sectors differ from each other in the
  7th decimal (a receiver's centimetres), which broke the page's
  best-of-each-sector highlight; laps are shown to the millisecond, as the
  tablet's records are.
- **Flaky:** `:replay`'s coalescing test failed once in a loaded full run and
  passed three times alone.

## 2026-09-27 — M14, drivers and events

- **The server's clock places sessions, and it works:** the dev server's test
  drives said 08:00 and 09:00 on the tablet's clock, the server heard them at
  22:05, and each landed in the right practice.
- **An in-memory store can hide a Firestore fault.** An empty driver id found
  nothing in memory and made Firestore throw (a 500) in production. Ids from
  a request are now checked before Firestore sees them. **zsh doesn't split
  an unquoted variable into words** (`set -- $IDS` gave one argument), which
  is how the empty id got there.
- **The deploy's stream kept clear of the proof:** a stream is a session too,
  and would have joined the test event's window; it came from a second
  throwaway car. Both cutovers (`00024`, `00025`) drained with `1012` and
  reconnected at once, under `caffeinate -i`: no Ping timeouts.
- **Chrome here loses the first click on a page** (and sometimes the next,
  after typing brings up an extension's overlay): the dev sign-in and form
  buttons needed a second click every time; nothing in the site's console.
  The extension's element sits in every page. Filling fields with the form
  tool and checking by reading the page was reliable.

## 2026-09-28 — M15, the race

- **A pit lane starts and ends on the track.** NHMS's does to the metre, and
  runs within 3 m of it 40 m from its end; a line made at an end would be
  crossed by cars that never pit. Measure the geometry before choosing where
  a made line goes: the lines went where the lane is 16 m clear (70 m in, 76 m
  before the exit), and a stop misses some 8 s of lane, the same every time.
- **The tablet's own battery shapes the race.** A driver change cuts the
  car's power, not the tablet's, so the run of the app carries on and so does
  the tablet's lap timing; only a restart needs bridging (the app's
  `HARDWARE.md`).
- **Two clocks, one answer:** flags Sam enters are real times, laps are on the
  tablet's; the smallest `created − started` over the race's sessions is the
  offset to within seconds, since a live-announced session is created as it
  starts.
- **The flaky replay test was a fixed sleep.** It waited 300 ms for the
  server to handle the socket's last batch; a loaded machine sometimes took
  longer. It now retries its assertion for up to 10 s. And a commit went in
  before its test run's result was read: read the result first.
- **Production proof by the crew's route only**: the live site's admin page in
  this Chrome is Sam's session, so it was only looked at; every change went
  through the crew's passcode, as the pit wall would make them.
