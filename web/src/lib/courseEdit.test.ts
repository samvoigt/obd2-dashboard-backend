import { describe, expect, it } from 'vitest'
import seedText from '../../../courses/seed/nhms.geojson?raw'
import {
  addSector, arrows, closed, emptyCourse, fromGeoJSON, idFrom, keepClosed, makeDefault, metres, moveSector, removeLayout, removeSector, sectorsOf,
  toGeoJSON, unsaved, type Pt,
} from './courseEdit'

const seed = JSON.parse(seedText) as Record<string, unknown>
const LON = -71.4611
const LAT = 43.3626
const across = (dLat: number): [Pt, Pt] => [[LON - 0.00012, LAT + dLat], [LON + 0.00012, LAT + dLat]]

describe('a course as the editor holds it (M12.4)', () => {
  it('reads NHMS and writes it back unchanged, attribution and notes included', () => {
    const course = fromGeoJSON(seed)
    expect(course.layouts.map((l) => l.id)).toEqual(['road', 'road-option', 'road-option-2'])
    expect(course.layouts.find((l) => l.default)?.name).toBe('Road Course')
    expect(course.startFinish[0]?.layout).toBeNull() // for every layout
    expect(course.startFinish[0]?.extra.guess).toBe(true)
    expect(course.pitLine).not.toBeNull()
    expect(toGeoJSON(course)).toEqual(seed)
  })

  it('numbers sectors 1…n per layout, whatever is added, removed or moved', () => {
    let c = fromGeoJSON(seed)
    for (const d of [0.001, 0.002, 0.003]) c = addSector(c, 'road', ...across(d))
    c = addSector(c, 'road-option', ...across(0.004))
    expect(sectorsOf(c, 'road').map((s) => [s.index, s.a[1]])).toEqual([[1, LAT + 0.001], [2, LAT + 0.002], [3, LAT + 0.003]])
    c = removeSector(c, 'road', 1)
    expect(sectorsOf(c, 'road').map((s) => [s.index, s.a[1]])).toEqual([[1, LAT + 0.002], [2, LAT + 0.003]])
    c = moveSector(c, 'road', 2, -1)
    expect(sectorsOf(c, 'road').map((s) => [s.index, s.a[1]])).toEqual([[1, LAT + 0.003], [2, LAT + 0.002]])
    expect(moveSector(c, 'road', 1, -1)).toBe(c) // already first
    expect(sectorsOf(c, 'road-option').map((s) => s.index)).toEqual([1]) // another layout's untouched
    const written = toGeoJSON(c).features as { properties: Record<string, unknown> }[]
    expect(written.filter((f) => f.properties.role === 'sector').map((f) => [f.properties.layout, f.properties.index])).toEqual([
      ['road', 1], ['road', 2], ['road-option', 1],
    ])
  })

  it('removing a layout takes its sectors and its own start/finish, and keeps a default', () => {
    let c = addSector(fromGeoJSON(seed), 'road', ...across(0.001))
    c = removeLayout(c, 'road')
    expect(c.layouts.map((l) => l.id)).toEqual(['road-option', 'road-option-2'])
    expect(c.layouts.filter((l) => l.default).map((l) => l.id)).toEqual(['road-option'])
    expect(c.sectors).toEqual([])
    expect(c.startFinish).toHaveLength(1) // the shared one stays
    expect(makeDefault(c, 'road-option-2').layouts.map((l) => l.default)).toEqual([false, true])
  })

  it('an empty course writes an empty collection', () => {
    expect(toGeoJSON(emptyCourse())).toEqual({ type: 'FeatureCollection', features: [] })
  })
})

describe('ids, distances and arrows', () => {
  it('makes an id the server accepts, never one taken', () => {
    expect(idFrom('Road Course')).toBe('road-course')
    expect(idFrom('Home loop', ['home-loop'])).toBe('home-loop-2')
    expect(idFrom('2nd Street')).toBe('c-2nd-street')
    expect(idFrom('!')).toBe('course')
    expect(idFrom('Écurie Rallye')).toBe('ecurie-rallye')
    for (const id of [idFrom('x'.repeat(80)), idFrom('Road Course'), idFrom('2nd Street')]) expect(id).toMatch(/^[a-z][a-z0-9-]{1,31}$/)
  })

  it('measures as the server does', () => {
    expect(metres([LON, LAT], [LON, LAT + 1])).toBeCloseTo(111_195, -1)
    expect(metres(...across(0))).toBeCloseTo(19.4, 0)
  })

  it('puts arrows along a layout, pointing the way cars go', () => {
    const north: Pt[] = [[LON, LAT], [LON, LAT + 0.01]] // about 1.1 km north
    const heads = arrows(north, 150)
    expect(heads).toHaveLength(7)
    for (const [left, tip, right] of heads) {
      expect(tip[1]).toBeGreaterThan(left[1]) // the tip is ahead, northwards
      expect(tip[1]).toBeGreaterThan(right[1])
      expect(left[0]).not.toBeCloseTo(right[0], 7) // the two sides spread either way
    }
    expect(arrows([[LON, LAT]], 150)).toEqual([])
  })
})

describe('layouts stay closed (contract §22.4)', () => {
  const A: Pt = [LON, LAT]
  const B: Pt = [LON + 0.001, LAT]
  const C: Pt = [LON + 0.001, LAT + 0.001]
  it('closes a drawn line by returning to its first point, once', () => {
    expect(closed([A, B, C])).toEqual([A, B, C, A])
    expect(closed([A, B, C, A])).toEqual([A, B, C, A])
  })
  it('keeps the closing point with the first, whichever of the two was moved', () => {
    const moved: Pt = [LON - 0.0005, LAT]
    expect(keepClosed([A, B, C, A], [moved, B, C, A])).toEqual([moved, B, C, moved]) // the first dragged
    expect(keepClosed([A, B, C, A], [A, B, C, moved])).toEqual([moved, B, C, moved]) // the last dragged
    expect(keepClosed([A, B, C, A], [A, B, moved, A])).toEqual([A, B, moved, A]) // a middle point: nothing to follow
  })
})


describe('unsaved changes', () => {
  it('a rename alone is a change, as is a drawing change', () => {
    expect(unsaved(false, 'NHMS', 'NHMS')).toBe(false)
    expect(unsaved(false, 'New Hampshire', 'NHMS')).toBe(true)
    expect(unsaved(true, 'NHMS', 'NHMS')).toBe(true)
  })
  it('spaces at the ends are not a change, since the save trims them', () => {
    expect(unsaved(false, ' NHMS ', 'NHMS')).toBe(false)
  })
})
