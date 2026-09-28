import { describe, expect, it } from 'vitest'
import { marksOf, mergeWithPrevious, raceClock, racePath, sendRaceEdit, setDriver, splitAt, timeOfDay, type CarRace, type RaceLap } from './race'

const lap = (number: number, start: number): RaceLap =>
  ({ number, session: 's', start, end: start + 70_000, time: 70, sectors: [], pitIn: false, pitOut: false, source: 'tablet', stint: 1 })
const laps = [lap(1, 0), lap(2, 70_000), lap(3, 140_000), lap(4, 210_000)]
const marks = [{ start: 0, driver: 'd-sam' }, { start: 140_000, driver: 'd-alex' }]

describe('the race (M15.4)', () => {
  it('a race’s length, and a tablet’s moment as the time of day', () => {
    expect(raceClock(21_735.4)).toBe('6:02:15')
    expect(raceClock(59)).toBe('0:00:59')
    expect(timeOfDay(1_000, Date.UTC(2026, 9, 4, 13, 0, 0) - 1_000, 'en-GB', 'UTC')).toBe('13:00:00')
    expect(timeOfDay(1_000, undefined)).toBe('')
  })

  it('the stints as they stand, to edit', () => {
    const car = { car: 'outback', laps, stops: [], seconds: 280, stints: [{ number: 1, driver: 'd-sam', start: 0, laps: 4, seconds: 280 }, { number: 2, start: 5, laps: 0, seconds: 0 }] } as CarRace
    expect(marksOf(car)).toEqual([{ start: 0, driver: 'd-sam' }, { start: 5, driver: null }])
  })

  it('merge a stint into the one before; the first can’t be', () => {
    expect(mergeWithPrevious(marks, 1)).toEqual([marks[0]])
    expect(mergeWithPrevious(marks, 0)).toEqual(marks)
  })

  it('split at a lap, once, never at the first; name a driver', () => {
    expect(splitAt(marks, laps, 2)).toEqual([marks[0], { start: 70_000, driver: null }, marks[1]])
    expect(splitAt(marks, laps, 3)).toEqual(marks) // a stint starts there already
    expect(splitAt(marks, laps, 1)).toEqual(marks)
    expect(splitAt(marks, laps, 9)).toEqual(marks)
    expect(setDriver(marks, 1, null)).toEqual([marks[0], { start: 140_000, driver: null }])
  })

  it('the admin’s paths and the crew’s', () => {
    expect(racePath('admin', 'race-day', 'outback', 'flags')).toBe('/api/admin/events/race-day/race')
    expect(racePath('admin', 'race-day', 'outback', 'stints')).toBe('/api/admin/events/race-day/race/stints/outback')
    expect(racePath('crew', 'race-day', 'outback', 'flags')).toBe('/api/cars/outback/events/race-day/race')
    expect(racePath('crew', 'race-day', 'outback', 'stints')).toBe('/api/cars/outback/events/race-day/race/stints')
  })

  it('an edit answers the new revision, or every problem', async () => {
    const answer = (status: number, body: unknown) => (async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch
    expect(await sendRaceEdit('/x', {}, answer(200, { revision: 4 }))).toBe(4)
    await expect(sendRaceEdit('/x', {}, answer(400, { message: 'No.', problems: ['the flag falls after the green flag'] }))).rejects.toThrow('No. the flag falls after the green flag')
    await expect(sendRaceEdit('/x', {}, answer(409, { message: 'Someone changed this event' }))).rejects.toThrow('Someone changed')
  })
})
