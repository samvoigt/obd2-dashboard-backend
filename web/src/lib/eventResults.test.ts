import { describe, expect, it } from 'vitest'
import { dateText, driverLabel, gap, sectorGap, windowText, partOn, whereText } from './eventResults'

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
