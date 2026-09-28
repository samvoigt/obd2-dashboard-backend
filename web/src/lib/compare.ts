/**
 * Two laps compared (M16.4): positions projected onto the course's own line,
 * so distance round the lap means the same for both whatever line each took;
 * each lap's signals resampled every metre; and the running delta, how far
 * ahead or behind the second lap is at each metre. Pure.
 */
import type { Pt } from './courseEdit'

/** Metres a degree of latitude, as the lap rule's frame has it. */
const METRES_PER_DEGREE = 111_195

/**
 * How far ahead of the last fix, along the line, the next is looked for, and
 * how far behind (GPS jitter): a car only goes forward, and a course may pass
 * near itself (NHMS's infield; a hairpin), where the nearest line can be the
 * wrong one.
 */
export const AHEAD_METRES = 250
export const BEHIND_METRES = 20

type XY = [number, number]

/** The course's line in a flat frame, and where the start/finish cuts it. */
export interface Track {
  lon0: number
  lat0: number
  perLon: number
  pts: XY[]
  /** Distance along the line to each point. */
  cum: number[]
  length: number
  /** Distance along the line of the start/finish. */
  start: number
}

export function toXY(t: Pick<Track, 'lon0' | 'lat0' | 'perLon'>, lon: number, lat: number): XY {
  return [(lon - t.lon0) * t.perLon, (lat - t.lat0) * METRES_PER_DEGREE]
}

/** The track of a layout's [path] (closed, in the cars' direction) and its start/finish [a]–[b]; null without a line. */
export function trackOf(path: readonly Pt[], sf: { a: Pt; b: Pt }): Track | null {
  if (path.length < 2) return null
  const [lon0, lat0] = path[0]!
  const frame = { lon0, lat0, perLon: METRES_PER_DEGREE * Math.cos((lat0 * Math.PI) / 180) }
  const pts = path.map(([lon, lat]) => toXY(frame, lon, lat))
  const cum = [0]
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1]! + dist(pts[i - 1]!, pts[i]!))
  const length = cum[cum.length - 1]!
  if (length <= 0) return null
  const a = toXY(frame, sf.a[0], sf.a[1])
  const b = toXY(frame, sf.b[0], sf.b[1])
  // Where the start/finish crosses the line, else the nearest point to its middle.
  let start: number | null = null
  for (let i = 0; i < pts.length - 1 && start === null; i++) {
    const t = crossing(pts[i]!, pts[i + 1]!, a, b)
    if (t !== null) start = cum[i]! + t * (cum[i + 1]! - cum[i]!)
  }
  const track = { ...frame, pts, cum, length, start: 0 }
  track.start = start ?? project(track, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
  return track
}

/** Distance along the line of the point nearest [x], [y]; if [near] is given, only from [BEHIND_METRES] behind it to [AHEAD_METRES] ahead. */
export function project(track: Track, x: number, y: number, near?: number): number {
  let best = Infinity
  let at = 0
  for (let i = 0; i < track.pts.length - 1; i++) {
    const [ax, ay] = track.pts[i]!
    const [bx, by] = track.pts[i + 1]!
    const dx = bx - ax
    const dy = by - ay
    const l2 = dx * dx + dy * dy
    const f = l2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / l2))
    const along = track.cum[i]! + f * (track.cum[i + 1]! - track.cum[i]!)
    if (near !== undefined) {
      const step = circular(along - near, track.length, true)
      if (step < -BEHIND_METRES || step > AHEAD_METRES) continue
    }
    const d = Math.hypot(x - (ax + f * dx), y - (ay + f * dy))
    if (d < best) { best = d; at = along }
  }
  return best === Infinity && near !== undefined ? project(track, x, y) : at
}

/** A lap's distance round the lap against time: from 0 at its start to the lap's length at its end. */
export interface Trace {
  t: number[]
  d: number[]
}

/** How far past a lap's ends the positions are read, to find where it crossed its lines on their own clock. */
export const MARGIN_MS = 3000

