import { describe, expect, it } from 'vitest'
import {
  applyRecords, applySession, applySnapshot, applyStatus, applyTiming, defaultChart, format, freshness, label,
  LIVE_WITHIN_MS, numericSignals, series, WINDOW_MS, type LiveState,
} from './live'

const id = '7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f'
const rpm = (value: number, seq: number) => ({ type: 'sample', signal: 'engine.rpm', value, seq })
const speed = (value: number, seq: number) => ({ type: 'sample', signal: 'vehicle.speed', value, seq })

/** A snapshot at server time 10 000 ms, received at page time 12 000 (a 2 s skew). */
function snap(extra: Record<string, unknown> = {}): LiveState {
  return applySnapshot({
    serverNow: 10_000,
    status: { state: 'live', lastDataAgoMs: 100 },
    session: { type: 'session', id, started: '2026-09-26T12:00:00Z', app: '1.0' },
    signals: [{ name: 'engine.rpm', unit: 'rpm', kind: 'number' }, { name: 'vehicle.speed', unit: 'km/h', kind: 'number' }],
    latest: [rpm(900, 1)],
    stopped: [],
    fault: null,
    history: [{ atMs: 9_000, record: rpm(900, 1) }],
    ...extra,
  }, 12_000)
}

describe('applying events', () => {
  it('a snapshot is the whole truth', () => {
    const s = snap()
    expect(s.state).toBe('live')
    expect(s.offsetMs).toBe(2_000)
    expect(s.latest['engine.rpm']?.value).toBe(900)
    expect(s.history).toHaveLength(1)
    const again = applySnapshot({ serverNow: 20_000, status: { state: 'offline' }, latest: [], history: [] }, 21_000)
    expect(again.latest).toEqual({})
    expect(again.session).toBeNull()
  })

  it('records update latest, stopped, fault and the signals list, and join the history', () => {
    let s = snap()
    s = applyRecords(s, {
      serverNow: 10_200, atMs: 10_200,
      records: [
        rpm(1800, 2),
        { type: 'stopped', signal: 'engine.oil_temperature', reason: 'r' },
        { type: 'fault', codes: ['P0301'] },
        { type: 'signals', signals: [{ name: 'fuel.tank_level', unit: '%', kind: 'number' }] },
      ],
    }, 12_200)
    expect(s.latest['engine.rpm']?.value).toBe(1800)
    expect(s.stopped['engine.oil_temperature']).toBeDefined()
    expect(s.fault?.codes).toEqual(['P0301'])
    expect(s.signals.map((x) => x.name)).toEqual(['fuel.tank_level'])
    expect(s.history).toHaveLength(5)
  })

  it('history keeps five minutes of server time', () => {
    let s = snap()
    s = applyRecords(s, { serverNow: 9_000 + WINDOW_MS, atMs: 9_000 + WINDOW_MS, records: [rpm(1, 2)] }, 0)
    expect(s.history).toHaveLength(2) // exactly five minutes old: kept
    s = applyRecords(s, { serverNow: 9_001 + WINDOW_MS, atMs: 9_001 + WINDOW_MS, records: [rpm(2, 3)] }, 0)
    expect(s.history.map((p) => p.rec.value)).toEqual([1, 2])
  })

  it('the same session again keeps its state; a new one starts afresh', () => {
    let s = snap()
    s = applySession(s, { serverNow: 10_000, session: { id }, signals: [] }, 12_000)
    expect(s.history).toHaveLength(1)
    s = applySession(s, { serverNow: 10_000, session: { id: 'other' }, signals: [] }, 12_000)
    expect(s.history).toHaveLength(0)
    expect(s.latest).toEqual({})
    expect(s.state).toBe('live') // the car's status is not the session's
  })

  it('where the car stands: from the snapshot, replaced by each timing event, kept through a new session (M17.5)', () => {
    let s = snap({ timing: { race: { lap: 3 } } })
    expect(s.standing).toEqual({ race: { lap: 3, leftPits: undefined } })
    s = applyTiming(s, { serverNow: 20_000, timing: { race: { lap: 4, leftPits: 15_000 } } }, 21_000)
    expect(s.standing?.race).toEqual({ lap: 4, leftPits: 15_000 })
    expect(s.offsetMs).toBe(1_000)
    s = applySession(s, { serverNow: 20_000, session: { id: 'other' }, signals: [] }, 21_000)
    expect(s.standing?.race?.lap).toBe(4)
    expect(applyTiming(s, { serverNow: 20_000, timing: null }, 21_000).standing).toBeNull()
    expect(snap().standing).toBeNull()
  })
})

