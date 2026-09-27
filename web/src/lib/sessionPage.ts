/**
 * A session's page (M7.5): the prepared series (M7.2) as the charts, lap
 * table, map and events want it. Pure, so it is tested without a browser.
 * Times in the file are milliseconds after `t0`; here they become absolute.
 */

export interface SeriesSignal {
  name: string
  unit: string
  kind: string
}

export interface LapRecord {
  track?: string
  layout?: string
  lap: number
  time: number
  pitIn?: boolean
  pitOut?: boolean
}

export interface Series {
  version: number
  t0: number
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
}

/** Laps in order; the best is the fastest on track, never a pit lap (§18). */
export function lapRows(series: Series): LapRow[] {
  const rows = series.events.lap
    .map(([t, r]) => {
      const end = series.t0 + t
      return { lap: r.lap, time: r.time, pitIn: r.pitIn === true, pitOut: r.pitOut === true, start: end - r.time * 1000, end, best: false }
    })
    .sort((a, b) => a.lap - b.lap)
  const onTrack = rows.filter((r) => !r.pitIn && !r.pitOut)
  if (onTrack.length > 0) {
    const fastest = onTrack.reduce((a, b) => (b.time < a.time ? b : a))
    fastest.best = true
  }
  return rows
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
  kind: 'fault' | 'gap' | 'stopped' | 'lap'
  text: string
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

/** Slow is blue, fast is red, through green and yellow; grey where unknown. */
export function speedColor(speed: number | null, min: number, max: number): string {
  if (speed === null || !Number.isFinite(speed)) return '#8b97a5'
  const f = max > min ? Math.min(1, Math.max(0, (speed - min) / (max - min))) : 0.5
  return `hsl(${Math.round(240 * (1 - f))}, 85%, 55%)`
}

export async function fetchSeries(id: string, fetcher: typeof fetch = fetch): Promise<Series | null> {
  const response = await fetcher(`/api/sessions/${id}/series`)
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`The server answered ${response.status}.`)
  return (await response.json()) as Series
}
