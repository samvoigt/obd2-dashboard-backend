import { describe, expect, it } from 'vitest'
import { clockText, dateRange, dayRange, dayText, dayTime } from './terms'

const at = (iso: string) => Date.parse(iso)
const now = at('2026-10-29T12:00:00Z')

describe('one date format (M36.9)', () => {
  it('says a moment as "Sun 18 Oct, 14:14" in the zone asked, the year only when it isn’t this one', () => {
    expect(dayTime(at('2026-10-18T14:14:00Z'), now, 'UTC')).toBe('Sun 18 Oct, 14:14')
    expect(dayTime(at('2026-10-18T18:14:00Z'), now, 'America/New_York')).toBe('Sun 18 Oct, 14:14')
    expect(dayTime(at('2025-09-07T09:05:00Z'), now, 'UTC')).toBe('Sun 7 Sep 2025, 09:05')
    // Midnight is 00, never 24; and the day is the zone's, not UTC's.
    expect(dayTime(at('2026-10-19T00:30:00Z'), now, 'UTC')).toBe('Mon 19 Oct, 00:30')
    expect(dayText(at('2026-10-19T02:00:00Z'), now, 'America/New_York')).toBe('Sun 18 Oct')
    expect(clockText(at('2026-10-18T14:14:59Z'), 'UTC')).toBe('14:14')
  })

  it('says days from one to another, the month once when they share it', () => {
    expect(dayRange(at('2026-10-16T09:00:00Z'), at('2026-10-18T17:00:00Z'), now, 'UTC')).toBe('Fri 16 – Sun 18 Oct')
    expect(dayRange(at('2026-10-30T09:00:00Z'), at('2026-11-01T17:00:00Z'), now, 'UTC')).toBe('Fri 30 Oct – Sun 1 Nov')
    expect(dayRange(at('2026-10-18T09:00:00Z'), at('2026-10-18T17:00:00Z'), now, 'UTC')).toBe('Sun 18 Oct')
    expect(dayRange(at('2025-12-31T09:00:00Z'), at('2026-01-01T09:00:00Z'), now, 'UTC')).toBe('Wed 31 Dec 2025 – Thu 1 Jan 2026')
  })

  it('says an event’s calendar days the same way, wherever the viewer is', () => {
    expect(dateRange('2026-10-16', '2026-10-18', now)).toBe('Fri 16 – Sun 18 Oct')
    expect(dateRange('2026-10-04', null, now)).toBe('Sun 4 Oct')
    expect(dateRange('2026-10-04', '2026-10-04', now)).toBe('Sun 4 Oct')
    expect(dateRange('someday', null, now)).toBe('someday')
  })
})
