/**
 * Where the car stands (M17.5), as the server says on the car's live stream:
 * who's driving and since when, the race's lap and when the car left the pits,
 * the best and the theoretical best. Moments are on the server's clock; the
 * page counts on from them with its own offset. Pure.
 */
export interface Standing {
  driver?: { name?: string; code?: string; since?: number }
  race?: { lap: number; leftPits?: number }
  best?: { time: number; driver?: string }
  bestSectors?: (number | null)[]
  theoretical?: number
}

type Json = Record<string, unknown>
const num = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)
const str = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined)
const obj = (v: unknown): Json | undefined => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : undefined)

/** The stream's `timing` as the page keeps it; null for none, or anything malformed. */
export function readStanding(v: unknown): Standing | null {
  const o = obj(v)
  if (!o) return null
  const d = obj(o.driver)
  const r = obj(o.race)
  const b = obj(o.best)
  const lap = num(r?.lap)
  const time = num(b?.time)
  return {
    ...(d ? { driver: { name: str(d.name), code: str(d.code), since: num(d.since) } } : {}),
    ...(r && lap !== undefined ? { race: { lap, leftPits: num(r.leftPits) } } : {}),
    ...(b && time !== undefined ? { best: { time, driver: str(b.driver) } } : {}),
    ...(Array.isArray(o.bestSectors) ? { bestSectors: o.bestSectors.map((x) => num(x) ?? null) } : {}),
    ...(num(o.theoretical) !== undefined ? { theoretical: num(o.theoretical) } : {}),
  }
}

/** Whole seconds from [since] (server ms) to [serverNow]; null without a moment. */
export function secondsSince(since: number | undefined, serverNow: number): number | null {
  return since === undefined ? null : Math.max(0, Math.floor((serverNow - since) / 1000))
}

/** A duration as `m:ss`, or `h:mm:ss` from an hour. */
export function duration(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/**
 * The best of each sector the page can mark: the event's (the server's) where
 * it has one, else the best among [rows] shown. A lap's in- and out-lap sectors
 * never count (§22.6), as the server's don't.
 */
export function sectorBests(rows: { sectors?: number[] | null; pitIn: boolean; pitOut: boolean }[], server?: (number | null)[]): (number | null)[] {
  const n = Math.max(server?.length ?? 0, ...rows.map((r) => r.sectors?.length ?? 0))
  return Array.from({ length: n }, (_, i) => {
    const own = rows
      .filter((r) => r.sectors && r.sectors.length > i && !(r.pitIn && i === r.sectors.length - 1) && !(r.pitOut && i === 0))
      .map((r) => r.sectors![i]!)
    const mine = own.length > 0 ? Math.min(...own) : null
    const theirs = server?.[i] ?? null
    return theirs === null ? mine : mine === null ? theirs : Math.min(theirs, mine)
  })
}
