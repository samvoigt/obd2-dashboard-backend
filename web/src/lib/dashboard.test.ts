import { describe, expect, it } from 'vitest'
import {
  fraction, freshnessOf, G, gTrail, timings, lapSummary, level, needleAngle, NO_PEAKS, peaks, profile, SHOWN, SLOTS, SWEEP, valueOf, zoneBands,
} from './dashboard'
import type { Point } from './live'

const sample = (signal: string, t: number, value: number): Point => ({ t, rec: { type: 'sample', signal, value } })

describe('ranges and zones', () => {
  it('come from the signal, else its unit, else 0 to 100', () => {
    expect(profile('engine.rpm', 'rpm')).toMatchObject({ min: 0, max: 8000, caution: { above: 6000 }, critical: { above: 7000 } })
    expect(profile('engine.coolant_temperature', '°C')).toMatchObject({ min: 40, max: 130 })
    expect(profile('some.other_temperature', '°C')).toEqual({ min: 0, max: 150, caution: undefined, critical: undefined })
    expect(profile('odd.signal', 'furlongs')).toMatchObject({ min: 0, max: 100 })
  })
  it('colour a reading, critical before caution, above or below', () => {
    const rpm = profile('engine.rpm', 'rpm')
    expect(level(5000, rpm)).toBe('normal')
    expect(level(6000, rpm)).toBe('normal') // the line itself is fine
    expect(level(6001, rpm)).toBe('caution')
    expect(level(7500, rpm)).toBe('critical')
    const volts = profile('control_module.voltage', 'V')
    expect(level(12.5, volts)).toBe('normal')
    expect(level(11.9, volts)).toBe('caution')
    expect(level(11.0, volts)).toBe('critical')
    expect(level(null, volts)).toBe('normal')
    expect(level(Number.NaN, volts)).toBe('normal')
  })
  it('place a reading on the dial, held at the ends', () => {
    const p = { min: 0, max: 8000 }
    expect(fraction(4000, p)).toBe(0.5)
    expect(fraction(-5, p)).toBe(0)
    expect(fraction(9000, p)).toBe(1)
    expect(fraction(5, { min: 10, max: 10 })).toBe(0)
    expect(needleAngle(0, p)).toBe(-SWEEP / 2)
    expect(needleAngle(4000, p)).toBe(0)
    expect(needleAngle(8000, p)).toBe(SWEEP / 2)
  })
  it('draw their zones as stretches of the range', () => {
    expect(zoneBands(profile('engine.rpm', 'rpm'))).toEqual([
      { from: 0.75, to: 1, level: 'caution' },
      { from: 0.875, to: 1, level: 'critical' },
    ])
    expect(zoneBands(profile('control_module.voltage', 'V'))).toEqual([ // zones below a line, on a 10–16 V range
      { from: 0, to: (12 - 10) / 6, level: 'caution' },
      { from: 0, to: (11.5 - 10) / 6, level: 'critical' },
    ])
    expect(zoneBands(profile('odd.signal', ''))).toEqual([])
    expect(zoneBands({ min: 10, max: 16, caution: { below: 5 }, critical: { above: 20 } })).toEqual([]) // zones off the scale draw nothing
  })
})

describe('the slots', () => {
  it('show each signal once, and the tiles below leave them out', () => {
    const all = [...SLOTS.gauges, ...SLOTS.numbers, ...SLOTS.bars, ...SLOTS.statuses]
    expect(new Set(all).size).toBe(all.length)
    expect(SHOWN.size).toBe(all.length)
    expect(SLOTS.gauges.length).toBeLessThanOrEqual(4)
    expect(SLOTS.numbers.length).toBeLessThanOrEqual(6)
    expect(SLOTS.bars.length).toBeLessThanOrEqual(4)
  })
})

describe('out of date', () => {
  const every200 = [0, 200, 400, 600, 800].map((t) => sample('engine.rpm', t, 1000))
  it('is judged by the signal’s own usual interval, never under 2 s', () => {
    const latest = every200[4]!.rec
    const timing = timings(every200)
    expect(freshnessOf('engine.rpm', timing, latest, false, 800 + 2000)).toBe('fresh')
    expect(freshnessOf('engine.rpm', timing, latest, false, 800 + 2001)).toBe('stale')
    const every10s = [0, 10_000, 20_000].map((t) => sample('fuel.tank_level', t, 50))
    const slow = timings(every10s)
    expect(freshnessOf('fuel.tank_level', slow, every10s[2]!.rec, false, 20_000 + 50_000)).toBe('fresh')
    expect(freshnessOf('fuel.tank_level', slow, every10s[2]!.rec, false, 20_000 + 50_001)).toBe('stale')
  })
  it('says stopped, or never, or fresh with only the snapshot’s reading', () => {
    const timing = timings(every200)
    expect(freshnessOf('engine.rpm', timing, every200[4]!.rec, true, 900)).toBe('stopped')
    expect(freshnessOf('engine.rpm', timings([]), undefined, false, 900)).toBe('never')
    expect(freshnessOf('engine.rpm', timings([]), { type: 'sample', signal: 'engine.rpm', value: 1 }, false, 99_999)).toBe('fresh')
    expect(freshnessOf('engine.rpm', timings([sample('other', 0, 1)]), undefined, false, 900)).toBe('never')
  })
  it('times every signal in one pass: its last reading and its median gap', () => {
    const t = timings([sample('a', 0, 1), sample('b', 50, 1), sample('a', 100, 1), sample('a', 300, 1), { t: 400, rec: { type: 'fault' } }])
    expect(t.get('a')).toEqual({ last: 300, usual: 200 })
    expect(t.get('b')).toEqual({ last: 50, usual: 0 })
    expect(t.has('fault')).toBe(false)
  })
  it('reads numbers only', () => {
    expect(valueOf({ value: 3.5 })).toBe(3.5)
    expect(valueOf({ value: 'x' })).toBeNull()
    expect(valueOf(undefined)).toBeNull()
  })
})

describe('the G-meter', () => {
  it('pairs lateral and longitudinal, in g, over the last seconds', () => {
    const h = [
      sample('motion.acceleration.lateral', 0, G),
      sample('motion.acceleration.longitudinal', 100, -2 * G),
      sample('engine.rpm', 150, 3000),
      sample('motion.acceleration.lateral', 6000, -0.5 * G),
    ]
    expect(gTrail(h, 6000, 5)).toEqual([{ t: 6000, lat: -0.5, lon: -2 }])
    expect(gTrail(h, 6000, 10)).toEqual([{ t: 100, lat: 1, lon: -2 }, { t: 6000, lat: -0.5, lon: -2 }])
  })
  it('keeps the largest each way', () => {
    const p = peaks(NO_PEAKS, [{ lat: 0.8, lon: 0.3 }, { lat: -1.1, lon: -0.9 }])
    expect(p).toEqual({ right: 0.8, left: 1.1, accel: 0.3, brake: 0.9 })
    expect(peaks(p, [{ lat: 0.5, lon: 0 }])).toEqual(p)
  })
})

describe('the lap panel', () => {
  it('shows the last lap, the best, and the difference', () => {
    expect(lapSummary([{ lap: 1, time: 96, best: false }, { lap: 3, time: 95.2, best: false }, { lap: 2, time: 94.5, best: true }]))
      .toEqual({ last: 95.2, lastLap: 3, best: 94.5, bestLap: 2, delta: 95.2 - 94.5 })
    expect(lapSummary([])).toEqual({ last: null, lastLap: null, best: null, bestLap: null, delta: null })
  })
})
