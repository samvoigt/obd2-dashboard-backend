/**
 * The race's lap-time chart (M16.3): lap times by lap number, a series per
 * car. Laps slower than [CAP] × a car's best on track (out-laps, the lap across
 * a restart) are drawn at that cap, so the racing laps keep the scale. Pure.
 */
import type { CarRace, RaceLap } from './race'

/** How far over its best a lap may be before it's drawn at the cap. */
export const CAP = 1.3

export interface LapChartData {
  /** Lap numbers, 1 to the most laps any car ran. */
  x: number[]
  /** Per car, each lap's time in seconds, capped; null where the car has no such lap. */
  ys: (number | null)[][]
  /** Per car, the laps drawn at the cap. */
  capped: number[][]
  /** The stints of the first car (the page shades one car's): lap spans and drivers. */
  stints: { from: number; to: number; driver: string | null }[]
  /** Laps to mark: the first car's stops (their in-laps), and where the flags fell. */
  marks: { lap: number; kind: 'stop' | 'green' | 'flag' }[]
}

function onTrack(l: RaceLap): boolean {
  return !l.pitIn && !l.pitOut && l.source !== 'restart'
}

export function lapChartData(cars: readonly CarRace[]): LapChartData {
  const most = Math.max(0, ...cars.map((c) => c.laps.length))
  const x = Array.from({ length: most }, (_, i) => i + 1)
  const ys: (number | null)[][] = []
  const capped: number[][] = []
  for (const c of cars) {
    const best = Math.min(...c.laps.filter(onTrack).map((l) => l.time))
    const cap = Number.isFinite(best) ? best * CAP : Infinity
    const byLap = new Map(c.laps.map((l) => [l.number, l.time]))
    ys.push(x.map((n) => {
      const t = byLap.get(n)
      return t === undefined ? null : Math.min(t, cap)
    }))
    capped.push(c.laps.filter((l) => l.time > cap).map((l) => l.number))
  }
  const first = cars[0]
  const stints = (first?.stints ?? [])
    .filter((s) => s.firstLap != null && s.lastLap != null)
    .map((s) => ({ from: s.firstLap!, to: s.lastLap!, driver: s.driver ?? null }))
  const marks: LapChartData['marks'] = [
    ...(first?.stops ?? []).map((s) => ({ lap: s.lap, kind: 'stop' as const })),
    ...(first?.greenLap != null ? [{ lap: first.greenLap, kind: 'green' as const }] : []),
    ...(first?.flagLap != null ? [{ lap: first.flagLap, kind: 'flag' as const }] : []),
  ]
  return { x, ys, capped, stints, marks }
}
