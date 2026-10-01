/**
 * A course from a file (M20): our own format, or plain lines (an OpenStreetMap
 * export, say) to be labelled in the editor. Nothing is guessed: a line without
 * a role comes in unassigned, and whatever can't be read is said, never
 * silently dropped. The server's rules still decide what can be saved.
 */
import { metres, readCourse, type EditCourse, type Pt, type Unassigned } from './courseEdit'

/** Larger than this isn't read at all (Sam, 2026-10-01): an export around a whole park is a few MB. */
export const MAX_FILE_BYTES = 10 * 1024 * 1024

/** Ends of two lines this close are one point, and the lines are joined. */
export const JOIN_METRES = 5

export const OSM_ATTRIBUTION = '© OpenStreetMap contributors, under the Open Database License (ODbL 1.0).'

export type CourseFile =
  | {
      course: EditCourse
      /** The file's own `name`, for a new course; null if it has none. */
      name: string | null
      /** Why each thing in the file isn't in the drawing, one line each, counted ("3 points"). */
      leftOut: string[]
    }
  | { refused: string }

type Json = Record<string, unknown>

const OSM_ID = /^(node|way|relation)\/\d+$/

/** [text], a file's contents, as a course to edit, or why it can't be one. */
export function readCourseFile(text: string): CourseFile {
  if (new TextEncoder().encode(text).length > MAX_FILE_BYTES) return { refused: `The file is over ${MAX_FILE_BYTES / 1024 / 1024} MB.` }
  let json: unknown
  try {
    json = JSON.parse(text)
  } catch {
    return { refused: "The file isn't JSON." }
  }
  const collection = asCollection(json)
  if (!collection) return { refused: "The file isn't GeoJSON: a FeatureCollection, a Feature or a geometry." }

  const notes: string[] = []
  const course = readCourse(collection, notes)
  const lines: Unassigned[] = []
  for (const f of collection.features as unknown[]) {
    if (typeof f !== 'object' || f === null) {
      notes.push('a feature that isn\'t an object')
      continue
    }
    const props = ((f as Json).properties ?? {}) as Json
    if (typeof props.role === 'string') continue // read above
    plain((f as Json).geometry, props, lines, notes)
  }
  course.unassigned = join(lines).sort((x, y) => length(y.path) - length(x.path))

  const osm = [...course.layouts.map((l) => l.extra.osm), course.pitLane?.extra.osm, ...course.unassigned.map((u) => u.extra.osm)]
  if (course.top.attribution === undefined && osm.some((id) => typeof id === 'string')) course.top = { ...course.top, attribution: OSM_ATTRIBUTION }
  const name = typeof collection.name === 'string' && collection.name.trim() ? collection.name.trim() : null
  return { course, name, leftOut: counted(notes) }
}

/** What a course holds, in words: "2 layouts, a start/finish, 4 sectors, a pit lane". */
export function contents(course: EditCourse): string {
  const n = (count: number, one: string, many: string) => (count === 0 ? [] : [count === 1 ? one : `${count} ${many}`])
  const parts = [
    ...n(course.layouts.length, 'a layout', 'layouts'),
    ...n(course.startFinish.length, 'a start/finish', 'start/finishes'),
    ...n(course.sectors.length, 'a sector line', 'sector lines'),
    ...(course.pitLane ? ['a pit lane'] : []),
    ...(course.pitIn ? ['pit in'] : []),
    ...(course.pitOut ? ['pit out'] : []),
    ...(course.pitLine ? ['a pit line'] : []),
    ...n(course.unassigned.length, 'a line to label', 'lines to label'),
  ]
  return parts.length ? parts.join(', ') : 'nothing'
}

/** Metres along a path. */
export function length(path: readonly Pt[]): number {
  let m = 0
  for (let i = 1; i < path.length; i++) m += metres(path[i - 1]!, path[i]!)
  return m
}

/** Any GeoJSON as a collection: a Feature or a bare geometry wrapped. */
function asCollection(json: unknown): (Json & { features: unknown[] }) | null {
  if (typeof json !== 'object' || json === null || Array.isArray(json)) return null
  const g = json as Json
  if (g.type === 'FeatureCollection') return Array.isArray(g.features) ? (g as Json & { features: unknown[] }) : null
  if (g.type === 'Feature') return { type: 'FeatureCollection', features: [g] }
  if (typeof g.type === 'string' && GEOMETRIES.has(g.type)) return { type: 'FeatureCollection', features: [{ type: 'Feature', properties: {}, geometry: g }] }
  return null
}

const GEOMETRIES = new Set(['Point', 'MultiPoint', 'LineString', 'MultiLineString', 'Polygon', 'MultiPolygon', 'GeometryCollection'])

