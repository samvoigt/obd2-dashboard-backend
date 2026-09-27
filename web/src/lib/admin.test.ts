import { describe, expect, it } from 'vitest'
import { AdminError, api, confirmed, day, errorText, passcodeProblem, slugProblem, stateText, tokenProblem, twiceProblem } from './admin'

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
