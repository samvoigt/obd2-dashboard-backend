/**
 * The landing page in summary (M21.6): every page it links to, in short, from
 * the same public endpoints those pages read, in their words.
 */
import type { CourseSummary } from './admin'
import type { PublicEvent } from './eventResults'
import { clockOf, dayOf, duration, lapTime, type Drive, type SessionItem } from './sessions'

/** Events shown before "All events →". */
export const EVENTS_SHOWN = 5

/** A car's latest session, whichever drive it's in; null before its first. */
export function latestSession(drives: readonly Drive[] | null): SessionItem | null {
  let latest: SessionItem | null = null
  for (const d of drives ?? []) for (const s of d.sessions) if (!latest || s.started > latest.started) latest = s
  return latest
}

/** "Sat, 26 Sept 2026 · 14:05 · 23 min · 12 laps · best 1:34.532", as the sessions page says them. */
export function sessionLine(s: SessionItem): string {
  const parts = [dayOf(s.started), clockOf(s.started), duration(s.ended - s.started)]
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
