/**
 * An event's results as the public page shows them (M14.5): the types
 * `GET /api/events/{id}` speaks, and a few words for the page. Pure.
 */
import type { Driver } from './events'
import type { SessionLaps } from './sessionPage'

export type StandingLap = SessionLaps['laps'][number]

export interface PublicPart {
  id: string
  kind: 'practice' | 'race'
  name: string
  start: number
  end: number
}

export interface PublicEvent {
  id: string
  name: string
  date: string
  course: string
  courseName: string
  layout: string
  layoutName: string
  cars: { slug: string; name: string }[]
  parts: PublicPart[]
}

export interface SessionResult {
  id: string
  car: string
  driver?: Driver | null
  heardFrom: number
  laps: StandingLap[]
  /** Its laps were timed on another layout: shown, never counted. */
  otherLayout?: string | null
  best?: StandingLap | null
}

export interface DriverBest {
  driver?: Driver | null
  car: string
  session: string
  lap: StandingLap
}

export interface PartResults {
  part: PublicPart
  sessions: SessionResult[]
  bests: DriverBest[]
  bestSectors: (number | null)[]
}

export interface EventResults {
  event: PublicEvent
  parts: PartResults[]
  practiceBests: DriverBest[]
  practiceBestSectors: (number | null)[]
}

/** "+0.900" behind the fastest; blank for the fastest itself. */
export function gap(time: number, fastest: number): string {
  const d = Math.round((time - fastest) * 1000) / 1000
  return d <= 0 ? '' : `+${d.toFixed(3)}`
}

/** A driver as a table shows them; a session nobody named is "Driver not set". */
export function driverLabel(d: Driver | null | undefined): string {
  return d ? `${d.name} (${d.code})` : 'Driver not set'
}

/** "4 Oct, 09:00–10:00", a part's window in the viewer's time zone. */
export function windowText(p: PublicPart, locale = 'en-GB', timeZone?: string): string {
  const time = (ms: number) => new Date(ms).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', hour12: false, timeZone })
  const day = new Date(p.start).toLocaleDateString(locale, { day: 'numeric', month: 'short', timeZone })
  return `${day}, ${time(p.start)}–${time(p.end)}`
}
