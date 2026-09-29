<script lang="ts">
  import { onMount, untrack } from 'svelte'
  import SessionMap from './SessionMap.svelte'
  import Faults from './widgets/Faults.svelte'
  import Gauge from './widgets/Gauge.svelte'
  import GMeter from './widgets/GMeter.svelte'
  import LapsPanel from './widgets/LapsPanel.svelte'
  import StandingPanel from './widgets/StandingPanel.svelte'
  import DriverPicker from './widgets/DriverPicker.svelte'
  import Readout from './widgets/Readout.svelte'
  import Status from './widgets/Status.svelte'
  import UnitsSwitch from './widgets/UnitsSwitch.svelte'
  import Bar from './widgets/Bar.svelte'
  import {
    dashboardLayout, fetchLiveLaps, freshnessOf, gTrail, lapsFrom, lapsFromLive, NO_PEAKS, peaks, shownIn, timings, valueOf, type LiveLap, type Peaks,
  } from './lib/dashboard'
  import { sourceLabel } from './lib/sessions'
  import { nearest } from './lib/sessionPage'
  import { columnShown, shownUnit } from './lib/units'
  import { units } from './lib/unitsState.svelte'
  import Chart from './Chart.svelte'
  import MessagePanel from './MessagePanel.svelte'
  import { applyList, applyOne, type CrewMessage } from './lib/messages'
  import {
    applyRecords, applySession, applySnapshot, applyStatus, applyTiming, defaultChart, empty, freshness,
    label, numericSignals, series, unitOf, type LiveState,
  } from './lib/live'
  import { stateLabel } from './lib/state'
  import { merge } from './lib/merge'
  import { color, translucent } from './lib/theme'
  import { fetchSeries, joined, type Series } from './lib/sessionPage'

  let { slug }: { slug: string } = $props()

  // Raw: every event replaces the whole state, so it needs no deep proxies, which made a page
  // of widgets crawl (M8.2).
  let live: LiveState = $state.raw(empty())
  let now = $state(Date.now())
  let chosen: string[] = $state([])
  let notFound = $state(false)
  let connected = $state(false)
  let crew = $state(false)
  let messages: CrewMessage[] = $state([])
  let reconnect: () => void = () => {}

  const fresh = $derived(freshness(live, now))
  const bannerText = $derived(
    fresh.state === 'stale'
      ? fresh.seconds === null ? 'Waiting for data' : `Last data ${fresh.seconds} s ago`
      : stateLabel[fresh.state],
  )
  const names = $derived(
    [...new Set([...live.signals.map((s) => s.name), ...Object.keys(live.latest), ...Object.keys(live.stopped)])].sort(),
  )

  // The dashboard (M8): its readings, and whether each is current, by one pass over the history per batch.
  const serverNow = $derived(now - live.offsetMs)
  // Every signal the car sends, in its widget (M18.5); timed in one pass, only what's shown (M8.4).
  const dash = $derived(dashboardLayout(live.signals, live.latest))
  const timing = $derived(timings(live.history, shownIn(dash)))
  // Each slot shows the first of its signals this session sends (M11).
  const current = (n: string) => freshnessOf(n, timing, live.latest[n], !!live.stopped[n], serverNow)
  const trail = $derived(gTrail(live.history, serverNow))
  let peak: Peaks = $state(NO_PEAKS)
  // Peaks since the page opened; read untracked, or the effect would rerun itself (M8.2).
  $effect(() => { const next = trail; peak = peaks(untrack(() => peak), next) })
  const positions = $derived(live.history.filter((p) => p.rec.signal === 'gps.position' && typeof p.rec.lat === 'number' && typeof p.rec.lon === 'number'))
  const trailSpeeds = $derived.by(() => {
    const speed = live.history.filter((p) => p.rec.signal === 'gps.speed' && typeof p.rec.value === 'number')
    const times = speed.map((p) => p.t)
    return positions.map((p) => { const i = nearest(times, p.t); return i < 0 ? null : (speed[i]!.rec.value as number) })
  })
  const codes = $derived(Array.isArray(live.fault?.codes) ? (live.fault.codes as string[]).filter((c) => typeof c === 'string') : [])

  /** The chart's columns in the viewer's units (M8.3). */
  function inUnits(data: [number[], ...(number | null | undefined)[][]]): [number[], ...(number | null | undefined)[][]] {
    const [x, ...ys] = data
    return [x, ...ys.map((y, i) => columnShown(y, unitOf(live, chartNames[i] ?? ''), units.system))]
  }

  const chartNames = $derived(chosen.length > 0 ? chosen : defaultChart(live))
  const chartUnits = $derived(chartNames.map((n) => shownUnit(unitOf(live, n), units.system)))
  // The session so far, for "Whole session" (M7.6), fetched **only while it's shown** (M19.4): for a session
  // still uploading the server sends it thinned and builds it as it grows; a long race's whole session each
  // minute for every viewer, full-size, ran production out of memory (M19.1).
  let whole = $state(false)
  let archived = $state.raw<Series | null>(null)
  const liveId = $derived(typeof live.session?.id === 'string' ? live.session.id : null)
  $effect(() => {
    const id = liveId
    if (!id || !whole) {
      archived = null
      return
    }
    let stopped = false
    const load = () => fetchSeries(id).then((s) => { if (!stopped) archived = s }).catch(() => {})
    load()
    const timer = setInterval(load, 60_000) // usually a 304
    return () => { stopped = true; clearInterval(timer) }
  })

  // Laps (M19.4): the server's live run holds them; once it doesn't (the session complete), the series once.
  let held = $state.raw<LiveLap[] | null>(null)
  let lapSeries = $state.raw<Series | null>(null)
  $effect(() => {
    const id = liveId
    held = null
    lapSeries = null
    if (!id) return
    let stopped = false
    const load = async () => {
      const laps = await fetchLiveLaps(id).catch(() => null)
      if (stopped) return
      held = laps
      if (laps === null && lapSeries === null) lapSeries = await fetchSeries(id).catch(() => null)
    }
    load()
    const timer = setInterval(load, 30_000)
    return () => { stopped = true; clearInterval(timer) }
  })

  const laps = $derived(held !== null ? lapsFromLive(held, live.history) : lapsFrom(lapSeries ?? archived, live.history))

  // The chart redraws at most every half second (a second for the whole session): the eye cannot use
  // more, and a phone should not work harder.
  let chartData: [number[], ...(number | null | undefined)[][]] = $state.raw([[]])
  let chartBands: { from: number; to: number; color: string }[] = $state.raw([])
  let lastChart = 0
  $effect(() => {
    const t = now
    if (t - lastChart < (whole ? 1000 : 500)) return
    lastChart = t
    if (whole) {
      const m = merge(archived, live.history.map((p) => p.rec as Record<string, unknown>), live.signals as Series['signals'])
      chartData = inUnits(joined(m.series, chartNames))
      const end = chartData[0][chartData[0].length - 1]
      chartBands = m.provisionalFrom !== null && end !== undefined ? [{ from: m.provisionalFrom / 1000, to: end, color: translucent(color('caution'), 0.1) }] : []
    } else {
      chartData = inUnits(series(live, chartNames))
      chartBands = []
    }
  })

  function pick(index: number, value: string) {
    const next = [...chartNames]
    if (value === '') next.splice(index, 1)
    else next[index] = value
    chosen = [...new Set(next)]
  }

  async function checkCrew() {
    const r = await fetch(`/api/cars/${slug}/crew`).catch(() => null)
    crew = r?.ok ? ((await r.json()) as { crew: boolean }).crew : false
    if (!crew) messages = []
  }

  onMount(() => {
    let source: EventSource | null = null
    // EventSource reconnects by itself; each snapshot is the whole truth (M4.4).
    // Crew or public is decided when it connects (M5.5), so a login or logout opens a new one.
    const open = () => {
      source?.close()
      const s = new EventSource(`/api/cars/${slug}/live`)
      source = s
      const on = (name: string, apply: (st: LiveState, e: Record<string, unknown>, t: number) => LiveState) =>
        s.addEventListener(name, (ev) => {
          connected = true
          live = apply(live, JSON.parse((ev as MessageEvent).data), Date.now())
        })
      on('snapshot', (_st, e, t) => applySnapshot(e, t))
      on('session', applySession)
      on('records', applyRecords)
      on('status', applyStatus)
      on('timing', applyTiming)
      s.addEventListener('messages', (ev) => {
        messages = applyList((JSON.parse((ev as MessageEvent).data) as { messages: CrewMessage[] }).messages)
      })
      s.addEventListener('message', (ev) => {
        messages = applyOne(messages, (JSON.parse((ev as MessageEvent).data) as { message: CrewMessage }).message)
      })
      s.onerror = async () => {
        connected = false
        if (s.readyState === EventSource.CLOSED) {
          const r = await fetch(`/api/cars/${slug}/live`, { method: 'HEAD' }).catch(() => null)
          notFound = r?.status === 404
        }
      }
    }
    reconnect = async () => { await checkCrew(); open() }
    reconnect()
    const tick = setInterval(() => (now = Date.now()), 250)
    return () => { clearInterval(tick); source?.close() }
  })
