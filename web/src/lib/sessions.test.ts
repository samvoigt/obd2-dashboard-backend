import { describe, expect, it } from 'vitest'
import { badge, clockOf, dayOf, duration, fetchDrives, lapTime, sourceLabel, trackOf, type SessionItem, mergeBar } from './sessions'

describe('words for past sessions', () => {
  it('writes durations', () => {
    expect(duration(45_400)).toBe('45 s')
    expect(duration(59_499)).toBe('59 s')
    expect(duration(60_000)).toBe('1 min')
    expect(duration(23 * 60_000 + 59_000)).toBe('23 min')
    expect(duration(65 * 60_000)).toBe('1 h 05 min')
    expect(duration(60 * 60_000)).toBe('1 h 00 min')
    expect(duration(-5_000)).toBe('0 s') // a clock stepped back: never negative
  })
  it('writes lap times as racers do', () => {
    expect(lapTime(94.532)).toBe('1:34.532')
    expect(lapTime(60)).toBe('1:00.000')
    expect(lapTime(58.12)).toBe('58.120')
    expect(lapTime(125.0004)).toBe('2:05.000')
    expect(lapTime(59.9996)).toBe('1:00.000') // rounds up into the next minute
  })
  it('writes days and times', () => {
    const t = Date.UTC(2026, 8, 26, 14, 5)
    expect(dayOf(t, 'en-GB', 'UTC')).toBe('Sat, 26 Sept 2026')
    expect(clockOf(t, 'en-GB', 'UTC')).toBe('14:05')
  })
  it('badges only what is not simply finished', () => {
    expect(badge('live')).toEqual({ text: 'Live now', kind: 'live' })
    expect(badge('uploading')).toEqual({ text: 'Uploading', kind: 'stale' })
    expect(badge('incomplete')).toEqual({ text: 'Upload stopped', kind: 'offline' })
    expect(badge('complete')).toBeNull()
  })
  it('names the track with its layout', () => {
    const s = { track: 'nhms', layout: 'Road Course' } as SessionItem
    expect(trackOf(s)).toBe('nhms · Road Course')
    expect(trackOf({ ...s, layout: null })).toBe('nhms')
    expect(trackOf({ ...s, track: null })).toBeNull()
  })
})

describe('fetchDrives', () => {
  it('is null for an unknown car, and throws on a failure', async () => {
    const answer = (status: number, body: unknown = []) =>
      (async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch
    expect(await fetchDrives('nope', answer(404))).toBeNull()
    expect(await fetchDrives('yaris', answer(200, []))).toEqual([])
    await expect(fetchDrives('yaris', answer(500))).rejects.toThrow('500')
  })
})

describe('what a session is (M11)', () => {
  it('says tablet-only and test-data sessions, and nothing for a car', () => {
    expect(sourceLabel('tablet')).toEqual({ text: 'Tablet only', kind: 'tablet' })
    expect(sourceLabel('fake')).toEqual({ text: 'Test data', kind: 'test' })
    for (const car of [undefined, null, '', 3]) expect(sourceLabel(car)).toBeNull()
  })
  it('shows a source it doesn’t know as sent, never guessed at', () => {
    expect(sourceLabel('replay')).toEqual({ text: 'replay', kind: 'other' })
  })
})

describe('the Merge bar (M33.8)', () => {
  const item = (id: string, members: string[] = []): SessionItem =>
    ({ id, started: 0, ended: 1, lines: 1, state: 'complete', track: null, layout: null, laps: 0, bestLap: null, faults: [], members })
  const items = [item('a'), item('b'), item('c'), item('m1', ['x', 'y']), item('m2', ['z', 'w'])]

  it('offers nothing under two, a merge of plain sessions, adding to one merged, and refuses two merged', () => {
    expect(mergeBar(new Set(['a']), items)).toEqual({ kind: 'none' })
    expect(mergeBar(new Set(['a', 'b', 'c']), items)).toEqual({ kind: 'merge', members: ['a', 'b', 'c'], label: 'Merge 3 sessions' })
    expect(mergeBar(new Set(['m1', 'c']), items)).toEqual({ kind: 'extend', into: 'm1', add: ['c'], label: 'Add 1 to the merged session' })
    expect(mergeBar(new Set(['m1', 'm2']), items)).toEqual({ kind: 'refused', message: 'Two merged sessions can\'t be merged: unmerge one first.' })
    // Ticked rows no longer listed (merged meanwhile) don't count.
    expect(mergeBar(new Set(['a', 'gone']), items)).toEqual({ kind: 'none' })
  })
})
