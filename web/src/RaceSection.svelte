<script lang="ts">
  import { onMount } from 'svelte'
  import { whoCanSet, type Driver, type DriverSetter } from './lib/events'
  import { fromLocalInput, toLocalInput } from './lib/events'
  import {
    marksOf, mergeWithPrevious, raceClock, racePath, sendRaceEdit, setDriver, splitAt, timeOfDay,
    type CarRace, type RaceResults, type StintMark,
  } from './lib/race'
  import { lapTime } from './lib/sessions'

  // The race (M15.4): one timeline per car, and for the admin or a car's crew, its flags and stints.
  let { race, eventId, revision, carName, onChanged }: {
    race: RaceResults
    eventId: string
    revision: number
    carName: (slug: string) => string
    onChanged: () => void
  } = $props()

  let drivers: Driver[] = $state.raw([])
  let setters: Record<string, DriverSetter> = $state({})
  let editing: string | null = $state(null) // the car whose stints are open
  let marks: StintMark[] = $state.raw([])
  let splitLap = $state('')
  let green = $state('')
  let flag = $state('')
  let busy = $state(false)
  let message: string | null = $state(null)

  /** A flag's input value, to the second. */
  const toSeconds = (ms: number) => `${toLocalInput(ms)}:${String(new Date(ms).getSeconds()).padStart(2, '0')}`
  const driverName = (id: string | null | undefined) => (id ? (drivers.find((d) => d.id === id)?.name ?? id) : 'Driver not set')
  const anyone = $derived(Object.values(setters).find((s) => s !== null) ?? null)

  onMount(async () => {
    drivers = await fetch('/api/drivers').then((r) => (r.ok ? r.json() : [])).catch(() => [])
    for (const c of race.cars) setters[c.car] = await whoCanSet(c.car)
    green = race.green ? toSeconds(race.green) : ''
    flag = race.flag ? toSeconds(race.flag) : ''
  })

  async function edit(action: () => Promise<number>) {
    busy = true
    message = null
    try {
      await action()
      onChanged()
    } catch (e) {
      message = e instanceof Error ? e.message : String(e)
    } finally {
      busy = false
    }
  }

  function saveFlags(car: string, who: 'admin' | 'crew') {
    const body = { expected: revision, green: fromLocalInput(green), flag: fromLocalInput(flag) }
    return edit(() => sendRaceEdit(racePath(who, eventId, car, 'flags'), body))
  }

  function open(c: CarRace) {
    editing = c.car
    marks = marksOf(c)
    splitLap = ''
  }

  function saveStints(car: string, who: 'admin' | 'crew', stints: StintMark[] | null) {
    return edit(async () => {
      const r = await sendRaceEdit(racePath(who, eventId, car, 'stints'), { expected: revision, stints })
      editing = null
      return r
    })
  }
</script>