</script>

<main>
  <p class="back"><a href="/">← Cars</a><a href={`/cars/${slug}/sessions`}>Past sessions →</a></p>

  {#if notFound}
    <h1>No car “{slug}”</h1>
  {:else}
    <section class={`banner ${fresh.state}`} aria-live="polite">
      <span class={`dot ${fresh.state}`}></span>
      <span class="banner-text">{bannerText}</span>
      {#if !connected}<span class="muted small">reconnecting…</span>{/if}
    </section>

    <h1>{slug}</h1>
    <div class="sessionline">
      {#if live.session}
        {@const src = sourceLabel(live.session.source)}
        <p class="session muted">
          {#if src}<span class={`source ${src.kind}`}>{src.text}</span>{/if}
          Session started {new Date(String(live.session.started)).toLocaleString()}
          {#if live.session.app} · app {String(live.session.app)}{/if}
          {#if live.session.device} · device {String(live.session.device).slice(0, 8)}{/if}
        </p>
      {/if}
      <UnitsSwitch />
    </div>

    <!-- The dashboard (M8): one fixed layout, Sam's slots (dashboard.ts). -->
    {#if dash.gauges.length > 0}
      <section class="dash gauges">
        {#each dash.gauges as n (n)}<Gauge signal={n} unit={unitOf(live, n)} value={valueOf(live.latest[n])} freshness={current(n)} />{/each}
      </section>
    {/if}
    {#if dash.numbers.length > 0}
      <section class="dash numbers">
        {#each dash.numbers as n (n)}<Readout signal={n} unit={unitOf(live, n)} value={valueOf(live.latest[n])} freshness={current(n)} />{/each}
      </section>
    {/if}
    {#if dash.bars.length > 0}
      <section class="dash bars">
        {#each dash.bars as n (n)}<Bar signal={n} unit={unitOf(live, n)} value={valueOf(live.latest[n])} freshness={current(n)} />{/each}
      </section>
    {/if}
    <!-- Always there (M10): empty, they say nothing has come, where hidden they'd say nothing. -->
    <section class="dash where">
      <GMeter {trail} peaks={peak} stale={trail.length === 0} />
      <SessionMap t={positions.map((p) => p.t)} lat={positions.map((p) => p.rec.lat as number)} lon={positions.map((p) => p.rec.lon as number)} speeds={trailSpeeds} follow />
    </section>
    {#if live.standing}<StandingPanel standing={live.standing} {serverNow} />{/if}
    {#if crew && liveId}<DriverPicker {slug} session={liveId} current={live.standing?.driver?.code} />{/if}
    {#if laps.length > 0}<LapsPanel rows={laps} bestSectors={live.standing?.bestSectors} />{/if}
    <section class="dash statuses">
      {#each dash.statuses as n (n)}<Status signal={n} rec={live.latest[n]} freshness={current(n)} />{/each}
      <Faults {codes} />
    </section>

    <MessagePanel {slug} {crew} list={messages} offsetMs={live.offsetMs} {now} onCrewChange={() => reconnect()} />

    {#if names.length > 0}
      <section class="panel">
        <div class="pickers">
          {#each [0, 1] as i}
            <select value={chartNames[i] ?? ''} onchange={(e) => pick(i, (e.currentTarget as HTMLSelectElement).value)}>
              <option value="">{i === 0 ? '— signal —' : '— none —'}</option>
              {#each numericSignals(live) as n}<option value={n}>{label(n)}</option>{/each}
            </select>
          {/each}
          <span class="toggle">
            <button class:on={!whole} onclick={() => (whole = false)}>Last 5 minutes</button>
            <button class:on={whole} disabled={!liveId} onclick={() => (whole = true)}>Whole session</button>
          </span>
        </div>
        {#if chartNames.length > 0}
          <Chart data={chartData} names={chartNames} units={chartUnits} zoom={whole} bands={chartBands} />
        {/if}
        {#if whole && chartBands.length > 0}
          <p class="muted small">Shaded: live, not yet in the archive. It fills in as the tablet uploads, every 2 minutes.</p>
        {/if}
      </section>

    {:else if connected}
      <p class="muted">No signals yet.</p>
    {/if}
  {/if}
</main>

<style>
  .back { margin: 0 0 8px; display: flex; justify-content: space-between; gap: 12px; }
  .sessionline { display: flex; justify-content: space-between; align-items: center; gap: 8px 12px; flex-wrap: wrap; margin-bottom: 4px; }
  .sessionline .session { margin: 0; }
  .dash { display: grid; gap: 10px; margin: 12px 0; }
  .gauges { grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); }
  .numbers { grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); }
  .bars { grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
  .where { grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); align-items: start; }
  .statuses { grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
  .back a { color: var(--muted); text-decoration: none; }
  .banner {
    display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-radius: 12px;
    font-size: 1.6rem; font-weight: 700; background: var(--panel); border: 2px solid var(--line);
  }
  .banner.live { border-color: var(--in-range); }
  .banner.stale { border-color: var(--caution); color: var(--caution); }
  .banner.no_session { border-color: var(--accent); }
  .banner.offline { color: var(--muted); }
  .small { font-size: 0.85rem; font-weight: 400; }
  .session { margin-top: -8px; }
  .fault { color: var(--critical); font-weight: 600; }
  .source { font-size: 0.85rem; color: var(--muted); border: 1px solid var(--line); border-radius: 6px; padding: 0 6px; margin-right: 6px; }
  .source.test { color: var(--caution); border-color: var(--caution); font-weight: 600; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; margin: 16px 0; }
  .pickers { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 8px; }
  .toggle { display: inline-flex; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
  .toggle button { background: var(--bg); color: var(--muted); border: none; padding: 6px 10px; font-size: 0.9rem; cursor: pointer; }
  .toggle button.on { background: var(--panel); color: var(--text); font-weight: 600; }
  .toggle button:disabled { opacity: 0.5; cursor: default; }
  select { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-size: 0.95rem; max-width: 100%; }
  @media (max-width: 480px) {
    .banner { font-size: 1.3rem; }
  }
</style>
