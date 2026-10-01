import { describe, expect, it } from 'vitest'
import type { PublicEvent } from './eventResults'
import { EVENTS_SHOWN, eventsShown, latestSession, layoutsLine, sessionLine, underWay } from './landing'
import type { SessionItem } from './sessions'

const session = (id: string, started: number, more: Partial<SessionItem> = {}): SessionItem => ({
  id, started, ended: started + 23 * 60_000, lines: 1, state: 'complete', track: null, layout: null, laps: 0, bestLap: null, faults: [], ...more,
})

const event = (id: string, parts: [number, number][]): PublicEvent => ({
  id, name: id, date: '2026-10-04', course: 'nhms', courseName: 'NHMS', layout: 'road', layoutName: 'Road Course',
  cars: [], parts: parts.map(([start, end], i) => ({ id: `p${i}`, kind: 'practice', name: `P${i}`, start, end })), revision: 1,
})

describe('the landing page in summary (M21.6)', () => {
  it("finds a car's latest session across its drives, or none", () => {
    const drives = [
      { started: 5, ended: 9, sessions: [session('b', 5), session('c', 7)] },
      { started: 1, ended: 2, sessions: [session('a', 1)] },
    ]
    expect(latestSession(drives)?.id).toBe('c')
    expect(latestSession([])).toBeNull()
    expect(latestSession(null)).toBeNull()
  })

  it('says a session as the sessions page does, laps and best only when there are any', () => {
    const started = new Date(2026, 8, 26, 14, 5).getTime()
    expect(sessionLine(session('a', started))).toMatch(/26 Sept? 2026 · 14:05 · 23 min$/)
    expect(sessionLine(session('a', started, { laps: 1, bestLap: { lap: 1, time: 94.532 } }))).toMatch(/23 min · 1 lap · best 1:34\.532$/)
    expect(sessionLine(session('a', started, { laps: 12 }))).toMatch(/· 12 laps$/)
  })

  it('an event is under way while one of its parts is', () => {
    const e = event('day', [[100, 200], [300, 400]])
    expect([99, 100, 199, 200, 250, 300, 400].map((t) => underWay(e, t))).toEqual([false, true, true, false, false, true, false])
    expect(underWay(event('none', []), 150)).toBe(false)
  })

  it('shows the newest few events, in the server’s order', () => {
    const all = Array.from({ length: EVENTS_SHOWN + 2 }, (_, i) => event(`e${i}`, []))
    expect(eventsShown(all).map((e) => e.id)).toEqual(all.slice(0, EVENTS_SHOWN).map((e) => e.id))
  })

  it("lists a course's layouts as the courses page does", () => {
    const c = { id: 'nhms', name: 'NHMS', version: 1, saved: 0, layouts: [
      { id: 'road', name: 'Road Course', default: true, sectors: 3 },
      { id: 'short', name: 'Short', default: false, sectors: 1 },
      { id: 'oval', name: 'Oval', default: false, sectors: 0 },
    ] }
    expect(layoutsLine(c)).toBe('Road Course, 3 sectors · Short, 1 sector · Oval')
  })
})
