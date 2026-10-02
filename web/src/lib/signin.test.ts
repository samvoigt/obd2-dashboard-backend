import { describe, expect, it } from 'vitest'
import { get } from 'svelte/store'
import { createSignIn, isMaster } from './signin'

const config = { enabled: true, googleClientId: 'client', dev: false }

/** A fake server: who's signed in, and every call made. */
function server(signedIn: string | null) {
  let email = signedIn
  const calls: string[] = []
  const fetcher = (async (input: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    calls.push(`${method} ${input}`)
    const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status })
    if (input === '/api/admin/config') return reply(200, config)
    if (input === '/api/admin/me') return email ? reply(200, { email, role: email.startsWith('sam') ? 'master' : 'user' }) : reply(401, { message: 'Sign in first.' })
    if (input === '/api/admin/login' && method === 'POST') {
      const credential = (JSON.parse(String(init?.body)) as { credential: string }).credential
      if (credential !== 'good') return reply(403, { message: 'Not on the list.' })
      email = 'sam@example.com'
      return reply(200, { email })
    }
    if (input === '/api/admin/login' && method === 'DELETE') {
      email = null
      return new Response(null, { status: 204 })
    }
    return reply(404, {})
  }) as typeof fetch
  return { fetcher, calls }
}

describe('who is signed in (M21.1)', () => {
  it('starts unknown, then asks the server once however often it is checked', async () => {
    const s = server('sam@example.com')
    const signin = createSignIn(s.fetcher)
    expect(get(signin)).toEqual({ state: 'unknown' })
    await Promise.all([signin.check(), signin.check()])
    await signin.check()
    expect(get(signin)).toEqual({ state: 'in', email: 'sam@example.com', role: 'master', config })
    expect(s.calls).toEqual(['GET /api/admin/config', 'GET /api/admin/me'])
  })

  it('is out when the server says so, signs in and out', async () => {
    const signin = createSignIn(server(null).fetcher)
    await signin.check()
    expect(get(signin)).toEqual({ state: 'out', config })
    await signin.signIn('good')
    expect(get(signin)).toMatchObject({ state: 'in', email: 'sam@example.com' })
    await signin.signOut()
    expect(get(signin)).toEqual({ state: 'out', config })
  })

  it("a refused sign-in throws the server's words and stays out", async () => {
    const signin = createSignIn(server(null).fetcher)
    await signin.check()
    await expect(signin.signIn('bad')).rejects.toThrow('Not on the list.')
    expect(get(signin).state).toBe('out')
  })

  it('a 401 anywhere signs the page out', async () => {
    const signin = createSignIn(server('sam@example.com').fetcher)
    await signin.check()
    signin.lapsed()
    expect(get(signin).state).toBe('out')
  })

  it('is out, not stuck, when the server is unreachable', async () => {
    const signin = createSignIn((async () => { throw new Error('offline') }) as typeof fetch)
    await signin.check()
    expect(get(signin)).toEqual({ state: 'out', config: null })
  })

  it('holds the role (M23): a master admin, or a user, and a user when an older server leaves it out', async () => {
    const fetcher = (async (input: string) => {
      if (input === '/api/admin/config') return new Response(JSON.stringify(config))
      return new Response(JSON.stringify({ email: 'ann@example.com', role: 'user' }))
    }) as typeof fetch
    const signin = createSignIn(fetcher)
    await signin.check()
    expect(get(signin)).toMatchObject({ state: 'in', role: 'user' })
    expect(isMaster(get(signin))).toBe(false)
    const old = createSignIn((async (input: string) =>
      new Response(JSON.stringify(input === '/api/admin/config' ? config : { email: 'x@example.com' }))) as typeof fetch)
    await old.check()
    expect(get(old)).toMatchObject({ state: 'in', role: 'user' })
  })
})
