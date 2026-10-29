/**
 * Driver changes within a session (M27): the session's driver from its start,
 * each change's from its moment on (the tablet's `wall`, milliseconds). A lap
 * is the driver's whose change came before it ended, as a race's stint. Pure,
 * but for [saveDriverChanges].
 */
import type { Driver } from './events'

/** A change as the session's page has it. */
export interface DriverChangeDetail {
  from: number
  driver?: Driver | null
}

/** A change as it's sent: a driver's id, or null for "not set". */
export interface DriverChangeId {
  from: number
  driver: string | null
}

/** Who drove a lap ending at [end]: the last change before it, else the session's driver. */
export function driverAt(start: Driver | null | undefined, changes: readonly DriverChangeDetail[], end: number): Driver | null {
  let found: DriverChangeDetail | undefined
  for (const c of changes) if (c.from < end) found = c
  return found ? (found.driver ?? null) : (start ?? null)
}

/** The changes as ids, in order. */
export function idsOf(changes: readonly DriverChangeDetail[]): DriverChangeId[] {
  return changes.map((c) => ({ from: c.from, driver: c.driver?.id ?? null })).sort((a, b) => a.from - b.from)
}

/** A change to [driver] from [from], replacing one at that moment. */
export function withChange(changes: readonly DriverChangeId[], from: number, driver: string | null): DriverChangeId[] {
  return [...changes.filter((c) => c.from !== from), { from, driver }].sort((a, b) => a.from - b.from)
}

export function withoutChange(changes: readonly DriverChangeId[], from: number): DriverChangeId[] {
  return changes.filter((c) => c.from !== from)
}

/** "Sam, then Alex": who drove, in turn, each once in a row. */
export function driversText(start: Driver | null | undefined, changes: readonly DriverChangeDetail[]): string {
  const names = [start?.name ?? 'Nobody yet', ...changes.map((c) => c.driver?.name ?? 'nobody')]
  return names.filter((n, i) => i === 0 || n !== names[i - 1]).join(', then ')
}

/** What the server answers a save of who drove (M32): the session's drivers as stored, and what a correction replaced. */
export interface DriversSaved {
  driver: string | null
  changes: DriverChangeId[]
  /** Entries the 60 s / one-lap rule replaced; the start's as a change at the session's start. */
  replaced: DriverChangeId[]
}

async function answerOf(r: Response): Promise<DriversSaved> {
  const body = (await r.json().catch(() => ({}))) as Partial<DriversSaved> & { message?: string }
  if (!r.ok) throw new Error(body.message || `The server answered ${r.status}.`)
  return { driver: body.driver ?? null, changes: body.changes ?? [], replaced: body.replaced ?? [] }
}

/** Sends the whole list, through the admin's path or the crew's; the server's words if it's refused. */
export async function saveDriverChanges(who: 'admin' | 'crew', slug: string, id: string, changes: DriverChangeId[], fetcher: typeof fetch = fetch): Promise<DriversSaved> {
  const path = who === 'admin' ? `/api/admin/sessions/${id}/driver-changes` : `/api/cars/${slug}/sessions/${id}/driver-changes`
  return answerOf(await fetcher(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ changes }) }))
}

/** Sets (or with null clears) who drove [id] from its start, through the admin's path or the crew's. */
export async function saveSessionDriver(who: 'admin' | 'crew', slug: string, id: string, driver: string | null, fetcher: typeof fetch = fetch): Promise<DriversSaved> {
  const path = who === 'admin' ? `/api/admin/sessions/${id}/driver` : `/api/cars/${slug}/sessions/${id}/driver`
  return answerOf(await fetcher(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ driver }) }))
}

/** When a swap happened (M34.1): now, at the last stop, from a lap, or at a time on the viewer's clock. */
export type SwapChoice = { kind: 'now' } | { kind: 'stop' } | { kind: 'lap'; lap: number } | { kind: 'time'; at: string }

/** A lap as the live page has it: on the tablet's `wall`. */
export interface SwapLap {
  lap: number
  start: number
  end: number
  pitOut: boolean
}