<section class="panel">
  <h2>{race.part.name}</h2>
  <p class="muted small">
    {#if race.green}Green flag {new Date(race.green).toLocaleTimeString('en-GB', { hour12: false })}{:else}No green flag entered{/if}
    · {#if race.flag}flag {new Date(race.flag).toLocaleTimeString('en-GB', { hour12: false })}{:else}no flag entered{/if}.
    Every lap counts, before the green flag and after the flag too.
  </p>

  {#if race.cars.length === 0}
    <p class="muted">No laps yet.</p>
  {:else}
    <table class="results">
      <thead><tr><th></th><th>Car</th><th>Laps</th><th>Time</th><th>Best</th><th>Stops</th></tr></thead>
      <tbody>
        {#each race.cars as c, i (c.car)}
          <tr><td class="pos">{i + 1}</td><td>{carName(c.car)}</td><td>{c.laps.length}</td><td>{raceClock(c.seconds)}</td>
            <td>{c.best ? lapTime(c.best.time) : '—'}</td><td>{c.stops.length}</td></tr>
        {/each}
      </tbody>
    </table>
  {/if}

  {#if anyone}
    {@const car = Object.entries(setters).find(([, s]) => s)?.[0] ?? race.cars[0]?.car ?? ''}
    <div class="edit">
      <h3>The flags</h3>
      <p class="row">
        <label>Green flag <input type="datetime-local" step="1" bind:value={green} /></label>
        <button onclick={() => (green = toSeconds(Date.now()))}>Now</button>
        <label>Flag <input type="datetime-local" step="1" bind:value={flag} /></label>
        <button onclick={() => (flag = toSeconds(Date.now()))}>Now</button>
        <button class="primary" disabled={busy} onclick={() => saveFlags(car, anyone)}>Save the flags</button>
      </p>
    </div>
  {/if}
  {#if message}<p class="error">{message}</p>{/if}

  {#each race.cars as c (c.car)}
    {@const offset = race.tabletOffset[c.car]}
    {@const who = setters[c.car]}
    <h3>{carName(c.car)}</h3>
    <table class="stints">
      <thead><tr><th>Stint</th><th>Driver</th><th>Laps</th><th>Time</th><th>Best</th></tr></thead>
      <tbody>
        {#each c.stints as s (s.number)}
          <tr><td>{s.number}</td><td>{#if s.driver}<a href={`/drivers/${s.driver}`}>{driverName(s.driver)}</a>{:else}{driverName(s.driver)}{/if}</td>
            <td>{s.laps > 0 ? `${s.firstLap}–${s.lastLap} (${s.laps})` : '—'}</td><td>{raceClock(s.seconds)}</td>
            <td>{s.best ? lapTime(s.best) : '—'}</td></tr>
        {/each}
      </tbody>
    </table>
    <p class="muted small">{c.stintsEdited ? 'Stints as set by hand.' : 'Split at every stop; merge a fuel stop, or split where drivers changed without one.'}</p>
    {#if c.stops.length > 0}
      <p class="small">Stops: {#each c.stops as s, i (s.entry)}{i > 0 ? ' · ' : ''}lap {s.lap}, {s.seconds != null ? `${s.seconds.toFixed(1)} s in the pit lane` : 'ended in the pits'}{/each}</p>
    {/if}

    {#if who}
      {#if editing === c.car}
        <div class="edit">
          {#each marks as m, i (m.start)}
            <p class="row">
              Stint {i + 1}, from {timeOfDay(m.start, offset) || `lap ${c.laps.find((l) => l.end > m.start)?.number ?? '—'}`}:
              <select value={m.driver ?? ''} onchange={(e) => (marks = setDriver(marks, i, e.currentTarget.value || null))} aria-label={`Stint ${i + 1}'s driver`}>
                <option value="">Driver not set</option>
                {#each drivers as d (d.id)}<option value={d.id}>{d.name} ({d.code})</option>{/each}
              </select>
              {#if i > 0}<button onclick={() => (marks = mergeWithPrevious(marks, i))}>Merge with stint {i}</button>{/if}
            </p>
          {/each}
          <p class="row">
            <label>Split at lap <input class="lap" bind:value={splitLap} inputmode="numeric" /></label>
            <button onclick={() => (marks = splitAt(marks, c.laps, Number(splitLap)))}>Split</button>
          </p>
          <p class="row">
            <button class="primary" disabled={busy} onclick={() => saveStints(c.car, who, marks)}>Save the stints</button>
            <button disabled={busy} onclick={() => saveStints(c.car, who, null)}>Back to splitting at every stop</button>
            <button onclick={() => (editing = null)}>Cancel</button>
          </p>
        </div>
      {:else}
        <p><button onclick={() => open(c)}>Edit stints</button></p>
      {/if}
    {/if}

    <details>
      <summary>Every lap ({c.laps.length})</summary>
      <table class="laps">
        <thead><tr><th>Lap</th><th>Time</th><th>Driver</th><th>Time of day</th><th></th></tr></thead>
        <tbody>
          {#each c.laps as l (l.number)}
            <tr class:best={c.best?.number === l.number}>
              <td>{l.number}</td><td class="time">{lapTime(l.time)}</td>
              <td>{driverName(c.stints[l.stint - 1]?.driver)}</td>
              <td class="muted">{timeOfDay(l.end, offset)}</td>
              <td class="muted">
                {[
                  l.number === c.greenLap ? 'Green flag' : '',
                  l.number === c.flagLap ? 'Flag' : '',
                  l.source === 'restart' ? 'Across a restart of the app' : l.source === 'retimed' ? 'Re-timed' : '',
                  l.pitIn ? 'Into the pits' : '',
                  l.pitOut ? 'Out of the pits' : '',
                ].filter(Boolean).join(' · ')}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </details>
  {/each}
</section>

<style>
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; overflow-x: auto; }
  h2 { margin: 0 0 4px; }
  h3 { margin: 16px 0 6px; }
  table { border-collapse: collapse; }
  th { text-align: left; color: var(--muted); font-weight: 600; font-size: 0.85rem; padding: 4px 8px; }
  td { padding: 5px 8px; border-top: 1px solid var(--line); font-variant-numeric: tabular-nums; }
  .pos { color: var(--muted); }
  .time { font-weight: 600; }
  tr.best .time { color: var(--in-range); }
  .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 6px 0; }
  .edit { border: 1px solid var(--line); border-radius: 8px; padding: 8px; margin: 8px 0; }
  .lap { width: 4em; }
  .error { color: var(--critical); }
  details { margin-top: 8px; }
</style>
