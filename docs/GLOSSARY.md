# Glossary: the site's words

One word for each thing the website shows (M36.9), and what it means in a
sentence. `web/src/lib/terms.ts` holds the same words and sentences: the
pages show a term's sentence when its "?" is tapped. A new term goes in both.
Dates are written one way everywhere: **Sun 18 Oct, 14:14** (the year added
when it isn't this year), and days as **Fri 16 – Sun 18 Oct**.

| Term | What a visitor sees | What it means |
|---|---|---|
| session | Session | One recording by the car's tablet, from when it starts recording to when it stops. |
| merged session | "N sessions merged" | Sessions joined into one because they were one drive, split by an app restart or a lost connection. |
| drive | (the sessions list's groups) | Sessions close together in time, listed together. |
| part | Practice, Race (an event's parts) | A stretch of an event's schedule, such as practice or the race, with its own start and end. |
| race session | Session (in the race's stints table) | A session that falls inside the race part. |
| stint | Stint | A driver's unbroken time in the car during the race, from one driver change to the next. |
| stop | Stop | The time spent in the pit lane, from the pit-in line to the pit-out line. |
| in-lap | Into the pits | The lap that ends in the pit lane. |
| out-lap | Out of the pits | The lap that starts from the pit lane. |
| re-timed | Re-timed | Lap times worked out again by the server from the GPS trace, against the course as drawn now. |
| tablet only | Tablet only | Recorded by the tablet without the car's own readings (no OBD link), so GPS and motion only. |
| theoretical best | Theoretical best | The best time for each sector, added up: the lap that was possible if every sector went as well as it ever did. |
| median | Median | The middle lap time: half the laps were faster, half slower. |
| spread | Spread | How much the lap times vary (the standard deviation); smaller means more consistent. |
| within 1% | Within 1% | How many laps were within 1% of the driver's best. |

Laps in the pit lane (in-laps and out-laps) don't count toward the median,
spread, within 1% or the theoretical best.
