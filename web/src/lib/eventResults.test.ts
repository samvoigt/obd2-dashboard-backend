import { describe, expect, it } from 'vitest'
import { dateText, driverLabel, gap, sectorGap, sessionRows, windowText, partOn, whereText, type SessionResult } from './eventResults'

describe('an event’s results (M14.5)', () => {
  it('the gap to the fastest, to the millisecond', () => {
    expect(gap(70.9, 70)).toBe('+0.900')
    expect(gap(70, 70)).toBe('')
    expect(gap(70.0004, 70)).toBe('')
  })
  it('a driver, or none set', () => {
    expect(driverLabel({ id: 'd', name: 'Sam Voigt', code: 'SAM' })).toBe('Sam Voigt (SAM)')
    expect(driverLabel(null)).toBe('Driver not set')
  })
  it('a part’s window', () => {
    const start = Date.UTC(2026, 9, 4, 13, 0)
    expect(windowText({ id: 'p1', kind: 'practice', name: 'P1', start, end: start + 3_600_000 }, 'UTC', Date.UTC(2026, 9, 29))).toBe('Sun 4 Oct, 13:00–14:00')
  })
  it('a sector\u2019s gap to the best of all', () => {
    expect(sectorGap(0.1234)).toBe('+0.123')
    expect(sectorGap(0)).toBe('best')
    expect(sectorGap(null)).toBe('')
  })
})

describe('the page refreshing itself (M17.6)', () => {
  it('while a part is on, from its start up to its end', () => {
    const parts = [{ start: 1_000, end: 2_000 }, { start: 5_000, end: 6_000 }]
    expect(partOn(parts, 999)).toBe(false)
    expect(partOn(parts, 1_000)).toBe(true)
    expect(partOn(parts, 1_999)).toBe(true)
    expect(partOn(parts, 2_000)).toBe(false)
    expect(partOn(parts, 5_500)).toBe(true)
    expect(partOn([], 1_500)).toBe(false)
  })
})

describe('where an event is (M25)', () => {
  it('names the course and layout, and nothing for a course hidden from you', () => {
    expect(whereText({ course: 'nhms', courseName: 'NHMS', layoutName: 'Road Course' })).toBe('NHMS, Road Course')
    expect(whereText({ course: '', courseName: '', layoutName: '' })).toBe('')
  })
})

describe('an event’s days (M26)', () => {
  it('one date for one day, a range for several', () => {
    expect(dateText({ date: '2026-10-04' }, Date.UTC(2026, 9, 29))).toBe('Sun 4 Oct')
    expect(dateText({ date: '2026-10-04', endDate: '2026-10-04' }, Date.UTC(2026, 9, 29))).toBe('Sun 4 Oct')
    expect(dateText({ date: '2026-10-02', endDate: '2026-10-04' }, Date.UTC(2026, 9, 29))).toBe('Fri 2 – Sun 4 Oct')
  })
})

describe('one row per session (M37.1)', () => {
  const lap = (n: number, time: number, source: 'tablet' | 'retimed' = 'tablet') =>
    ({ lap: n, start: n * 100_000, end: n * 100_000 + time * 1000, time, sectors: [], pitIn: false, pitOut: false, source }) as SessionResult['laps'][number]
  const sam = { id: 'd-sam', name: 'Sam', code: 'SAM' }
  const alex = { id: 'd-alex', name: 'Alex', code: 'ALX' }
  const row = (id: string, heardFrom: number, more: Partial<SessionResult> = {}): SessionResult =>
    ({ id, car: 'outback', heardFrom, laps: [], ...more })

  it('a session with no driver changes is one row with one stretch', () => {
    const rows = sessionRows([row('a', 1, { driver: sam, laps: [lap(1, 71), lap(2, 70)], best: lap(2, 70) })])
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ id: 'a', laps: 2, best: { time: 70 }, live: false, retimed: false })
    expect(rows[0]!.stretches).toEqual([{ driver: sam, laps: 2, best: lap(2, 70) }])
  })

  it('three stretches fold into one row, the best of all of them; a merged session’s too, under the merge’s id', () => {
    const rows = sessionRows([
      row('m', 5, { driver: sam, laps: [lap(1, 72)], best: lap(1, 72), stretch: 0, name: 'Sunday' }),
      row('a', 1, { driver: alex, laps: [lap(1, 75)], best: lap(1, 75), stretch: 0 }),
      row('m', 5, { driver: alex, laps: [lap(2, 69, 'retimed'), lap(3, 70)], best: lap(2, 69), stretch: 1, name: 'Sunday' }),
      row('m', 5, { driver: sam, laps: [], best: null, stretch: 2, name: 'Sunday' }),
    ])
    expect(rows.map((r) => r.id)).toEqual(['a', 'm'])
    expect(rows[1]).toMatchObject({ name: 'Sunday', laps: 3, best: { time: 69 }, retimed: true })
    expect(rows[1]!.stretches.map((s) => [s.driver?.code, s.laps])).toEqual([['SAM', 1], ['ALX', 2], ['SAM', 0]])
  })

  it('carries live and another layout', () => {
    const rows = sessionRows([row('a', 1, { otherLayout: 'oval' }), row('b', 2, { live: true, stretch: 0 }), row('b', 2, { stretch: 1 })])
    expect(rows.map((r) => [r.otherLayout ?? null, r.live])).toEqual([['oval', false], [null, true]])
  })
})
