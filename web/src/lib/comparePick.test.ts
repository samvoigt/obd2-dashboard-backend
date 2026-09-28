import { describe, expect, it } from 'vitest'
import { loadPick, pickAction, savePick, type LapPick } from './comparePick'

const lap = (session: string, start: number, course = 'box'): LapPick =>
  ({ ref: { car: 'outback', session: `5ace0000-1111-4111-8111-00000000000${session}`, start, end: start + 70_000 }, course, layout: 'box', label: `Lap ${session}` })

describe('picking two laps to compare (M16.4)', () => {
  it('the first pick is held; the same lap again lets it go; another lap compares the two', () => {
    expect(pickAction(null, lap('a', 0))).toEqual({ kind: 'pick' })
    expect(pickAction(lap('a', 0), lap('a', 0))).toEqual({ kind: 'unpick' })
    const c = pickAction(lap('a', 0), lap('b', 100))
    expect(c.kind).toBe('compare')
    expect(c.kind === 'compare' && c.href.startsWith('/compare?a=outback%2F5ace')).toBe(true)
    // A lap on another course can't be compared with it: it becomes the pick instead.
    expect(pickAction(lap('a', 0), lap('b', 100, 'nhms'))).toEqual({ kind: 'pick' })
  })

  it('held in the browser, and nothing when storage fails or holds something else', () => {
    const map = new Map<string, string>()
    const storage = { getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => void map.set(k, v), removeItem: (k: string) => void map.delete(k) }
    savePick(lap('a', 0), storage)
    expect(loadPick(storage)).toEqual(lap('a', 0))
    savePick(null, storage)
    expect(loadPick(storage)).toBeNull()
    map.set('compare-pick', 'not json')
    expect(loadPick(storage)).toBeNull()
    const broken = { getItem: () => { throw new Error('private') }, setItem: () => { throw new Error('private') }, removeItem: () => {} }
    expect(loadPick(broken)).toBeNull()
    expect(() => savePick(lap('a', 0), broken)).not.toThrow()
  })
})
