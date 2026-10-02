/**
 * Who's signed in (M21.1): the admin's Google sign-in, asked once for the
 * whole page and held here, so the header and every page agree. Signed in,
 * pages show their edits; the server still checks every change (decision 25).
 */
import { get, writable, type Readable } from 'svelte/store'

export interface SignInConfig {
  enabled: boolean
  googleClientId: string | null
  dev: boolean
  /** The dev server signs in a user too (M23), as `dev-user`. */
  devUser?: boolean
}

/** A master admin (the allowlist) or an invited user (M23). */
export type Role = 'master' | 'user'

export type SignInState =
  | { state: 'unknown' }
  | { state: 'out'; config: SignInConfig | null }
  | { state: 'in'; email: string; role: Role; config: SignInConfig | null }

export interface SignIn extends Readable<SignInState> {
  /** Asks the server once: its sign-in setup, and whether this browser is signed in. */
  check(): Promise<void>
  /** A Google credential, or `dev` on the dev server. */
  signIn(credential: string): Promise<void>
  signOut(): Promise<void>
  /** A call answered `401`: the sign-in lapsed, or was taken off the allowlist. */
  lapsed(): void
}

/** `GET /api/admin/me` (M23): the role, which an older server leaves out. */
interface Me {
  email: string
  role?: string
}

async function json<T>(response: Response | null): Promise<T | null> {
  return response?.ok ? ((await response.json().catch(() => null)) as T | null) : null
}

export function createSignIn(fetcher: typeof fetch = (...a) => fetch(...a)): SignIn {
  const store = writable<SignInState>({ state: 'unknown' })
  let config: SignInConfig | null = null
  let checking: Promise<void> | null = null

  const out = () => store.set({ state: 'out', config })
  const signedIn = (me: Me): SignInState => ({ state: 'in', email: me.email, role: me.role === 'master' ? 'master' : 'user', config })

  async function check() {
    checking ??= (async () => {
      config = await json<SignInConfig>(await fetcher('/api/admin/config').catch(() => null))
      const me = config?.enabled ? await json<Me>(await fetcher('/api/admin/me').catch(() => null)) : null
      store.set(me ? signedIn(me) : { state: 'out', config })
    })()
    return checking
  }

  async function signIn(credential: string) {
    const response = await fetcher('/api/admin/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ credential }),
    })
    const loggedIn = await json<{ email: string }>(response)
    if (!loggedIn) {
      const body = (await response.json().catch(() => ({}))) as { message?: string }
      throw new Error(body.message || `The server answered ${response.status}.`)
    }
    // The role comes from `me`, which every request answers afresh.
    const me = await json<Me>(await fetcher('/api/admin/me').catch(() => null))
    store.set(signedIn(me ?? { email: loggedIn.email }))
  }

  async function signOut() {
    await fetcher('/api/admin/login', { method: 'DELETE' }).catch(() => null)
    out()
  }

  function lapsed() {
    if (get(store).state === 'in') out()
  }

  return { subscribe: store.subscribe, check, signIn, signOut, lapsed }
}

/** The page's one sign-in. */
export const signin = createSignIn()

/** Whether [s] is signed in: what pages ask before showing an edit. */
export function isSignedIn(s: SignInState): s is Extract<SignInState, { state: 'in' }> {
  return s.state === 'in'
}

/** Whether [s] is a master admin, who may do anything and manage users. */
export function isMaster(s: SignInState): boolean {
  return s.state === 'in' && s.role === 'master'
}
