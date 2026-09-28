import { describe, expect, it } from 'vitest'
import {
  clockNote,
  AdminError, api, confirmed, day, deleteBlocked, errorText, passcodeProblem, sessionConfirmation, sessionStateText, slugProblem,
  retimingText, stateText, tokenProblem, twiceProblem, when, type AdminSession,
} from './admin'

describe('the rules, as the server words them', () => {
  it('checks a slug', () => {
    for (const good of ['yaris', 'car-42', 'ab', 'a'.repeat(32)]) expect(slugProblem(good)).toBeNull()
    expect(slugProblem('a')).toBe('A slug needs at least 2 characters')
    expect(slugProblem('a'.repeat(33))).toBe('A slug has at most 32 characters')
    for (const bad of ['Yaris', '4runner', 'my car', 'my_car']) {
      expect(slugProblem(bad)).toBe('A slug is lower-case letters, digits and hyphens, starting with a letter')
    }
    expect(slugProblem('admin')).toBe('"admin" is used by the site itself')
  })
  it('checks a chosen token', () => {
    for (const good of ['bears-15', 'Outback.Blue_2026~x', 'a'.repeat(128)]) expect(tokenProblem(good)).toBeNull()
    expect(tokenProblem('bears15')).toBe('A token needs at least 8 characters')
    expect(tokenProblem('a'.repeat(129))).toBe('A token has at most 128 characters')
    expect(tokenProblem('bad bears')).toBe('A token may use only letters, digits and . _ ~ - (no spaces)')
  })
  it('checks a secret typed twice, its own rule first', () => {
    expect(twiceProblem('bears-15', 'bears-15', tokenProblem)).toBeNull()
    expect(twiceProblem('bears-15', 'bears-16', tokenProblem)).toBe("The two don't match")
    expect(twiceProblem('short', 'other', tokenProblem)).toBe('A token needs at least 8 characters')
    expect(passcodeProblem('12345')).toBe('A passcode needs at least 6 characters')
    expect(passcodeProblem('123456')).toBeNull()
  })
  it('confirms only the exact name', () => {
    expect(confirmed('yaris', 'yaris')).toBe(true)
    expect(confirmed(' yaris ', 'yaris')).toBe(true)
    expect(confirmed('Yaris', 'yaris')).toBe(false)
    expect(confirmed('yari', 'yaris')).toBe(false)
    expect(confirmed('', 'yaris')).toBe(false)
  })
})

describe('words for the page', () => {
  it('names states, unknown ones as offline', () => {
    expect(stateText('live')).toBe('Live')
    expect(stateText('no_session')).toBe('Connected, no session')
    expect(stateText('warp')).toBe('Offline')
  })
  it('writes a day', () => {
    expect(day(Date.UTC(2026, 8, 26, 12), 'en-GB', 'UTC')).toBe('26 Sept 2026')
  })
})

describe('sessions', () => {
  const s = (state: AdminSession['state']): AdminSession => ({ id: '3a3b3c3d-3333-4333-8333-33333333abcd', started: 0, lines: 42, state })
  it('names their states', () => {
    expect(sessionStateText('live')).toBe('Live now')
    expect(sessionStateText('uploading')).toBe('Uploading')
    expect(sessionStateText('complete')).toBe('Complete')
    expect(sessionStateText('incomplete')).toBe('Incomplete (upload stopped)')
  })
  it('blocks deleting a live or uploading one, and says why', () => {
    expect(deleteBlocked(s('live'))).toContain('Live')
    expect(deleteBlocked(s('uploading'))).toContain('Still uploading')
    expect(deleteBlocked(s('complete'))).toBeNull()
    expect(deleteBlocked(s('incomplete'))).toBeNull()
  })
  it('asks for the first 8 characters of the id', () => {
    expect(sessionConfirmation('3a3b3c3d-3333-4333-8333-33333333abcd')).toBe('3a3b3c3d')
  })
  it('writes a date and time', () => {
    expect(when(Date.UTC(2026, 8, 26, 14, 5), 'en-GB', 'UTC')).toBe('26 Sept 2026, 14:05')
  })
})

