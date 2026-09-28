import { describe, expect, it } from 'vitest'
import { compareLink, delta, distanceAt, grid, lapTrace, readLapRef, resample, speedFromTrace, timeAt, trackOf } from './compare'
import type { Pt } from './courseEdit'

const LON0 = -71.4611
const LAT0 = 43.3626
const perLon = 111_195 * Math.cos((LAT0 * Math.PI) / 180)
const ll = (x: number, y: number): Pt => [LON0 + x / perLon, LAT0 + y / 111_195]

/** The 1000 × 400 m box, anticlockwise from its corner; the start/finish across the bottom at x = 500. */
const box = [ll(0, 0), ll(1000, 0), ll(1000, 400), ll(0, 400), ll(0, 0)]
const sf = { a: ll(500, -15), b: ll(500, 15) }

/** Where the car is [d] metres round the box from the start/finish, [inset] metres inside the line. */
function round(d: number, inset = 0): [number, number] {
  const s = (((d + 500) % 2800) + 2800) % 2800
  if (s < 1000) return [s, inset]
  if (s < 1400) return [1000 - inset, s - 1000]
  if (s < 2400) return [1000 - (s - 1400), 400 - inset]
  return [inset, 400 - (s - 2400)]
}

/** A lap from t = 0 at [speed] m/s, a fix a second, [inset] metres inside the line. */
function lap(speed: number, inset = 0) {
  const secs = 2800 / speed
  const t: number[] = []
  const lat: number[] = []
  const lon: number[] = []
  for (let s = 1; s < secs; s++) {
    const [x, y] = round(speed * s, inset)
    const p = ll(x, y)
    t.push(s * 1000); lon.push(p[0]); lat.push(p[1])
  }
  return { t, lat, lon, end: secs * 1000 }
}

