import { describe, expect, it } from 'vitest'
import seedText from '../../../courses/seed/nhms.geojson?raw'
import { assignLayout, assignPitLane, emptyCourse, removeUnassigned, reverse, toGeoJSON, type Pt, type Unassigned } from './courseEdit'
import { contents, join, MAX_FILE_BYTES, OSM_ATTRIBUTION, readCourseFile } from './courseFile'

const seed = JSON.parse(seedText) as Record<string, unknown>

// A made-up loop near nowhere in particular: four corners about 200 m apart.
const A: Pt = [-72.0, 42.0]
const B: Pt = [-71.9976, 42.0]
const C: Pt = [-71.9976, 42.0018]
const D: Pt = [-72.0, 42.0018]
const way = (coordinates: Pt[], props: Record<string, unknown> = {}) => ({ type: 'Feature', properties: props, geometry: { type: 'LineString', coordinates } })
const file = (...features: unknown[]) => JSON.stringify({ type: 'FeatureCollection', features })
const read = (text: string) => {
  const r = readCourseFile(text)
  if ('refused' in r) throw new Error(r.refused)
  return r
}
const line = (path: Pt[], osm?: string): Unassigned => ({ path, extra: osm ? { osm } : {}, name: '' })

describe('a course from a file (M20.1)', () => {
  it('reads our own format whole: nothing left out, nothing to label, written back the same', () => {
    const r = read(seedText)
    expect(r.leftOut).toEqual([])
    expect(r.course.unassigned).toEqual([])
    expect(r.name).toBe('New Hampshire Motor Speedway')
    expect(toGeoJSON(r.course)).toEqual(seed)
  })

  it('says what of our own format it leaves out, and why', () => {
    const r = read(file(
      way([A, B], { role: 'start_finish' }),
      way([A, B, C], { role: 'pit_in' }),
      way([A, B], { role: 'sector' }),
      way([A, B], { role: 'chicane' }),
      { type: 'Feature', properties: { role: 'layout' }, geometry: { type: 'Point', coordinates: A } },
    ))
    expect(r.course.startFinish).toHaveLength(1)
    expect(r.course.pitIn).toBeNull()
    expect(r.leftOut).toEqual([
      'a pit in that isn\'t 2 points',
      'a sector without its layout and index',
      'a feature with the unknown role "chicane"',
      'a layout that isn\'t a LineString',
    ])
  })

  it('joins a circuit mapped as three ways, one drawn backwards, into one line', () => {
    const r = read(file(
      way([A, B], { '@id': 'way/1', name: 'Circuit' }),
      way([D, C], { '@id': 'way/3' }), // backwards
      way([B, [-71.9976, 42.0009], C], { '@id': 'way/2' }),
      way([D, A], { '@id': 'way/4' }),
    ))
    expect(r.course.unassigned).toHaveLength(1)
    const [u] = r.course.unassigned
    expect(u!.path).toEqual([A, B, [-71.9976, 42.0009], C, D, A])
    expect(u!.extra.osm).toBe('way/1, way/2, way/3, way/4')
    expect(u!.name).toBe('Circuit')
    expect(r.course.top.attribution).toBe(OSM_ATTRIBUTION)
  })

  it('joins ends a few metres apart, and not three ends at a junction', () => {
    const nearB: Pt = [B[0] + 0.00002, B[1]] // under 2 m
    expect(join([line([A, B]), line([nearB, C])]).map((u) => u.path)).toEqual([[A, B, nearB, C]])
    const three = join([line([A, B]), line([B, C]), line([B, D])])
    expect(three).toHaveLength(3)
  })

  it('a chain that comes round to itself is one closed line', () => {
    const [u, ...rest] = join([line([C, D]), line([A, B]), line([D, A]), line([B, C])])
    expect(rest).toEqual([])
    expect(u!.path).toHaveLength(5)
    expect(u!.path[0]).toEqual(u!.path[4])
  })

  it('takes a MultiLineString, a bare Feature and a bare geometry, and drops heights', () => {
    const multi = read(JSON.stringify({ type: 'Feature', properties: {}, geometry: { type: 'MultiLineString', coordinates: [[A, B], [C, D]] } }))
    expect(multi.course.unassigned).toHaveLength(2)
    const bare = read(JSON.stringify({ type: 'LineString', coordinates: [[...A, 120], [...B, 121]] }))
    expect(bare.course.unassigned[0]!.path).toEqual([A, B])
    expect(bare.name).toBeNull()
  })

  it('lists what isn\'t a line, counted, and keeps a file\'s own attribution', () => {
    const r = read(JSON.stringify({
      type: 'FeatureCollection',
      attribution: 'Drawn by us',
      features: [
        way([A, B], { id: 'way/9' }),
        { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: A } },
        { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: B } },
        { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [[A, B, C, A]] } },
        { type: 'Feature', properties: {}, geometry: null },
        way([[200, 42], B]),
      ],
    }))
    expect(r.leftOut).toEqual(['2 × point', 'a polygon', 'a feature without a shape', 'a line with coordinates out of range'])
    expect(r.course.top.attribution).toBe('Drawn by us')
    expect(r.course.unassigned[0]!.extra.osm).toBe('way/9')
  })

  it('refuses what isn\'t GeoJSON, or is too big, and reads an empty collection as nothing', () => {
    expect(readCourseFile('not json')).toEqual({ refused: "The file isn't JSON." })
    expect('refused' in readCourseFile('{"type":"Topology"}')).toBe(true)
    expect('refused' in readCourseFile('[1,2]')).toBe(true)
    expect('refused' in readCourseFile(' '.repeat(MAX_FILE_BYTES + 1))).toBe(true)
    const empty = read(file())
    expect(contents(empty.course)).toBe('nothing')
  })

  it('says what a course holds', () => {
    expect(contents(read(seedText).course)).toBe('3 layouts, a start/finish, a pit lane, a pit line')
  })
})