/**
 * The live page's clocks (M34.1): its newest point, [newest] (`t` when the
 * server had it, `wall` the tablet's), and [offsetMs], the viewer's clock
 * less the server's. Between them, a moment on either clock is on the other.
 */
export interface LiveClocks {
  newest: { t: number; wall: number } | null
  offsetMs: number
}

/** A tablet `wall` on the viewer's clock, epoch milliseconds; null without a point to go by. */
export function viewerTime(wall: number, clocks: LiveClocks): number | null {
  return clocks.newest ? wall - clocks.newest.wall + clocks.newest.t + clocks.offsetMs : null
}

/** A moment on the viewer's clock as the tablet's `wall`; null without a point to go by. */
export function tabletWall(viewer: number, clocks: LiveClocks): number | null {
  return clocks.newest ? clocks.newest.wall + (viewer - clocks.offsetMs - clocks.newest.t) : null
}

/** The latest stop (M34.1): its out-lap, whose start is when the new driver took over. */
export function lastStop(laps: readonly SwapLap[]): SwapLap | null {
  let found: SwapLap | null = null
  for (const l of laps) if (l.pitOut && (found === null || l.end > found.end)) found = l
  return found
}

/**
 * **When the swap happened, on the tablet's `wall`** (M34.1), or why not.
 * Now: the newest record's. The last stop: its out-lap's start, so the in-lap
 * stays the old driver's. Lap N: its start, as a change at a lap is on the
 * session page. A time: today on the viewer's clock (`14:05`), never in the
 * future, nor before [startWall] (the session's first moment).
 */
export function swapMoment(choice: SwapChoice, laps: readonly SwapLap[], clocks: LiveClocks, viewerNow: number, startWall: number | null = null): { from: number } | { error: string } {
  switch (choice.kind) {
    case 'now':
      return clocks.newest ? { from: clocks.newest.wall } : { error: 'Nothing has come from the car yet.' }
    case 'stop': {
      const stop = lastStop(laps)
      return stop ? { from: stop.start } : { error: 'No stop yet.' }
    }
    case 'lap': {
      const lap = laps.find((l) => l.lap === choice.lap)
      return lap ? { from: lap.start } : { error: `There's no lap ${choice.lap}.` }
    }
    case 'time': {
      const m = /^(\d{1,2}):(\d{2})$/.exec(choice.at.trim())
      if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) return { error: 'Give a time as 14:05.' }
      const day = new Date(viewerNow)
      day.setHours(Number(m[1]), Number(m[2]), 0, 0)
      if (day.getTime() > viewerNow) return { error: 'That time hasn’t come yet.' }
      const wall = tabletWall(day.getTime(), clocks)
      if (wall === null) return { error: 'Nothing has come from the car yet.' }
      if (startWall !== null && wall < startWall) return { error: 'That’s before this session began.' }
      return { from: wall }
    }
  }
}

/**
 * Sets who's driving the live session (M34.1): with no moment (no laps yet),
 * its driver from the start; else a change at [moment] added to its list.
 * The server's answer, with what it replaced.
 */
export async function setDriverAt(who: 'admin' | 'crew', slug: string, session: string, driver: string | null, moment: number | null, fetcher: typeof fetch = fetch): Promise<DriversSaved> {
  if (moment === null) return saveSessionDriver(who, slug, session, driver, fetcher)
  const r = await fetcher(`/api/sessions/${session}`)
  if (!r.ok) throw new Error('Not saved: the session wasn’t found.')
  const detail = (await r.json()) as { driverChanges?: DriverChangeDetail[] }
  return saveDriverChanges(who, slug, session, withChange(idsOf(detail.driverChanges ?? []), moment, driver), fetcher)
}

/** "replaced Sam Voigt from 14:04": what a save's correction replaced, in words; null if nothing. */
export function replacedText(saved: DriversSaved, drivers: readonly Driver[], clock: (wall: number) => string): string | null {
  if (saved.replaced.length === 0) return null
  const name = (id: string | null) => (id ? drivers.find((d) => d.id === id)?.name ?? id : 'nobody')
  return `replaced ${saved.replaced.map((r) => `${name(r.driver)} from ${clock(r.from)}`).join(', ')}`
}
