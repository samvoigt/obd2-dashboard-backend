import { describe, expect, it } from 'vitest'
import { changeAt, changeEntry, driverAt, driverTimeline, removeEntry, driversText, idsOf, lastStop, replacedText, saveDriverChanges, setDriverAt, swapMoment, tabletWall, viewerTime, withChange, withoutChange, type LiveClocks } from './driverChanges'

const sam = { id: 'd-sam', name: 'Sam', code: 'SAM' }
const alex = { id: 'd-alex', name: 'Alex', code: 'ALE' }

describe('driver changes within a session (M27)', () => {
  it('a lap is the driver’s whose change came before it ended', () => {
    const changes = [{ from: 10_000, driver: alex }, { from: 20_000, driver: null }]
    expect(driverAt(sam, [], 5_000)).toEqual(sam)
    expect(driverAt(sam, changes, 10_000)).toEqual(sam) // ending at the change: the driver before
    expect(driverAt(sam, changes, 10_001)).toEqual(alex)
    expect(driverAt(sam, changes, 20_001)).toBeNull() // a change to nobody
    expect(driverAt(null, [], 1)).toBeNull()
  })

  it('changes made and taken out by moment, kept in order', () => {
    const ids = idsOf([{ from: 20_000, driver: sam }, { from: 10_000, driver: alex }])
    expect(ids).toEqual([{ from: 10_000, driver: 'd-alex' }, { from: 20_000, driver: 'd-sam' }])
    expect(withChange(ids, 15_000, null).map((c) => c.from)).toEqual([10_000, 15_000, 20_000])
    expect(withChange(ids, 10_000, 'd-sam')[0]).toEqual({ from: 10_000, driver: 'd-sam' }) // replaced, not doubled
    expect(withoutChange(ids, 10_000)).toEqual([{ from: 20_000, driver: 'd-sam' }])
  })

  it('who drove, in words', () => {
    expect(driversText(sam, [])).toBe('Sam')
    expect(driversText(sam, [{ from: 1, driver: alex }, { from: 2, driver: alex }, { from: 3, driver: sam }])).toBe('Sam, then Alex, then Sam')
    expect(driversText(null, [{ from: 1, driver: sam }])).toBe('Nobody yet, then Sam')
  })

  it('sends the whole list by the right path, and says why a refusal was', async () => {
    const sent: string[] = []
    const ok = (async (path: string, init: RequestInit) => { sent.push(`${path} ${init.body}`); return new Response('{}') }) as unknown as typeof fetch
    await saveDriverChanges('crew', 'outback', 's1', [{ from: 1, driver: 'd-sam' }], ok)
    await saveDriverChanges('admin', 'outback', 's1', [], ok)
    expect(sent).toEqual([
      '/api/cars/outback/sessions/s1/driver-changes {"changes":[{"from":1,"driver":"d-sam"}]}',
      '/api/admin/sessions/s1/driver-changes {"changes":[]}',
    ])
    const refused = (async () => new Response('{"message":"No such driver."}', { status: 400 })) as unknown as typeof fetch
    await expect(saveDriverChanges('crew', 'outback', 's1', [], refused)).rejects.toThrow('No such driver.')
  })
})

