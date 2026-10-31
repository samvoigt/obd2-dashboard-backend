<script lang="ts">
  import TermHelp from './widgets/TermHelp.svelte'
  import { onMount } from 'svelte'
  import { whoCanSet, type Driver, type DriverSetter } from './lib/events'
  import { signin } from './lib/signin'
  import { fromLocalInput, toLocalInput } from './lib/events'
  import {
    flagsAt, pitTime, raceClock, racePath, sendRaceEdit, sessionStartingAt, stintSession, stopAfter, timeOfDay,
    type CarRace, type RaceLap, type RaceResults, type RaceSessionResults,
  } from './lib/race'
  import { lapTime } from './lib/sessions'
  import { lapLink } from './lib/laps'
  import LapChart from './LapChart.svelte'
  import type { LapPick } from './lib/comparePick'
  import ComparePick from './ComparePick.svelte'

  // The race (M15.4): one timeline per car, and for the admin or a car's crew, its flags. Its stints follow from who
  // drove each session, set on the session's page (M32, decision 48). A race in sessions (M26) is one race; each
  // session has its own flags.
  let { race, eventId, revision, carName, onChanged, course, layout, merged = {}, sessionTime }: {
    race: RaceResults
    eventId: string
    revision: number
    carName: (slug: string) => string
    onChanged: () => void
    /** The event's course and layout, for comparing laps (M16.4). */
    course: string
    layout: string
    /** Member id → merged session id (M33.6), so a stint links to the page standing for its session (M37.3). */
    merged?: Record<string, string>
    /** A session's start as its link reads it (M37.3), "08:40". */
    sessionTime: (id: string) => string
  } = $props()

  /** A race lap as a compare pick (M16.4). */
  const pickOfLap = (c: CarRace, l: RaceLap): LapPick =>
    ({ ref: { car: c.car, session: l.session, start: l.start, end: l.end }, course, layout, label: `${carName(c.car)} lap ${l.number}, ${lapTime(l.time)}` })

  let drivers: Driver[] = $state.raw([])
  let setters: Record<string, DriverSetter> = $state({})
  // Each session's flags as typed, by its part's id.
  let green: Record<string, string> = $state({})
  let flag: Record<string, string> = $state({})
  const inSessions = $derived(race.sessions.length > 1)
  const sessionName = (part: string) => race.sessions.find((s) => s.part.id === part)?.part.name ?? part
  /** A time as "Sat 14:05:00" in a race over days, else "14:05:00". */
  const clock = (ms: number) =>
    new Date(ms).toLocaleString('en-GB', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', ...(inSessions ? { weekday: 'short' } : {}) })
  let busy = $state(false)
  // The flags form, behind "Edit the race" (M37.4).
  let editingRace = $state(false)
  let message: string | null = $state(null)

  /** A flag's input value, to the second. */
  const toSeconds = (ms: number) => `${toLocalInput(ms)}:${String(new Date(ms).getSeconds()).padStart(2, '0')}`
  const driverName = (id: string | null | undefined) => (id ? (drivers.find((d) => d.id === id)?.name ?? id) : 'Driver not set')
  const anyone = $derived(Object.values(setters).find((s) => s !== null) ?? null)

  onMount(async () => {
    drivers = await fetch('/api/drivers').then((r) => (r.ok ? r.json() : [])).catch(() => [])
    for (const s of race.sessions) {
      green[s.part.id] = s.green ? toSeconds(s.green) : ''
      flag[s.part.id] = s.flag ? toSeconds(s.flag) : ''
    }
  })

  // Who may set each car's flags: asked again when the header signs in or out (M21.1).
  $effect(() => {
    void $signin
    const cars = race.cars.map((c) => c.car)
    void (async () => {
      const next: typeof setters = {}
      // Flags go through the event's admin routes (M23): an editor of the event, else the car's crew.
      for (const car of cars) next[car] = await whoCanSet(car, fetch, signin, { kind: 'event', id: eventId })
      setters = next
    })()
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

  function saveFlags(s: RaceSessionResults, car: string, who: 'admin' | 'crew') {
    const body = { expected: revision, green: fromLocalInput(green[s.part.id] ?? ''), flag: fromLocalInput(flag[s.part.id] ?? ''), part: s.part.id }
    return edit(() => sendRaceEdit(racePath(who, eventId, car), body))
  }
</script>

<section class="panel">
  <h2>{inSessions ? 'The race' : race.sessions[0]?.part.name}</h2>
  <p class="muted small">
    Every lap counts, before the green flag and after the flag too.
    {#if inSessions}One race in {race.sessions.length} sessions: laps and time add up across them, and the break between is never a lap.{/if}
    {#if race.cars.some((c) => c.laps.some((l) => l.live))}Laps marked live are the stint being driven: as the tablet sent them, until its session is uploaded.{/if}
  </p>

  {#if race.cars.length === 0}
    <p class="muted">No laps yet.</p>
  {:else}
    <div class="table-scroll">
      <table class="results">
      <thead><tr><th></th><th>Car</th><th>Laps</th><th>Time</th><th>Best</th><th>Stops</th></tr></thead>
      <tbody>
        {#each race.cars as c, i (c.car)}
          <tr><td class="pos">{i + 1}</td><td>{carName(c.car)}</td><td>{c.laps.length}</td><td>{raceClock(c.seconds)}</td>
            <td>{c.best ? lapTime(c.best.time) : '—'}</td><td>{c.stops.length}</td></tr>
        {/each}
      </tbody>
    </table>
    </div>
    <LapChart cars={race.cars} {carName} />
  {/if}

  {#if anyone}
    <p><button aria-expanded={editingRace} onclick={() => (editingRace = !editingRace)}>{editingRace ? 'Done editing' : 'Edit the race'}</button></p>
  {/if}
  {#if anyone && editingRace}
    {@const car = Object.entries(setters).find(([, s]) => s)?.[0] ?? race.cars[0]?.car ?? ''}
    <div class="edit">
      <h3>The flags</h3>
      <p class="muted small">
        {#each race.sessions as s, i (s.part.id)}
          {#if inSessions}{i > 0 ? ' ' : ''}<strong>{s.part.name}:</strong>{/if}
          {#if s.green}Green flag {clock(s.green)}{:else}No green flag entered{/if}
          · {#if s.flag}flag {clock(s.flag)}{:else}no flag entered{/if}.
        {/each}
      </p>
      {#each race.sessions as s (s.part.id)}
        {@const id = s.part.id}
        <p class="row">
          {#if inSessions}<strong>{s.part.name}</strong>{/if}
          <label>Green flag <input type="datetime-local" step="1" bind:value={green[id]} /></label>
          <button onclick={() => (green[id] = toSeconds(Date.now()))}>Now</button>
          <label>Flag <input type="datetime-local" step="1" bind:value={flag[id]} /></label>
          <button onclick={() => (flag[id] = toSeconds(Date.now()))}>Now</button>
          <button class="primary" disabled={busy} onclick={() => saveFlags(s, car, anyone)}>Save the flags</button>
        </p>
      {/each}
    </div>
  {/if}
  {#if message}<p class="error">{message}</p>{/if}

  {#each race.cars as c (c.car)}
    {@const offset = race.tabletOffset[c.car]}
    <h3>{carName(c.car)}</h3>
    {@const shown = c.stints.filter((s) => s.laps > 0)}
    <div class="table-scroll">
      <table class="stints">
      <thead><tr><th><TermHelp term="stint" /></th><th>Session</th><th>Driver</th><th>Laps</th><th>Time</th><th>Best</th><th><TermHelp term="median" /></th><th><TermHelp term="spread" /></th><th><TermHelp term="stop" /></th></tr></thead>
      <tbody>
        {#each shown as s (s.number)}
          {@const session = stintSession(c, s, merged)}
          {@const stop = stopAfter(c, s)}
          <tr><td>{s.number}</td>
            <td>{#if session}<a href={`/cars/${c.car}/sessions/${session}`} title="Who drove is set on this session's page">{sessionTime(session)}</a>{/if}</td>
            <td>{#if s.driver}<a href={`/drivers/${s.driver}`}>{driverName(s.driver)}</a>{:else}{driverName(s.driver)}{/if}</td>
            <td>{s.firstLap}–{s.lastLap} ({s.laps})</td><td>{raceClock(s.seconds)}</td>
            <td>{s.best ? lapTime(s.best) : '—'}</td>
            <td>{s.consistency ? lapTime(s.consistency.median) : '—'}</td><td>{s.consistency ? `± ${s.consistency.spread.toFixed(3)} s` : '—'}</td>
            <td class="stop">{stop ? (stop.seconds != null ? pitTime(stop.seconds) : 'ended in the pits') : ''}</td></tr>
        {/each}
      </tbody>
    </table>
    </div>
    {#if shown.length < c.stints.length}
      {@const hidden = c.stints.length - shown.length}
      <p class="muted small">{hidden} stint{hidden === 1 ? '' : 's'} with no laps not shown.</p>
    {/if}
    {#if c.theoretical}<p class="small"><TermHelp term="theoretical" /> <strong>{lapTime(c.theoretical)}</strong> <span class="muted">(the race's best of each sector added up)</span></p>{/if}
    <p class="muted small">Who drove is set on each session's page: follow its time in the Session column.</p>

    <details>
      <summary>Every lap ({c.laps.length})</summary>
      <div class="table-scroll">
        <table class="laps">
        <thead><tr><th>Lap</th><th>Time</th><th>Driver</th><th>Time of day</th><th></th></tr></thead>
        <tbody>
          {#each c.laps as l (l.number)}
            {@const starts = sessionStartingAt(c, l.number)}
            {@const flags = flagsAt(c, l.number)}
            {#if starts}<tr class="session"><td colspan="6">{sessionName(starts)}</td></tr>{/if}
            <tr class:best={c.best?.number === l.number}>
              <td><a href={lapLink(c.car, l.session, l.start, l.end)}>{l.number}</a></td><td class="time">{lapTime(l.time)}</td>
              <td>{driverName(c.stints[l.stint - 1]?.driver)}</td>
              <td class="muted">{timeOfDay(l.end, offset)}</td>
              <td class="muted">
                {[
                  flags.green ? 'Green flag' : '',
                  flags.flag ? 'Flag' : '',
                  l.source === 'restart' ? 'Across a restart of the app' : l.source === 'retimed' ? 'Re-timed' : '',
                  l.pitIn ? 'Into the pits' : '',
                  l.pitOut ? 'Out of the pits' : '',
                  l.live ? 'Live' : '',
                ].filter(Boolean).join(' · ')}
              </td>
              <td><ComparePick lap={pickOfLap(c, l)} best={c.best ? pickOfLap(c, c.best) : null} /></td>
            </tr>
          {/each}
        </tbody>
      </table>
      </div>
    </details>
  {/each}
</section>

<style>
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; }
  h2 { margin: 0 0 4px; }
  h3 { margin: 16px 0 6px; }
  table { border-collapse: collapse; }
  th { text-align: left; color: var(--muted); font-weight: 600; font-size: 0.85rem; padding: 4px 8px; }
  td { padding: 5px 8px; border-top: 1px solid var(--line); font-variant-numeric: tabular-nums; }
  /* One line a stint (M37.3): on a phone the table scrolls sideways instead. */
  .stints td { white-space: nowrap; }
  .stints td.stop { white-space: normal; }
  .pos { color: var(--muted); }
  .time { font-weight: 600; }
  tr.best .time { color: var(--in-range); }
  .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 6px 0; }
  .edit { border: 1px solid var(--line); border-radius: 8px; padding: 8px; margin: 8px 0; }
  .error { color: var(--critical); }
  details { margin-top: 8px; }
  tr.session td { font-weight: 600; color: var(--muted); padding-top: 10px; }
</style>
