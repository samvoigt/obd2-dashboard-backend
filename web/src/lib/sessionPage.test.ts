import { describe, expect, it } from 'vitest'
import {
  bestSectors, defaultSignals, DETAIL_WINDOW_S, events, fetchLaps, fetchSeries, joined, lapNote, lapRows, messageMarkers, nearest, needsDetail, speedColor, speedsAtPositions, speedSignal, standingRows,
  type Series, type SessionLaps,
} from './sessionPage'

const t0 = 1_790_000_000_000

function series(over: Partial<Series> = {}): Series {
  return {
    version: 1, t0, signals: [{ name: 'engine.rpm', unit: 'rpm', kind: 'number' }],
    numbers: {}, states: {}, sets: {}, positions: { t: [], lat: [], lon: [] },
    events: { stopped: [], fault: [], gap: [], lap: [] },
    ...over,
  }
}

describe('laps', () => {
  it('are in order, each spanning its time up to when it was published, the best never a pit lap', () => {
    const rows = lapRows(series({
      events: {
        stopped: [], fault: [], gap: [],
        lap: [
          [200_000, { lap: 2, time: 94.5 }],
          [100_000, { lap: 1, time: 96.0 }],
          [290_000, { lap: 3, time: 88.0, pitIn: true }],
          [420_000, { lap: 4, time: 130.0, pitOut: true }],
        ],
      },
    }))
    expect(rows.map((r) => r.lap)).toEqual([1, 2, 3, 4])
    expect(rows.map((r) => r.best)).toEqual([false, true, false, false])
    expect(rows[1]).toMatchObject({ start: t0 + 200_000 - 94_500, end: t0 + 200_000 })
    expect(rows[2]).toMatchObject({ pitIn: true, pitOut: false })
  })
  it('have no best when every lap is a pit lap, or there are none', () => {
    expect(lapRows(series({ events: { stopped: [], fault: [], gap: [], lap: [[1, { lap: 1, time: 90, pitIn: true }]] } })).some((r) => r.best)).toBe(false)
    expect(lapRows(series())).toEqual([])
  })
  it('carry their sectors when the tablet timed them (M12.7), older laps none, side by side', () => {
    const rows = lapRows(series({
      events: {
        stopped: [], fault: [], gap: [],
        lap: [
          [100_000, { lap: 1, time: 96.0 }], // before courses came from the website
          [200_000, { lap: 2, time: 94.5, course: 'nhms', courseVersion: 7, layout: 'road', sectors: [31.5, 33.0, 30.0] }],
          [300_000, { lap: 3, time: 94.2, course: 'nhms', courseVersion: 7, layout: 'road', sectors: [31.2, 33.4, 29.6] }],
          [390_000, { lap: 4, time: 88.0, pitIn: true, sectors: [30.0, 30.0, 28.0] }], // into the pits: its last sector ends at the pit line
        ],
      },
    }))
    expect(rows.map((r) => r.sectors)).toEqual([null, [31.5, 33.0, 30.0], [31.2, 33.4, 29.6], [30.0, 30.0, 28.0]])
    // §22.6: an in-lap's last sector never counts; its others are ordinary sectors.
    expect(bestSectors(rows)).toEqual([30.0, 30.0, 29.6])
  })
  it('have no sector columns when no lap has sectors, and a sector no lap on track has is null', () => {
    expect(bestSectors(lapRows(series({ events: { stopped: [], fault: [], gap: [], lap: [[1, { lap: 1, time: 90 }]] } })))).toEqual([])
    const odd = lapRows(series({
      events: { stopped: [], fault: [], gap: [], lap: [[1, { lap: 1, time: 90, sectors: [45, 45] }], [2, { lap: 2, time: 91, pitOut: true, sectors: [30, 30, 31] }]] },
    }))
    expect(bestSectors(odd)).toEqual([45, 30, 31]) // an out-lap's first sector never counts; its others do
    expect(lapRows(series({ events: { stopped: [], fault: [], gap: [], lap: [[1, { lap: 1, time: 90, sectors: ['x'] as unknown as number[] }]] } }))[0]!.sectors).toBeNull()
  })
})

