import { describe, expect, it } from 'vitest'
import { addSession, codeFrom, fromLocalInput, heardText, newPart, removeSession, saveBody, toLocalInput, type Part } from './events'

const part = (id: string, added: string[] = [], removed: string[] = []): Part =>
  ({ id, kind: 'practice', name: id, start: 0, end: 1, added, removed })

describe('an event in the editor (M14.3)', () => {
  it('times go to the input and back in the viewer’s zone', () => {
    const ms = new Date(2026, 9, 4, 9, 5).getTime()
    expect(toLocalInput(ms)).toBe('2026-10-04T09:05')
    expect(fromLocalInput('2026-10-04T09:05')).toBe(ms)
    expect(fromLocalInput('')).toBeNull()
    expect(fromLocalInput('4 Oct')).toBeNull()
  })

  it('a new part follows the last, or starts at 9:00 on the day', () => {
    const first = newPart([], 'practice', '2026-10-04')
    expect(first.name).toBe('Practice 1')
    expect(first.start).toBe(fromLocalInput('2026-10-04T09:00'))
    expect(first.end - first.start).toBe(3_600_000)
    const race = newPart([first], 'race', '2026-10-04')
    expect(race.name).toBe('Race')
    expect(race.start).toBe(first.end)
    expect(race.end - race.start).toBe(6 * 3_600_000)
    expect(newPart([first, race], 'practice', '2026-10-04').name).toBe('Practice 2')
    expect(first.id).toBeUndefined() // the server gives it one
  })

  it('a session added by hand is in that part only, and no longer removed from it', () => {
    const parts = addSession([part('p1', ['s']), part('p2', [], ['s'])], 'p2', 's')
    expect(parts).toEqual([part('p1'), part('p2', ['s'])])
    expect(addSession(parts, 'p2', 's')).toEqual(parts) // once
  })

  it('a session taken out: added by hand, no longer; caught by the window, removed', () => {
    expect(removeSession([part('p1', ['s'])], 'p1', 's')).toEqual([part('p1')])
    expect(removeSession([part('p1'), part('p2')], 'p1', 's')).toEqual([part('p1', [], ['s']), part('p2')])
    expect(removeSession([part('p1', [], ['s'])], 'p1', 's')).toEqual([part('p1', [], ['s'])])
  })

  it('what a save sends', () => {
    const e = { name: 'NHMS', date: '2026-10-04', course: 'nhms', layout: 'road', cars: ['outback'], parts: [part('p1')] }
    expect(saveBody(e, 3)).toEqual({ expected: 3, ...e })
  })

  it('a driver’s code from their name, and when a session was heard', () => {
    expect(codeFrom('Sam Voigt')).toBe('SAM')
    expect(codeFrom('Él')).toBe('EL')
    const from = Date.UTC(2026, 9, 4, 13, 5)
    expect(heardText({ id: 's', car: 'c', heardFrom: from, heardTo: from + 35 * 60_000, laps: 0 }, 'en-GB', 'UTC')).toBe('4 Oct, 13:05–13:40')
  })
})
