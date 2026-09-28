/**
 * The first lap picked to compare (M16.4), held in the viewer's browser until
 * they pick the second: a per-viewer convenience, so storage failing (a
 * private window) only means picking again. The rule for what a second pick
 * does is pure.
 */
import { compareLink, type LapRef } from './compare'

export interface LapPick {
  ref: LapRef
  course: string
  layout: string
  label: string
}

const KEY = 'compare-pick'

export function loadPick(storage: Pick<Storage, 'getItem'> | null = safeStorage()): LapPick | null {
  try {
    const raw = storage?.getItem(KEY)
    const p = raw ? (JSON.parse(raw) as LapPick) : null
    return p && p.ref && typeof p.course === 'string' && typeof p.layout === 'string' ? p : null
  } catch {
    return null
  }
}

export function savePick(p: LapPick | null, storage: Pick<Storage, 'setItem' | 'removeItem'> | null = safeStorage()): void {
  try {
    if (p) storage?.setItem(KEY, JSON.stringify(p))
    else storage?.removeItem(KEY)
  } catch {
    // a private window: the pick lasts this page only
  }
}

/**
 * What picking [lap] does, given the [pending] pick: nothing pending, it
 * becomes the pick; the same lap, the pick is let go; another lap on the same
 * course and layout, the two are compared; another course, it replaces the pick.
 */
export function pickAction(pending: LapPick | null, lap: LapPick): { kind: 'pick' } | { kind: 'unpick' } | { kind: 'compare'; href: string } {
  if (!pending) return { kind: 'pick' }
  const same = pending.ref.session === lap.ref.session && pending.ref.start === lap.ref.start
  if (same) return { kind: 'unpick' }
  if (pending.course !== lap.course || pending.layout !== lap.layout) return { kind: 'pick' }
  return { kind: 'compare', href: compareLink(pending.ref, lap.ref, lap.course, lap.layout) }
}

function safeStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}