describe('labelling lines from a file (M20.2)', () => {
  const start = () => ({ ...emptyCourse(), unassigned: [line([A, B, C, D], 'way/1'), line([C, B])] })

  it('makes a line a layout: closed, named, its id from the name, default if first', () => {
    let c = assignLayout(start(), 0, ' Full Course ')
    expect(c.layouts).toEqual([{ id: 'full-course', name: 'Full Course', default: true, path: [A, B, C, D, A], extra: { osm: 'way/1' } }])
    expect(c.unassigned).toHaveLength(1)
    c = assignLayout({ ...c, unassigned: [line([A, B, A])] }, 0, 'Full course')
    expect(c.layouts.map((l) => [l.id, l.default])).toEqual([['full-course', true], ['full-course-2', false]])
    expect(c.layouts[1]!.path).toEqual([A, B, A]) // already closed: no point added
  })

  it('makes a line the pit lane, in place of any', () => {
    const c = assignPitLane(assignPitLane(start(), 1), 0)
    expect(c.pitLane?.path).toEqual([A, B, C, D])
    expect(c.unassigned).toEqual([])
  })

  it('removes and reverses', () => {
    expect(removeUnassigned(start(), 0).unassigned.map((u) => u.path)).toEqual([[C, B]])
    expect(reverse(start(), { unassigned: 1 }).unassigned[1]!.path).toEqual([B, C])
    const laid = assignLayout(start(), 0, 'Loop')
    const back = reverse(laid, { layout: 'loop' })
    expect(back.layouts[0]!.path).toEqual([A, D, C, B, A]) // closed, from the same point
    const pit = assignPitLane(start(), 1)
    expect(reverse(pit, 'pit_lane').pitLane?.path).toEqual([B, C])
    expect(reverse(emptyCourse(), 'pit_lane')).toEqual(emptyCourse())
  })

  it('never writes an unassigned line', () => {
    expect(toGeoJSON(start())).toEqual({ type: 'FeatureCollection', features: [] })
  })
})