/**
 * The lap of positions [t] (ms), [lat], [lon] from [start] to [end] (ms), on
 * [track]: distance against **the positions' own clock**, from 0 where the lap
 * crossed its start to the lap's length where it crossed its end, each found
 * between the fixes either side (extrapolated if there's none beyond). The
 * lap's [start] and [end] are the crossings on `fixAt`; the positions are on
 * `wall` at `at`, later by the tablet's delay in hearing a fix, so pinning the
 * lap's ends there would squeeze its first and last stretches.
 */
export function lapTrace(track: Track, t: readonly number[], lat: readonly number[], lon: readonly number[], start: number, end: number): Trace {
  const L = track.length
  const ts: number[] = []
  const ds: number[] = []
  let along: number | undefined
  let d = 0
  for (let i = 0; i < t.length; i++) {
    const ti = t[i]!
    if (ti < start - MARGIN_MS || ti > end + MARGIN_MS) continue
    const [x, y] = toXY(track, lon[i]!, lat[i]!)
    const s = project(track, x, y, along)
    // Round the lap from the start/finish, unwrapped so it only ever runs on.
    const step = along === undefined ? wrap(s - track.start, L) : circular(s - along, L, true)
    d = along === undefined ? (step > L / 2 ? step - L : step) : d + step
    along = s
    ts.push(ti)
    ds.push(ds.length === 0 ? d : Math.max(d, ds[ds.length - 1]!))
  }
  if (ts.length < 2) return { t: [start, end], d: [0, L] }
  const t0 = crossAt(ts, ds, 0) ?? start
  const tL = crossAt(ts, ds, L) ?? end
  const out: Trace = { t: [t0], d: [0] }
  ts.forEach((ti, k) => {
    if (ds[k]! > 0 && ds[k]! < L && ti > t0 && ti < tL) { out.t.push(ti); out.d.push(ds[k]!) }
  })
  out.t.push(tL)
  out.d.push(L)
  return out
}

/** When the distance [ds] (against [ts]) reached [target]: between the fixes either side, else from the nearest two. */
function crossAt(ts: readonly number[], ds: readonly number[], target: number): number | null {
  for (let k = 0; k < ds.length - 1; k++) {
    if (ds[k]! <= target && ds[k + 1]! >= target && ds[k + 1]! > ds[k]!) {
      return ts[k]! + ((ts[k + 1]! - ts[k]!) * (target - ds[k]!)) / (ds[k + 1]! - ds[k]!)
    }
  }
  const [i, j] = target <= ds[0]! ? [0, 1] : [ds.length - 2, ds.length - 1]
  const dd = ds[j]! - ds[i]!
  return dd > 0 ? ts[i]! + ((ts[j]! - ts[i]!) * (target - ds[i]!)) / dd : null
}

/** Where the lap was at time [ms], interpolated. */
export function distanceAt(trace: Trace, ms: number): number {
  return interpolate(trace.t, trace.d, ms)
}

/** When the lap reached distance [d], interpolated. */
export function timeAt(trace: Trace, d: number): number {
  return interpolate(trace.d, trace.t, d)
}

/** Distances every [step] metres from 0 to [length]. */
export function grid(length: number, step = 1): number[] {
  const n = Math.floor(length / step)
  return Array.from({ length: n + 1 }, (_, i) => i * step)
}

/**
 * A signal's samples ([t] ms, [v]) during the lap, placed by distance and
 * resampled at each of [at]; null where the samples don't reach, or across a
 * gap in them (a null sample).
 */
export function resample(trace: Trace, t: readonly number[], v: readonly (number | null)[], at: readonly number[]): (number | null)[] {
  const start = trace.t[0]!
  const end = trace.t[trace.t.length - 1]!
  const ds: number[] = []
  const vs: (number | null)[] = []
  for (let i = 0; i < t.length; i++) {
    if (t[i]! < start || t[i]! > end) continue
    ds.push(distanceAt(trace, t[i]!))
    vs.push(v[i] ?? null)
  }
  return at.map((g) => {
    // A millimetre's grace at the ends: a sample's distance is computed, and can land a hair past a grid point.
    if (ds.length === 0 || g < ds[0]! - 1e-3 || g > ds[ds.length - 1]! + 1e-3) return null
    let k = 0
    while (k < ds.length - 1 && ds[k + 1]! < g) k++
    const a = vs[k]
    const b = vs[Math.min(k + 1, vs.length - 1)]
    if (a === null || a === undefined || b === null || b === undefined) return null
    const span = ds[Math.min(k + 1, ds.length - 1)]! - ds[k]!
    return span <= 0 ? a : a + ((b - a) * (g - ds[k]!)) / span
  })
}