describe('when the swap happened (M34.1)', () => {
  // The viewer's clock runs 2 s ahead of the server's; the tablet's 30 s behind it (its newest record, sent at server 1_000_000).
  const clocks: LiveClocks = { newest: { t: 1_000_000, wall: 970_000 }, offsetMs: 2_000 }
  const laps = [
    { lap: 58, start: 600_000, end: 700_000, pitOut: false },
    { lap: 59, start: 700_000, end: 800_000, pitOut: true }, // out of the pits: the new driver's
    { lap: 60, start: 800_000, end: 900_000, pitOut: false },
  ]

  it('now is the newest record; the last stop its out-lap\'s start; lap N its start', () => {
    expect(swapMoment({ kind: 'now' }, laps, clocks, 0)).toEqual({ from: 970_000 })
    expect(swapMoment({ kind: 'stop' }, laps, clocks, 0)).toEqual({ from: 700_000 })
    expect(lastStop(laps)?.lap).toBe(59)
    expect(swapMoment({ kind: 'lap', lap: 60 }, laps, clocks, 0)).toEqual({ from: 800_000 })
    expect(swapMoment({ kind: 'lap', lap: 61 }, laps, clocks, 0)).toEqual({ error: "There's no lap 61." })
    expect(swapMoment({ kind: 'stop' }, laps.map((l) => ({ ...l, pitOut: false })), clocks, 0)).toEqual({ error: 'No stop yet.' })
    expect(swapMoment({ kind: 'now' }, [], { newest: null, offsetMs: 0 }, 0)).toEqual({ error: 'Nothing has come from the car yet.' })
  })

  it('a time on the viewer\'s clock is the tablet\'s wall; never in the future, nor before the session', () => {
    expect(tabletWall(1_002_000, clocks)).toBe(970_000) // the newest record, seen on the viewer's clock
    expect(viewerTime(970_000, clocks)).toBe(1_002_000)
    const now = new Date(2026, 9, 18, 14, 10).getTime()
    const real: LiveClocks = { newest: { t: now - 2_000, wall: now - 32_000 }, offsetMs: 2_000 } // viewer = server + 2 s, tablet = server − 30 s
    const at1405 = new Date(2026, 9, 18, 14, 5).getTime()
    expect(swapMoment({ kind: 'time', at: '14:05' }, [], real, now)).toEqual({ from: at1405 - 32_000 })
    expect(swapMoment({ kind: 'time', at: '14:11' }, [], real, now)).toEqual({ error: 'That time hasn’t come yet.' })
    expect(swapMoment({ kind: 'time', at: '13:00' }, [], real, now, at1405)).toEqual({ error: 'That’s before this session began.' })
    expect(swapMoment({ kind: 'time', at: '2pm' }, [], real, now)).toEqual({ error: 'Give a time as 14:05.' })
    expect(swapMoment({ kind: 'time', at: '25:00' }, [], real, now)).toEqual({ error: 'Give a time as 14:05.' })
  })

  it('no moment sets the session\'s driver; a moment adds a change to its list; crew and admin each their path', async () => {
    const calls: [string, string | undefined][] = []
    const fetcher = (async (url: string, init?: RequestInit) => {
      calls.push([url, init?.body as string | undefined])
      if (!init) return new Response(JSON.stringify({ driverChanges: [{ from: 100, driver: sam }] }))
      return new Response(JSON.stringify({ driver: 'd-sam', changes: [], replaced: [{ from: 100, driver: 'd-sam' }] }))
    }) as unknown as typeof fetch
    const saved = await setDriverAt('crew', 'outback', 's1', 'd-alex', null, fetcher)
    expect(calls).toEqual([['/api/cars/outback/sessions/s1/driver', '{"driver":"d-alex"}']])
    expect(saved.replaced).toEqual([{ from: 100, driver: 'd-sam' }])
    calls.length = 0
    await setDriverAt('admin', 'outback', 's1', 'd-alex', 200, fetcher)
    expect(calls).toEqual([
      ['/api/sessions/s1', undefined],
      ['/api/admin/sessions/s1/driver-changes', '{"changes":[{"from":100,"driver":"d-sam"},{"from":200,"driver":"d-alex"}]}'],
    ])
  })

  it('says what a correction replaced', () => {
    const clock = (w: number) => `t${w}`
    expect(replacedText({ driver: 'd-alex', changes: [], replaced: [] }, [sam, alex], clock)).toBe(null)
    expect(replacedText({ driver: null, changes: [], replaced: [{ from: 5, driver: 'd-sam' }, { from: 6, driver: null }] }, [sam, alex], clock))
      .toBe('replaced Sam from t5, nobody from t6')
  })
})

