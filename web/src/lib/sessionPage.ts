/**
 * A session's page (M7.5): the prepared series (M7.2) as the charts, lap
 * table, map and events want it. Pure, so it is tested without a browser.
 * Times in the file are milliseconds after `t0`; here they become absolute.
 */

import { lapTime } from './sessions'

export interface SeriesSignal {
  name: string
  unit: string
  kind: string
}

export interface LapRecord {
  track?: string
  /** The layout's `id` since courses came from the website (§22.4), its name before. */
  layout?: string
  /** The course and version the tablet timed it on (§22.4); absent before. */
  course?: string
  courseVersion?: number
  lap: number
  time: number
  /** Sector times, seconds, in order (§22.4); absent when not timed. */
  sectors?: number[]
  pitIn?: boolean
  pitOut?: boolean
}

export interface Series {
  version: number
  t0: number
  /** The last `seq` the file covers (M7.6), for merging with the live lane; null if none. */
  lastSeq?: number | null
  signals: SeriesSignal[]
  numbers: Record<string, { t: number[]; v: (number | null)[] }>
  states: Record<string, [number, number | null, string | null][]>
  sets: Record<string, [number, string[]][]>
  positions: { t: number[]; lat: number[]; lon: number[] }
  events: {
    stopped: [number, string, string][]
    fault: [number, string[]][]
    gap: [number, number][]
    lap: [number, LapRecord][]
  }
}

export interface LapRow {
  lap: number
  /** Seconds. */
  time: number
  pitIn: boolean
  pitOut: boolean
  /** Epoch milliseconds: when it began (its end less its time) and ended (when it was published). */
  start: number
  end: number
  best: boolean
  /** Its sector times, seconds; null when the lap has none (older records, or a course without sectors). */
  sectors?: number[] | null
  /** Re-timed by the server on this course version (M13.5); absent for the tablet's own. */
  retimedOn?: number
  /** Where re-timing disagrees with the tablet's lap (M13.5): what it found there, or `{}` for no lap. */
  flag?: { time?: number | null; start?: number | null; end?: number | null }
}

/** `GET /api/sessions/{id}/laps` (M13.5): the laps as they stand, on the wall clock. */
export interface SessionLaps {
  course: string
  courseName: string
  courseVersion: number
  layout: string
  laps: {
    lap: number
    time: number
    sectors: number[]
    pitIn: boolean
    pitOut: boolean
    start: number
    end: number
    source: 'tablet' | 'retimed'
    checked?: boolean
    flag?: { time?: number | null; start?: number | null; end?: number | null } | null
  }[]
}

/** The rows of [laps], the best by the same rule as [lapRows]. */
export function standingRows(laps: SessionLaps): LapRow[] {
  const rows: LapRow[] = laps.laps.map((l) => ({
    lap: l.lap,
    time: l.time,
    pitIn: l.pitIn,
    pitOut: l.pitOut,
    start: l.start,
    end: l.end,
    best: false,
    sectors: l.sectors.length > 0 ? l.sectors : null,
    ...(l.source === 'retimed' ? { retimedOn: laps.courseVersion } : {}),
    ...(l.flag ? { flag: l.flag } : {}),
  }))
  markBest(rows)
  return rows
}

/** What a lap's last column says of where its time came from (M13.5). */
export function lapNote(row: LapRow): string | null {
  if (row.flag) return row.flag.time != null ? `Re-timing found ${lapTime(row.flag.time)}` : 'Re-timing found no such lap'
  if (row.retimedOn !== undefined) return `Re-timed on version ${row.retimedOn}`
  return null
}

/** `GET /api/sessions/{id}/laps`, or null where the series' laps stand (still uploading, or at no course). */
export async function fetchLaps(id: string, fetcher: typeof fetch = fetch): Promise<SessionLaps | null> {
  const response = await fetcher(`/api/sessions/${id}/laps`)
  if (response.status === 204 || response.status === 404) return null
  if (!response.ok) throw new Error(`The server answered ${response.status}.`)
  return (await response.json()) as SessionLaps
}

/** Laps in order; the best is the fastest on track, never a pit lap (§18). */
export function lapRows(series: Series): LapRow[] {
  const rows = series.events.lap
    .map(([t, r]) => {
      const end = series.t0 + t
      const sectors = Array.isArray(r.sectors) && r.sectors.every((x) => typeof x === 'number') ? r.sectors : null
      return { lap: r.lap, time: r.time, pitIn: r.pitIn === true, pitOut: r.pitOut === true, start: end - r.time * 1000, end, best: false, sectors }
    })
    .sort((a, b) => a.lap - b.lap)
  markBest(rows)
  return rows
}

