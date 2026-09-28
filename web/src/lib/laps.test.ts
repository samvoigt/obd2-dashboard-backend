import { describe, expect, it } from 'vitest'
import { indexesIn, lapLink, readWindow, withWindow } from './laps'

describe('a lap’s moment as a link (M16.1)', () => {
  it('links to its session with the window, to the millisecond', () => {
    expect(lapLink('outback', 's1', 1_000.4, 71_000.6)).toBe('/cars/outback/sessions/s1?from=1000&to=71001')
  })

  it('reads the window back, and nothing that isn’t one', () => {
    expect(readWindow('?from=1000&to=71000')).toEqual([1000, 71000])
    expect(readWindow('?to=71000&from=1000&x=1')).toEqual([1000, 71000])
    for (const bad of ['', '?from=1000', '?from=71000&to=1000', '?from=a&to=b', '?from=1.5&to=3', '?from=5&to=5']) expect(readWindow(bad)).toBeNull()
  })

  it('writes the window into an address, keeping what else is there, and takes it out', () => {
    expect(withWindow('?x=1', [1000, 2000])).toBe('?x=1&from=1000&to=2000')
    expect(withWindow('?from=1&to=2&x=1', null)).toBe('?x=1')
    expect(withWindow('?from=1&to=2', null)).toBe('')
  })

  it('the positions inside a window', () => {
    expect(indexesIn([0, 10, 20, 30], [10, 20])).toEqual([1, 2])
    expect(indexesIn([0, 10], null)).toEqual([0, 1])
  })
})
