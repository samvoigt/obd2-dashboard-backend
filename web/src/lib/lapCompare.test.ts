import { describe, expect, it } from 'vitest'
import { compareBar, heldHere, isTicked, toggleTick, type Tick } from './lapCompare'
import type { LapPick } from './comparePick'

const pick = (start: number, more: Partial<LapPick> = {}): LapPick =>
  ({ ref: { car: 'outback', session: 's1', start, end: start + 90_000 }, course: 'nhms', layout: 'road', label: `Outback lap at ${start}`, ...more })
const tick = (lap: number, more: Partial<LapPick> = {}): Tick => ({ pick: pick(lap * 100_000, more), lap })

describe('pick two laps to compare (M38.1)', () => {
  it('ticks at most two, a third dropping the oldest; ticking again unticks', () => {
    let t = toggleTick([], tick(72))
    t = toggleTick(t, tick(107))
    expect(t.map((x) => x.lap)).toEqual([72, 107])
    expect(toggleTick(t, tick(3)).map((x) => x.lap)).toEqual([107, 3])
    expect(toggleTick(t, tick(72)).map((x) => x.lap)).toEqual([107])
    expect(isTicked(t, pick(7_200_000))).toBe(true)
    expect(isTicked(t, pick(1))).toBe(false)
  })

  it('offers nothing with none ticked', () => {
    expect(compareBar([], tick(107), pick(5))).toEqual([])
  })

  it('one ticked: against the best, and kept for another page; only kept when it is the best', () => {
    const one = compareBar([tick(72)], tick(107), null)
    expect(one.map((a) => a.label)).toEqual(['Compare with the best lap (107)', 'Keep for another page'])
    expect(one[0]).toMatchObject({ href: '/compare?a=outback%2Fs1%2F10700000%2F10790000&b=outback%2Fs1%2F7200000%2F7290000&course=nhms&layout=road' })
    expect(compareBar([tick(107)], tick(107), null).map((a) => a.label)).toEqual(['Keep for another page'])
  })

  it('two ticked: one compare, the earlier lap first, whichever was ticked first', () => {
    const bar = compareBar([tick(107), tick(72)], tick(107), pick(5))
    expect(bar).toHaveLength(1)
    expect(bar[0]).toMatchObject({ label: 'Compare lap 72 with lap 107', href: expect.stringContaining('a=outback%2Fs1%2F7200000') })
  })

  it('a pick held from another page: offered on the same course and layout, never against itself', () => {
    const held = pick(1_000, { ref: { car: 'outback', session: 's9', start: 1_000, end: 91_000 }, label: 'Outback lap 4, 1:31.000' })
    expect(compareBar([tick(72)], null, held).map((a) => a.label)).toEqual(['Compare lap 72 with Outback lap 4, 1:31.000', 'Keep for another page'])
    expect(compareBar([tick(72)], null, { ...held, layout: 'oval' }).map((a) => a.label)).toEqual(['Keep for another page'])
    expect(compareBar([tick(72)], null, pick(7_200_000)).map((a) => a.label)).toEqual(['Keep for another page'])
    expect(heldHere(held, 'nhms', 'road')).toBe(held)
    expect(heldHere(held, 'nhms', 'oval')).toBeNull()
    expect(heldHere(null, 'nhms', 'road')).toBeNull()
  })
})
