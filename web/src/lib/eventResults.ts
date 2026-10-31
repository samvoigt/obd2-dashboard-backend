/**
 * An event's results as the public page shows them (M14.5): the types
 * `GET /api/events/{id}` speaks, and a few words for the page. Pure.
 */
import type { Driver } from './events'
import type { SessionLaps } from './sessionPage'
import type { RaceResults } from './race'
import { clockText, dateRange, dayText } from './terms'

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
  /** What an edit to the race names (M15.4). */
  revision: number
  /** Said only to someone signed in (M25). */
  hidden?: boolean
  /** Its last day (M26), for an event over several. */
  endDate?: string | null
}

/** "Fri 2 – Sun 4 Oct": an event's days (M26), in the one format (M36.9); one day for one. */
export function dateText(e: Pick<PublicEvent, 'date' | 'endDate'>, now: number = Date.now()): string {
  return dateRange(e.date, e.endDate, now)
}

/** "NHMS, Road Course": where it is; nothing for a course hidden from you (M25), which isn't named. */
export function whereText(e: Pick<PublicEvent, 'course' | 'courseName' | 'layoutName'>): string {
  return e.course ? `${e.courseName}, ${e.layoutName}` : ''
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
  /** Still being driven or uploaded: its laps are provisional (M17.6). */
  live?: boolean
  /** What the admin or the crew called it (M18.3). */
  name?: string | null
  /** Which of its drivers' stretches this row is (M27): a session whose driver changed is a row per driver. */
  stretch?: number
}

export interface DriverBest {
  driver?: Driver | null
  car: string
  session: string
  lap: StandingLap
}

/** How consistent a run of laps was (M16.2): over laps on track only, seconds. */
export interface Consistency {
  laps: number
  best: number
  median: number
  spread: number
  withinOnePercent: number
}

export interface SectorRow {
  driver?: Driver | null
  best?: number | null
  sectors: (number | null)[]
  gaps: (number | null)[]
  theoretical?: number | null
}

export interface DriverConsistency {
  driver?: Driver | null
  consistency: Consistency
}

export interface PartResults {
  part: PublicPart
  sessions: SessionResult[]
  bests: DriverBest[]
  bestSectors: (number | null)[]
  theoretical?: number | null
  sectorRows: SectorRow[]
  consistency: DriverConsistency[]
}

export interface EventResults {
  event: PublicEvent
  parts: PartResults[]
  practiceBests: DriverBest[]
  practiceBestSectors: (number | null)[]
  /** The race as one timeline (M15.4). */
  race?: RaceResults | null
  practiceTheoretical?: number | null
  practiceSectorRows: SectorRow[]
  practiceConsistency: DriverConsistency[]
  /** Each merged session's members (M33.6): member id → the merged session's id, whose row and page stand for it. */
  merged?: Record<string, string>
}

/** One session as the Sessions panel lists it (M37.1): its drivers' stretches folded into one row. */
export interface SessionRow {
  id: string
  car: string
  name?: string | null
  heardFrom: number
  live: boolean
  otherLayout?: string | null
  /** Any of its laps re-timed by the server. */
  retimed: boolean
  laps: number
  best?: StandingLap | null
  stretches: { driver?: Driver | null; laps: number; best?: StandingLap | null }[]
}

/**
 * The server's rows (one per driver's stretch, M27; a merged session's under
 * the merge's id, M33.6) as one row per session, in the order first heard.
 */
export function sessionRows(rows: readonly SessionResult[]): SessionRow[] {
  const byId = new Map<string, SessionRow>()
  for (const r of rows) {
    let row = byId.get(r.id)
    if (!row) {
      row = { id: r.id, car: r.car, name: r.name, heardFrom: r.heardFrom, live: false, otherLayout: r.otherLayout, retimed: false, laps: 0, best: null, stretches: [] }
      byId.set(r.id, row)
    }
    row.live ||= r.live === true
    row.retimed ||= r.laps.some((l) => l.source === 'retimed')
    row.laps += r.laps.length
    if (r.best && (!row.best || r.best.time < row.best.time)) row.best = r.best
    row.stretches.push({ driver: r.driver, laps: r.laps.length, best: r.best })
  }
  return [...byId.values()].sort((a, b) => a.heardFrom - b.heardFrom)
}

/** "+0.123" behind the best of all; "best" for the best itself; blank for none. */
export function sectorGap(gap: number | null | undefined): string {
  if (gap === null || gap === undefined) return ''
  return gap <= 0 ? 'best' : `+${gap.toFixed(3)}`
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

/** "Sun 4 Oct, 09:00–10:00", a part's window in the viewer's time zone. */
export function windowText(p: PublicPart, timeZone?: string, now: number = Date.now()): string {
  return `${dayText(p.start, now, timeZone)}, ${clockText(p.start, timeZone)}–${clockText(p.end, timeZone)}`
}

/** `GET /api/drivers/{id}` (M15.5). */
export interface DriverRecord {
  driver: Driver
  events: {
    event: PublicEvent
    practice: { part: string; best: DriverBest }[]
    stints: { car: string; stint: { number: number; firstLap?: number | null; lastLap?: number | null; laps: number; seconds: number; best?: number | null } }[]
  }[]
  courses: { course: string; courseName: string; event: string; best: DriverBest }[]
}

/** How often the event page asks again while a part is on (M17.6). */
export const REFRESH_MS = 30_000

/** Whether any of [parts] is on at [now] (epoch ms): the page then refreshes itself. */
export function partOn(parts: { start: number; end: number }[], now: number): boolean {
  return parts.some((p) => p.start <= now && now < p.end)
}
