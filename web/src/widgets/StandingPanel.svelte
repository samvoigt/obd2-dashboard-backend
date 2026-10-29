<script lang="ts">
  import { duration, secondsSince, type Standing } from '../lib/standing'
  import { clockOf, lapTime } from '../lib/sessions'
  import { lastStop, replacedText, setDriverAt, swapMoment, viewerTime, type LiveClocks, type SwapChoice, type SwapLap } from '../lib/driverChanges'
  import type { Driver } from '../lib/events'

  // Where the car stands (M17.5): the server's, counted on by the page between events. The crew sets who's
  // driving here (M34.2): who, then (once the session has laps) when the swap happened.
  let { standing = null, serverNow, crew = false, slug = '', session = null, laps = [], clocks = { newest: null, offsetMs: 0 }, startWall = null }: {
    standing?: Standing | null
    serverNow: number
    /** Logged in as this car's crew: the control shows. */
    crew?: boolean
    slug?: string
    /** The live session, whose driver is set. */
    session?: string | null
    /** Its laps so far, on the tablet's clock. */
    laps?: readonly SwapLap[]
    clocks?: LiveClocks
    /** The session's first moment on the tablet's clock: a time before it is refused. */
    startWall?: number | null
  } = $props()

  const stint = $derived(secondsSince(standing?.driver?.since, serverNow))
  const sinceStop = $derived(secondsSince(standing?.race?.leftPits, serverNow))

  let drivers: Driver[] = $state.raw([])
  let step: 'closed' | 'who' | 'when' = $state('closed')
  let who: string | null = $state(null)
  let lapDraft = $state('')
  let timeDraft = $state('')
  let busy = $state(false)
  let error: string | null = $state(null)
  let confirmed: string | null = $state(null)
  let confirmTimer: ReturnType<typeof setTimeout> | undefined

  const stop = $derived(lastStop(laps))
  const onClock = (wall: number) => { const v = viewerTime(wall, clocks); return v === null ? '' : clockOf(v) }
  const nameOf = (id: string | null) => (id ? drivers.find((d) => d.id === id)?.name ?? id : 'Not set')

  async function open() {
    error = null
    step = 'who'
    if (drivers.length === 0) drivers = await fetch('/api/drivers').then((r) => (r.ok ? r.json() : [])).catch(() => [])
  }

  function close() {
    step = 'closed'
    lapDraft = ''
    timeDraft = ''
  }

  function pick(id: string | null) {
    who = id
    error = null
    // No laps yet: who drove the session from its start.
    if (laps.length === 0) void save(null)
    else step = 'when'
  }

  function choose(choice: SwapChoice) {
    const moment = swapMoment(choice, laps, clocks, Date.now(), startWall)
    if ('error' in moment) { error = moment.error; return }
    void save(moment.from)
  }

  async function save(moment: number | null) {
    if (!session) return
    busy = true
    error = null
    try {
      const saved = await setDriverAt('crew', slug, session, who, moment, fetch)
      const replaced = replacedText(saved, drivers, onClock)
      confirmed = `${nameOf(who)} · ${moment === null ? 'from the start' : `from ${onClock(moment)}`}${replaced ? ` (${replaced})` : ''}`
      clearTimeout(confirmTimer)
      confirmTimer = setTimeout(() => (confirmed = null), 10_000)
      close()
    } catch (e) {
      error = e instanceof TypeError || !(e instanceof Error) ? 'Not saved: no connection.' : e.message
    } finally {
      busy = false
    }
  }
</script>