describe('freshness, on the page\'s own clock', () => {
  it('stays live within two seconds and turns stale by itself after', () => {
    const s = applyStatus(snap(), { state: 'live', lastDataAgoMs: 0 }, 50_000)
    expect(freshness(s, 50_000 + LIVE_WITHIN_MS)).toEqual({ state: 'live', seconds: 0 })
    expect(freshness(s, 50_000 + LIVE_WITHIN_MS + 1)).toEqual({ state: 'stale', seconds: 2 })
    expect(freshness(s, 50_000 + 40_000)).toEqual({ state: 'stale', seconds: 40 })
  })

  it('counts on from the server\'s stale age', () => {
    const s = applyStatus(snap(), { state: 'stale', lastDataAgoMs: 12_000 }, 1_000)
    expect(freshness(s, 6_000)).toEqual({ state: 'stale', seconds: 17 })
  })

  it('offline and no session are as the server says', () => {
    expect(freshness(applyStatus(snap(), { state: 'offline' }, 0), 99_999)).toEqual({ state: 'offline', seconds: null })
    expect(freshness(applyStatus(snap(), { state: 'no_session' }, 0), 5)).toEqual({ state: 'no_session', seconds: null })
  })
})

describe('the chart', () => {
  it('places samples on the page\'s clock, carries values between samples, and breaks at a gap', () => {
    let s = snap()
    s = applyRecords(s, { serverNow: 10_000, atMs: 10_000, records: [speed(50, 2)] }, 12_000)
    s = applyRecords(s, { serverNow: 10_200, atMs: 10_200, records: [{ type: 'gap', missed: 3 }] }, 12_200)
    s = applyRecords(s, { serverNow: 10_400, atMs: 10_400, records: [rpm(2000, 5)] }, 12_400)
    const [x, r, v] = series(s, ['engine.rpm', 'vehicle.speed'])
    expect(x).toEqual([11, 12, 12.2, 12.4]) // server ms + 2 s offset, in seconds
    expect(r).toEqual([900, 900, null, 2000])
    expect(v).toEqual([null, 50, null, null]) // the gap resets what is carried
  })

  it('offers numeric signals, preferring engine and road speed', () => {
    const s = snap()
    expect(numericSignals(s)).toEqual(['engine.rpm', 'vehicle.speed'])
    expect(defaultChart(s)).toEqual(['engine.rpm', 'vehicle.speed'])
    const other = snap({ signals: [{ name: 'fuel.tank_level', unit: '%', kind: 'number' }, { name: 'diagnostics.mil', unit: '', kind: 'flag' }], latest: [] })
    expect(defaultChart(other)).toEqual(['fuel.tank_level'])
  })
})

describe('formatting a reading by its kind', () => {
  it('numbers with their unit and sensible places', () => {
    expect(format({ value: 1816.5 }, 'rpm')).toBe('1817 rpm')
    expect(format({ value: 13.84 }, 'V')).toBe('13.8 V')
    expect(format({ value: 0.25 }, '')).toBe('0.25')
  })
  it('states, flags, flag sets and positions', () => {
    expect(format({ code: 2, text: 'Closed loop' })).toBe('Closed loop')
    expect(format({ flag: true })).toBe('On')
    expect(format({ flag: false })).toBe('Off')
    expect(format({ flags: [] })).toBe('None')
    expect(format({ flags: ['Catalyst', 'Misfire'] })).toBe('Catalyst, Misfire')
    expect(format({ lat: 47.6205, lon: -122.3493 })).toBe('47.62050, -122.34930')
    expect(format(undefined)).toBe('—')
  })
  it('labels keep the name, only made readable', () => {
    expect(label('engine.coolant_temperature')).toBe('engine · coolant temperature')
  })
})
