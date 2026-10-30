import { describe, expect, it } from 'vitest'
import { createSignIn } from './signin'
import { saveSessionDriver } from './driverChanges'
import { addSession, codeFrom, fromLocalInput, heardText, newPart, removeSession, saveBody, setSessionName, toLocalInput, whoCanSet, type Part } from './events'

const part = (id: string, added: string[] = [], removed: string[] = []): Part =>
  ({ id, kind: 'practice', name: id, start: 0, end: 1, added, removed })

describe('an event in the editor (M14.3)', () => {
  it('times go to the input and back in the viewer’s zone', () => {
    const ms = new Date(2026, 9, 4, 9, 5).getTime()
    expect(toLocalInput(ms)).toBe('2026-10-04T09:05')
    expect(fromLocalInput('2026-10-04T09:05')).toBe(ms)
    expect(fromLocalInput('2026-10-04T09:05:30')).toBe(ms + 30_000) // with seconds, as the race's flags have them
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

  it('another session of the race is the next morning’s, from 9:00, named in turn (M26)', () => {
    const saturday = { ...newPart([], 'race', '2026-10-03'), start: fromLocalInput('2026-10-03T12:00')!, end: fromLocalInput('2026-10-03T18:00')! }
    const sunday = newPart([saturday], 'race', '2026-10-03')
    expect(sunday.name).toBe('Race, session 2')
    expect(sunday.start).toBe(fromLocalInput('2026-10-04T09:00'))
    expect(sunday.end - sunday.start).toBe(6 * 3_600_000)
    expect(newPart([saturday, sunday], 'race', '2026-10-03').name).toBe('Race, session 3')
    // A practice still follows on where the last part ended.
    expect(newPart([saturday], 'practice', '2026-10-03').start).toBe(saturday.end)
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
    expect(saveBody(e, 3)).toEqual({ expected: 3, ...e, endDate: null })
    expect(saveBody({ ...e, endDate: '2026-10-06' }, 3).endDate).toBe('2026-10-06') // over several days (M26)
    expect(saveBody({ ...e, endDate: '' }, 3).endDate).toBeNull()
  })

  it('a driver’s code from their name, and when a session was heard', () => {
    expect(codeFrom('Sam Voigt')).toBe('SAM')
    expect(codeFrom('Él')).toBe('EL')
    const from = Date.UTC(2026, 9, 4, 13, 5)
    expect(heardText({ id: 's', car: 'c', heardFrom: from, heardTo: from + 35 * 60_000, laps: 0 }, 'UTC', Date.UTC(2026, 9, 29))).toBe('Sun 4 Oct, 13:05–13:40')
  })
})

describe('who drove (M14.4)', () => {
  const seen: { url: string; init?: RequestInit }[] = []
  const answering = (answers: Record<string, [number, unknown?]>) =>
    (async (url: string, init?: RequestInit) => {
      seen.push({ url, init })
      const [status, body] = answers[url] ?? [404, { message: 'nope' }]
      return new Response(body === undefined ? null : JSON.stringify(body), { status })
    }) as unknown as typeof fetch

  it('the admin through the admin\u2019s path, the crew through the car\u2019s', async () => {
    seen.length = 0
    const ok = answering({ '/api/admin/sessions/s1/driver': [200, {}], '/api/cars/outback/sessions/s1/driver': [200, {}] })
    await saveSessionDriver('admin', 'outback', 's1', 'd-sam', ok)
    await saveSessionDriver('crew', 'outback', 's1', null, ok)
    expect(seen.map((s) => s.url)).toEqual(['/api/admin/sessions/s1/driver', '/api/cars/outback/sessions/s1/driver'])
    expect(seen[0]!.init!.method).toBe('PUT')
    expect(seen[0]!.init!.body).toBe('{"driver":"d-sam"}')
    expect(seen[1]!.init!.body).toBe('{"driver":null}')
    await expect(saveSessionDriver('crew', 'yaris', 's1', 'd-sam', ok)).rejects.toThrow('nope')
  })

  it('a name through the same two paths, the name as stored back (M18.3)', async () => {
    seen.length = 0
    const ok = answering({ '/api/admin/sessions/s1/name': [200, { name: 'Wet' }], '/api/cars/outback/sessions/s1/name': [200, { name: null }] })
    expect(await setSessionName('admin', 'outback', 's1', '  Wet ', ok)).toBe('Wet')
    expect(await setSessionName('crew', 'outback', 's1', null, ok)).toBeNull()
    expect(seen.map((s) => s.url)).toEqual(['/api/admin/sessions/s1/name', '/api/cars/outback/sessions/s1/name'])
    expect(seen[0]!.init!.body).toBe('{"name":"  Wet "}')
    await expect(setSessionName('crew', 'yaris', 's1', 'x', ok)).rejects.toThrow('nope')
  })

  it('who may set it: the admin first, else the car\u2019s crew, else nobody', async () => {
    const ask = (answers: Parameters<typeof answering>[0]) => {
      const fetcher = answering({ '/api/admin/config': [200, { enabled: true, googleClientId: null, dev: true }], ...answers })
      return whoCanSet('outback', fetcher, createSignIn(fetcher))
    }
    expect(await ask({ '/api/admin/me': [200, { email: 'a', role: 'master' }] })).toBe('admin')
    // A user (M23): through the admin API only for a car they may edit; else as its crew, or not at all.
    const access = { creator: 'a', editors: [], canShare: true, canDelete: true }
    const user: Record<string, [number, unknown?]> = { '/api/admin/me': [200, { email: 'a', role: 'user' }] }
    expect(await ask({ ...user, '/api/admin/cars': [200, [{ slug: 'outback', access }]] })).toBe('admin')
    expect(await ask({ ...user, '/api/admin/cars': [200, []], '/api/cars/outback/crew': [200, { crew: true }] })).toBe('crew')
    expect(await ask({ ...user, '/api/admin/cars': [200, []], '/api/cars/outback/crew': [200, { crew: false }] })).toBeNull()
    expect(await ask({ '/api/admin/me': [401], '/api/cars/outback/crew': [200, { crew: true }] })).toBe('crew')
    expect(await ask({ '/api/admin/me': [401], '/api/cars/outback/crew': [200, { crew: false }] })).toBeNull()
  })
})
