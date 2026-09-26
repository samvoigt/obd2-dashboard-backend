import { describe as suite, expect, it } from 'vitest'
import { age, applyList, applyOne, current, describe, length, LIFETIMES, PRESETS, sendable, type CrewMessage } from './messages'

const m = (id: string, sentAt: number, extra: Partial<CrewMessage> = {}): CrewMessage =>
  ({ id, text: id.toUpperCase(), state: 'queued', sentAt, expiresAt: sentAt + 1_800_000, ...extra })

suite('merging the crew stream', () => {
  it('keeps the newest first, and a message event replaces by id', () => {
    let list = applyList([m('a', 1000), m('b', 3000), m('c', 2000)])
    expect(list.map((x) => x.id)).toEqual(['b', 'c', 'a'])
    list = applyOne(list, m('c', 2000, { state: 'displayed' }))
    expect(list.map((x) => `${x.id}:${x.state}`)).toEqual(['b:queued', 'c:displayed', 'a:queued'])
    list = applyOne(list, m('d', 4000))
    expect(list[0]!.id).toBe('d')
  })
})

suite('the current message', () => {
  it('is the newest active one, on the server\'s clock', () => {
    const list = applyList([m('old', 1000, { state: 'replaced' }), m('now', 2000, { state: 'displayed', expiresAt: 10_000 })])
    // Page clock 2 s ahead of the server.
    expect(current(list, 11_999, 2000)?.id).toBe('now')
    expect(current(list, 12_000, 2000)).toBeNull() // server time 10 000: past its time
  })
  it('is none when everything has ended', () => {
    expect(current([m('a', 1, { state: 'cleared' })], 2, 0)).toBeNull()
  })
})

suite('words for the crew', () => {
  it('describes each state, and an active one past its time as expired', () => {
    expect(describe(m('a', 0), 1, 0)).toBe('Waiting for the car')
    expect(describe(m('a', 0, { state: 'received' }), 1, 0)).toBe('On the tablet, not on screen')
    expect(describe(m('a', 0, { state: 'displayed' }), 1, 0)).toBe('On the driver\'s screen')
    expect(describe(m('a', 0, { state: 'displayed', expiresAt: 5 }), 5, 0)).toBe('Expired')
    expect(describe(m('a', 0, { state: 'replaced' }), 1, 0)).toBe('Replaced')
  })
  it('counts age in seconds, minutes, hours', () => {
    expect(age(m('a', 0), 12_999, 0)).toBe('12 s')
    expect(age(m('a', 0), 59_999, 0)).toBe('59 s')
    expect(age(m('a', 0), 60_000, 0)).toBe('1 min')
    expect(age(m('a', 0), 15_000, 5000)).toBe('10 s') // page clock 5 s ahead of the server
    expect(age(m('a', 0), 180_000, 0)).toBe('3 min')
    expect(age(m('a', 0), 3_900_000, 0)).toBe('1 h 5 min')
    expect(age(m('a', 10_000), 5_000, 0)).toBe('0 s') // never negative
  })
})

suite('what may be sent', () => {
  it('is 1 to 40 characters, counted as the server counts them', () => {
    expect(sendable('')).toBe(false)
    expect(sendable('   ')).toBe(false)
    expect(sendable('x'.repeat(40))).toBe(true)
    expect(sendable('x'.repeat(41))).toBe(false)
    expect(length('🏁'.repeat(40))).toBe(40)
    expect(sendable('🏁'.repeat(40))).toBe(true)
  })
  it('offers the contract\'s presets and the default lifetime first', () => {
    expect(PRESETS.map((p) => p.preset)).toEqual(['pit', 'box', 'fuel', 'push', 'slow'])
    expect(PRESETS.every((p) => sendable(p.text))).toBe(true)
    expect(LIFETIMES[0]!.seconds).toBe(1800)
    expect(LIFETIMES.every((l) => l.seconds >= 60 && l.seconds <= 1800)).toBe(true)
  })
})
