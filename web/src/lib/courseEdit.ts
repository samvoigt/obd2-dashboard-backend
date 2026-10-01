/**
 * Editing a course (M12.4): its GeoJSON as a model the editor changes, and back.
 *
 * **What the editor doesn't touch is kept**: the collection's attribution, a
 * line's note, a layout's `osm`. The server's `CourseRules` decide what's valid
 * (the editor asks them as it draws); these functions only keep the drawing
 * tidy, sectors numbered 1…n above all.
 */

/** A point as GeoJSON has it: longitude first. */
export type Pt = [number, number]

export interface EditLine {
  a: Pt
  b: Pt
  /** Properties the editor doesn't edit, kept as they came. */
  extra: Record<string, unknown>
}

export interface EditLayout {
  id: string
  name: string
  default: boolean
  /** In the direction cars go. */
  path: Pt[]
  extra: Record<string, unknown>
}

export interface EditCourse {
  /** The collection's own fields other than `type` and `features` (its name, its attribution). */
  top: Record<string, unknown>
  layouts: EditLayout[]
  /** `layout` null: the start/finish of every layout without its own. */
  startFinish: (EditLine & { layout: string | null })[]
  sectors: (EditLine & { layout: string; index: number })[]
  pitLane: { path: Pt[]; extra: Record<string, unknown> } | null
  pitIn: EditLine | null
  pitOut: EditLine | null
  pitLine: EditLine | null
  /** Lines from a file with no role yet (M20), to be made a layout or the pit lane. Never saved. */
  unassigned: Unassigned[]
}

/** A line from a file with no role (M20): its points, its OSM id as `osm`, and the name the file gave it, if any. */
export interface Unassigned {
  path: Pt[]
  extra: Record<string, unknown>
  name: string
}

type Feature = { type: 'Feature'; properties: Record<string, unknown>; geometry: { type: 'LineString'; coordinates: Pt[] } }

export function emptyCourse(): EditCourse {
  return { top: {}, layouts: [], startFinish: [], sectors: [], pitLane: null, pitIn: null, pitOut: null, pitLine: null, unassigned: [] }
}

function without(props: Record<string, unknown>, ...keys: string[]): Record<string, unknown> {
  return Object.fromEntries(Object.entries(props).filter(([k]) => !keys.includes(k)))
}

function points(feature: Feature): Pt[] {
  return (feature.geometry?.coordinates ?? []).map((c) => [Number(c[0]), Number(c[1])] as Pt)
}

function line(feature: Feature, ...keys: string[]): EditLine | null {
  const p = points(feature)
  return p.length === 2 ? { a: p[0]!, b: p[1]!, extra: without(feature.properties ?? {}, 'role', ...keys) } : null
}

/** What each role is called when it's left out (M20). */
const ROLE_NAMES: Record<string, string> = {
  layout: 'a layout', start_finish: 'a start/finish', sector: 'a sector', pit_lane: 'a pit lane', pit_in: 'a pit in', pit_out: 'a pit out', pit_line: 'a pit line',
}

/** A course's GeoJSON as the editor's model. Anything it can't read is dropped from the model, not guessed at. */
export function fromGeoJSON(geojson: unknown): EditCourse {
  return readCourse(geojson, [])
}

/**
 * [fromGeoJSON], saying in [leftOut] why each feature with a role was dropped
 * (M20: a file, unlike a stored course, hasn't been through the server's rules).
 * Features without a role are left for the caller.
 */
export function readCourse(geojson: unknown, leftOut: string[]): EditCourse {
  const course = emptyCourse()
  if (typeof geojson !== 'object' || geojson === null) return course
  const g = geojson as Record<string, unknown>
  course.top = without(g, 'type', 'features')
  for (const f of (Array.isArray(g.features) ? g.features : []) as Feature[]) {
    const props = f?.properties ?? {}
    if (typeof props.role !== 'string') continue
    const what = ROLE_NAMES[props.role]
    if (what === undefined) {
      leftOut.push(`a feature with the unknown role "${props.role}"`)
      continue
    }
    if (f.geometry?.type !== 'LineString' || !Array.isArray(f.geometry.coordinates)) {
      leftOut.push(`${what} that isn't a LineString`)
      continue
    }
    const two = (l: EditLine | null): EditLine | null => {
      if (!l) leftOut.push(`${what} that isn't 2 points`)
      return l
    }
    switch (props.role) {
      case 'layout':
        course.layouts.push({
          id: String(props.id ?? ''),
          name: String(props.name ?? ''),
          default: props.default === true,
          path: points(f),
          extra: without(props, 'role', 'id', 'name', 'default'),
        })
        break
      case 'start_finish': {
        const l = two(line(f, 'layout'))
        if (l) course.startFinish.push({ ...l, layout: typeof props.layout === 'string' ? props.layout : null })
        break
      }
      case 'sector': {
        const l = two(line(f, 'layout', 'index'))
        if (l && typeof props.layout === 'string' && typeof props.index === 'number') {
          course.sectors.push({ ...l, layout: props.layout, index: props.index })
        } else if (l) leftOut.push('a sector without its layout and index')
        break
      }
      case 'pit_lane':
        course.pitLane = { path: points(f), extra: without(props, 'role') }
        break
      case 'pit_in':
        course.pitIn = two(line(f))
        break
      case 'pit_out':
        course.pitOut = two(line(f))
        break
      case 'pit_line':
        course.pitLine = two(line(f))
        break
    }
  }
  course.sectors.sort((x, y) => x.layout.localeCompare(y.layout) || x.index - y.index)
  return course
}

