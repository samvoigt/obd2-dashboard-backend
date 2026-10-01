/**
 * The signed-in edits' logic (M6.5, M21): the server's rules mirrored for instant hints
 * (the server still decides), and the API calls. Pure where it can be, so it is
 * tested without a browser.
 */
import { signin } from './signin'
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
  /** How far its tablet's clock is behind the server's, ms (ahead if negative), from live batches (M11). */
  clockOffsetMs?: number | null
}

/** A tablet clock this far off is worth saying (M11); under it, network delay and drift. */
export const CLOCK_WORTH_SAYING_MS = 2 * 60_000

/**
 * "Tablet clock 10 h 58 min slow", or "… fast", when it's off by more than
 * [CLOCK_WORTH_SAYING_MS]; null otherwise, or before the tablet has streamed.
 * Only said: times are the tablet's (contract §3), never corrected.
 */
export function clockNote(offsetMs: number | null | undefined): string | null {
  if (offsetMs === null || offsetMs === undefined || Math.abs(offsetMs) <= CLOCK_WORTH_SAYING_MS) return null
  const minutes = Math.round(Math.abs(offsetMs) / 60_000)
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  const size = h === 0 ? `${m} min` : m === 0 ? `${h} h` : `${h} h ${m} min`
  return `Tablet clock ${size} ${offsetMs > 0 ? 'slow' : 'fast'}`
}

export type SessionState = 'live' | 'uploading' | 'complete' | 'incomplete'

export interface AdminSession {
  id: string
  /** Epoch milliseconds. */
  started: number
  lines: number
  state: SessionState
  /** What the admin or the crew called it (M18.3). */
  name?: string | null
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
  return (await errorOf(response)).message
}

/** The server's words and, for a refused save, every problem it found (M14.3). */
async function errorOf(response: Response): Promise<{ message: string; problems: string[] }> {
  try {
    const body = (await response.json()) as { message?: string; problems?: unknown }
    const problems = Array.isArray(body.problems) ? body.problems.filter((p): p is string => typeof p === 'string') : []
    return { message: body.message || `The server answered ${response.status}.`, problems }
  } catch {
    return { message: `The server answered ${response.status}.`, problems: [] }
  }
}

/** A call to the admin API; the cookie goes along by itself. Errors throw the server's words. */
export async function api<T>(method: string, path: string, body?: unknown, fetcher: typeof fetch = fetch): Promise<T> {
  const response = await fetcher(`/api/admin${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    if (response.status === 401) signin.lapsed()
    const { message, problems } = await errorOf(response)
    throw new AdminError(response.status, message, problems)
  }
  return (response.status === 204 ? undefined : await response.json()) as T
}

export class AdminError extends Error {
  constructor(
    readonly status: number,
    message: string,
    /** Every problem the server found with a refused save; empty otherwise. */
    readonly problems: string[] = [],
  ) {
    super(message)
  }
}

/** A course in the admin list (M12.3). */
export interface CourseSummary {
  id: string
  name: string
  version: number
  /** Epoch milliseconds. */
  saved: number
  layouts: { id: string; name: string; default: boolean; sectors: number }[]
}

/** One version of a course, whole. */
export interface CourseView {
  id: string
  name: string
  version: number
  saved: number
  geojson: Record<string, unknown>
}

export interface CourseVersion {
  version: number
  name: string
  saved: number
}


/** A course save's re-timing (M13.4), as `GET /courses/{id}/retiming` has it. */
export interface RetimingProgress {
  version: number
  runs: number
  sessions: number
  done: number
  failed: number
  finished: boolean
}

/** What the editor says of a save's re-timing. */
export function retimingText(p: RetimingProgress): string {
  const sessions = `${p.sessions} session${p.sessions === 1 ? '' : 's'}`
  if (p.sessions === 0) return `Version ${p.version}: no sessions were driven here, so nothing to re-time.`
  const failed = p.failed > 0 ? `; ${p.failed} of ${p.runs} runs failed, and will be re-timed when viewed` : ''
  if (p.finished) return `Version ${p.version}: re-timed ${sessions}${failed}.`
  return `Version ${p.version}: re-timing ${sessions}, ${p.done} of ${p.runs} runs done${failed}…`
}
