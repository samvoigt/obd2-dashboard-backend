/**
 * The landing page in summary (M21.6): every page it links to, in short, from
 * the same public endpoints those pages read, in their words.
 */
import type { CarSummary } from './api'
import type { CourseSummary } from './admin'
import type { PublicEvent } from './eventResults'
import { duration, lapTime, type Drive, type SessionItem } from './sessions'
import { dayTime } from './terms'

/** Events shown before "All events →". */
export const EVENTS_SHOWN = 5

/** A car's latest session, whichever drive it's in; null before its first. */
export function latestSession(drives: readonly Drive[] | null): SessionItem | null {
  let latest: SessionItem | null = null
  for (const d of drives ?? []) for (const s of d.sessions) if (!latest || s.started > latest.started) latest = s
  return latest
}

/** "Sat 26 Sep, 14:05 · 23 min · 12 laps · best 1:34.532", as the sessions page says them. */
export function sessionLine(s: SessionItem, now: number = Date.now(), timeZone?: string): string {
  const parts = [dayTime(s.started, now, timeZone), duration(s.ended - s.started)]
  if (s.laps > 0) parts.push(`${s.laps} lap${s.laps === 1 ? '' : 's'}`)
  if (s.bestLap) parts.push(`best ${lapTime(s.bestLap.time)}`)
  return parts.join(' · ')
}

/** Whether one of the event's parts is going on at [now]. */
export function underWay(event: PublicEvent, now: number): boolean {
  return event.parts.some((p) => now >= p.start && now < p.end)
}

/** The events the landing page shows: the newest few, the server's order kept (newest first). */
export function eventsShown(events: readonly PublicEvent[]): PublicEvent[] {
  return events.slice(0, EVENTS_SHOWN)
}

/** "Road Course, 3 sectors · Short": a course's layouts, as the courses page lists them. */
export function layoutsLine(c: CourseSummary): string {
  return c.layouts.map((l) => `${l.name}${l.sectors > 0 ? `, ${l.sectors} sector${l.sectors === 1 ? '' : 's'}` : ''}`).join(' · ')
}

/** The landing's "Now" card (M36.2): what's happening, else what happened last. */
export type NowCard =
  | { kind: 'live'; cars: CarSummary[] }
  | { kind: 'event'; event: PublicEvent }
  | { kind: 'last'; car: CarSummary; session: SessionItem }

/**
 * **The "Now" card's choice** (M36.2), pure: every car streaming (behind
 * counts), in the list's order; else the first event under way; else the
 * latest session over all cars; else nothing.
 */
export function nowCard(
  cars: readonly CarSummary[], latest: Readonly<Record<string, SessionItem | null | undefined>>, events: readonly PublicEvent[] | null, now: number,
): NowCard | null {
  const live = cars.filter((c) => c.state === 'live' || c.state === 'stale')
  if (live.length > 0) return { kind: 'live', cars: live }
  const event = (events ?? []).find((e) => underWay(e, now))
  if (event) return { kind: 'event', event }
  let last: { car: CarSummary; session: SessionItem } | null = null
  for (const car of cars) {
    const s = latest[car.slug]
    if (s && (!last || s.started > last.session.started)) last = { car, session: s }
  }
  return last ? { kind: 'last', ...last } : null
}

/** "last seen Sun 18 Oct, 14:14" (M36.7): when the car's latest session ended, for an offline car's banner. */
export function lastSeenText(s: Pick<SessionItem, 'ended'>, now: number = Date.now(), timeZone?: string): string {
  return `last seen ${dayTime(s.ended, now, timeZone)}`
}