describe('who drove, as one timeline (M34.4)', () => {
  const drew = { id: 'd-drew', name: 'Drew', code: 'DRE' }
  // Laps of 100 s: lap N from N·100 s. A plain session: one member, laps that name none.
  const lap = (n: number, session?: string) => ({ lap: n, start: n * 100_000, end: (n + 1) * 100_000, ...(session ? { session } : {}) })

  it('one member, no changes: one entry, from the start', () => {
    expect(driverTimeline([{ id: 's', started: 0, driver: sam }], [lap(1), lap(2)])).toEqual([{ from: 0, lap: null, driver: sam, member: 's', kind: 'start' }])
  })

  it('one member, two changes: each from the lap it falls in', () => {
    const t = driverTimeline([{ id: 's', started: 0, driver: sam, driverChanges: [{ from: 300_000, driver: alex }, { from: 500_000, driver: null }] }], [1, 2, 3, 4, 5].map((n) => lap(n)))
    expect(t.map((e) => [e.kind, e.lap, e.driver?.code ?? null])).toEqual([['start', null, 'SAM'], ['change', 3, 'ALE'], ['change', 5, null]])
  })

  // Saturday's shape: a, then b set to nobody (carries Sam on) or to Sam (the same: no entry), then c set to Drew.
  const members = [
    { id: 'a', started: 0, driver: sam },
    { id: 'b', started: 300_000, driver: null },
    { id: 'c', started: 400_000, driver: drew, driverChanges: [{ from: 600_000, driver: alex }] },
  ]
  const laps = [lap(1, 'a'), lap(2, 'a'), lap(3, 'b'), lap(4, 'c'), lap(5, 'c'), lap(6, 'c')]

  it('three members: one carrying the same driver has no entry, another driver has one', () => {
    expect(driverTimeline(members, laps).map((e) => [e.member, e.kind, e.lap, e.driver?.code ?? null])).toEqual([
      ['a', 'start', null, 'SAM'], ['c', 'start', 4, 'DRE'], ['c', 'change', 6, 'ALE'],
    ])
    expect(driverTimeline([members[0]!, { ...members[1]!, driver: sam }], laps)).toHaveLength(1)
  })

  it('a change at a later member\'s first lap is its driver; mid-member, a change on it', () => {
    expect(changeAt(members, laps, 4, 'd-sam')).toEqual({ member: 'c', kind: 'driver', driver: 'd-sam' })
    expect(changeAt(members, laps, 3, 'd-alex')).toEqual({ member: 'b', kind: 'driver', driver: 'd-alex' })
    expect(changeAt(members, laps, 5, 'd-sam')).toEqual({ member: 'c', kind: 'changes', changes: [{ from: 500_000, driver: 'd-sam' }, { from: 600_000, driver: 'd-alex' }] })
    expect(changeAt(members, laps, 2, null)).toEqual({ member: 'a', kind: 'changes', changes: [{ from: 200_000, driver: null }] })
    expect(changeAt(members, laps, 9, null)).toEqual({ error: "There's no lap 9." })
    // A plain session's laps name no member: the one they began in.
    expect(changeAt([{ id: 's', started: 0, driver: sam }], [lap(1), lap(2)], 1, 'd-alex')).toEqual({ member: 's', kind: 'driver', driver: 'd-alex' })
    expect(changeAt([{ id: 's', started: 0, driver: sam }], [lap(1), lap(2)], 2, 'd-alex')).toEqual({ member: 's', kind: 'changes', changes: [{ from: 200_000, driver: 'd-alex' }] })
  })

  it('changing an entry, and removing one: a later start goes back to whoever drove before', () => {
    const t = driverTimeline(members, laps)
    expect(changeEntry(members, t[0]!, 'd-alex')).toEqual({ member: 'a', kind: 'driver', driver: 'd-alex' })
    expect(changeEntry(members, t[2]!, 'd-sam')).toEqual({ member: 'c', kind: 'changes', changes: [{ from: 600_000, driver: 'd-sam' }] })
    expect(removeEntry(members, t, t[0]!)).toBe(null)
    expect(removeEntry(members, t, t[1]!)).toEqual({ member: 'c', kind: 'driver', driver: 'd-sam' })
    expect(removeEntry(members, t, t[2]!)).toEqual({ member: 'c', kind: 'changes', changes: [] })
  })
})