describe('the chart', () => {
  it('starts with engine and road speed, else the first two numbers', () => {
    expect(defaultSignals(series({ numbers: { 'a': { t: [], v: [] }, 'vehicle.speed': { t: [], v: [] }, 'engine.rpm': { t: [], v: [] } } })))
      .toEqual(['engine.rpm', 'vehicle.speed'])
    expect(defaultSignals(series({ numbers: { a: { t: [], v: [] }, b: { t: [], v: [] }, c: { t: [], v: [] } } }))).toEqual(['a', 'b'])
  })
  it('joins signals on one axis in seconds: a gap stays null, a missing sample is undefined', () => {
    const s = series({
      numbers: {
        a: { t: [0, 1000, 1001, 3000], v: [1, 2, null, 4] },
        b: { t: [500, 1000], v: [10, 20] },
      },
    })
    const [x, a, b] = joined(s, ['a', 'b'])
    expect(x).toEqual([0, 500, 1000, 1001, 3000].map((t) => (t0 + t) / 1000))
    expect(a).toEqual([1, undefined, 2, null, 4])
    expect(b).toEqual([undefined, 10, 20, undefined, undefined])
  })
  it('joins a signal it does not have as nothing', () => {
    const [x, a] = joined(series(), ['missing'])
    expect(x).toEqual([])
    expect(a).toEqual([])
  })
  it('marks faults, gaps and stopped signals, in time order', () => {
    const e = events(series({
      events: {
        stopped: [[3000, 'engine.oil_temperature', 'it stopped answering']],
        fault: [[1000, ['P0301', 'P0420']], [5000, []]],
        gap: [[2000, 1], [4000, 12]],
        lap: [],
      },
    }))
    expect(e.map((m) => m.kind)).toEqual(['fault', 'gap', 'stopped', 'gap', 'fault'])
    expect(e.map((m) => m.text)).toEqual([
      'Trouble codes: P0301, P0420', 'The tablet missed 1 reading', 'engine.oil_temperature stopped: it stopped answering',
      'The tablet missed 12 readings', 'Trouble codes cleared',
    ])
    expect(e[0]!.t).toBe((t0 + 1000) / 1000)
  })
})

describe('the map', () => {
  it('finds the nearest time, the earlier on a tie', () => {
    expect(nearest([], 5)).toBe(-1)
    expect(nearest([10, 20, 30], 0)).toBe(0)
    expect(nearest([10, 20, 30], 24)).toBe(1)
    expect(nearest([10, 20, 30], 26)).toBe(2)
    expect(nearest([10, 20, 30], 25)).toBe(1)
    expect(nearest([10, 20, 30], 99)).toBe(2)
  })
  it('colours by GPS speed, else the car speed, grey where unknown', () => {
    const withGps = series({
      numbers: { 'gps.speed': { t: [0, 1000], v: [50, 150] }, 'vehicle.speed': { t: [0], v: [1] } },
      positions: { t: [100, 900], lat: [1, 2], lon: [1, 2] },
    })
    expect(speedSignal(withGps)).toBe('gps.speed')
    expect(speedsAtPositions(withGps)).toEqual([50, 150])
    expect(speedSignal(series({ numbers: { 'vehicle.speed': { t: [0], v: [1] } } }))).toBe('vehicle.speed')
    expect(speedsAtPositions(series({ positions: { t: [1], lat: [1], lon: [1] } }))).toEqual([null])
    const stops = ['#000000', '#ffffff', '#ff0000']
    expect(speedColor(0, 0, 100, stops)).toBe('#000000')
    expect(speedColor(25, 0, 100, stops)).toBe('#808080') // halfway to the second stop
    expect(speedColor(50, 0, 100, stops)).toBe('#ffffff')
    expect(speedColor(75, 0, 100, stops)).toBe('#ff8080')
    expect(speedColor(100, 0, 100, stops)).toBe('#ff0000')
    expect(speedColor(200, 0, 100, stops)).toBe('#ff0000') // past the top: the top
    expect(speedColor(-5, 0, 100, stops)).toBe('#000000')
    expect(speedColor(50, 50, 50, stops)).toBe('#ffffff') // one speed only: the middle
    expect(speedColor(null, 0, 100, stops)).toBeNull()
    expect(speedColor(10, 0, 100, [])).toBeNull()
    expect(speedColor(10, 0, 100, ['#ABCDEF'])).toBe('#abcdef')
  })
})

