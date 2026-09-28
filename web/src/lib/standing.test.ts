import { describe, expect, it } from 'vitest'
import { duration, readStanding, secondsSince, sectorBests } from './standing'

describe('the car standing (M17.5)', () => {
  it('reads the stream, leaving out what is malformed', () => {
    expect(readStanding({ driver: { name: 'Sam Voigt', code: 'SAM', since: 1000 }, race: { lap: 58, leftPits: 2000 }, best: { time: 94.5, driver: 'SAM' }, bestSectors: [31.2, null], theoretical: 94.2 }))
      .toEqual({ driver: { name: 'Sam Voigt', code: 'SAM', since: 1000 }, race: { lap: 58, leftPits: 2000 }, best: { time: 94.5, driver: 'SAM' }, bestSectors: [31.2, null], theoretical: 94.2 })
    expect(readStanding(null)).toBeNull()
    expect(readStanding('x')).toBeNull()
    // In the pits: a race with no leftPits. A best with no time: none.
    expect(readStanding({ race: { lap: 3 }, best: { driver: 'SAM' } })).toEqual({ race: { lap: 3, leftPits: undefined } })
    expect(readStanding({})).toEqual({})
    expect(readStanding({ race: { leftPits: 5 } })).toEqual({}) // a race with no lap: none
  })

  it('counts on from a moment on the server clock', () => {
    expect(secondsSince(1_000, 62_999)).toBe(61)
    expect(secondsSince(5_000, 1_000)).toBe(0) // never negative
    expect(secondsSince(undefined, 1_000)).toBeNull()
    expect(duration(61)).toBe('1:01')
    expect(duration(3_725)).toBe('1:02:05')
    expect(duration(0)).toBe('0:00')
  })

  it("marks each sector's best: the event's, or the laps shown, never an in-lap's last or an out-lap's first", () => {
    const rows = [
      { sectors: [20, 25, 24], pitIn: false, pitOut: false },
      { sectors: [19, 26, 18], pitIn: true, pitOut: false }, // its last, 18, never counts
      { sectors: [10, 24.5, 25], pitIn: false, pitOut: true }, // its first, 10, never counts
      { sectors: null, pitIn: false, pitOut: false },
    ]
    expect(sectorBests(rows)).toEqual([19, 24.5, 24])
    expect(sectorBests(rows, [18.5, null, 24.2])).toEqual([18.5, 24.5, 24])
    expect(sectorBests([], [18.5, 30])).toEqual([18.5, 30])
    expect(sectorBests([])).toEqual([])
  })
})
