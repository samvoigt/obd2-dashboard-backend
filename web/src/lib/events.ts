/**
 * Drivers and events (M14): the types the admin API speaks, and the event
 * editor's logic. Pure, so it's tested without a browser.
 */

import { saveSessionDriver, type DriversSaved } from './driverChanges'
import { get } from 'svelte/store'
import { isMaster, isSignedIn, signin, type SignIn } from './signin'
import { allowedFor, type Kind } from './access'
export interface Driver {
  id: string
  name: string
  code: string
}

export type PartKind = 'practice' | 'race'

export interface Part {
  /** Absent on a part added in the editor: the server gives it the next id. */
  id?: string
  kind: PartKind
  name: string
  /** Epoch milliseconds, the server's time: `[start, end)`. */
  start: number
  end: number
  added: string[]
  removed: string[]
}

export interface EventView {
  id: string
  name: string
  date: string
  course: string
  layout: string
  cars: string[]
  parts: Part[]
  revision: number
  /** Its last day (M26), for an event over several. */
  endDate?: string | null
}

/** A session as an event's page lists it. */
export interface SessionBrief {
  id: string
  car: string
  /** When the server first and last heard it: what places it in a part. */
  heardFrom: number
  heardTo: number
  started?: number | null
  source?: string | null
  driver?: string | null
  laps: number
  /** What the admin or the crew called it (M18.3). */
  name?: string | null
}

export interface AdminEvent {
  event: EventView
  /** Each part's sessions, by part id. */
  sessions: Record<string, SessionBrief[]>
  /** The entered cars' other sessions around the event's day, to add by hand. */
  others: SessionBrief[]
}

/** What `PUT /events/{id}` takes: the revision it replaces (0 for new). */
export function saveBody(e: Omit<EventView, 'id' | 'revision'>, expected: number) {
  return { expected, name: e.name, date: e.date, endDate: e.endDate || null, course: e.course, layout: e.layout, cars: e.cars, parts: e.parts }
}

/** A `datetime-local` input's value for [ms], in the viewer's time zone: `2026-10-04T09:00`. */
export function toLocalInput(ms: number): string {
  const d = new Date(ms)
  const two = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}`
}

/** A `datetime-local` value back to epoch milliseconds, in the viewer's time zone, seconds and all; null if it isn't one. */
export function fromLocalInput(value: string): number | null {
  // Seconds when the input allows them (`step="1"`, the race's flags, M15.4): without them a flag lost its value.
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?$/.test(value)) return null
  const ms = new Date(value).getTime()
  return Number.isNaN(ms) ? null : ms
}

/**
 * A new part after the last (an hour, starting where the last ended, or at 9:00 on [date]). Another session of the
 * race (M26) is the next day's, from 9:00: the car was off track overnight.
 */
export function newPart(parts: readonly Part[], kind: PartKind, date: string): Part {
  const races = parts.filter((p) => p.kind === 'race').length
  const last = parts.length > 0 ? Math.max(...parts.map((p) => p.end)) : null
  const after = last === null ? (fromLocalInput(`${date}T09:00`) ?? Date.now()) : kind === 'race' && races > 0 ? nextMorning(last) : last
  const practices = parts.filter((p) => p.kind === 'practice').length
  return {
    kind,
    name: kind === 'race' ? (races > 0 ? `Race, session ${races + 1}` : 'Race') : `Practice ${practices + 1}`,
    start: after,
    end: after + (kind === 'race' ? 6 : 1) * 3_600_000,
    added: [],
    removed: [],
  }
}

/** 9:00 the day after [ms], where you are. */
function nextMorning(ms: number): number {
  const d = new Date(ms)
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1, 9).getTime()
}

/** [session] into part [partId] by hand: added there (and nowhere else), no longer removed from it. */
export function addSession(parts: readonly Part[], partId: string, session: string): Part[] {
  return parts.map((p) =>
    p.id === partId
      ? { ...p, added: p.added.includes(session) ? p.added : [...p.added, session], removed: p.removed.filter((s) => s !== session) }
      : { ...p, added: p.added.filter((s) => s !== session) },
  )
}

/** [session] out of part [partId]: if it was added by hand, no longer; if the window caught it, removed. */
export function removeSession(parts: readonly Part[], partId: string, session: string): Part[] {
  return parts.map((p) => {
    if (p.id !== partId) return p
    if (p.added.includes(session)) return { ...p, added: p.added.filter((s) => s !== session) }
    return { ...p, removed: p.removed.includes(session) ? p.removed : [...p.removed, session] }
  })
}

/** A driver's code from their name: the first three letters, capitals. */
export function codeFrom(name: string): string {
  return name.normalize('NFKD').replace(/[^A-Za-z]/g, '').slice(0, 3).toUpperCase()
}

/** "4 Oct, 13:05–13:40": when the server heard a session, in the viewer's time zone. */
export function heardText(s: SessionBrief, locale = 'en-GB', timeZone?: string): string {
  const time = (ms: number) => new Date(ms).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', hour12: false, timeZone })
  const date = new Date(s.heardFrom).toLocaleDateString(locale, { day: 'numeric', month: 'short', timeZone })
  return `${date}, ${time(s.heardFrom)}–${time(s.heardTo)}`
}

/** Who may say who drove a session: the admin, the car's crew, or nobody looking (M14.4). */
export type DriverSetter = 'admin' | 'crew' | null

/**
 * Sets (or with null clears) who drove session [id] of car [slug]: through the
 * admin's path or the crew's, since each sign-in's cookie reaches only its own.
 */
export async function setSessionDriver(who: 'admin' | 'crew', slug: string, id: string, driver: string | null, fetcher: typeof fetch = fetch): Promise<DriversSaved> {
  return saveSessionDriver(who, slug, id, driver, fetcher)
}

/** A session's name set or cleared (M18.3), by the admin or the car's crew, as who drove it is. The name as stored. */
export async function setSessionName(who: 'admin' | 'crew', slug: string, id: string, name: string | null, fetcher: typeof fetch = fetch): Promise<string | null> {
  const path = who === 'admin' ? `/api/admin/sessions/${id}/name` : `/api/cars/${slug}/sessions/${id}/name`
  const response = await fetcher(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name }) })
  const body = (await response.json().catch(() => ({}))) as { name?: string | null; message?: string }
  if (!response.ok) throw new Error(body.message || `The server answered ${response.status}.`)
  return body.name ?? null
}

/**
 * Whether whoever is looking may set who drove: through the admin API first
 * (the page's one sign-in, M21.1) if they're a master admin or may edit
 * [through] (M23: the car, or for a race the event), else as this car's crew.
 */
export async function whoCanSet(
  slug: string,
  fetcher: typeof fetch = fetch,
  from: SignIn = signin,
  through: { kind: Kind; id: string } = { kind: 'car', id: slug },
): Promise<DriverSetter> {
  await from.check()
  const s = get(from)
  if (isMaster(s)) return 'admin'
  if (isSignedIn(s) && (await allowedFor(through.kind, through.id, fetcher)).edit) return 'admin'
  const crew = await fetcher(`/api/cars/${slug}/crew`).catch(() => null)
  const body = crew?.ok ? ((await crew.json().catch(() => ({}))) as { crew?: boolean }) : {}
  return body.crew === true ? 'crew' : null
}
