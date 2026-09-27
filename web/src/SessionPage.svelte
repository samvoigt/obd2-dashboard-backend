<script lang="ts">
  import { onMount } from 'svelte'
  import Chart from './Chart.svelte'
  import SessionMap from './SessionMap.svelte'
  import {
    defaultSignals, events, fetchSeries, joined, lapRows, speedsAtPositions, unitOf,
    type LapRow, type Series,
  } from './lib/sessionPage'
  import { badge, clockOf, dayOf, duration, lapTime, trackOf, type SessionItem } from './lib/sessions'

  let { slug, id }: { slug: string; id: string } = $props()

  interface Detail {
    session: SessionItem
    car: string
    carName: string
    gaps: number
    missed: number
  }

  let detail = $state<Detail | null>(null)
  let series = $state<Series | null>(null)
  let missing = $state(false)
  let error = $state<string | null>(null)

  let chosen: string[] = $state([])
  let range = $state<[number, number] | null>(null)
  let chosenLap = $state<number | null>(null)
  let cursor = $state<number | null>(null)

  const names = $derived(series ? (chosen.length > 0 ? chosen : defaultSignals(series)) : [])
  const data = $derived(series ? joined(series, names) : null)
  const units = $derived(series ? names.map((n) => unitOf(series!, n)) : [])
  const numbers = $derived(series ? Object.keys(series.numbers).sort() : [])
  const laps: LapRow[] = $derived(series ? lapRows(series) : [])
  const happened = $derived(series ? events(series) : [])
  const markers = $derived(happened.map((m) => ({ t: m.t, color: m.kind === 'fault' ? '#ff5c5c' : m.kind === 'gap' ? '#ffb020' : '#8b97a5' })))
  const positions = $derived(series ? series.positions.t.map((t) => series!.t0 + t) : [])
  const speeds = $derived(series ? speedsAtPositions(series) : [])

  onMount(async () => {
    try {
      const response = await fetch(`/api/sessions/${id}`)
      if (response.status === 404) {
        missing = true
        return
      }
      if (!response.ok) throw new Error(`The server answered ${response.status}.`)
      detail = (await response.json()) as Detail
      series = await fetchSeries(id)
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  })

  function pick(index: number, value: string) {
    const next = [...names]
    if (value) next[index] = value
    else next.splice(index, 1)
    chosen = [...new Set(next.filter(Boolean))]
  }

  function showLap(lap: LapRow) {
    chosenLap = lap.lap
    range = [lap.start / 1000, lap.end / 1000]
  }

  function showAll() {
    chosenLap = null
    if (data && data[0].length > 0) range = [data[0][0]!, data[0][data[0].length - 1]!]
  }
</script>

<main>
  <p class="back"><a href={`/cars/${slug}/sessions`}>← Past sessions</a></p>

  {#if missing}
    <h1>No such session</h1>
  {:else if error}
    <p class="error">Could not load the session: {error}</p>
  {:else if detail}
    {@const s = detail.session}
    {@const b = badge(s.state)}
    <h1>{detail.carName} <span class="muted">· {dayOf(s.started)}, {clockOf(s.started)}</span></h1>
    <p class="facts">
      <span>{duration(s.ended - s.started)}</span>
      {#if trackOf(s)}<span>{trackOf(s)}</span>{/if}
      {#if s.bestLap}<span>Best <strong>{lapTime(s.bestLap.time)}</strong> <span class="muted">(lap {s.bestLap.lap})</span></span>{/if}
      {#if s.faults.length > 0}<span class="fault">{s.faults.join(', ')}</span>{/if}
      {#if b}<span class={`badge ${b.kind}`}><span class={`dot ${b.kind}`}></span>{b.text}</span>{/if}
      {#if s.state === 'live'}<a href={`/cars/${slug}`}>Watch live →</a>{/if}
    </p>
    {#if s.state === 'uploading' || s.state === 'live'}
      <p class="muted small">Still being uploaded: this shows what has arrived so far. Reload for more.</p>
    {/if}

    {#if !series}
      <p class="muted">Loading the session's data…</p>
    {:else}
      <section class="panel">
        <div class="pickers">
          {#each [0, 1] as i (i)}
            <select value={names[i] ?? ''} onchange={(e) => pick(i, (e.currentTarget as HTMLSelectElement).value)}>
              {#if i > 0}<option value="">(none)</option>{/if}
              {#each numbers as n (n)}<option value={n}>{n}</option>{/each}
            </select>
          {/each}
          <span class="muted small">Drag across the chart to zoom in; double-click to zoom out.</span>
        </div>
        {#if data && data[0].length > 0}
          <Chart {data} {names} {units} zoom {range} {markers} onCursor={(t) => (cursor = t === null ? null : t * 1000)} />
        {:else}
          <p class="muted">No readings to chart.</p>
        {/if}
      </section>

      {#if positions.length > 0}
        <section class="panel">
          <h2>Where it went</h2>
          <SessionMap t={positions} lat={series.positions.lat} lon={series.positions.lon} {speeds} {cursor} />
          <p class="muted small">Coloured by speed, blue slow to red fast. The dot follows the chart's cursor.</p>
        </section>
      {/if}

      {#if laps.length > 0}
        <section class="panel">
          <h2>Laps</h2>
          <table class="laps">
            <thead><tr><th>Lap</th><th>Time</th><th></th></tr></thead>
            <tbody>
              {#each laps as lap (lap.lap)}
                <tr class:best={lap.best} class:chosen={chosenLap === lap.lap} onclick={() => showLap(lap)}>
                  <td>{lap.lap}</td>
                  <td class="time">{lapTime(lap.time)}</td>
                  <td class="muted">{lap.best ? 'Best' : lap.pitIn ? 'Into the pits' : lap.pitOut ? 'Out of the pits' : ''}</td>
                </tr>
              {/each}
            </tbody>
          </table>
          <p class="muted small">Choose a lap to zoom the chart to it. {#if chosenLap !== null}<button class="link" onclick={showAll}>Show the whole session</button>{/if}</p>
        </section>
      {/if}

      {#if happened.length > 0}
        <section class="panel">
          <h2>What happened</h2>
          <ul class="events">
            {#each happened as m, i (i)}
              <li class={m.kind}><span class="muted">{clockOf(m.t * 1000)}</span> {m.text}</li>
            {/each}
          </ul>
        </section>
      {/if}
    {/if}
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  .back { margin: 0 0 8px; }
  .back a { color: var(--muted); text-decoration: none; }
  h1 { overflow-wrap: anywhere; }
  h2 { font-size: 1rem; margin: 0 0 10px; }
  .facts { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: center; margin: 0 0 8px; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; margin: 16px 0; }
  .pickers { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 8px; }
  select { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-size: 0.95rem; max-width: 100%; }
  .laps { border-collapse: collapse; width: 100%; max-width: 420px; }
  .laps th { text-align: left; color: var(--muted); font-weight: 600; font-size: 0.85rem; padding: 4px 8px; }
  .laps td { padding: 6px 8px; border-top: 1px solid var(--line); }
  .laps tbody tr { cursor: pointer; }
  .laps tbody tr:hover td { background: var(--bg); }
  .laps .time { font-variant-numeric: tabular-nums; font-weight: 600; }
  .laps tr.best .time { color: var(--live); }
  .laps tr.chosen td { background: var(--bg); }
  .events { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; font-size: 0.95rem; }
  .events li.fault { color: var(--danger); }
  .events li.gap { color: var(--stale); }
  .badge { display: inline-flex; align-items: center; font-size: 0.85rem; font-weight: 600; }
  .badge.live { color: var(--live); }
  .badge.stale { color: var(--stale); }
  .badge.offline { color: var(--muted); }
  .fault { color: var(--danger); font-weight: 600; }
  .small { font-size: 0.85rem; }
  .error { color: var(--danger); }
  button.link { background: none; border: none; color: var(--idle); text-decoration: underline; padding: 0; cursor: pointer; font-size: inherit; }
</style>
