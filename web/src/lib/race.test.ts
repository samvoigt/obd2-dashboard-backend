import { describe, expect, it } from 'vitest'
import { flagsAt, lapsIn, raceClock, racePath, sendRaceEdit, sessionStartingAt, stintsIn, timeOfDay, type CarRace, type RaceLap } from './race'

const lap = (number: number, start: number): RaceLap =>
  ({ number, session: 's', start, end: start + 70_000, time: 70, sectors: [], pitIn: false, pitOut: false, source: 'tablet', stint: 1 })
const laps = [lap(1, 0), lap(2, 70_000), lap(3, 140_000), lap(4, 210_000)]

describe('the race (M15.4)', () => {
  it('a race’s length, and a tablet’s moment as the time of day', () => {
    expect(raceClock(21_735.4)).toBe('6:02:15')
    expect(raceClock(59)).toBe('0:00:59')
    expect(timeOfDay(1_000, Date.UTC(2026, 9, 4, 13, 0, 0) - 1_000, 'en-GB', 'UTC')).toBe('13:00:00')
    expect(timeOfDay(1_000, undefined)).toBe('')
  })

  it('the admin’s path for the flags and the crew’s', () => {
    expect(racePath('admin', 'race-day', 'outback')).toBe('/api/admin/events/race-day/race')
    expect(racePath('crew', 'race-day', 'outback')).toBe('/api/cars/outback/events/race-day/race')
  })

  it('an edit answers the new revision, or every problem', async () => {
    const answer = (status: number, body: unknown) => (async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch
    expect(await sendRaceEdit('/x', {}, answer(200, { revision: 4 }))).toBe(4)
    await expect(sendRaceEdit('/x', {}, answer(400, { message: 'No.', problems: ['the flag falls after the green flag'] }))).rejects.toThrow('No. the flag falls after the green flag')
    await expect(sendRaceEdit('/x', {}, answer(409, { message: 'Someone changed this event' }))).rejects.toThrow('Someone changed')
  })
})

describe('a race in sessions (M26)', () => {
  // Saturday: laps 1–3, Sam; Sunday: laps 4–5, Alex.
  const weekend = {
    car: 'outback', laps: [...laps.slice(0, 3), lap(4, 60_000_000), lap(5, 60_070_000)], stops: [], seconds: 350,
    stints: [{ number: 1, driver: 'd-sam', start: 0, firstLap: 1, lastLap: 3, laps: 3, seconds: 210 }, { number: 2, driver: 'd-alex', start: 60_000_000, firstLap: 4, lastLap: 5, laps: 2, seconds: 140 }],
    greenLap: 1, flagLap: null,
    sessions: [{ part: 'p2', firstLap: 1, laps: 3, seconds: 210, greenLap: 1, flagLap: 3 }, { part: 'p3', firstLap: 4, laps: 2, seconds: 140, greenLap: 4 }],
  } as CarRace

  it('each session’s laps and stints', () => {
    expect(lapsIn(weekend, 'p2').map((l) => l.number)).toEqual([1, 2, 3])
    expect(lapsIn(weekend, 'p3').map((l) => l.number)).toEqual([4, 5])
    expect(stintsIn(weekend, 'p3').map((s) => s.driver)).toEqual(['d-alex'])
    // A session the car missed has none; a race in one is all of it.
    expect(lapsIn({ ...weekend, sessions: [...weekend.sessions!, { part: 'p4', laps: 0, seconds: 0 }] }, 'p4')).toEqual([])
    expect(lapsIn({ ...weekend, sessions: [weekend.sessions![0]!] }, 'p2')).toHaveLength(5)
  })

  it('where each session began, and every session’s flags marked', () => {
    expect(sessionStartingAt(weekend, 4)).toBe('p3')
    expect(sessionStartingAt(weekend, 1)).toBe('p2')
    expect(sessionStartingAt(weekend, 2)).toBeNull()
    expect(sessionStartingAt({ ...weekend, sessions: [weekend.sessions![0]!] }, 1)).toBeNull() // a race in one: nothing to mark
    expect(flagsAt(weekend, 3)).toEqual({ green: false, flag: true })
    expect(flagsAt(weekend, 4)).toEqual({ green: true, flag: false })
    // Before M26, a car's race has no sessions: its own flags.
    expect(flagsAt({ ...weekend, sessions: undefined, flagLap: 5 }, 5)).toEqual({ green: false, flag: true })
  })
})
