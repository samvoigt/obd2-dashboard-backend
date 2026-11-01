/**
 * **Pick two** (M38.1): the session page's laps are ticked to compare, and a
 * bar under the table offers what the ticks allow. The rule, pure; the pick
 * held for another page is `comparePick`'s (M16.4), shared with the pages that
 * keep their "Compare…" buttons.
 */
import { compareLink } from './compare'
import type { LapPick } from './comparePick'

/** A ticked lap: its pick, and its number as the table shows it. */
export interface Tick {
  pick: LapPick
  lap: number
}

/** A compare, or keeping the one ticked lap for another page; `held` when it uses the held pick, which it then lets go. */
export type BarAction = { kind: 'compare'; label: string; href: string; held?: boolean } | { kind: 'keep'; label: string }

const sameLap = (a: LapPick, b: LapPick) => a.ref.session === b.ref.session && a.ref.start === b.ref.start
const sameCourse = (a: LapPick, b: LapPick) => a.course === b.course && a.layout === b.layout

/** [lap] ticked or unticked: at most two, a third dropping the oldest. */
export function toggleTick(ticked: readonly Tick[], lap: Tick): Tick[] {
  if (ticked.some((t) => sameLap(t.pick, lap.pick))) return ticked.filter((t) => !sameLap(t.pick, lap.pick))
  return [...ticked, lap].slice(-2)
}

/** Whether [ticked] holds [lap]. */
export function isTicked(ticked: readonly Tick[], lap: LapPick): boolean {
  return ticked.some((t) => sameLap(t.pick, lap))
}

/** A pick held from another page that can be compared here: on this page's course and layout. */
export function heldHere(held: LapPick | null, course: string, layout: string): LapPick | null {
  return held && held.course === course && held.layout === layout ? held : null
}

/**
 * What the bar offers: two ticked, one compare (the earlier lap first); one,
 * against the best lap (unless it is the best), against a pick [held] from
 * another page on the same course and layout (unless it is that lap), and
 * keeping it for another page; none, nothing.
 */
export function compareBar(ticked: readonly Tick[], best: Tick | null, held: LapPick | null): BarAction[] {
  if (ticked.length >= 2) {
    const [a, b] = [...ticked].sort((x, y) => x.pick.ref.start - y.pick.ref.start) as [Tick, Tick]
    return [{ kind: 'compare', label: `Compare lap ${a.lap} with lap ${b.lap}`, href: compareLink(a.pick.ref, b.pick.ref, b.pick.course, b.pick.layout) }]
  }
  const t = ticked[0]
  if (!t) return []
  const actions: BarAction[] = []
  if (best && !sameLap(best.pick, t.pick)) {
    actions.push({ kind: 'compare', label: `Compare with the best lap (${best.lap})`, href: compareLink(best.pick.ref, t.pick.ref, t.pick.course, t.pick.layout) })
  }
  if (held && !sameLap(held, t.pick) && sameCourse(held, t.pick)) {
    actions.push({ kind: 'compare', label: `Compare lap ${t.lap} with ${held.label}`, href: compareLink(held.ref, t.pick.ref, t.pick.course, t.pick.layout), held: true })
  }
  actions.push({ kind: 'keep', label: 'Keep for another page' })
  return actions
}
