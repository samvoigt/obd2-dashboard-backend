/**
 * **One word for each thing, one date format** (M36.9): the words the site
 * shows for its terms (`docs/GLOSSARY.md` says what each means), and the one
 * way it writes a day and a time, "Sun 18 Oct, 14:14". Built here rather than
 * by the locale, whose en-GB says "Sept" and puts commas where it likes. Pure.
 */

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

interface Day { year: number; month: number; day: number; weekday: number; hour: number; minute: number }

/** [ms] as its calendar day and time in [timeZone] (the viewer's when absent). */
function partsOf(ms: number, timeZone?: string): Day {
  const parts = new Intl.DateTimeFormat('en-GB', {
    year: 'numeric', month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric', hourCycle: 'h23', timeZone,
  }).formatToParts(new Date(ms))
  const n = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0)
  const year = n('year'), month = n('month'), day = n('day')
  return { year, month, day, weekday: new Date(Date.UTC(year, month - 1, day)).getUTCDay(), hour: n('hour') % 24, minute: n('minute') }
}

const two = (n: number) => String(n).padStart(2, '0')
const dayWords = (d: Day, year: boolean) => `${WEEKDAYS[d.weekday]} ${d.day} ${MONTHS[d.month - 1]}${year ? ` ${d.year}` : ''}`

/** "Sun 18 Oct", with the year only when it isn't [now]'s. */
export function dayText(ms: number, now: number = Date.now(), timeZone?: string): string {
  const d = partsOf(ms, timeZone)
  return dayWords(d, d.year !== partsOf(now, timeZone).year)
}

/** "14:14", a time of day. */
export function clockText(ms: number, timeZone?: string): string {
  const d = partsOf(ms, timeZone)
  return `${two(d.hour)}:${two(d.minute)}`
}

/** "Sun 18 Oct, 14:14": **the** date format (M36.9). */
export function dayTime(ms: number, now: number = Date.now(), timeZone?: string): string {
  return `${dayText(ms, now, timeZone)}, ${clockText(ms, timeZone)}`
}

/**
 * "Fri 16 – Sun 18 Oct": the days from [a] to [b], the month (and year) said
 * once when they share it; one day when they're the same.
 */
export function dayRange(a: number, b: number, now: number = Date.now(), timeZone?: string): string {
  const x = partsOf(a, timeZone), y = partsOf(b, timeZone)
  const year = y.year !== partsOf(now, timeZone).year || x.year !== y.year
  if (x.year === y.year && x.month === y.month && x.day === y.day) return dayWords(x, year)
  if (x.year === y.year && x.month === y.month) return `${WEEKDAYS[x.weekday]} ${x.day} – ${dayWords(y, year)}`
  return `${dayWords(x, x.year !== y.year)} – ${dayWords(y, year)}`
}

/** An event's days ("2026-10-16", its last "2026-10-18") as [dayRange] says them: calendar days, no time zone. */
export function dateRange(date: string, endDate?: string | null, now: number = Date.now()): string {
  const ms = (d: string) => Date.parse(`${d}T12:00:00Z`)
  if (Number.isNaN(ms(date))) return date
  const end = endDate && !Number.isNaN(ms(endDate)) ? endDate : date
  return dayRange(ms(date), ms(end), now, 'UTC')
}

/** A term the site shows (`docs/GLOSSARY.md`): its words, and its meaning in a sentence. */
export interface Term {
  word: string
  means: string
}

/** The site's terms (M36.9), kept with `docs/GLOSSARY.md`; the session and event pages explain each on tap. */
export const TERMS = {
  session: { word: 'Session', means: "One recording by the car's tablet, from when it starts recording to when it stops." },
  merged: { word: 'Merged session', means: 'Sessions joined into one because they were one drive, split by an app restart or a lost connection.' },
  part: { word: 'Part', means: "A stretch of an event's schedule, such as practice or the race, with its own start and end." },
  stint: { word: 'Stint', means: "A driver's unbroken time in the car during the race, from one driver change to the next." },
  stop: { word: 'Stop', means: 'The time spent in the pit lane, from the pit-in line to the pit-out line.' },
  inLap: { word: 'Into the pits', means: 'The lap that ends in the pit lane.' },
  outLap: { word: 'Out of the pits', means: 'The lap that starts from the pit lane.' },
  retimed: { word: 'Re-timed', means: 'Lap times worked out again by the server from the GPS trace, against the course as drawn now.' },
  tabletOnly: { word: 'Tablet only', means: "Recorded by the tablet without the car's own readings (no OBD link), so GPS and motion only." },
  theoretical: { word: 'Theoretical best', means: 'The best time for each sector, added up: the lap that was possible if every sector went as well as it ever did.' },
  median: { word: 'Median', means: 'The middle lap time: half the laps were faster, half slower. Laps in the pit lane don’t count.' },
  spread: { word: 'Spread', means: 'How much the lap times vary (the standard deviation); smaller means more consistent. Laps in the pit lane don’t count.' },
  withinOne: { word: 'Within 1%', means: "How many laps were within 1% of the driver's best. Laps in the pit lane don’t count." },
} as const satisfies Record<string, Term>

export type TermKey = keyof typeof TERMS
