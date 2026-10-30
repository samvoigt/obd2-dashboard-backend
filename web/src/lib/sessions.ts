/**
 * Past sessions on the site (M7.4): the list's data and its words. Pure, so it
 * is tested without a browser. The server groups drives (M7.3).
 */

import { clockText, dayText } from './terms'
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
  /** The event and part it's in (M14.5). */
  event?: { id: string; name: string; part: string } | null
  /** What the admin or the crew called it (M18.3). */
  name?: string | null
  /** A merged session's members, in order (M33.5); absent or empty for a session. */
  members?: string[]
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
  /** Runs of its sessions that look like one drive, offered for merging (M33.5): ids in time order. */
  suggested?: string[][]
}

/** What ticking sessions on the list offers (M33.8). */
export type MergeBar =
  | { kind: 'none' }
  | { kind: 'merge'; members: string[]; label: string }
  | { kind: 'extend'; into: string; add: string[]; label: string }
  | { kind: 'refused'; message: string }

/**
 * **The Merge bar** (M33.8), pure: under two ticked, nothing; plain sessions,
 * merge them; one merged session and plain ones, add them to it; two merged
 * sessions, refused. The server checks the rest (parts, overlaps, uploads).
 */
export function mergeBar(selected: ReadonlySet<string>, items: readonly SessionItem[]): MergeBar {
  const picked = items.filter((s) => selected.has(s.id))
  if (picked.length < 2) return { kind: 'none' }
  const merged = picked.filter((s) => (s.members ?? []).length > 0)
  if (merged.length > 1) return { kind: 'refused', message: 'Two merged sessions can\'t be merged: unmerge one first.' }
  const plain = picked.filter((s) => (s.members ?? []).length === 0)
  const into = merged[0]
  if (into) return { kind: 'extend', into: into.id, add: plain.map((s) => s.id), label: `Add ${plain.length} to the merged session` }
  return { kind: 'merge', members: plain.map((s) => s.id), label: `Merge ${plain.length} sessions` }
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

/** "Sat 26 Sep", in the viewer's time zone, the year only when it isn't this one (M36.9's one format). */
export function dayOf(ms: number, timeZone?: string, now: number = Date.now()): string {
  return dayText(ms, now, timeZone)
}

/** "14:05". */
export function clockOf(ms: number, timeZone?: string): string {
  return clockText(ms, timeZone)
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
