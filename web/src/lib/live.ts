import { isState, type State } from './state'
import { readStanding, type Standing } from './standing'

/**
 * A car's live state on the page, rebuilt from the server's SSE events
 * (`/api/cars/{slug}/live`). Pure: every function takes the state and returns
 * the next, so it is tested without a browser. The components only draw.
 */

export type Rec = Record<string, unknown> & { type?: string; signal?: string }

export interface Signal {
  name: string
  unit: string
  kind: string
}

/** A record and when the server received it (server epoch ms). */
export interface Point {
  t: number
  rec: Rec
}

export interface LiveState {
  state: State
  /** Server-reported age of the last data when the status arrived; null if none yet. */
  lastDataAgoMs: number | null
  /** The page's clock when that status arrived. */
  statusAtLocal: number
  /** The page's clock minus the server's, from the latest event. */
  offsetMs: number
  session: Rec | null
  signals: Signal[]
  latest: Record<string, Rec>
  stopped: Record<string, Rec>
  fault: Rec | null
  history: Point[]
  /** Where the car stands (M17.5): the server's, kept across sessions as the server keeps it. */
  standing: Standing | null
}

/** How much history the page keeps: the server sends five minutes. */
export const WINDOW_MS = 5 * 60 * 1000
/** Batches come every 200 ms; two seconds without one is visibly behind (matches the server). */
export const LIVE_WITHIN_MS = 2000

export function empty(): LiveState {
  return {
    state: 'offline', lastDataAgoMs: null, statusAtLocal: 0, offsetMs: 0,
    session: null, signals: [], latest: {}, stopped: {}, fault: null, history: [], standing: null,
  }
}

type Json = Record<string, unknown>

function statusFrom(s: Json, localNow: number) {
  const state = typeof s.state === 'string' && isState(s.state) ? s.state : 'offline'
  const ago = typeof s.lastDataAgoMs === 'number' ? s.lastDataAgoMs : null
  return { state, lastDataAgoMs: ago, statusAtLocal: localNow }
}

function offset(e: Json, localNow: number, fallback: number): number {
  return typeof e.serverNow === 'number' ? localNow - e.serverNow : fallback
}

function signalsFrom(v: unknown): Signal[] {
  if (!Array.isArray(v)) return []
  return v.flatMap((s) => {
    const o = s as Json
    return typeof o.name === 'string'
      ? [{ name: o.name, unit: typeof o.unit === 'string' ? o.unit : '', kind: typeof o.kind === 'string' ? o.kind : 'number' }]
      : []
  })
}

/** Folds records into latest, stopped, fault and the signals list, as the server does. */
function absorb(state: LiveState, records: Rec[]): Pick<LiveState, 'latest' | 'stopped' | 'fault' | 'signals'> {
  const latest = { ...state.latest }
  const stopped = { ...state.stopped }
  let fault = state.fault
  let signals = state.signals
  for (const r of records) {
    if (r.type === 'sample' && typeof r.signal === 'string') latest[r.signal] = r
    else if (r.type === 'stopped' && typeof r.signal === 'string') stopped[r.signal] = r
    else if (r.type === 'fault') fault = r
    else if (r.type === 'signals') signals = signalsFrom(r.signals)
  }
  return { latest, stopped, fault, signals }
}

function trim(history: Point[], serverNow: number): Point[] {
  const oldest = serverNow - WINDOW_MS
  let i = 0
  while (i < history.length && history[i]!.t < oldest) i++
  return i === 0 ? history : history.slice(i)
}

/** A snapshot is the whole truth: nothing from before it survives. */
export function applySnapshot(e: Json, localNow: number): LiveState {
  const base = empty()
  const history: Point[] = Array.isArray(e.history)
    ? e.history.flatMap((h) => {
        const o = h as Json
        return typeof o.atMs === 'number' && o.record && typeof o.record === 'object' ? [{ t: o.atMs, rec: o.record as Rec }] : []
      })
    : []
  const latest: Record<string, Rec> = {}
  for (const r of (Array.isArray(e.latest) ? e.latest : []) as Rec[]) if (typeof r.signal === 'string') latest[r.signal] = r
  const stopped: Record<string, Rec> = {}
  for (const r of (Array.isArray(e.stopped) ? e.stopped : []) as Rec[]) if (typeof r.signal === 'string') stopped[r.signal] = r
  return {
    ...base,
    ...statusFrom((e.status ?? {}) as Json, localNow),
    offsetMs: offset(e, localNow, 0),
    session: (e.session as Rec | null) ?? null,
    signals: signalsFrom(e.signals),
    latest,
    stopped,
    fault: (e.fault as Rec | null) ?? null,
    history,
    standing: readStanding(e.timing),
  }
}

/** Where the car stands, as the server now says (M17.5). */
export function applyTiming(state: LiveState, e: Json, localNow: number): LiveState {
  return { ...state, standing: readStanding(e.timing), offsetMs: offset(e, localNow, state.offsetMs) }
}