/** A feature without a role: its lines into [lines], anything else noted. */
function plain(geometry: unknown, props: Json, lines: Unassigned[], notes: string[]) {
  const g = (geometry ?? null) as Json | null
  const id = [props['@id'], props.id].find((v) => typeof v === 'string' && OSM_ID.test(v)) as string | undefined
  const name = typeof props.name === 'string' ? props.name.trim() : ''
  const add = (coordinates: unknown) => {
    const path = pointsOf(coordinates)
    if (!path) notes.push('a line with coordinates out of range')
    else if (path.length < 2) notes.push('a line of fewer than 2 points')
    else lines.push({ path, extra: id ? { osm: id } : {}, name })
  }
  switch (g?.type) {
    case 'LineString':
      add(g.coordinates)
      break
    case 'MultiLineString':
      for (const c of Array.isArray(g.coordinates) ? g.coordinates : []) add(c)
      break
    case 'Point':
    case 'MultiPoint':
      notes.push('a point')
      break
    case 'Polygon':
    case 'MultiPolygon':
      notes.push('a polygon')
      break
    case 'GeometryCollection':
      for (const part of Array.isArray(g.geometries) ? g.geometries : []) plain(part, props, lines, notes)
      break
    default:
      notes.push(g === null ? 'a feature without a shape' : 'a shape that isn\'t GeoJSON')
  }
}

/** [lon, lat] points, any height dropped; null if any isn't a point in range. */
function pointsOf(coordinates: unknown): Pt[] | null {
  if (!Array.isArray(coordinates)) return null
  const out: Pt[] = []
  for (const c of coordinates) {
    if (!Array.isArray(c) || c.length < 2) return null
    const lon = c[0]
    const lat = c[1]
    if (typeof lon !== 'number' || typeof lat !== 'number' || !(Math.abs(lon) <= 180) || !(Math.abs(lat) <= 90)) return null
    out.push([lon, lat])
  }
  return out
}

/**
 * Lines meeting end to end, joined into one, reversing pieces as needed. Two
 * ends meet within [JOIN_METRES]; they're joined only where exactly two ends
 * meet, so a junction of three is left as it is.
 */
export function join(lines: readonly Unassigned[]): Unassigned[] {
  if (lines.length < 2) return [...lines]
  // Every end: line i's start is 2i, its end 2i+1. Found near each other through a grid of JOIN_METRES cells.
  const ends: Pt[] = lines.flatMap((l) => [l.path[0]!, l.path[l.path.length - 1]!])
  const kx = 111_320 * Math.cos((ends[0]![1] * Math.PI) / 180)
  const ky = 110_574
  const cell = (p: Pt) => [Math.floor((p[0] * kx) / JOIN_METRES), Math.floor((p[1] * ky) / JOIN_METRES)] as const
  const grid = new Map<string, number[]>()
  ends.forEach((p, e) => {
    const [x, y] = cell(p)
    const key = `${x},${y}`
    grid.set(key, [...(grid.get(key) ?? []), e])
  })
  const near = (e: number): number[] => {
    const [x, y] = cell(ends[e]!)
    const out: number[] = []
    for (let dx = -1; dx <= 1; dx++) {
      for (let dy = -1; dy <= 1; dy++) {
        for (const o of grid.get(`${x + dx},${y + dy}`) ?? []) if (o !== e && metres(ends[e]!, ends[o]!) <= JOIN_METRES) out.push(o)
      }
    }
    return out
  }
  // An end's partner: the one other end at its point, on another line, with nothing else there.
  // Both ways round, so every partner's partner is itself: the chains can't fork.
  const only = ends.map((_, e) => {
    const n = near(e)
    return n.length === 1 ? n[0]! : -1
  })
  const partner = new Map<number, number>()
  only.forEach((o, e) => {
    if (o >= 0 && only[o] === e && o >> 1 !== e >> 1) partner.set(e, o)
  })

  const used = new Set<number>()
  const out: Unassigned[] = []
  for (let start = 0; start < lines.length; start++) {
    if (used.has(start)) continue
    // Back to the first line of the chain: follow partners from this line's start, until none, or round to here.
    let line = start
    let entry = 2 * start // the end the chain enters [line] by
    for (;;) {
      const p = partner.get(entry)
      if (p === undefined) break
      if (p >> 1 === start) {
        // Round to here: a loop, which starts where the file's first piece of it does.
        line = start
        entry = 2 * start
        break
      }
      line = p >> 1
      entry = p ^ 1 // through that line, out of its other end
    }
    // Then forwards, each piece the way the chain goes.
    const chain: { line: number; forward: boolean }[] = []
    let at = line
    let enter = entry
    while (!used.has(at)) {
      used.add(at)
      chain.push({ line: at, forward: enter % 2 === 0 })
      const p = partner.get(enter ^ 1)
      if (p === undefined) break
      at = p >> 1
      enter = p
    }
    const path: Pt[] = []
    for (const { line: i, forward } of chain) {
      const piece = forward ? lines[i]!.path : [...lines[i]!.path].reverse()
      const last = path[path.length - 1]
      path.push(...(last && metres(last, piece[0]!) < 0.5 ? piece.slice(1) : piece))
    }
    const ids = chain.map((c) => lines[c.line]!.extra.osm).filter((v): v is string => typeof v === 'string')
    const name = chain.map((c) => lines[c.line]!.name).find((n) => n) ?? ''
    out.push({ path, extra: ids.length ? { osm: ids.join(', ') } : {}, name })
  }
  return out
}

/** "3 points, a polygon": the same reason said once, with how many. */
function counted(notes: string[]): string[] {
  const counts = new Map<string, number>()
  for (const n of notes) counts.set(n, (counts.get(n) ?? 0) + 1)
  return [...counts].map(([n, c]) => (c === 1 ? n : `${c} × ${n.replace(/^an? /, '')}`))
}