function feature(role: string, coordinates: Pt[], props: Record<string, unknown>): Feature {
  return { type: 'Feature', properties: { role, ...props }, geometry: { type: 'LineString', coordinates } }
}

/** The model as GeoJSON, in a steady order: layouts, start/finish, sectors by layout and number, then the pit. */
export function toGeoJSON(course: EditCourse): Record<string, unknown> {
  const features: Feature[] = []
  for (const l of course.layouts) features.push(feature('layout', l.path, { id: l.id, name: l.name, default: l.default, ...l.extra }))
  for (const s of course.startFinish) {
    features.push(feature('start_finish', [s.a, s.b], { ...(s.layout === null ? {} : { layout: s.layout }), ...s.extra }))
  }
  const sectors = [...course.sectors].sort((x, y) => x.layout.localeCompare(y.layout) || x.index - y.index)
  for (const s of sectors) features.push(feature('sector', [s.a, s.b], { layout: s.layout, index: s.index, ...s.extra }))
  if (course.pitLane) features.push(feature('pit_lane', course.pitLane.path, course.pitLane.extra))
  for (const [role, l] of [['pit_in', course.pitIn], ['pit_out', course.pitOut], ['pit_line', course.pitLine]] as const) {
    if (l) features.push(feature(role, [l.a, l.b], l.extra))
  }
  return { type: 'FeatureCollection', ...course.top, features }
}

/** A layout's sectors, in order. */
export function sectorsOf(course: EditCourse, layout: string): EditCourse['sectors'] {
  return course.sectors.filter((s) => s.layout === layout).sort((x, y) => x.index - y.index)
}

/** A new sector after the layout's last. */
export function addSector(course: EditCourse, layout: string, a: Pt, b: Pt): EditCourse {
  const index = sectorsOf(course, layout).length + 1
  return { ...course, sectors: [...course.sectors, { a, b, extra: {}, layout, index }] }
}

/** Renumbers a layout's sectors 1…n in their current order. */
function renumber(course: EditCourse, layout: string, ordered: EditCourse['sectors']): EditCourse {
  const others = course.sectors.filter((s) => s.layout !== layout)
  return { ...course, sectors: [...others, ...ordered.map((s, i) => ({ ...s, index: i + 1 }))] }
}

/** Removes sector [index] of [layout]; those after it move up one. */
export function removeSector(course: EditCourse, layout: string, index: number): EditCourse {
  return renumber(course, layout, sectorsOf(course, layout).filter((s) => s.index !== index))
}

/** Moves sector [index] of [layout] one place earlier (−1) or later (+1). */
export function moveSector(course: EditCourse, layout: string, index: number, by: -1 | 1): EditCourse {
  const list = sectorsOf(course, layout)
  const i = list.findIndex((s) => s.index === index)
  const j = i + by
  if (i < 0 || j < 0 || j >= list.length) return course
  const swapped = [...list]
  ;[swapped[i], swapped[j]] = [swapped[j]!, swapped[i]!]
  return renumber(course, layout, swapped)
}

/** Removes a layout, and the sectors and start/finish that were only its. */
export function removeLayout(course: EditCourse, id: string): EditCourse {
  const layouts = course.layouts.filter((l) => l.id !== id)
  if (layouts.length > 0 && !layouts.some((l) => l.default)) layouts[0] = { ...layouts[0]!, default: true }
  return {
    ...course,
    layouts,
    sectors: course.sectors.filter((s) => s.layout !== id),
    startFinish: course.startFinish.filter((s) => s.layout !== id),
  }
}

/** Unassigned line [i] as a layout named [name] (M20): closed, its id from the name, the default if it's the first. */
export function assignLayout(course: EditCourse, i: number, name: string): EditCourse {
  const u = course.unassigned[i]
  if (!u) return course
  const id = idFrom(name, course.layouts.map((l) => l.id))
  const layout: EditLayout = { id, name: name.trim(), default: course.layouts.length === 0, path: closed(u.path), extra: { ...u.extra } }
  return { ...course, layouts: [...course.layouts, layout], unassigned: course.unassigned.filter((_, j) => j !== i) }
}