describe('fetching the series', () => {
  const answering = (status: number) => (async () => new Response(status === 204 ? null : '{}', { status })) as unknown as typeof fetch
  it('is none while there is nothing, 204 (M11) or 404, and an error otherwise', async () => {
    expect(await fetchSeries('s', answering(204))).toBeNull()
    expect(await fetchSeries('s', answering(404))).toBeNull()
    await expect(fetchSeries('s', answering(500))).rejects.toThrow('500')
  })
})

describe('laps as they stand (M13.5)', () => {
  const lap = (n: number, time: number, more: Partial<SessionLaps['laps'][number]> = {}): SessionLaps['laps'][number] =>
    ({ lap: n, time, sectors: [], pitIn: false, pitOut: false, start: n * 100_000, end: n * 100_000 + time * 1000, source: 'tablet', ...more })
  const laps = (list: SessionLaps['laps']): SessionLaps => ({ course: 'box', courseName: 'Box', courseVersion: 2, layout: 'box', laps: list })

  it('rows from the server, the best on track, re-timed and flagged laps marked', () => {
    const rows = standingRows(laps([
      lap(1, 70, { source: 'retimed', sectors: [35, 35] }),
      lap(2, 69, { pitIn: true }),
      lap(3, 70.003, { flag: { time: 70, start: 1, end: 2 } }),
      lap(4, 71, { flag: {} }),
    ]))
    expect(rows.map((r) => r.best)).toEqual([true, false, false, false])
    expect(rows[0]!.sectors).toEqual([35, 35])
    expect(rows[1]!.sectors).toBeNull()
    expect(rows.map(lapNote)).toEqual(['Re-timed on version 2', null, 'Re-timing found 1:10.000', 'Re-timing found no such lap'])
    expect(rows[0]!.start).toBe(100_000)
    expect(rows[0]!.end).toBe(170_000)
  })

  it('none where the series stands: still uploading, or at no course', async () => {
    const answer = (status: number, body?: unknown) =>
      (async () => new Response(body === undefined ? null : JSON.stringify(body), { status })) as unknown as typeof fetch
    expect(await fetchLaps('s', answer(204))).toBeNull()
    expect(await fetchLaps('s', answer(404))).toBeNull()
    expect((await fetchLaps('s', answer(200, laps([lap(1, 70)]))))!.laps.length).toBe(1)
    await expect(fetchLaps('s', answer(500))).rejects.toThrow('500')
  })
})

describe('crew messages beside a session (M18.4)', () => {
  it('each where it was sent on the tablet\u2019s clock, saying how soon it was received and shown', () => {
    const sent = 1_790_000_100_000
    expect(messageMarkers([
      { text: 'PIT NOW', state: 'displayed', sentAt: sent, receivedAt: sent + 400, displayedAt: sent + 900, at: t0 + 60_000 },
      { text: 'Push', state: 'received', sentAt: sent, receivedAt: sent + 1_250, at: t0 + 90_000 },
      { text: 'Fuel', state: 'expired', sentAt: sent, at: t0 + 120_000 },
      { text: 'Unplaced', state: 'queued', sentAt: sent, at: null }, // the session's clock unknown: left out
    ])).toEqual([
      { t: (t0 + 60_000) / 1000, kind: 'message', text: 'Crew message “PIT NOW”: received 0.4 s later, shown 0.9 s later' },
      { t: (t0 + 90_000) / 1000, kind: 'message', text: 'Crew message “Push”: received 1.3 s later, not shown' },
      { t: (t0 + 120_000) / 1000, kind: 'message', text: 'Crew message “Fuel”: never received' },
    ])
  })
})

describe('a long session\u2019s page (M19.6)', () => {
  it('opens thinned, and loads the detail once narrowed to half an hour', () => {
    expect(needsDetail(true, null)).toBe(false)
    expect(needsDetail(true, [0, DETAIL_WINDOW_S + 1])).toBe(false)
    expect(needsDetail(true, [100, 100 + DETAIL_WINDOW_S])).toBe(true)
    expect(needsDetail(false, [0, 60])).toBe(false) // already the detail
  })

  it('asks for the thinned series when told', async () => {
    const asked: string[] = []
    const f = (async (url: string) => { asked.push(url); return new Response('{}', { status: 200 }) }) as unknown as typeof fetch
    await fetchSeries('s', f, true)
    await fetchSeries('s', f)
    expect(asked).toEqual(['/api/sessions/s/series?thin=1', '/api/sessions/s/series'])
  })
})

