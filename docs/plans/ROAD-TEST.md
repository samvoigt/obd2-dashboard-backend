# Road test — the website with a real car

The first drive with the tablet streaming to https://badnewsbears.live. Everything
so far was proven with replays and a synthetic race; this is the real signals,
a phone hotspot and a patchy network. Sam executes it; the analysis afterwards
is mine.

**It answers the website's half** of the app's owed rows (its
`docs/plans/CAR-SESSION.md`): A18 (a session reaches the backend), A19 (live
data and a crew message), A20 (GPS), A22 (a whole drive), A24 (a tablet
session). The tablet's own checks in those rows stay in the app's plan; do
them on the same drive.

**Two people is best:** the driver, and someone watching the site (at home on
a laptop, or a passenger). Alone works too: every check on the site is done
**parked**. **Nobody looks at a screen while driving.**

About 45 minutes: 15 at the desk, 10 in the driveway, a 15–20 minute drive,
10 after.

---

## 1. At the desk (tablet on home Wi-Fi, no car)

- [ ] **Set the crew passcode** (the Outback has none, so nobody can send it
      messages yet). On the laptop, in this repo:
      `scripts/admin.sh set-passcode outback-2018` and type it twice. Give it
      to whoever is watching.
- [ ] **The tablet**, as the app's "Before the engine starts" says: the build
      installed, capture on, rotation locked, a long screen timeout. On its
      dashboard, a **Map** widget and a **Crew messages** widget.
- [ ] **Cars page:** the Outback's token is there (it was set 2026-09-27).
      **STREAM on.**
- [ ] **The dry run (A24):** with no adapter connected, a tablet session
      starts (the strip reads "… · tablet"). Within a few seconds,
      https://badnewsbears.live shows **Sam's Outback: Live**. On its page: the
      banner **Live**, the **map** and **G-meter** (move the tablet to see
      the dot move), and the car's gauges at **"—"**, since there's no car.
      The first connection after a quiet while can take ~10 s (the server
      starts cold).
      - **If it stays Offline:** stop here. Check the token and STREAM, note
        the time, and tell me; don't drive until this passes.
- [ ] **The watcher's device:** open https://badnewsbears.live/cars/outback-2018,
      log in to **Crew messages** with the passcode, and pick Metric or US.
      Send **"test"**: the tablet shows it with a tone; the site says **On the
      tablet, not on screen**, then **On the driver's screen**. **Clear** it:
      it leaves the tablet, and the site says **Cleared**.

## 2. In the driveway (engine running, parked)

- [ ] **Phone hotspot on**, the tablet on it (it has no cellular). The strip
      reads **live**.
- [ ] **Adapter in, start the car.** The tablet session ends and the car's
      begins. On the site within a few seconds: **rpm, speed 0, coolant,
      voltage** moving, and the same numbers as the tablet's (give or take
      an update). **GPS speed** 0 or "—". The status lights: **MIL Off**, the
      fuel system's words, **Trouble codes None** (unless there are some).
- [ ] **The dead zone, safely:** switch the **hotspot off for a minute**.
      - The site: within 2 s the banner reads **"Last data N s ago"**, counting;
        after 30–45 s, **Offline**. The readings go grey.
      - Send a message **while it's off**: the site says **Waiting for the
        car**.
      - **Hotspot back on:** within a few seconds, **Live** again, the readings
        current at once (not replayed), and the waiting message appears on
        the tablet.
      - Note the times (off, on).

## 3. The drive (15–20 minutes, around the neighbourhood)

The driver just drives. Include a few turns, a stop or two, a stretch at a
steady speed, one firm (safe) stop, and anywhere the phone's coverage is weak.

**The watcher**, or the driver when parked:
- [ ] **Gauges** follow the car: rpm and speed rise and fall; coolant warms
      and settles (the tablet's history: 85–95 °C once warm); voltage ~14 V
      running. Nothing in pink unless it's really there.
- [ ] **GPS speed against the speed gauge** at a steady speed: close. A big
      gap is a bug; note both numbers.
- [ ] **The G-meter** moves on turns (left and right) and braking; its peaks
      grow. A firm stop should read a few tenths of a g.
- [ ] **The map** follows the car, its trail on the road and coloured by
      speed. **Drag it away**, then press **Follow the car**: it follows
      again, and keeps following.
- [ ] **Where coverage drops:** the banner and times, as in the driveway.
      Afterwards, the chart shows a gap there, not a line drawn across it.
- [ ] **Whole session** on the chart: the drive since the start, the last
      stretch shaded until the archive has it (a chunk every ~2 minutes).
- [ ] **On a real phone:** the page scrolls smoothly and keeps up for the
      whole drive; note if the phone gets warm or the page ever freezes.
- [ ] **Screenshots**: a few of the page while moving (the watcher's device).

## 4. After (parked, engine off)

- [ ] **Engine off:** the car session ends. The tablet's Logs page goes from
      "Streamed, uploading" to **"Streamed and uploaded"** once the hotspot
      carries the rest. Ten seconds later a **tablet session** starts (M40):
      the site shows Live again, with only the map and G-meter. That's
      expected.
- [ ] **STREAM off** when done, which ends the tablet session and uploads it.
      (Left on at home, it keeps a tablet session streaming.)
- [ ] **Past sessions** (the car page's link): the drive is listed, grouped
      with the tablet sessions around it. Open it: the **duration** is right,
      the chart covers the whole drive with gaps where the network dropped,
      the **map** traces the route coloured by speed, and the events list any
      stops or faults.
- [ ] **The admin page** (https://badnewsbears.live/admin, signed in with
      Google): the drive's **Download** gives the whole log (M7's owed
      check). It opens as `.jsonl.gz`.
- [ ] **The tablet's files** go to the app's `test-data/`, as its plan says
      (the app's side, not this repo's).

## 5. Tell me

- The **times**: engine start, the hotspot off and on, any dead spots, the
  messages, engine off. Rough is fine.
- **Anything odd**, with screenshots, and which device it was on.
- Which boxes didn't tick.

**Then I'll:**
- read the service's logs for the drive: connections, reconnects, errors,
  how long batches took;
- check the drive's session: the record count the same as the tablet's log,
  `complete` accepted, the series prepared, laps none;
- compare what the site showed with the log: GPS speed against road speed,
  the gaps where you saw them;
- write it up in `docs/JOURNAL.md` and close this plan, with anything found
  as a fix or a next milestone.

---

## Known, and expected

- **A tablet session looks like any other session** on the site (the car's
  gauges at "—", "app … · device …" as usual). Showing it as "tablet only",
  as the app's contract §20 invites, isn't built.
- **One server, cold starts:** the first connection after a quiet spell
  takes up to ~10 s.
- **A drive over 55 minutes** sees the live link close and reopen once (the
  server's limit, contract §5.3): a blip of "Last data 1 s ago", then Live.
- **No laps:** lap timing needs a track (A21).