/** Unassigned line [i] as the pit lane (M20), in place of any pit lane there was. */
export function assignPitLane(course: EditCourse, i: number): EditCourse {
  const u = course.unassigned[i]
  if (!u) return course
  return { ...course, pitLane: { path: [...u.path], extra: { ...u.extra } }, unassigned: course.unassigned.filter((_, j) => j !== i) }
}

export function removeUnassigned(course: EditCourse, i: number): EditCourse {
  return { ...course, unassigned: course.unassigned.filter((_, j) => j !== i) }
}

/**
 * A line the other way round (M20): an unassigned one, a layout or the pit
 * lane. A closed layout stays closed from the same point; its start/finish and
 * sectors stay where they are.
 */
export function reverse(course: EditCourse, which: { unassigned: number } | { layout: string } | 'pit_lane'): EditCourse {
  if (which === 'pit_lane') return course.pitLane ? { ...course, pitLane: { ...course.pitLane, path: [...course.pitLane.path].reverse() } } : course
  if ('layout' in which) {
    return { ...course, layouts: course.layouts.map((l) => (l.id === which.layout ? { ...l, path: [...l.path].reverse() } : l)) }
  }
  return { ...course, unassigned: course.unassigned.map((u, j) => (j === which.unassigned ? { ...u, path: [...u.path].reverse() } : u)) }
}

/** Makes [id] the default layout, and no other. */
export function makeDefault(course: EditCourse, id: string): EditCourse {
  return { ...course, layouts: course.layouts.map((l) => ({ ...l, default: l.id === id })) }
}

/** An id from a name, as the server wants ids (lower case, digits, hyphens), never one already [taken]. */
export function idFrom(name: string, taken: readonly string[] = []): string {
  // Accents dropped, not turned into hyphens: "Écurie" is "ecurie".
  let base = name.toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 28)
  if (!/^[a-z]/.test(base)) base = `c-${base}`.replace(/-+$/, '')
  if (base.length < 2) base = 'course'
  let id = base
  for (let n = 2; taken.includes(id); n++) id = `${base}-${n}`
  return id
}

/** Great-circle distance in metres, as the server measures a line. */
export function metres(a: Pt, b: Pt): number {
  const r = 6_371_008.8
  const rad = Math.PI / 180
  const dLat = (b[1] - a[1]) * rad
  const dLon = (b[0] - a[0]) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dLon / 2) ** 2
  return 2 * r * Math.asin(Math.sqrt(h))
}

/**
 * Small arrowheads along [path], every [every] metres, pointing the way cars
 * go: each is three points, the tip in the middle.
 */
export function arrows(path: readonly Pt[], every = 150, size = 10): [Pt, Pt, Pt][] {
  const out: [Pt, Pt, Pt][] = []
  let next = every / 2
  let run = 0
  for (let i = 0; i + 1 < path.length; i++) {
    const a = path[i]!
    const b = path[i + 1]!
    const seg = metres(a, b)
    // Metres to degrees here, near enough over a short arrow.
    const kx = 111_320 * Math.cos((a[1] * Math.PI) / 180)
    const ky = 110_574
    while (seg > 0 && next <= run + seg) {
      const t = (next - run) / seg
      const tip: Pt = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]
      const dx = ((b[0] - a[0]) * kx) / seg
      const dy = ((b[1] - a[1]) * ky) / seg
      const back = (s: number): Pt => [tip[0] + ((-dx * size + s * -dy * size * 0.6) / kx), tip[1] + ((-dy * size + s * dx * size * 0.6) / ky)]
      out.push([back(1), tip, back(-1)])
      next += every
    }
    run += seg
  }
  return out
}

/** A layout's line closed, its first point again at the end (§22.4), as the server requires. */
export function closed(path: readonly Pt[]): Pt[] {
  if (path.length < 2) return [...path]
  const first = path[0]!
  const last = path[path.length - 1]!
  return first[0] === last[0] && first[1] === last[1] ? [...path] : [...path, [first[0], first[1]]]
}

/** After a point was moved: the closing point follows the first, whichever of the two moved. */
export function keepClosed(before: readonly Pt[], after: readonly Pt[]): Pt[] {
  if (after.length < 2) return [...after]
  const movedLast = before.length === after.length && (after[0]![0] === before[0]![0] && after[0]![1] === before[0]![1])
  const anchor = movedLast ? after[after.length - 1]! : after[0]!
  return [[anchor[0], anchor[1]], ...after.slice(1, -1), [anchor[0], anchor[1]]]
}


/**
 * Whether the editor holds anything unsaved: the drawing changed, or the name
 * did (spaces at its ends aside, as the save trims them). A course renamed and
 * nothing else is still a new version.
 */
export function unsaved(drawn: boolean, name: string, savedName: string): boolean {
  return drawn || name.trim() !== savedName.trim()
}
