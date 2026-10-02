/**
 * Who may do what to each car, event, course and driver (M23), as the admin
 * lists say it: they list only what the caller may edit, each with its access.
 * The pages only hide what isn't yours; the server checks every change again.
 */
import { api } from './admin'
import type { Role } from './signin'

export type Kind = 'car' | 'event' | 'course' | 'driver'

/** A thing's creator (null: made before users existed, by a master admin), its editors, and what you may do to it. */
export interface ItemAccess {
  creator: string | null
  editors: string[]
  canShare: boolean
  canDelete: boolean
}

/** What a page may show for one thing: nothing beyond the public page unless the admin list has it. */
export interface Allowed {
  edit: boolean
  share: boolean
  delete: boolean
}

export const NOTHING: Allowed = { edit: false, share: false, delete: false }

/** "master admin" or "user", as the header says it. */
export function roleText(role: Role): string {
  return role === 'master' ? 'master admin' : 'user'
}

/** What [access] allows, for a thing in the admin list; a thing missing from it allows nothing. */
export function allowed(access: ItemAccess | undefined | null, listed = access != null): Allowed {
  if (!listed) return NOTHING
  return { edit: true, share: access?.canShare === true, delete: access?.canDelete === true }
}

/** An item's access, read leniently: an older server without M23 is a master admin's list, all allowed. */
export function accessOf(item: { access?: Partial<ItemAccess> | null }): ItemAccess {
  const a = item.access
  if (!a) return { creator: null, editors: [], canShare: true, canDelete: true }
  return {
    creator: typeof a.creator === 'string' ? a.creator : null,
    editors: Array.isArray(a.editors) ? a.editors.filter((e): e is string => typeof e === 'string') : [],
    canShare: a.canShare === true,
    canDelete: a.canDelete === true,
  }
}

const LIST_PATH: Record<Kind, string> = { car: '/cars', event: '/events', course: '/courses', driver: '/drivers' }
const ID_FIELD: Record<Kind, string> = { car: 'slug', event: 'id', course: 'id', driver: 'id' }

/** What the caller may do to each [kind] they can edit, by id: the admin list, read once. */
export async function mine(kind: Kind, fetcher: typeof fetch = fetch): Promise<Map<string, ItemAccess>> {
  const items = await api<Record<string, unknown>[]>('GET', LIST_PATH[kind], undefined, fetcher)
  const out = new Map<string, ItemAccess>()
  for (const item of items) {
    const id = item[ID_FIELD[kind]]
    if (typeof id === 'string') out.set(id, accessOf(item as { access?: Partial<ItemAccess> }))
  }
  return out
}

/** A thing's sharing, as `GET /api/admin/access/{kind}/{id}` has it. */
export interface Sharing {
  creator: string | null
  editors: string[]
  /** Every invited user but the creator. */
  invitable: string[]
}

export const normal = (email: string) => email.trim().toLowerCase()

/** Why [email] can't be added as an editor; null if it can. The server decides again. */
export function editorProblem(sharing: Sharing, editors: readonly string[], email: string): string | null {
  const e = normal(email)
  if (!e) return 'Choose a user'
  if (sharing.creator !== null && e === normal(sharing.creator)) return 'They made it: they can already edit it'
  if (editors.some((x) => normal(x) === e)) return 'Already an editor'
  if (!sharing.invitable.some((x) => normal(x) === e)) return 'Only invited users can edit; a master admin invites them on Users'
  return null
}

export function addEditor(editors: readonly string[], email: string): string[] {
  const e = normal(email)
  return editors.some((x) => normal(x) === e) ? [...editors] : [...editors, e].sort()
}

export function removeEditor(editors: readonly string[], email: string): string[] {
  const e = normal(email)
  return editors.filter((x) => normal(x) !== e)
}

/** The invited users who aren't editors yet, to pick from. */
export function addable(sharing: Sharing, editors: readonly string[]): string[] {
  const taken = new Set(editors.map(normal))
  return sharing.invitable.filter((u) => !taken.has(normal(u))).sort()
}

/** Whether the editors differ from what was saved, order aside. */
export function editorsChanged(saved: readonly string[], now: readonly string[]): boolean {
  const a = [...saved].map(normal).sort()
  const b = [...now].map(normal).sort()
  return a.length !== b.length || a.some((x, i) => x !== b[i])
}

/** "made by ann@…", or for a thing from before users, by a master admin. */
export function creatorText(creator: string | null): string {
  return creator ? `Made by ${creator}` : 'Made before users existed, by a master admin'
}

/** A thing key from `/api/admin/users`' `created` (`car:outback`) as a link and words; null for one it can't read. */
export function thingLink(key: string): { href: string; label: string } | null {
  const i = key.indexOf(':')
  if (i <= 0) return null
  const kind = key.slice(0, i)
  const id = key.slice(i + 1)
  if (!id) return null
  switch (kind) {
    case 'car': return { href: `/cars/${id}`, label: `Car ${id}` }
    case 'event': return { href: `/events/${id}`, label: `Event ${id}` }
    case 'course': return { href: `/courses/${id}`, label: `Course ${id}` }
    case 'driver': return { href: `/drivers/${id}`, label: `Driver ${id}` }
    default: return null
  }
}

/** A user as `GET /api/admin/users` lists them. */
export interface InvitedUser {
  email: string
  invitedBy: string
  /** Epoch milliseconds. */
  invited: number
  created: string[]
}

/** Why [raw] can't be invited; null if it can. */
export function inviteProblem(raw: string, users: readonly InvitedUser[]): string | null {
  const e = normal(raw)
  if (!e) return null
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e)) return 'That isn’t an email address'
  if (users.some((u) => normal(u.email) === e)) return 'Already a user'
  return null
}

/** What the caller may do to one thing: nothing, if the admin list can't be read or doesn't have it. */
export async function allowedFor(kind: Kind, id: string, fetcher: typeof fetch = fetch): Promise<Allowed> {
  try {
    const yours = await mine(kind, fetcher)
    return allowed(yours.get(id), yours.has(id))
  } catch {
    return NOTHING
  }
}
