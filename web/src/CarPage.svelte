<script lang="ts">
  import { onMount } from 'svelte'
  import Chart from './Chart.svelte'
  import MessagePanel from './MessagePanel.svelte'
  import { applyList, applyOne, type CrewMessage } from './lib/messages'
  import {
    applyRecords, applySession, applySnapshot, applyStatus, defaultChart, empty, format, freshness,
    label, numericSignals, series, unitOf, type LiveState,
  } from './lib/live'
  import { stateLabel } from './lib/state'

  let { slug }: { slug: string } = $props()

  let live: LiveState = $state(empty())
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
  const chartNames = $derived(chosen.length > 0 ? chosen : defaultChart(live))
  const chartUnits = $derived(chartNames.map((n) => unitOf(live, n)))
  // The chart redraws at most every half second: the eye cannot use more, and a phone should not work harder.
  let chartData: [number[], ...(number | null)[][]] = $state([[]])
  let lastChart = 0
  $effect(() => {
    const t = now
    if (t - lastChart >= 500) {
      lastChart = t
      chartData = series(live, chartNames)
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
  <p class="back"><a href="/">← Cars</a></p>

  {#if notFound}
    <h1>No car “{slug}”</h1>
  {:else}
    <section class={`banner ${fresh.state}`} aria-live="polite">
      <span class={`dot ${fresh.state}`}></span>
      <span class="banner-text">{bannerText}</span>
      {#if !connected}<span class="muted small">reconnecting…</span>{/if}
    </section>

    <h1>{slug}</h1>
    {#if live.session}
      <p class="session muted">
        Session started {new Date(String(live.session.started)).toLocaleString()}
        {#if live.session.app} · app {String(live.session.app)}{/if}
        {#if live.session.device} · device {String(live.session.device).slice(0, 8)}{/if}
      </p>
    {/if}

    <MessagePanel {slug} {crew} list={messages} offsetMs={live.offsetMs} {now} onCrewChange={() => reconnect()} />

    {#if live.fault && Array.isArray(live.fault.codes) && live.fault.codes.length > 0}
      <p class="fault">Trouble codes: {(live.fault.codes as string[]).join(', ')}</p>
    {/if}

    {#if names.length > 0}
      <section class="panel">
        <div class="pickers">
          {#each [0, 1] as i}
            <select value={chartNames[i] ?? ''} onchange={(e) => pick(i, (e.currentTarget as HTMLSelectElement).value)}>
              <option value="">{i === 0 ? '— signal —' : '— none —'}</option>
              {#each numericSignals(live) as n}<option value={n}>{label(n)}</option>{/each}
            </select>
          {/each}
          <span class="muted small">last 5 minutes</span>
        </div>
        {#if chartNames.length > 0}
          <Chart data={chartData} names={chartNames} units={chartUnits} />
        {/if}
      </section>

      <section class="tiles">
        {#each names as n (n)}
          {@const stopped = live.stopped[n]}
          {@const text = format(live.latest[n], unitOf(live, n))}
          <div class="tile" class:stopped={!!stopped} title={stopped ? String(stopped.reason ?? 'stopped') : n}>
            <div class="name">{label(n)}</div>
            <div class="value" class:long={text.length > 14}>{text}</div>
            {#if stopped}<div class="why">{String(stopped.reason ?? 'stopped')}</div>{/if}
          </div>
        {/each}
      </section>
    {:else if connected}
      <p class="muted">No signals yet.</p>
    {/if}
  {/if}
</main>

<style>
  .back { margin: 0 0 8px; }
  .back a { color: var(--muted); text-decoration: none; }
  .banner {
    display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-radius: 12px;
    font-size: 1.6rem; font-weight: 700; background: var(--panel); border: 2px solid var(--line);
  }
  .banner.live { border-color: var(--live); }
  .banner.stale { border-color: var(--stale); color: var(--stale); }
  .banner.no_session { border-color: var(--idle); }
  .banner.offline { color: var(--muted); }
  .small { font-size: 0.85rem; font-weight: 400; }
  .session { margin-top: -8px; }
  .fault { color: var(--danger); font-weight: 600; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; margin: 16px 0; }
  .pickers { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 8px; }
  select { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-size: 0.95rem; max-width: 100%; }
  .tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 10px; }
  .tile { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; min-width: 0; }
  .tile .name { color: var(--muted); font-size: 0.8rem; overflow-wrap: anywhere; }
  .tile .value { font-size: 1.5rem; font-weight: 700; margin-top: 4px; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
  .tile .value.long { font-size: 1rem; font-weight: 600; line-height: 1.3; }
  .tile.stopped { opacity: 0.45; }
  .tile .why { font-size: 0.75rem; color: var(--muted); margin-top: 4px; }
  @media (max-width: 480px) {
    .banner { font-size: 1.3rem; }
    .tiles { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    .tile .value { font-size: 1.25rem; }
  }
</style>
