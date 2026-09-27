/**
 * The admin page's logic (M6.5): the server's rules mirrored for instant hints
 * (the server still decides), and the API calls. Pure where it can be, so it is
 * tested without a browser.
 */
import { isState, stateLabel, type State } from './state'

export interface AdminConfig {
  enabled: boolean
  googleClientId: string | null
  dev: boolean
}

export interface AdminCar {
  slug: string
  name: string
  tokenHint: string
  /** Epoch milliseconds. */
  tokenIssued: number
  passcodeSet: boolean
  state: string
  sessions: number
  /** The session its tablet is streaming now, if any. */
  liveSession: string | null
}

export type SessionState = 'live' | 'uploading' | 'complete' | 'incomplete'

export interface AdminSession {
  id: string
  /** Epoch milliseconds. */
  started: number
  lines: number
  state: SessionState
}

const SESSION_WORDS: Record<SessionState, string> = {
  live: 'Live now',
  uploading: 'Uploading',
  complete: 'Complete',
  incomplete: 'Incomplete (upload stopped)',
}

export function sessionStateText(state: string): string {
  return SESSION_WORDS[state as SessionState] ?? state
}

/** Why a session can't be deleted yet, in plain words; null if it can. The server decides again. */
export function deleteBlocked(session: AdminSession): string | null {
  if (session.state === 'live') return 'Live: its tablet is still sending. Delete it once the drive has ended.'
  if (session.state === 'uploading') return 'Still uploading. It can be deleted once it has been quiet for 5 minutes.'
  return null
}

/** What must be typed to delete a session: the first 8 characters of its id. */
export function sessionConfirmation(id: string): string {
  return id.slice(0, 8)
}

/** A car and, exactly once, its generated token. */
export interface CarWithToken {
  car: AdminCar
  token: string | null
}

export const SLUG_MIN = 2
export const SLUG_MAX = 32
export const RESERVED = ['api', 'v1', 'cars', 'admin', 'health', 'static']
export const TOKEN_MIN = 8
export const TOKEN_MAX = 128
export const PASSCODE_MIN = 6

/** Why [raw] can't be a slug, in the server's words; null if it can. */
export function slugProblem(raw: string): string | null {
  if (raw.length < SLUG_MIN) return `A slug needs at least ${SLUG_MIN} characters`
  if (raw.length > SLUG_MAX) return `A slug has at most ${SLUG_MAX} characters`
  if (!/^[a-z][a-z0-9-]*$/.test(raw)) return 'A slug is lower-case letters, digits and hyphens, starting with a letter'
  if (RESERVED.includes(raw)) return `"${raw}" is used by the site itself`
  return null
}

/** Why [raw] can't be a chosen token (decision 24), in the server's words; null if it can. */
export function tokenProblem(raw: string): string | null {
  if (raw.length < TOKEN_MIN) return `A token needs at least ${TOKEN_MIN} characters`
  if (raw.length > TOKEN_MAX) return `A token has at most ${TOKEN_MAX} characters`
  if (!/^[A-Za-z0-9._~-]*$/.test(raw)) return 'A token may use only letters, digits and . _ ~ - (no spaces)'
  return null
}

/** A secret typed twice: its problem, a mismatch, or null. */
export function twiceProblem(first: string, second: string, problem: (s: string) => string | null): string | null {
  return problem(first) ?? (first === second ? null : "The two don't match")
}

export function passcodeProblem(raw: string): string | null {
  return raw.length < PASSCODE_MIN ? `A passcode needs at least ${PASSCODE_MIN} characters` : null
}

/** A destructive action goes ahead only when its name is typed exactly. */
export function confirmed(typed: string, expected: string): boolean {
  return typed.trim() === expected
}

export function stateText(state: string): string {
  return isState(state) ? stateLabel[state as State] : 'Offline'
}

/** "26 Sep 2026", in the viewer's time zone. */
export function day(ms: number, locale = 'en-GB', timeZone?: string): string {
  return new Date(ms).toLocaleDateString(locale, { day: 'numeric', month: 'short', year: 'numeric', timeZone })
}

/** "26 Sept 2026, 14:05", in the viewer's time zone. */
export function when(ms: number, locale = 'en-GB', timeZone?: string): string {
  return new Date(ms).toLocaleString(locale, {
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone,
  })
}

/** The server's plain words from an error response, or its status. */
export async function errorText(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string }
    if (body.message) return body.message
  } catch {
    // not JSON
  }
  return `The server answered ${response.status}.`
}

/** A call to the admin API; the cookie goes along by itself. Errors throw the server's words. */
export async function api<T>(method: string, path: string, body?: unknown, fetcher: typeof fetch = fetch): Promise<T> {
  const response = await fetcher(`/api/admin${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) throw new AdminError(response.status, await errorText(response))
  return (response.status === 204 ? undefined : await response.json()) as T
}

export class AdminError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}