describe('two laps compared (M16.4)', () => {
  const track = trackOf(box, sf)!

  it('a track: its length, and the start/finish 500 m along', () => {
    expect(track.length).toBeCloseTo(2800, 6)
    expect(track.start).toBeCloseTo(500, 6)
  })

  it('a lap projected to its true distance round the lap, from 0 to its length', () => {
    const l = lap(40)
    const trace = lapTrace(track, l.t, l.lat, l.lon, 0, l.end)
    expect(trace.d[0]).toBe(0)
    expect(trace.d[trace.d.length - 1]).toBeCloseTo(2800, 6)
    for (const s of [1, 12, 30, 45, 69]) expect(distanceAt(trace, s * 1000)).toBeCloseTo(40 * s, 3)
    expect(timeAt(trace, 1400)).toBeCloseTo(35_000, 3)
  })

  it('the delta: at 38 m/s the second lap falls behind as it should, and ends the lap-time difference behind', () => {
    const a = lap(40)
    const b = lap(38)
    const at = grid(2800, 100)
    // Lap B driven 500 s later, as a real second lap is: the delta counts from each lap's own start.
    const bt = b.t.map((x) => x + 500_000)
    const d = delta(lapTrace(track, a.t, a.lat, a.lon, 0, a.end), lapTrace(track, bt, b.lat, b.lon, 500_000, b.end + 500_000), at)
    at.forEach((g, i) => expect(d[i]!).toBeCloseTo(g / 38 - g / 40, 3))
    expect(d[d.length - 1]!).toBeCloseTo(2800 / 38 - 70, 3)
  })

  it('a lap on another line, 5 m inside, projected to the same distances on the straights', () => {
    const l = lap(40, 5)
    const trace = lapTrace(track, l.t, l.lat, l.lon, 0, l.end)
    for (const s of [5, 30, 45, 62]) expect(distanceAt(trace, s * 1000)).toBeCloseTo(40 * s, 3)
  })

  it('a signal resampled every metre by distance, and nothing where it doesn\\u2019t reach or has a gap', () => {
    const l = lap(40)
    const trace = lapTrace(track, l.t, l.lat, l.lon, 0, l.end)
    const t = [1000, 2000, 3000, 4000]
    const v = [10, 20, null, 40]
    const r = resample(trace, t, v, [40, 60, 80, 100, 120, 160, 1000])
    expect(r[0]).toBeCloseTo(10, 6)
    expect(r[1]).toBeCloseTo(15, 6)
    expect(r[2]).toBeCloseTo(20, 6)
    expect(r[3]).toBeNull() // across the gap
    expect(r[6]).toBeNull() // past the samples
    expect(grid(3.5)).toEqual([0, 1, 2, 3])
  })

  it('a course that passes close to itself: the car is followed, not the nearest line', () => {
    // A long thin loop, out along y = 0 and back along y = 10; driving back 6 m below the return line.
    const loop = [ll(0, 0), ll(1000, 0), ll(1000, 10), ll(0, 10), ll(0, 0)]
    const t2 = trackOf(loop, { a: ll(100, -15), b: ll(100, 5) })!
    const t: number[] = []
    const lat: number[] = []
    const lon: number[] = []
    const at = (d: number): [number, number] => (d < 900 ? [100 + d, 0] : d < 910 ? [1000, d - 900] : [1000 - (d - 910), 4])
    for (let s = 1; s < 50; s++) {
      const p = ll(...at(s * 40)); t.push(s * 1000); lon.push(p[0]); lat.push(p[1])
    }
    const trace = lapTrace(t2, t, lat, lon, 0, 50_000)
    // 1200 m round: on the way back, 6 m nearer the way out than the way back, and still counted as the way back.
    expect(distanceAt(trace, 30_000)).toBeCloseTo(1200, 0)
  })

  it('positions heard later than the lap\u2019s crossings (the tablet\u2019s delay): measured on their own clock, no squeeze at the ends', () => {
    const l = lap(40)
    const late = l.t.map((x) => x + 150) // heard 150 ms after each fix; the lap's start and end stay on fixAt
    const trace = lapTrace(track, late, l.lat, l.lon, 0, l.end)
    expect(trace.t[0]).toBeCloseTo(150, 3)
    expect(trace.t[trace.t.length - 1]).toBeCloseTo(70_150, 3)
    for (const v of speedFromTrace(trace, [0, 20, 2780, 2800])) expect(v!).toBeCloseTo(144, 3)
    // Fixes either side of both lines, as a real lap has: found between them.
    const all = lap(40)
    const around = { t: [-500, ...all.t, 70_500], lat: [...all.lat], lon: [...all.lon] }
    const before = ll(...round(-20)); const after = ll(...round(2820))
    around.lat = [before[1], ...all.lat, after[1]]; around.lon = [before[0], ...all.lon, after[0]]
    const t2 = lapTrace(track, around.t, around.lat, around.lon, 0, 70_000)
    expect(t2.t[0]).toBeCloseTo(0, 3)
    expect(t2.t[t2.t.length - 1]).toBeCloseTo(70_000, 3)
  })

  it('speed from the lap itself, for a drive with no speed signal', () => {
    const l = lap(40)
    const trace = lapTrace(track, l.t, l.lat, l.lon, 0, l.end)
    for (const v of speedFromTrace(trace, [0, 500, 1400, 2799])) expect(v!).toBeCloseTo(144, 3)
  })

  it('a lap in the address, and the compare link', () => {
    const a = { car: 'outback', session: '5ace0000-1111-4111-8111-00000000d161', start: 1000, end: 71000 }
    const b = { ...a, start: 71000, end: 141000 }
    const link = compareLink(a, b, 'box', 'box')
    const q = new URLSearchParams(link.split('?')[1])
    expect(readLapRef(q.get('a'))).toEqual(a)
    expect(readLapRef(q.get('b'))).toEqual(b)
    expect(q.get('course')).toBe('box')
    expect(readLapRef('outback/nope/1/2')).toBeNull()
    expect(readLapRef(`outback/${a.session}/5/5`)).toBeNull()
    expect(readLapRef(null)).toBeNull()
  })
})