<section class="standing" aria-label="Where the car stands">
  <div class="driver">
    <div class="name">Driver</div>
    <div class="value">
      {#if standing?.driver?.name}{standing.driver.name}{#if standing.driver.code}{' '}<span class="muted">{standing.driver.code}</span>{/if}
      {:else}Not set{#if !crew}<span class="muted hint"> · the crew sets it here</span>{/if}{/if}
    </div>
    {#if stint !== null}<div class="muted small">stint {duration(stint)}</div>{/if}
    {#if confirmed}<div class="confirmed small" role="status">{confirmed}</div>{/if}
    {#if crew && session && step === 'closed'}
      <button class="set" onclick={open}>{standing?.driver?.name ? 'Change driver' : 'Set driver'}</button>
    {/if}
  </div>
  {#if standing?.race}
    <div>
      <div class="name">Race lap</div>
      <div class="value">{standing.race.lap}</div>
      <div class="muted small">{sinceStop !== null ? `${duration(sinceStop)} since the stop` : 'In the pits'}</div>
    </div>
  {/if}
  {#if standing?.best}
    <div>
      <div class="name">Best</div>
      <div class="value best">{lapTime(standing.best.time)}</div>
      <div class="muted small">{[standing.best.driver, standing.theoretical ? `theoretical ${lapTime(standing.theoretical)}` : ''].filter(Boolean).join(' · ')}</div>
    </div>
  {/if}
  {#if step !== 'closed'}
    <div class="picker">
      {#if step === 'who'}
        <div class="name">Who's driving?</div>
        <div class="choices">
          {#each drivers as d (d.id)}<button disabled={busy} onclick={() => pick(d.id)}>{d.name} <span class="muted">{d.code}</span></button>{/each}
          <button disabled={busy} onclick={() => pick(null)}>Not set</button>
          <button class="cancel" onclick={close}>Cancel</button>
        </div>
      {:else}
        <div class="name">{nameOf(who)} took over…</div>
        <div class="choices">
          <button disabled={busy} onclick={() => choose({ kind: 'now' })}>Now</button>
          {#if stop}<button disabled={busy} onclick={() => choose({ kind: 'stop' })}>At the last stop (lap {stop.lap}, {onClock(stop.start)})</button>{/if}
        </div>
        <form class="choices" onsubmit={(e) => { e.preventDefault(); choose({ kind: 'lap', lap: Number(lapDraft) }) }}>
          <label>From lap <input class="lap" bind:value={lapDraft} inputmode="numeric" aria-label="From lap" /></label>
          <button disabled={busy || !lapDraft.trim()} type="submit">Set</button>
        </form>
        <form class="choices" onsubmit={(e) => { e.preventDefault(); choose({ kind: 'time', at: timeDraft }) }}>
          <label>At <input class="time" bind:value={timeDraft} placeholder="14:05" inputmode="numeric" aria-label="At a time" /></label>
          <button disabled={busy || !timeDraft.trim()} type="submit">Set</button>
        </form>
        <div class="choices"><button class="cancel" onclick={() => (step = 'who')}>Back</button><button class="cancel" onclick={close}>Cancel</button></div>
      {/if}
    </div>
  {/if}
  {#if error}<div class="error picker" role="alert">{error}</div>{/if}
</section>

<style>
  .standing { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 8px; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
  .name { color: var(--muted); font-size: 0.8rem; }
  .value { font-size: 1.4rem; font-weight: 700; font-variant-numeric: tabular-nums; }
  .value.best { color: var(--in-range); }
  .hint { font-size: 0.85rem; font-weight: 400; }
  .small { font-size: 0.85rem; font-variant-numeric: tabular-nums; }
  .confirmed { color: var(--in-range); }
  .set { margin-top: 6px; }
  .picker { grid-column: 1 / -1; display: grid; gap: 8px; }
  .choices { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .choices button { min-height: 44px; }
  .lap { width: 4em; }
  .time { width: 5em; }
  input { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 6px 12px; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  .cancel { color: var(--muted); }
  .error { color: var(--critical); }
  /* A phone (M31.5): what the crew reads in a hurry is never under 0.95rem. */
  @media (max-width: 600px) {
    .name, .small, .hint { font-size: 0.95rem; }
  }
</style>
