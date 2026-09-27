import { describe, expect, it } from 'vitest'
import { bestSectors, defaultSignals, events, fetchSeries, joined, lapRows, nearest, speedColor, speedsAtPositions, speedSignal, type Series } from './sessionPage'

const t0 = 1_790_000_000_000

function series(over: Partial<Series> = {}): Series {
  return {
    version: 1, t0, signals: [{ name: 'engine.rpm', unit: 'rpm', kind: 'number' }],
    numbers: {}, states: {}, sets: {}, positions: { t: [], lat: [], lon: [] },
    events: { stopped: [], fault: [], gap: [], lap: [] },
    ...over,
  }
}

describe('laps', () => {
  it('are in order, each spanning its time up to when it was published, the best never a pit lap', () => {
    const rows = lapRows(series({
      events: {
        stopped: [], fault: [], gap: [],
        lap: [
          [200_000, { lap: 2, time: 94.5 }],
          [100_000, { lap: 1, time: 96.0 }],
          [290_000, { lap: 3, time: 88.0, pitIn: true }],
          [420_000, { lap: 4, time: 130.0, pitOut: true }],
        ],
      },
    }))
    expect(rows.map((r) => r.lap)).toEqual([1, 2, 3, 4])
    expect(rows.map((r) => r.best)).toEqual([false, true, false, false])
    expect(rows[1]).toMatchObject({ start: t0 + 200_000 - 94_500, end: t0 + 200_000 })
    expect(rows[2]).toMatchObject({ pitIn: true, pitOut: false })
  })
  it('have no best when every lap is a pit lap, or there are none', () => {
    expect(lapRows(series({ events: { stopped: [], fault: [], gap: [], lap: [[1, { lap: 1, time: 90, pitIn: true }]] } })).some((r) => r.best)).toBe(false)
    expect(lapRows(series())).toEqual([])
  })
  it('carry their sectors when the tablet timed them (M12.7), older laps none, side by side', () => {
    const rows = lapRows(series({
      events: {
        stopped: [], fault: [], gap: [],
        lap: [
          [100_000, { lap: 1, time: 96.0 }], // before courses came from the website
          [200_000, { lap: 2, time: 94.5, course: 'nhms', courseVersion: 7, layout: 'road', sectors: [31.5, 33.0, 30.0] }],
          [300_000, { lap: 3, time: 94.2, course: 'nhms', courseVersion: 7, layout: 'road', sectors: [31.2, 33.4, 29.6] }],
          [390_000, { lap: 4, time: 88.0, pitIn: true, sectors: [30.0, 30.0, 28.0] }], // into the pits: its last sector ends at the pit line
        ],
      },
    }))
    expect(rows.map((r) => r.sectors)).toEqual([null, [31.5, 33.0, 30.0], [31.2, 33.4, 29.6], [30.0, 30.0, 28.0]])
    // §22.6: an in-lap's last sector never counts; its others are ordinary sectors.
    expect(bestSectors(rows)).toEqual([30.0, 30.0, 29.6])
  })
  it('have no sector columns when no lap has sectors, and a sector no lap on track has is null', () => {
    expect(bestSectors(lapRows(series({ events: { stopped: [], fault: [], gap: [], lap: [[1, { lap: 1, time: 90 }]] } })))).toEqual([])
    const odd = lapRows(series({
      events: { stopped: [], fault: [], gap: [], lap: [[1, { lap: 1, time: 90, sectors: [45, 45] }], [2, { lap: 2, time: 91, pitOut: true, sectors: [30, 30, 31] }]] },
    }))
    expect(bestSectors(odd)).toEqual([45, 30, 31]) // an out-lap's first sector never counts; its others do
    expect(lapRows(series({ events: { stopped: [], fault: [], gap: [], lap: [[1, { lap: 1, time: 90, sectors: ['x'] as unknown as number[] }]] } }))[0]!.sectors).toBeNull()
  })
})

