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
}

type Feature = { type: 'Feature'; properties: Record<string, unknown>; geometry: { type: 'LineString'; coordinates: Pt[] } }

export function emptyCourse(): EditCourse {
  return { top: {}, layouts: [], startFinish: [], sectors: [], pitLane: null, pitIn: null, pitOut: null, pitLine: null }
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

/** A course's GeoJSON as the editor's model. Anything it can't read is dropped from the model, not guessed at. */
export function fromGeoJSON(geojson: unknown): EditCourse {
  const course = emptyCourse()
  if (typeof geojson !== 'object' || geojson === null) return course
  const g = geojson as Record<string, unknown>
  course.top = without(g, 'type', 'features')
  for (const f of (Array.isArray(g.features) ? g.features : []) as Feature[]) {
    const props = f?.properties ?? {}
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
        const l = line(f, 'layout')
        if (l) course.startFinish.push({ ...l, layout: typeof props.layout === 'string' ? props.layout : null })
        break
      }
      case 'sector': {
        const l = line(f, 'layout', 'index')
        if (l && typeof props.layout === 'string' && typeof props.index === 'number') {
          course.sectors.push({ ...l, layout: props.layout, index: props.index })
        }
        break
      }
      case 'pit_lane':
        course.pitLane = { path: points(f), extra: without(props, 'role') }
        break
      case 'pit_in':
        course.pitIn = line(f)
        break
      case 'pit_out':
        course.pitOut = line(f)
        break
      case 'pit_line':
        course.pitLine = line(f)
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
