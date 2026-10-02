import { describe, expect, it } from 'vitest'
import { across, metres, PIT_HALF_METRES, TRACK_HALF_METRES, type Obstacle, type Pt } from './courseEdit'

// A made-up spot, and paths laid out in metres east (x) and north (y) of it.
const LON = -72.0
const LAT = 42.0
const KX = 111_320 * Math.cos((LAT * Math.PI) / 180)
const KY = 110_574
const at = (x: number, y: number): Pt => [LON + x / KX, LAT + y / KY]
const xyOf = (p: Pt): [number, number] => [(p[0] - LON) * KX, (p[1] - LAT) * KY]
const line = (...pts: [number, number][]) => pts.map(([x, y]) => at(x, y))

// A straight going east, 400 m.
const straight = line([0, 0], [400, 0])
const made = (r: ReturnType<typeof across>) => {
  if (!('a' in r)) throw new Error(JSON.stringify(r))
  return { a: xyOf(r.a), b: xyOf(r.b), shortened: r.shortened, length: metres(r.a, r.b) }
}
const close = (v: [number, number], x: number, y: number) => {
  expect(v[0]).toBeCloseTo(x, 0)
  expect(v[1]).toBeCloseTo(y, 0)
}

describe('a timing line in one click (M22)', () => {
  it('is square across the path at its nearest point, 12 m each side, left of the way cars go first', () => {
    const r = made(across(straight, at(100, 5), TRACK_HALF_METRES))
    close(r.a, 100, 12)
    close(r.b, 100, -12)
    expect(r.length).toBeCloseTo(24, 0)
    expect(r.shortened).toEqual([])
  })

  it('takes its direction over 10 m each way, so a kinked path doesn’t skew it', () => {
    // A straight drawn by hand: a 1 m jog every 4 m.
    const kinked = line(...Array.from({ length: 101 }, (_, i) => [i * 4, i % 2] as [number, number]))
    const r = made(across(kinked, at(201, 3), TRACK_HALF_METRES))
    const skew = Math.abs(Math.atan2(r.a[0] - r.b[0], r.a[1] - r.b[1]) * (180 / Math.PI))
    expect(skew).toBeLessThan(5)
  })

  it('works at an open path’s end, and round a closed one’s first point', () => {
    close(made(across(straight, at(-3, 2), PIT_HALF_METRES)).a, 0, 8)
    const square = line([0, 0], [200, 0], [200, 200], [0, 200], [0, 0])
    const r = made(across(square, at(1, -1), TRACK_HALF_METRES)) // at the corner where it closes
    expect(r.length).toBeCloseTo(24, 0)
  })

  it('stops 1 m short of the pit lane beside it, and says so', () => {
    const pitLane: Obstacle = { path: line([0, 6], [400, 6]), name: 'the pit lane', sameWithin: 0.5 }
    const r = made(across(straight, at(100, 0), TRACK_HALF_METRES, [pitLane]))
    close(r.a, 100, 5)
    close(r.b, 100, -12)
    expect(r.shortened).toEqual(['the pit lane'])
  })

  it('a pit line stops short of the track the same way', () => {
    const track: Obstacle = { path: straight, name: 'the track', sameWithin: 0.5 }
    const r = made(across(line([0, 6], [400, 6]), at(100, 6), PIT_HALF_METRES, [track]))
    close(r.a, 100, 14)
    close(r.b, 100, 1)
    expect(r.shortened).toEqual(['the track'])
  })

  it('makes nothing where there’s no room, or for a click too far away', () => {
    const tight: Obstacle = { path: line([0, 1.5], [400, 1.5]), name: 'the pit lane', sameWithin: 0.5 }
    expect(across(straight, at(100, 0), TRACK_HALF_METRES, [tight])).toEqual({ tooTight: 'the pit lane' })
    const far = across(straight, at(100, 31), TRACK_HALF_METRES)
    expect('tooFar' in far && far.tooFar).toBeCloseTo(31, 0)
  })

  it('another layout drawn a few metres off the same track isn’t in the way; one further off is', () => {
    const near: Obstacle = { path: line([0, 2], [400, 2]), name: 'another layout', sameWithin: 5 }
    expect(made(across(straight, at(100, 0), TRACK_HALF_METRES, [near])).shortened).toEqual([])
    const further: Obstacle = { path: line([0, 9], [400, 9]), name: 'another layout', sameWithin: 5 }
    const r = made(across(straight, at(100, 0), TRACK_HALF_METRES, [further]))
    close(r.a, 100, 8)
    expect(r.shortened).toEqual(['another layout'])
  })

  it('stops short of its own layout coming back past', () => {
    // A hairpin: out east along y = 0, back west along y = 10.
    const hairpin = line([0, 0], [300, 0], [310, 5], [300, 10], [0, 10])
    const self: Obstacle = { path: hairpin, name: 'another stretch of this layout', sameWithin: 0.5 }
    const r = made(across(hairpin, at(100, 0), TRACK_HALF_METRES, [self]))
    close(r.a, 100, 9)
    expect(r.shortened).toEqual(['another stretch of this layout'])
  })
})
