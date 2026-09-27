/**
 * The dashboard (M8): one fixed layout for every car. Its slots, and each
 * signal's range and warning zones, **generic**: by unit, and by what a signal
 * is on any engine, never by which car it is (the app's decision 33). Pure, so
 * it is tested without a browser.
 */
import type { Point, Rec } from './live'
import { lapRows, type LapRecord, type LapRow, type Series } from './sessionPage'

/**
 * What fills each slot: Sam's choice (2026-09-27): "rpm, speed, coolant temp,
 * charging, gps speed, acceleration, gps position, status lights". The G-meter
 * and the map are fixed sections. No bars for now.
 *
 * **A slot is a list of signals, the first a session sends shown** (M11): a
 * tablet sends only what its own dashboard shows plus chosen extras, so one
 * name can be missing from a drive. Charging is the control module's supply
 * voltage, or the battery's at the OBD port (the first drive sent only that).
 */
export const SLOTS = {
  gauges: [['engine.rpm'], ['vehicle.speed'], ['engine.coolant_temperature'], ['control_module.voltage', 'vehicle.system_voltage']],
  numbers: [['gps.speed']],
  bars: [],
  statuses: [['diagnostics.mil'], ['fuel.system_1_status']],
} as const satisfies Record<string, readonly (readonly string[])[]>

/** Every signal any slot may show, so the tiles below leave them all out. */
export const SHOWN: ReadonlySet<string> = new Set([...SLOTS.gauges, ...SLOTS.numbers, ...SLOTS.bars, ...SLOTS.statuses].flat())

/**
 * The signal a slot shows: the first of [choices] the session declares; with no
 * session record yet, the first with a reading; else the first, which reads "—".
 */
export function slotSignal(choices: readonly string[], declared: ReadonlySet<string>, latest: Readonly<Record<string, unknown>>): string {
  return choices.find((n) => declared.has(n)) ?? choices.find((n) => latest[n] !== undefined) ?? choices[0]!
}

export type Level = 'normal' | 'caution' | 'critical'

/** A zone: a reading above or below a line, in the signal's own (tablet's) unit. */
export interface Zone {
  above?: number
  below?: number
}

export interface Profile {
  min: number
  max: number
  caution?: Zone
  critical?: Zone
}

/** Ranges by unit, when a signal has none of its own. */
const BY_UNIT: Record<string, { min: number; max: number }> = {
  'rpm': { min: 0, max: 8000 },
  'km/h': { min: 0, max: 250 },
  '°C': { min: 0, max: 150 },
  '%': { min: 0, max: 100 },
  'V': { min: 0, max: 16 },
  'kPa': { min: 0, max: 300 },
  'L/h': { min: 0, max: 50 },
  'g/s': { min: 0, max: 300 },
}

/** What a signal is on any engine: generic ranges and zones, never one car's. */
const BY_SIGNAL: Record<string, Partial<Profile>> = {
  'engine.rpm': { caution: { above: 6000 }, critical: { above: 7000 } },
  'engine.coolant_temperature': { min: 40, max: 130, caution: { above: 105 }, critical: { above: 115 } },
  'engine.oil_temperature': { min: 40, max: 150, caution: { above: 120 }, critical: { above: 135 } },
  'control_module.voltage': { min: 10, max: 16, caution: { below: 12.0 }, critical: { below: 11.5 } },
  'vehicle.system_voltage': { min: 10, max: 16, caution: { below: 12.0 }, critical: { below: 11.5 } },
  'ambient.air_temperature': { min: -20, max: 50 },
  'intake.air_temperature': { min: -20, max: 80 },
  'fuel.tank_level': { caution: { below: 15 }, critical: { below: 7 } },
}

export function profile(signal: string, unit: string): Profile {
  const own = BY_SIGNAL[signal] ?? {}
  const base = BY_UNIT[unit] ?? { min: 0, max: 100 }
  return { min: own.min ?? base.min, max: own.max ?? base.max, caution: own.caution, critical: own.critical }
}

function inZone(value: number, zone: Zone | undefined): boolean {
  if (!zone) return false
  return (zone.above !== undefined && value > zone.above) || (zone.below !== undefined && value < zone.below)
}

/** Critical before caution: a reading in both is critical. */
export function level(value: number | null, p: Profile): Level {
  if (value === null || !Number.isFinite(value)) return 'normal'
  if (inZone(value, p.critical)) return 'critical'
  if (inZone(value, p.caution)) return 'caution'
  return 'normal'
}

/** Where a reading falls in its range, 0 to 1, held at the ends. */
export function fraction(value: number, p: Profile): number {
  if (p.max <= p.min) return 0
  return Math.min(1, Math.max(0, (value - p.min) / (p.max - p.min)))
}

/** A dial's sweep: 240°, from lower left (−120°) to lower right (+120°), 0° straight up. */
export const SWEEP = 240

export function needleAngle(value: number, p: Profile): number {
  return -SWEEP / 2 + fraction(value, p) * SWEEP
}

/** The stretches of a range that are caution or critical, as fractions, for drawing on a dial or bar. */
export function zoneBands(p: Profile): { from: number; to: number; level: Exclude<Level, 'normal'> }[] {
  const bands: { from: number; to: number; level: Exclude<Level, 'normal'> }[] = []
  const add = (zone: Zone | undefined, lv: Exclude<Level, 'normal'>) => {
    if (!zone) return
    if (zone.above !== undefined) bands.push({ from: fraction(zone.above, p), to: 1, level: lv })
    if (zone.below !== undefined) bands.push({ from: 0, to: fraction(zone.below, p), level: lv })
  }
  add(p.caution, 'caution')
  add(p.critical, 'critical')
  return bands.filter((b) => b.to > b.from)
}

