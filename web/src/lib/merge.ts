/**
 * A session being driven, whole (M7.6, contract §7): the archive's prepared
 * series up to its `lastSeq`, then the live lane's records after it. Archive
 * rows win wherever both have a `seq`; the live ones are provisional until the
 * archive covers them. Pure, so it is tested without a browser.
 */
import type { LapRecord, Series } from './sessionPage'

type Rec = Record<string, unknown>

/** Where two readings more than this apart meet, the line breaks: never bridged. */
export const BREAK_MS = 5_000

export interface Merged {
  series: Series
  /** Epoch ms from which the data is only live, not yet archived; null when all of it is archived. */
  provisionalFrom: number | null
}

const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)

/**
 * [series] (or nothing yet) with [live] records after its `lastSeq`, for session [id].
 * Live records need a `seq` and a `wall`; others can't be placed, and are left out.
 */
export function merge(series: Series | null, live: Rec[], fallbackSignals: Series['signals'] = []): Merged {
  const cutoff = series?.lastSeq ?? -Infinity
  const seen = new Set<number>()
  const tail = live
    .filter((r) => {
      const seq = num(r.seq)
      if (seq === null || num(r.wall) === null || seq <= cutoff || seen.has(seq)) return false
      seen.add(seq)
      return true
    })
    .sort((a, b) => (a.seq as number) - (b.seq as number))

  if (tail.length === 0) {
    return { series: series ?? emptySeries(0, fallbackSignals), provisionalFrom: null }
  }
  const t0 = series?.t0 ?? (tail[0]!.wall as number)
  const out: Series = series
    ? {
        ...series,
        numbers: Object.fromEntries(Object.entries(series.numbers).map(([k, c]) => [k, { t: [...c.t], v: [...c.v] }])),
        positions: { t: [...series.positions.t], lat: [...series.positions.lat], lon: [...series.positions.lon] },
        events: {
          stopped: [...series.events.stopped], fault: [...series.events.fault], gap: [...series.events.gap], lap: [...series.events.lap],
        },
      }
    : emptySeries(t0, fallbackSignals)

  let lastSeq = series?.lastSeq ?? null
  for (const r of tail) {
    const t = (r.wall as number) - t0
    lastSeq = r.seq as number
    switch (r.type) {
      case 'sample': {
        const signal = typeof r.signal === 'string' ? r.signal : null
        if (!signal) break
        const value = num(r.value) ?? (typeof r.flag === 'boolean' ? (r.flag ? 1 : 0) : null)
        if (value !== null) {
          const column = (out.numbers[signal] ??= { t: [], v: [] })
          const last = column.t[column.t.length - 1]
          if (last !== undefined && t < last) break // a coalesced reading from before what's shown: skip it
          if (last !== undefined && t - last > BREAK_MS) {
            column.t.push(last + 1)
            column.v.push(null)
          }
          column.t.push(t)
          column.v.push(value)
        } else if (num(r.lat) !== null && num(r.lon) !== null) {
          out.positions.t.push(t)
          out.positions.lat.push(r.lat as number)
          out.positions.lon.push(r.lon as number)
        }
        break
      }
      case 'fault':
        out.events.fault.push([t, Array.isArray(r.codes) ? r.codes.filter((c): c is string => typeof c === 'string') : []])
        break
      case 'gap':
        out.events.gap.push([t, num(r.missed) ?? 0])
        break
      case 'stopped':
        if (typeof r.signal === 'string') out.events.stopped.push([t, r.signal, typeof r.reason === 'string' ? r.reason : ''])
        break
      case 'lap':
        if (num(r.lap) !== null && num(r.time) !== null) out.events.lap.push([t, r as unknown as LapRecord])
        break
    }
  }
  out.lastSeq = lastSeq
  return { series: out, provisionalFrom: tail[0]!.wall as number }
}

function emptySeries(t0: number, signals: Series['signals']): Series {
  return {
    version: 0, t0, lastSeq: null, signals, numbers: {}, states: {}, sets: {},
    positions: { t: [], lat: [], lon: [] },
    events: { stopped: [], fault: [], gap: [], lap: [] },
  }
}
