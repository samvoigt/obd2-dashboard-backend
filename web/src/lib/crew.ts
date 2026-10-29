/**
 * **The crew's logins** (M34.3): which cars this browser is logged into as
 * crew, for the header's Crew button and every page that asks. Each car's
 * login is its own cookie, scoped to `/api/cars/{slug}`, so it reaches that
 * car's routes from any page; this only remembers what each car's `/crew`
 * said. The car last logged into is remembered in the browser, to preselect.
 */
import { get, writable, type Readable } from 'svelte/store'

export interface CrewState {
  /** By car: logged in as its crew. A car not asked yet isn't here. */
  cars: Record<string, boolean>
}

export interface Crew extends Readable<CrewState> {
  /** Asks each car whether this browser is its crew. */
  check(slugs: readonly string[]): Promise<void>
  /** Logs into [slug] as crew; the server's words if refused. */
  login(slug: string, passcode: string): Promise<void>
  logout(slug: string): Promise<void>
  /** The car last logged into, if the browser kept it. */
  remembered(): string | null
}

const KEY = 'crew-car'

export function createCrew(fetcher: () => typeof fetch = () => fetch, storage: () => Storage | null = () => globalThis.localStorage ?? null): Crew {
  const state = writable<CrewState>({ cars: {} })
  const set = (slug: string, crew: boolean) => state.update((s) => ({ cars: { ...s.cars, [slug]: crew } }))
  const remember = (slug: string) => { try { storage()?.setItem(KEY, slug) } catch { /* a browser that keeps nothing */ } }

  return {
    subscribe: state.subscribe,
    async check(slugs) {
      await Promise.all(slugs.map(async (slug) => {
        const r = await fetcher()(`/api/cars/${slug}/crew`).catch(() => null)
        const body = r?.ok ? ((await r.json().catch(() => ({}))) as { crew?: boolean }) : {}
        set(slug, body.crew === true)
      }))
    },
    async login(slug, passcode) {
      const r = await fetcher()(`/api/cars/${slug}/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ passcode }) })
      if (!r.ok) {
        const body = (await r.json().catch(() => ({}))) as { message?: string }
        throw new Error(body.message || `The server answered ${r.status}.`)
      }
      set(slug, true)
      remember(slug)
    },
    async logout(slug) {
      await fetcher()(`/api/cars/${slug}/login`, { method: 'DELETE' }).catch(() => null)
      set(slug, false)
    },
    remembered() {
      try { return storage()?.getItem(KEY) ?? null } catch { return null }
    },
  }
}

/** The page's one. */
export const crew: Crew = createCrew()

/** Whether this browser is [slug]'s crew, as last asked. */
export function isCrew(slug: string, from: Crew = crew): boolean {
  return get(from).cars[slug] === true
}
