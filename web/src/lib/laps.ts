/**
 * A lap's moment, as a link (M16.1): its session's page with the window in the
 * address, on the tablet's `wall` clock, milliseconds. Pure.
 */

/** The link to lap [start]–[end] of [session] of [car]. */
export function lapLink(car: string, session: string, start: number, end: number): string {
  return `/cars/${car}/sessions/${session}?from=${Math.round(start)}&to=${Math.round(end)}`
}

/** The window an address asks for: `from` before `to`, both whole numbers; null otherwise. */
export function readWindow(search: string): [number, number] | null {
  const q = new URLSearchParams(search)
  const from = Number(q.get('from'))
  const to = Number(q.get('to'))
  if (!q.has('from') || !q.has('to') || !Number.isInteger(from) || !Number.isInteger(to) || from >= to) return null
  return [from, to]
}

/** [search] with the window set, or taken out when null; other parameters kept. */
export function withWindow(search: string, window: [number, number] | null): string {
  const q = new URLSearchParams(search)
  q.delete('from')
  q.delete('to')
  if (window) {
    q.set('from', String(Math.round(window[0])))
    q.set('to', String(Math.round(window[1])))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

/** The indexes of [t] (ascending) inside [window], or every index without one. */
export function indexesIn(t: readonly number[], window: [number, number] | null): number[] {
  return t.flatMap((x, i) => (window === null || (x >= window[0] && x <= window[1]) ? [i] : []))
}