describe('the API', () => {
  const answer = (status: number, body?: unknown) =>
    (async () => new Response(body === undefined ? null : JSON.stringify(body), { status })) as unknown as typeof fetch

  it('returns the body, or nothing for a 204', async () => {
    expect(await api('GET', '/me', undefined, answer(200, { email: 'sam@example.com' }))).toEqual({ email: 'sam@example.com' })
    expect(await api('DELETE', '/login', undefined, answer(204))).toBeUndefined()
  })
  it("throws the server's words, with the status", async () => {
    const error = await api('POST', '/cars', {}, answer(409, { error: 'refused', message: 'A car with slug "yaris" already exists' })).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(AdminError)
    expect((error as AdminError).status).toBe(409)
    expect((error as AdminError).message).toBe('A car with slug "yaris" already exists')
    expect((error as AdminError).problems).toEqual([])
  })
  it('keeps every problem a refused save names (M14.3)', async () => {
    const error = await api('PUT', '/events/x', {}, answer(400, { error: 'invalid', message: 'No.', problems: ['one', 2, 'two'] })).catch((e: unknown) => e)
    expect((error as AdminError).problems).toEqual(['one', 'two'])
  })
  it('sends JSON only when there is a body', async () => {
    const seen: RequestInit[] = []
    const spy = (async (_: string, init: RequestInit) => { seen.push(init); return new Response('{}') }) as unknown as typeof fetch
    await api('GET', '/cars', undefined, spy)
    await api('POST', '/cars', { slug: 'yaris' }, spy)
    expect(seen[0]!.headers).toEqual({})
    expect(seen[1]!.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(seen[1]!.body).toBe('{"slug":"yaris"}')
  })
  it('falls back to the status when the error is not JSON', async () => {
    expect(await errorText(new Response('oops', { status: 502 }))).toBe('The server answered 502.')
  })
})

describe("the tablet's clock (M11)", () => {
  const min = 60_000
  it('is said when more than 2 minutes off, slow or fast', () => {
    expect(clockNote((10 * 60 + 58) * min)).toBe('Tablet clock 10 h 58 min slow') // the first drive's
    expect(clockNote(-3 * min)).toBe('Tablet clock 3 min fast')
    expect(clockNote(2 * 60 * min)).toBe('Tablet clock 2 h slow')
  })
  it('is left unsaid within 2 minutes, or before the tablet has streamed', () => {
    expect(clockNote(2 * min)).toBeNull()
    expect(clockNote(-2 * min)).toBeNull()
    expect(clockNote(40)).toBeNull()
    expect(clockNote(null)).toBeNull()
    expect(clockNote(undefined)).toBeNull()
  })
})


describe('a save\'s re-timing (M13.4)', () => {
  const p = { version: 3, runs: 2, sessions: 5, done: 1, failed: 0, finished: false }
  it('says how far it has got, and when it is done', () => {
    expect(retimingText(p)).toBe('Version 3: re-timing 5 sessions, 1 of 2 runs done…')
    expect(retimingText({ ...p, done: 2, finished: true })).toBe('Version 3: re-timed 5 sessions.')
    expect(retimingText({ ...p, sessions: 1, runs: 1, done: 1, finished: true })).toBe('Version 3: re-timed 1 session.')
  })
  it('says what failed, and when there was nothing to do', () => {
    expect(retimingText({ ...p, done: 2, failed: 1, finished: true })).toBe(
      'Version 3: re-timed 5 sessions; 1 of 2 runs failed, and will be re-timed when viewed.',
    )
    expect(retimingText({ ...p, runs: 0, sessions: 0, done: 0, finished: true })).toBe(
      'Version 3: no sessions were driven here, so nothing to re-time.',
    )
  })
})