/** The fastest lap on track is the best, never a pit lap (§18). */
function markBest(rows: LapRow[]) {
  const onTrack = rows.filter((r) => !r.pitIn && !r.pitOut)
  if (onTrack.length > 0) {
    const fastest = onTrack.reduce((a, b) => (b.time < a.time ? b : a))
    fastest.best = true
  }
}

/**
 * The best time for each sector (M12.7), null for a sector no lap has. As many
 * as the most any lap has. **An in-lap's last sector** (it ends at the pit line)
 * **and an out-lap's first** (it begins there) **never count**; their other
 * sectors are ordinary ones (contract §22.6).
 */
export function bestSectors(rows: LapRow[]): (number | null)[] {
  const n = Math.max(0, ...rows.map((r) => r.sectors?.length ?? 0))
  return Array.from({ length: n }, (_, i) => {
    const times = rows
      .filter((r) => countsForBest(r, i))
      .map((r) => r.sectors?.[i])
      .filter((x): x is number => typeof x === 'number')
    return times.length > 0 ? Math.min(...times) : null
  })
}

/** Whether sector [i] of [row] is one that could be a best (§22.6): not an in-lap's last, nor an out-lap's first. */
export function countsForBest(row: LapRow, i: number): boolean {
  return !(row.pitIn && i === (row.sectors?.length ?? 0) - 1) && !(row.pitOut && i === 0)
}

/** The signals to chart first: engine speed and road speed, as the live page does; else the first two. */
export function defaultSignals(series: Series): string[] {
  const names = Object.keys(series.numbers)
  const preferred = ['engine.rpm', 'vehicle.speed'].filter((n) => names.includes(n))
  return preferred.length > 0 ? preferred : names.slice(0, 2)
}

export function unitOf(series: Series, name: string): string {
  return series.signals.find((s) => s.name === name)?.unit ?? ''
}

/**
 * The chosen signals on one time axis, in seconds, for uPlot. A signal's own
 * `null` (a gap) stays `null`, which breaks its line; where it simply has no
 * sample at another's time, the value is `undefined`, which uPlot bridges.
 */
export function joined(series: Series, names: string[]): [number[], ...(number | null | undefined)[][]] {
  const columns = names.map((n) => series.numbers[n] ?? { t: [], v: [] })
  const times: number[] = []
  const at = columns.map(() => 0)
  const out: (number | null | undefined)[][] = columns.map(() => [])
  for (;;) {
    let next = Infinity
    columns.forEach((c, i) => { if (at[i]! < c.t.length) next = Math.min(next, c.t[at[i]!]!) })
    if (next === Infinity) break
    times.push((series.t0 + next) / 1000)
    columns.forEach((c, i) => {
      if (at[i]! < c.t.length && c.t[at[i]!] === next) {
        out[i]!.push(c.v[at[i]!])
        at[i]!++
      } else {
        out[i]!.push(undefined)
      }
    })
  }
  return [times, ...out]
}

export interface Marker {
  /** Seconds, as the chart's axis. */
  t: number
  kind: 'fault' | 'gap' | 'stopped' | 'lap' | 'message'
  text: string
}

/** A crew message sent while the session ran (M18.4), as `GET /api/sessions/{id}/messages` has it; times on the server's clock. */
export interface SessionMessage {
  text: string
  preset?: string | null
  state: string
  sentAt: number
  receivedAt?: number | null
  displayedAt?: number | null
  endedAt?: number | null
  /** When it was sent on the tablet's `wall`, the chart's clock; null if the session's offset isn't known. */
  at?: number | null
}

const later = (from: number, to: number | null | undefined) => (to == null ? null : `${((to - from) / 1000).toFixed(1)} s later`)

/**
 * Crew messages as the chart's marks and "What happened" (M18.4): each where
 * it was sent, saying whether and how soon the tablet received and showed it.
 * A message the session's clock can't place is left out.
 */
export function messageMarkers(messages: readonly SessionMessage[]): Marker[] {
  return messages.flatMap((m): Marker[] => {
    if (m.at == null) return []
    const received = later(m.sentAt, m.receivedAt)
    const shown = later(m.sentAt, m.displayedAt)
    const fate = received === null ? 'never received' : shown === null ? `received ${received}, not shown` : `received ${received}, shown ${shown}`
    return [{ t: m.at / 1000, kind: 'message', text: `Crew message “${m.text}”: ${fate}` }]
  })
}

