/**
 * The race (M15.4): the types `GET /api/events/{id}` gives its `race` in, and
 * the stints editor's logic. Laps, stops and stints are on each tablet's
 * clock; a car's `tabletOffset` turns one into the time of day. Pure.
 */
import type { Consistency, PublicPart } from './eventResults'

export interface RaceLap {
  number: number
  session: string
  start: number
  end: number
  time: number
  sectors: number[]
  pitIn: boolean
  pitOut: boolean
  /** `tablet`, `retimed`, or `restart` (the gap across a restart of the app). */
  source: 'tablet' | 'retimed' | 'restart'
  stint: number
  /** In a session still being driven or uploaded (M17.6). */
  live?: boolean
}

export interface RaceStop {
  lap: number
  entry: number
  exit?: number | null
  seconds?: number | null
}

export interface RaceStint {
  number: number
  driver?: string | null
  start: number
  firstLap?: number | null
  lastLap?: number | null
  laps: number
  seconds: number
  best?: number | null
  consistency?: Consistency | null
}

export interface CarRace {
  car: string
  laps: RaceLap[]
  stops: RaceStop[]
  stints: RaceStint[]
  seconds: number
  best?: RaceLap | null
  greenLap?: number | null
  flagLap?: number | null
  bestSectors?: (number | null)[]
  theoretical?: number | null
  /** Each session of the race (M26): where it began in the laps, its laps and time, and its flags' laps. */
  sessions?: RaceSessionSpan[]
}

/** One session of a car's race (M26): none of its laps (`firstLap` null) if the car didn't run in it. */
export interface RaceSessionSpan {
  part: string
  firstLap?: number | null
  laps: number
  seconds: number
  greenLap?: number | null
  flagLap?: number | null
}

/** A session of the race (M26), a race part: its flags as entered. */
export interface RaceSessionResults {
  part: PublicPart
  green?: number | null
  flag?: number | null
}

export interface RaceResults {
  /** One for a race run in one; Saturday's and Sunday's, say, for one in sessions (M26). */
  sessions: RaceSessionResults[]
  cars: CarRace[]
  tabletOffset: Record<string, number>
}

/** The laps of [car]'s race in session [part] (M26); all of them for a race in one. */
export function lapsIn(car: CarRace, part: string): RaceLap[] {
  const span = car.sessions?.find((s) => s.part === part)
  if (!span || car.sessions!.length < 2) return car.laps
  if (span.firstLap == null) return []
  return car.laps.filter((l) => l.number >= span.firstLap! && l.number < span.firstLap! + span.laps)
}

/** The stints of [car]'s race in session [part] (M26): a stint never crosses one. */
export function stintsIn(car: CarRace, part: string): RaceStint[] {
  const laps = new Set(lapsIn(car, part).map((l) => l.number))
  return car.stints.filter((s) => s.firstLap != null && laps.has(s.firstLap))
}

/** The session a lap is the first of, for marking where each began (M26); null for any other lap, or a race in one. */
export function sessionStartingAt(car: CarRace, lap: number): string | null {
  if (!car.sessions || car.sessions.length < 2) return null
  return car.sessions.find((s) => s.firstLap === lap)?.part ?? null
}

/** Whether lap [lap] is where a session's green flag, or its flag, fell (M26: any session's). */
export function flagsAt(car: CarRace, lap: number): { green: boolean; flag: boolean } {
  const spans = car.sessions?.length ? car.sessions : [{ greenLap: car.greenLap, flagLap: car.flagLap }]
  return { green: spans.some((s) => s.greenLap === lap), flag: spans.some((s) => s.flagLap === lap) }
}

/** "6:02:15", a race's length. */
export function raceClock(seconds: number): string {
  const s = Math.round(seconds)
  const two = (n: number) => String(n).padStart(2, '0')
  return `${Math.floor(s / 3600)}:${two(Math.floor((s % 3600) / 60))}:${two(s % 60)}`
}

/** A moment on a car's tablet clock as the time of day, in the viewer's zone; blank without its offset. */
export function timeOfDay(tabletMs: number, offset: number | undefined, locale = 'en-GB', timeZone?: string): string {
  if (offset === undefined) return ''
  return new Date(tabletMs + offset).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone })
}

/** The path for an edit to the race's flags, the admin's or the car's crew's (each sign-in's cookie reaches only its own). */
export function racePath(who: 'admin' | 'crew', eventId: string, car: string): string {
  return who === 'admin' ? `/api/admin/events/${eventId}/race` : `/api/cars/${car}/events/${eventId}/race`
}

/** Sends an edit; the server's words if it's refused, every problem included. */
export async function sendRaceEdit(path: string, body: unknown, fetcher: typeof fetch = fetch): Promise<number> {
  const r = await fetcher(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  const answer = (await r.json().catch(() => ({}))) as { revision?: number; message?: string; problems?: string[] }
  if (!r.ok) throw new Error([answer.message || `The server answered ${r.status}.`, ...(answer.problems ?? [])].join(' '))
  return answer.revision ?? 0
}
