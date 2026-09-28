import { describe, expect, it } from 'vitest'
import { lapChartData } from './lapChart'
import type { CarRace, RaceLap } from './race'

const lap = (number: number, time: number, more: Partial<RaceLap> = {}): RaceLap =>
  ({ number, session: 's', start: 0, end: 0, time, sectors: [], pitIn: false, pitOut: false, source: 'retimed', stint: 1, ...more })

const car = (name: string, laps: RaceLap[], more: Partial<CarRace> = {}): CarRace =>
  ({ car: name, laps, stops: [], stints: [], seconds: 0, ...more })

describe('the lap-time chart (M16.3)', () => {
  it('a series per car by lap number, slow laps drawn at 130% of its best on track', () => {
    const d = lapChartData([
      car('outback', [lap(1, 70), lap(2, 145, { pitOut: true }), lap(3, 69), lap(4, 91, { source: 'restart' })]),
      car('yaris', [lap(1, 80), lap(2, 81)]),
    ])
    expect(d.x).toEqual([1, 2, 3, 4])
    expect(d.ys[0]).toEqual([70, 69 * 1.3, 69, 69 * 1.3])
    expect(d.capped[0]).toEqual([2, 4])
    expect(d.ys[1]).toEqual([80, 81, null, null])
    expect(d.capped[1]).toEqual([])
  })

  it('a lap under the cap is drawn as it is, even an out-lap', () => {
    expect(lapChartData([car('c', [lap(1, 70), lap(2, 80, { pitOut: true })])]).ys[0]).toEqual([70, 80])
  })

  it('the cap comes from the best lap on track, never a quick lap across a restart', () => {
    const d = lapChartData([car('c', [lap(1, 70), lap(2, 30, { source: 'restart' })])])
    expect(d.ys[0]).toEqual([70, 30])
    expect(d.capped[0]).toEqual([])
  })

  it('a stint with no laps isn\u2019t shaded', () => {
    const d = lapChartData([car('c', [lap(1, 70)], {
      stints: [{ number: 1, start: 0, firstLap: 1, lastLap: 1, laps: 1, seconds: 70 }, { number: 2, start: 9, firstLap: null, lastLap: null, laps: 0, seconds: 0 }],
    })])
    expect(d.stints).toEqual([{ from: 1, to: 1, driver: null }])
  })

  it('the stints shaded and the stops and flags marked, from the first car', () => {
    const d = lapChartData([car('c', [lap(1, 70), lap(2, 70), lap(3, 70)], {
      stints: [{ number: 1, driver: 'd-sam', start: 0, firstLap: 1, lastLap: 2, laps: 2, seconds: 140 }, { number: 2, start: 5, firstLap: 3, lastLap: 3, laps: 1, seconds: 70 }],
      stops: [{ lap: 2, entry: 0, exit: 1, seconds: 100 }],
      greenLap: 1,
      flagLap: 3,
    })])
    expect(d.stints).toEqual([{ from: 1, to: 2, driver: 'd-sam' }, { from: 3, to: 3, driver: null }])
    expect(d.marks).toEqual([{ lap: 2, kind: 'stop' }, { lap: 1, kind: 'green' }, { lap: 3, kind: 'flag' }])
  })

  it('nothing to draw for no cars', () => {
    expect(lapChartData([])).toEqual({ x: [], ys: [], capped: [], stints: [], marks: [] })
  })
})