/** What happened, in time order: faults, gaps, signals that stopped, and each lap's end. */
export function events(series: Series): Marker[] {
  const s = (t: number) => (series.t0 + t) / 1000
  return [
    ...series.events.fault.map(([t, codes]): Marker => ({ t: s(t), kind: 'fault', text: codes.length > 0 ? `Trouble codes: ${codes.join(', ')}` : 'Trouble codes cleared' })),
    ...series.events.gap.map(([t, missed]): Marker => ({ t: s(t), kind: 'gap', text: `The tablet missed ${missed} reading${missed === 1 ? '' : 's'}` })),
    ...series.events.stopped.map(([t, signal, reason]): Marker => ({ t: s(t), kind: 'stopped', text: `${signal} stopped: ${reason}` })),
  ].sort((a, b) => a.t - b.t)
}

/** The index of the time in [times] (ascending) nearest [t]; -1 if there are none. */
export function nearest(times: number[], t: number): number {
  if (times.length === 0) return -1
  let lo = 0
  let hi = times.length - 1
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (times[mid]! < t) lo = mid + 1
    else hi = mid
  }
  if (lo > 0 && Math.abs(times[lo - 1]! - t) <= Math.abs(times[lo]! - t)) return lo - 1
  return lo
}

/** The speed signal to colour the trace by: GPS speed, else the car's. */
export function speedSignal(series: Series): string | null {
  return ['gps.speed', 'vehicle.speed'].find((n) => (series.numbers[n]?.t.length ?? 0) > 0) ?? null
}

/** Each position's speed, the nearest sample in time (null where none is known). */
export function speedsAtPositions(series: Series): (number | null)[] {
  const name = speedSignal(series)
  if (!name) return series.positions.t.map(() => null)
  const { t, v } = series.numbers[name]!
  return series.positions.t.map((pt) => {
    const i = nearest(t, pt)
    return i < 0 ? null : v[i] ?? null
  })
}

/**
 * [speed]'s colour on a scale of [stops] (`#rrggbb`, slow to fast; the site's are
 * blue, mint, light pink, hot pink), mixed between the two it falls between.
 * Null where the speed isn't known, for the caller's "no data" colour.
 */
export function speedColor(speed: number | null, min: number, max: number, stops: string[]): string | null {
  if (speed === null || !Number.isFinite(speed) || stops.length === 0) return null
  const f = max > min ? Math.min(1, Math.max(0, (speed - min) / (max - min))) : 0.5
  const at = f * (stops.length - 1)
  const i = Math.min(stops.length - 2, Math.floor(at))
  if (stops.length === 1) return stops[0]!.toLowerCase()
  return mix(stops[i]!, stops[i + 1]!, at - i)
}

/** Two `#rrggbb` colours mixed, [f] of the way from [a] to [b]. */
export function mix(a: string, b: string, f: number): string {
  const channels = (hex: string) => { const n = parseInt(hex.replace('#', ''), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255] }
  const x = channels(a)
  const y = channels(b)
  return '#' + x.map((c, k) => Math.round(c + (y[k]! - c) * f).toString(16).padStart(2, '0')).join('')
}

/** Fetches a session's series; [thin] (M19.6), its thinned one, what a long session's page opens on. */
export async function fetchSeries(id: string, fetcher: typeof fetch = fetch, thin = false): Promise<Series | null> {
  const response = await fetcher(`/api/sessions/${id}/series${thin ? '?thin=1' : ''}`)
  // None yet: 204 for a live session with nothing uploaded (M11), 404 before M11 or for no session.
  if (response.status === 404 || response.status === 204) return null
  if (!response.ok) throw new Error(`The server answered ${response.status}.`)
  return (await response.json()) as Series
}

/** A session this long (ms) opens on its thinned series (M19.6). */
export const LONG_SESSION_MS = 3_600_000
/** A view this narrow (seconds) or narrower loads the full detail. */
export const DETAIL_WINDOW_S = 1_800

/** Whether a page showing a thinned series should load the full one: its view narrowed to [DETAIL_WINDOW_S] or less. */
export function needsDetail(thin: boolean, range: readonly [number, number] | null): boolean {
  return thin && range !== null && range[1] - range[0] <= DETAIL_WINDOW_S
}

