/**
 * The race (M15.4): the types `GET /api/events/{id}` gives its `race` in, and
 * the stints editor's logic. Laps, stops and stints are on each tablet's
 * clock; a car's `tabletOffset` turns one into the time of day. Pure.
 */
import type { PublicPart } from './eventResults'

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
  stintsEdited?: boolean
}

export interface RaceResults {
  part: PublicPart
  cars: CarRace[]
  green?: number | null
  flag?: number | null
  tabletOffset: Record<string, number>
}

/** A stint as the editor holds it: from [start] on the tablet's clock. */
export interface StintMark {
  start: number
  driver: string | null
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

/** The stints as they stand, to edit. */
export function marksOf(car: CarRace): StintMark[] {
  return car.stints.map((s) => ({ start: s.start, driver: s.driver ?? null }))
}

/** Stint [i] merged into the one before it (a stop without a driver change). The first can't be. */
export function mergeWithPrevious(marks: readonly StintMark[], i: number): StintMark[] {
  return i <= 0 || i >= marks.length ? [...marks] : marks.filter((_, j) => j !== i)
}

/** A new stint from lap [lap] (a change without a stop), its driver not yet named; nothing if one starts there already. */
export function splitAt(marks: readonly StintMark[], laps: readonly RaceLap[], lap: number): StintMark[] {
  const at = laps.find((l) => l.number === lap)
  if (!at || marks.some((m) => m.start === at.start) || lap === laps[0]?.number) return [...marks]
  return [...marks, { start: at.start, driver: null }].sort((a, b) => a.start - b.start)
}

export function setDriver(marks: readonly StintMark[], i: number, driver: string | null): StintMark[] {
  return marks.map((m, j) => (j === i ? { ...m, driver } : m))
}

/** The path for an edit to the race, the admin's or the car's crew's (each sign-in's cookie reaches only its own). */
export function racePath(who: 'admin' | 'crew', eventId: string, car: string, what: 'flags' | 'stints'): string {
  if (who === 'admin') return what === 'flags' ? `/api/admin/events/${eventId}/race` : `/api/admin/events/${eventId}/race/stints/${car}`
  return what === 'flags' ? `/api/cars/${car}/events/${eventId}/race` : `/api/cars/${car}/events/${eventId}/race/stints`
}

/** Sends an edit; the server's words if it's refused, every problem included. */
export async function sendRaceEdit(path: string, body: unknown, fetcher: typeof fetch = fetch): Promise<number> {
  const r = await fetcher(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
  const answer = (await r.json().catch(() => ({}))) as { revision?: number; message?: string; problems?: string[] }
  if (!r.ok) throw new Error([answer.message || `The server answered ${r.status}.`, ...(answer.problems ?? [])].join(' '))
  return answer.revision ?? 0
}