/** A new session starts afresh (the same session again keeps its state, as on the server). */
export function applySession(state: LiveState, e: Json, localNow: number): LiveState {
  const session = (e.session as Rec | null) ?? null
  const same = session && state.session && session.id === state.session.id
  return {
    ...(same ? state : { ...empty(), state: state.state, lastDataAgoMs: state.lastDataAgoMs, statusAtLocal: state.statusAtLocal, standing: state.standing }),
    offsetMs: offset(e, localNow, state.offsetMs),
    session,
    signals: signalsFrom(e.signals),
  }
}

export function applyRecords(state: LiveState, e: Json, localNow: number): LiveState {
  const records = (Array.isArray(e.records) ? e.records : []) as Rec[]
  const t = typeof e.atMs === 'number' ? e.atMs : localNow - state.offsetMs
  const serverNow = typeof e.serverNow === 'number' ? e.serverNow : t
  return {
    ...state,
    ...absorb(state, records),
    offsetMs: offset(e, localNow, state.offsetMs),
    history: trim([...state.history, ...records.map((rec) => ({ t, rec }))], serverNow),
  }
}

export function applyStatus(state: LiveState, e: Json, localNow: number): LiveState {
  return { ...state, ...statusFrom(e, localNow), offsetMs: offset(e, localNow, state.offsetMs) }
}

/**
 * What the banner shows now, counted on the page's own clock from the last
 * status. **Live turns to behind by itself** when no status arrives for two
 * seconds, because a silent server sends nothing to say so.
 */
export function freshness(state: LiveState, localNow: number): { state: State; seconds: number | null } {
  const ago = state.lastDataAgoMs === null ? null : state.lastDataAgoMs + Math.max(0, localNow - state.statusAtLocal)
  if (state.state === 'live' || state.state === 'stale') {
    if (ago === null) return { state: 'stale', seconds: null }
    return ago <= LIVE_WITHIN_MS ? { state: 'live', seconds: 0 } : { state: 'stale', seconds: Math.floor(ago / 1000) }
  }
  return { state: state.state, seconds: null }
}

/**
 * uPlot's columns for [names]: x in seconds on the page's clock, one y per
 * signal. **A `gap` record breaks every line** (a `null` row), never bridged.
 */
export function series(state: LiveState, names: string[]): [number[], ...(number | null)[][]] {
  const x: number[] = []
  const ys: (number | null)[][] = names.map(() => [])
  const last: (number | null)[] = names.map(() => null)
  for (const p of state.history) {
    const r = p.rec
    const seconds = (p.t + state.offsetMs) / 1000
    if (r.type === 'gap') {
      x.push(seconds)
      ys.forEach((y) => y.push(null))
      last.fill(null)
      continue
    }
    const i = r.type === 'sample' && typeof r.signal === 'string' ? names.indexOf(r.signal) : -1
    if (i < 0 || typeof r.value !== 'number') continue
    last[i] = r.value
    x.push(seconds)
    // Each row carries every chosen signal's latest value, so lines stay continuous between samples.
    ys.forEach((y, j) => y.push(last[j] ?? null))
  }
  return [x, ...ys]
}

/** Numeric signals, for the chart's picker. */
export function numericSignals(state: LiveState): string[] {
  const named = state.signals.filter((s) => s.kind === 'number').map((s) => s.name)
  const seen = Object.values(state.latest).filter((r) => typeof r.value === 'number').map((r) => r.signal as string)
  return [...new Set([...named, ...seen])].sort()
}

/** The chart's first choice: engine speed and road speed when present, else the first two numbers. */
export function defaultChart(state: LiveState): string[] {
  const numeric = numericSignals(state)
  const preferred = ['engine.rpm', 'vehicle.speed'].filter((n) => numeric.includes(n))
  return preferred.length > 0 ? preferred : numeric.slice(0, 2)
}

export function unitOf(state: LiveState, name: string): string {
  return state.signals.find((s) => s.name === name)?.unit ?? ''
}

/** A reading as the tile shows it, by its kind (contract §3.4). */
export function format(r: Rec | undefined, unit = ''): string {
  if (!r) return '—'
  if (typeof r.value === 'number') {
    const v = r.value
    const text = Math.abs(v) >= 100 ? v.toFixed(0) : Math.abs(v) >= 10 ? v.toFixed(1) : v.toFixed(2)
    return unit ? `${text} ${unit}` : text
  }
  if (typeof r.text === 'string') return r.text
  if (typeof r.flag === 'boolean') return r.flag ? 'On' : 'Off'
  if (Array.isArray(r.flags)) return r.flags.length === 0 ? 'None' : r.flags.join(', ')
  if (typeof r.lat === 'number' && typeof r.lon === 'number') return `${r.lat.toFixed(5)}, ${r.lon.toFixed(5)}`
  return '—'
}

/** A signal's display name: its dotted name, made readable, never reworded. */
export function label(name: string): string {
  return name.replaceAll('_', ' ').replaceAll('.', ' · ')
}
