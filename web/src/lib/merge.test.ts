import { describe, expect, it } from 'vitest'
import { BREAK_MS, merge } from './merge'
import type { Series } from './sessionPage'

const t0 = 1_790_000_000_000

function archived(lastSeq: number, rpm: { t: number[]; v: (number | null)[] }): Series {
  return {
    version: 2, t0, lastSeq, signals: [{ name: 'engine.rpm', unit: 'rpm', kind: 'number' }],
    numbers: { 'engine.rpm': rpm }, states: {}, sets: {},
    positions: { t: [], lat: [], lon: [] },
    events: { stopped: [], fault: [], gap: [], lap: [] },
  }
}

const rpm = (seq: number, t: number, value: number) => ({ type: 'sample', signal: 'engine.rpm', value, seq, at: t, wall: t0 + t })

describe('merging the archive with the live lane by seq', () => {
  it('appends only what the archive does not cover, and marks it provisional', () => {
    const series = archived(20, { t: [0, 1000, 2000], v: [1, 2, 3] })
    const m = merge(series, [rpm(19, 1900, 99), rpm(20, 2000, 99), rpm(21, 3000, 4), rpm(22, 4000, 5)])
    expect(m.series.numbers['engine.rpm']).toEqual({ t: [0, 1000, 2000, 3000, 4000], v: [1, 2, 3, 4, 5] })
    expect(m.provisionalFrom).toBe(t0 + 3000)
    expect(m.series.lastSeq).toBe(22)
    expect(series.numbers['engine.rpm']!.t).toEqual([0, 1000, 2000]) // the fetched file is never changed
  })
  it('is the archive alone once the archive has caught up', () => {
    const series = archived(30, { t: [0, 1000], v: [1, 2] })
    const m = merge(series, [rpm(21, 3000, 4), rpm(30, 4000, 5)])
    expect(m.series).toBe(series)
    expect(m.provisionalFrom).toBeNull()
  })
  it('keeps each seq once, in seq order, whatever order the live records came in', () => {
    const m = merge(archived(0, { t: [], v: [] }), [rpm(3, 3000, 3), rpm(1, 1000, 1), rpm(3, 3000, 3), rpm(2, 2000, 2)])
    expect(m.series.numbers['engine.rpm']).toEqual({ t: [1000, 2000, 3000], v: [1, 2, 3] })
  })
  it('breaks the line where archive and live do not meet, or within the live part', () => {
    const m = merge(archived(20, { t: [0, 1000], v: [1, 2] }), [rpm(21, 1000 + BREAK_MS + 1, 3), rpm(22, 1000 + BREAK_MS + 500, 4)])
    expect(m.series.numbers['engine.rpm']).toEqual({ t: [0, 1000, 1001, 6001, 6500], v: [1, 2, null, 3, 4] })
    const close = merge(archived(20, { t: [0, 1000], v: [1, 2] }), [rpm(21, 1000 + BREAK_MS, 3)])
    expect(close.series.numbers['engine.rpm']!.v).toEqual([1, 2, 3]) // exactly the limit: no break
  })
  it('builds the whole session from live records when nothing is archived yet', () => {
    const m = merge(null, [rpm(5, 1000, 1), rpm(6, 1200, 2)], [{ name: 'engine.rpm', unit: 'rpm', kind: 'number' }])
    expect(m.series.t0).toBe(t0 + 1000)
    expect(m.series.numbers['engine.rpm']).toEqual({ t: [0, 200], v: [1, 2] })
    expect(m.series.signals.map((s) => s.name)).toEqual(['engine.rpm'])
    expect(m.provisionalFrom).toBe(t0 + 1000)
    expect(merge(null, []).series.numbers).toEqual({})
  })
  it('takes flags, positions, faults, gaps, stopped signals and laps from the live part', () => {
    const m = merge(archived(0, { t: [], v: [] }), [
      { type: 'sample', signal: 'diagnostics.mil', flag: true, seq: 1, wall: t0 + 100 },
      { type: 'sample', signal: 'gps.position', lat: 43.36, lon: -71.46, seq: 2, wall: t0 + 200 },
      { type: 'fault', codes: ['P0301'], seq: 3, wall: t0 + 300 },
      { type: 'gap', missed: 4, seq: 4, wall: t0 + 400 },
      { type: 'stopped', signal: 'engine.oil_temperature', reason: 'gone', seq: 5, wall: t0 + 500 },
      { type: 'lap', lap: 3, time: 94.5, pitIn: true, seq: 6, wall: t0 + 600 },
      { type: 'weather', seq: 7, wall: t0 + 700 },
      { type: 'lap', lap: 4, seq: 8, wall: t0 + 800 }, // no time: not a lap to show
    ])
    expect(m.series.numbers['diagnostics.mil']).toEqual({ t: [100], v: [1] })
    expect(m.series.positions).toEqual({ t: [200], lat: [43.36], lon: [-71.46] })
    expect(m.series.events.fault).toEqual([[300, ['P0301']]])
    expect(m.series.events.gap).toEqual([[400, 4]])
    expect(m.series.events.stopped).toEqual([[500, 'engine.oil_temperature', 'gone']])
    expect(m.series.events.lap).toEqual([[600, expect.objectContaining({ lap: 3, time: 94.5, pitIn: true })]])
    expect(m.series.lastSeq).toBe(8)
  })
  it('leaves out live records it cannot place, and a reading from before what is shown', () => {
    const m = merge(archived(20, { t: [0, 5000], v: [1, 2] }), [
      { type: 'sample', signal: 'engine.rpm', value: 9, seq: 21 }, // no wall
      { type: 'sample', signal: 'engine.rpm', value: 9, wall: t0 + 6000 }, // no seq
      rpm(22, 4000, 9), // after the archive by seq, but earlier in time than its last reading
      rpm(23, 6000, 3),
    ])
    expect(m.series.numbers['engine.rpm']).toEqual({ t: [0, 5000, 6000], v: [1, 2, 3] })
  })
})