describe('the chart', () => {
  it('starts with engine and road speed, else the first two numbers', () => {
    expect(defaultSignals(series({ numbers: { 'a': { t: [], v: [] }, 'vehicle.speed': { t: [], v: [] }, 'engine.rpm': { t: [], v: [] } } })))
      .toEqual(['engine.rpm', 'vehicle.speed'])
    expect(defaultSignals(series({ numbers: { a: { t: [], v: [] }, b: { t: [], v: [] }, c: { t: [], v: [] } } }))).toEqual(['a', 'b'])
  })
  it('joins signals on one axis in seconds: a gap stays null, a missing sample is undefined', () => {
    const s = series({
      numbers: {
        a: { t: [0, 1000, 1001, 3000], v: [1, 2, null, 4] },
        b: { t: [500, 1000], v: [10, 20] },
      },
    })
    const [x, a, b] = joined(s, ['a', 'b'])
    expect(x).toEqual([0, 500, 1000, 1001, 3000].map((t) => (t0 + t) / 1000))
    expect(a).toEqual([1, undefined, 2, null, 4])
    expect(b).toEqual([undefined, 10, 20, undefined, undefined])
  })
  it('joins a signal it does not have as nothing', () => {
    const [x, a] = joined(series(), ['missing'])
    expect(x).toEqual([])
    expect(a).toEqual([])
  })
  it('marks faults, gaps and stopped signals, in time order', () => {
    const e = events(series({
      events: {
        stopped: [[3000, 'engine.oil_temperature', 'it stopped answering']],
        fault: [[1000, ['P0301', 'P0420']], [5000, []]],
        gap: [[2000, 1], [4000, 12]],
        lap: [],
      },
    }))
    expect(e.map((m) => m.kind)).toEqual(['fault', 'gap', 'stopped', 'gap', 'fault'])
    expect(e.map((m) => m.text)).toEqual([
      'Trouble codes: P0301, P0420', 'The tablet missed 1 reading', 'engine.oil_temperature stopped: it stopped answering',
      'The tablet missed 12 readings', 'Trouble codes cleared',
    ])
    expect(e[0]!.t).toBe((t0 + 1000) / 1000)
  })
})

describe('the map', () => {
  it('finds the nearest time, the earlier on a tie', () => {
    expect(nearest([], 5)).toBe(-1)
    expect(nearest([10, 20, 30], 0)).toBe(0)
    expect(nearest([10, 20, 30], 24)).toBe(1)
    expect(nearest([10, 20, 30], 26)).toBe(2)
    expect(nearest([10, 20, 30], 25)).toBe(1)
    expect(nearest([10, 20, 30], 99)).toBe(2)
  })
  it('colours by GPS speed, else the car speed, grey where unknown', () => {
    const withGps = series({
      numbers: { 'gps.speed': { t: [0, 1000], v: [50, 150] }, 'vehicle.speed': { t: [0], v: [1] } },
      positions: { t: [100, 900], lat: [1, 2], lon: [1, 2] },
    })
    expect(speedSignal(withGps)).toBe('gps.speed')
    expect(speedsAtPositions(withGps)).toEqual([50, 150])
    expect(speedSignal(series({ numbers: { 'vehicle.speed': { t: [0], v: [1] } } }))).toBe('vehicle.speed')
    expect(speedsAtPositions(series({ positions: { t: [1], lat: [1], lon: [1] } }))).toEqual([null])
    const stops = ['#000000', '#ffffff', '#ff0000']
    expect(speedColor(0, 0, 100, stops)).toBe('#000000')
    expect(speedColor(25, 0, 100, stops)).toBe('#808080') // halfway to the second stop
    expect(speedColor(50, 0, 100, stops)).toBe('#ffffff')
    expect(speedColor(75, 0, 100, stops)).toBe('#ff8080')
    expect(speedColor(100, 0, 100, stops)).toBe('#ff0000')
    expect(speedColor(200, 0, 100, stops)).toBe('#ff0000') // past the top: the top
    expect(speedColor(-5, 0, 100, stops)).toBe('#000000')
    expect(speedColor(50, 50, 50, stops)).toBe('#ffffff') // one speed only: the middle
    expect(speedColor(null, 0, 100, stops)).toBeNull()
    expect(speedColor(10, 0, 100, [])).toBeNull()
    expect(speedColor(10, 0, 100, ['#ABCDEF'])).toBe('#abcdef')
  })
})

describe('fetching the series', () => {
  const answering = (status: number) => (async () => new Response(status === 204 ? null : '{}', { status })) as unknown as typeof fetch
  it('is none while there is nothing, 204 (M11) or 404, and an error otherwise', async () => {
    expect(await fetchSeries('s', answering(204))).toBeNull()
    expect(await fetchSeries('s', answering(404))).toBeNull()
    await expect(fetchSeries('s', answering(500))).rejects.toThrow('500')
  })
})
