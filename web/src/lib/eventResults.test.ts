import { describe, expect, it } from 'vitest'
import { driverLabel, gap, sectorGap, windowText } from './eventResults'

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
    expect(windowText({ id: 'p1', kind: 'practice', name: 'P1', start, end: start + 3_600_000 }, 'en-GB', 'UTC')).toBe('4 Oct, 13:00–14:00')
  })
  it('a sector\u2019s gap to the best of all', () => {
    expect(sectorGap(0.1234)).toBe('+0.123')
    expect(sectorGap(0)).toBe('best')
    expect(sectorGap(null)).toBe('')
  })
})