/** At each of [at], how far behind (+) or ahead (−) lap [b] is of lap [a], seconds, each from its own start. */
export function delta(a: Trace, b: Trace, at: readonly number[]): number[] {
  const a0 = a.t[0]!
  const b0 = b.t[0]!
  return at.map((g) => (timeAt(b, g) - b0 - (timeAt(a, g) - a0)) / 1000)
}

/** A lap in a compare page's address: `car/session/start/end`. */
export interface LapRef {
  car: string
  session: string
  start: number
  end: number
}

export function lapRef(ref: LapRef): string {
  return `${ref.car}/${ref.session}/${Math.round(ref.start)}/${Math.round(ref.end)}`
}

export function readLapRef(s: string | null): LapRef | null {
  const m = s ? /^([a-z][a-z0-9-]{1,31})\/([0-9a-f-]{36})\/(\d+)\/(\d+)$/.exec(s) : null
  if (!m) return null
  const start = Number(m[3])
  const end = Number(m[4])
  return start < end ? { car: m[1]!, session: m[2]!, start, end } : null
}

/** The compare page for laps [a] and [b] on [course]'s [layout]. */
export function compareLink(a: LapRef, b: LapRef, course: string, layout: string): string {
  const q = new URLSearchParams({ a: lapRef(a), b: lapRef(b), course, layout })
  return `/compare?${q.toString()}`
}

function dist(p: XY, q: XY): number {
  return Math.hypot(q[0] - p[0], q[1] - p[1])
}

function wrap(x: number, L: number): number {
  return ((x % L) + L) % L
}

/** [x] brought into (−L/2, L/2]; its size unless [signed]. */
function circular(x: number, L: number, signed = false): number {
  const w = wrap(x + L / 2, L) - L / 2
  return signed ? w : Math.abs(w)
}

function crossing(p1: XY, p2: XY, a: XY, b: XY): number | null {
  const r: XY = [p2[0] - p1[0], p2[1] - p1[1]]
  const s: XY = [b[0] - a[0], b[1] - a[1]]
  const den = r[0] * s[1] - r[1] * s[0]
  if (den === 0) return null
  const q: XY = [a[0] - p1[0], a[1] - p1[1]]
  const t = (q[0] * s[1] - q[1] * s[0]) / den
  const u = (q[0] * r[1] - q[1] * r[0]) / den
  return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? t : null
}

function interpolate(xs: readonly number[], ys: readonly number[], x: number): number {
  if (x <= xs[0]!) return ys[0]!
  if (x >= xs[xs.length - 1]!) return ys[ys.length - 1]!
  let lo = 0
  let hi = xs.length - 1
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1
    if (xs[mid]! <= x) lo = mid
    else hi = mid
  }
  const span = xs[hi]! - xs[lo]!
  return span === 0 ? ys[lo]! : ys[lo]! + ((ys[hi]! - ys[lo]!) * (x - xs[lo]!)) / span
}

/** Speed from the lap itself (km/h), at each of [at]: how fast its distance grew there. For a drive with no speed signal. */
export function speedFromTrace(trace: Trace, at: readonly number[]): (number | null)[] {
  return at.map((g) => {
    let k = 0
    while (k < trace.d.length - 2 && trace.d[k + 1]! < g) k++
    const dd = trace.d[k + 1]! - trace.d[k]!
    const dt = trace.t[k + 1]! - trace.t[k]!
    return dt > 0 && dd >= 0 ? (dd / dt) * 3600 : null
  })
}