export type Freshness = 'fresh' | 'stale' | 'stopped' | 'never'

/** Five times a signal's usual interval, never under this. */
export const MIN_STALE_MS = 2000

/** Each signal's last reading time and usual interval, from one pass over the history (M8). */
export type Timing = Map<string, { last: number; usual: number | null }>

/**
 * Every signal's timing, in **one pass**: the page asks for each widget's
 * freshness many times a second, and scanning the whole history per widget was
 * enough to hang it (found in M8.2).
 */
export function timings(history: Point[], only?: ReadonlySet<string>): Timing {
  const times = new Map<string, number[]>()
  for (const p of history) {
    if (p.rec.type !== 'sample' || typeof p.rec.signal !== 'string') continue
    if (only && !only.has(p.rec.signal)) continue // the page needs only its slots' (M8.4)
    let list = times.get(p.rec.signal)
    if (!list) times.set(p.rec.signal, (list = []))
    list.push(p.t)
  }
  const out: Timing = new Map()
  for (const [signal, list] of times) {
    const gaps = list.slice(1).map((t, i) => t - list[i]!).sort((a, b) => a - b)
    // One reading has no usual interval to judge by (M8.3): null, and it isn't judged.
    out.set(signal, { last: list[list.length - 1]!, usual: gaps.length > 0 ? gaps[Math.floor(gaps.length / 2)]! : null })
  }
  return out
}

/**
 * Whether [signal]'s reading is current: stale once its last reading is 5 times
 * its usual interval old (the history's median gap, never under 2 s), "stopped"
 * if the tablet said so, "never" if none has come. Times are the server's, as
 * the history's are.
 */
export function freshnessOf(signal: string, timing: Timing, latest: Rec | undefined, stopped: boolean, serverNow: number): Freshness {
  if (stopped) return 'stopped'
  const t = timing.get(signal)
  if (!t) return latest ? 'fresh' : 'never' // only a snapshot's latest, no history to judge by
  // A signal read once in 5 minutes can't be judged by its own pace: left alone, the car's
  // banner says when everything has gone quiet (M8.3).
  if (t.usual === null) return 'fresh'
  return serverNow - t.last > Math.max(MIN_STALE_MS, t.usual * 5) ? 'stale' : 'fresh'
}

/** A number from a reading, if it is one. */
export function valueOf(rec: Rec | undefined): number | null {
  const v = rec?.value
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

export const G = 9.80665

/** The G-meter's points: lateral and longitudinal, in g, over the last [seconds]. */
export function gTrail(history: Point[], serverNow: number, seconds = 5): { t: number; lat: number; lon: number }[] {
  let lat: number | null = null
  let lon: number | null = null
  const out: { t: number; lat: number; lon: number }[] = []
  for (const p of history) {
    if (p.rec.type !== 'sample') continue
    const v = valueOf(p.rec)
    if (v === null) continue
    if (p.rec.signal === 'motion.acceleration.lateral') lat = v / G
    else if (p.rec.signal === 'motion.acceleration.longitudinal') lon = v / G
    else continue
    if (lat !== null && lon !== null && p.t >= serverNow - seconds * 1000) out.push({ t: p.t, lat, lon })
  }
  return out
}

export interface Peaks {
  left: number
  right: number
  accel: number
  brake: number
}

export const NO_PEAKS: Peaks = { left: 0, right: 0, accel: 0, brake: 0 }

/** The largest seen each way, in g: lateral positive is to the right, longitudinal positive is speeding up (§4.2). */
export function peaks(prev: Peaks, points: { lat: number; lon: number }[]): Peaks {
  return points.reduce((p, x) => ({
    right: Math.max(p.right, x.lat),
    left: Math.max(p.left, -x.lat),
    accel: Math.max(p.accel, x.lon),
    brake: Math.max(p.brake, -x.lon),
  }), prev)
}

/** The last lap, the best, and how far off it the last was (seconds; null without both). */
export function lapSummary(rows: { lap: number; time: number; best: boolean }[]): { last: number | null; best: number | null; delta: number | null; lastLap: number | null; bestLap: number | null } {
  const last = rows.reduce<{ lap: number; time: number } | null>((a, r) => (!a || r.lap > a.lap ? r : a), null)
  const best = rows.find((r) => r.best) ?? null
  return {
    last: last?.time ?? null,
    lastLap: last?.lap ?? null,
    best: best?.time ?? null,
    bestLap: best?.lap ?? null,
    delta: last && best ? last.time - best.time : null,
  }
}

/**
 * The session's laps: the archive's, then the live lane's after its `lastSeq`
 * (contract §7), **the lap events only**. The full merge copies every column
 * of the session, too much to do on every batch of a long race (M8.3).
 */
export function lapsFrom(archived: Series | null, history: Point[]): LapRow[] {
  const cutoff = archived?.lastSeq ?? -Infinity
  const t0 = archived?.t0 ?? 0
  const seen = new Set<number>()
  const live: [number, LapRecord][] = []
  for (const p of history) {
    const r = p.rec
    if (r.type !== 'lap' || typeof r.seq !== 'number' || typeof r.wall !== 'number' || r.seq <= cutoff || seen.has(r.seq)) continue
    if (typeof r.lap !== 'number' || typeof r.time !== 'number') continue
    seen.add(r.seq)
    live.push([r.wall - t0, r as unknown as LapRecord])
  }
  const events = { stopped: [], fault: [], gap: [], lap: [...(archived?.events.lap ?? []), ...live] }
  return lapRows({ version: 0, t0, lastSeq: null, signals: [], numbers: {}, states: {}, sets: {}, positions: { t: [], lat: [], lon: [] }, events })
}

