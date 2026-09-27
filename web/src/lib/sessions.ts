/**
 * Past sessions on the site (M7.4): the list's data and its words. Pure, so it
 * is tested without a browser. The server groups drives (M7.3).
 */

export type SessionState = 'live' | 'uploading' | 'complete' | 'incomplete'

export interface LapView {
  lap: number
  time: number
}

export interface SessionItem {
  id: string
  /** Epoch milliseconds. */
  started: number
  ended: number
  lines: number
  state: SessionState
  track: string | null
  layout: string | null
  laps: number
  bestLap: LapView | null
  faults: string[]
  /** `tablet` (no car read), `fake` (test data), or absent for a car's session (M11). */
  source?: string | null
}

/**
 * What a session is, when it isn't a car's drive (§20, §21): "Tablet only" for
 * the tablet's own signals, "Test data" for invented readings, and any other
 * source as sent, never guessed at (M11). Null for a car's session.
 */
export function sourceLabel(source: unknown): { text: string; kind: 'tablet' | 'test' | 'other' } | null {
  if (typeof source !== 'string' || source === '') return null
  if (source === 'tablet') return { text: 'Tablet only', kind: 'tablet' }
  if (source === 'fake') return { text: 'Test data', kind: 'test' }
  return { text: source, kind: 'other' }
}

export interface Drive {
  started: number
  ended: number
  sessions: SessionItem[]
}

export async function fetchDrives(slug: string, fetcher: typeof fetch = fetch): Promise<Drive[] | null> {
  const response = await fetcher(`/api/cars/${slug}/sessions`)
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`The server answered ${response.status}.`)
  return (await response.json()) as Drive[]
}

/** "45 s", "23 min", "1 h 05 min". */
export function duration(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000))
  if (s < 60) return `${s} s`
  const min = Math.floor(s / 60)
  if (min < 60) return `${min} min`
  return `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')} min`
}

/** A lap time as racers write it: "1:34.532", or "58.120" under a minute. */
export function lapTime(seconds: number): string {
  const ms = Math.round(seconds * 1000)
  const min = Math.floor(ms / 60_000)
  const rest = ((ms % 60_000) / 1000).toFixed(3)
  return min > 0 ? `${min}:${rest.padStart(6, '0')}` : rest
}

/** "Sat, 26 Sept 2026", in the viewer's time zone. */
export function dayOf(ms: number, locale = 'en-GB', timeZone?: string): string {
  return new Date(ms).toLocaleDateString(locale, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', timeZone })
}

/** "14:05". */
export function clockOf(ms: number, locale = 'en-GB', timeZone?: string): string {
  return new Date(ms).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', hour12: false, timeZone })
}

/** A badge for a session that isn't simply finished; null when it is. */
export function badge(state: SessionState): { text: string; kind: 'live' | 'stale' | 'offline' } | null {
  switch (state) {
    case 'live': return { text: 'Live now', kind: 'live' }
    case 'uploading': return { text: 'Uploading', kind: 'stale' }
    case 'incomplete': return { text: 'Upload stopped', kind: 'offline' }
    case 'complete': return null
  }
}

/** The track as a line: "nhms · Road Course", or null without laps. */
export function trackOf(s: SessionItem): string | null {
  if (!s.track) return null
  return s.layout ? `${s.track} · ${s.layout}` : s.track
}
