<script lang="ts">
  // Every dashboard widget on one page, fed by a car's live stream (M8.2).
  // Dev only: the route exists only when import.meta.env.DEV, so the build drops it.
  import { onMount, untrack } from 'svelte'
  import SessionMap from './SessionMap.svelte'
  import Bar from './widgets/Bar.svelte'
  import Faults from './widgets/Faults.svelte'
  import Gauge from './widgets/Gauge.svelte'
  import GMeter from './widgets/GMeter.svelte'
  import LapsPanel from './widgets/LapsPanel.svelte'
  import Readout from './widgets/Readout.svelte'
  import Status from './widgets/Status.svelte'
  import UnitsSwitch from './widgets/UnitsSwitch.svelte'
  import { freshnessOf, gTrail, NO_PEAKS, peaks, SLOTS, timings, valueOf, type Peaks } from './lib/dashboard'
  import { applyRecords, applySession, applySnapshot, applyStatus, empty, unitOf, type LiveState } from './lib/live'
  import { merge } from './lib/merge'
  import { fetchSeries, lapRows, type Series } from './lib/sessionPage'

  let { slug }: { slug: string } = $props()

  // Raw: every update replaces the whole state, so it needs no deep proxies (M8.2: they made it crawl).
  let live: LiveState = $state.raw(empty())
  let now = $state(Date.now())
  let archived = $state.raw<Series | null>(null)
  let peak: Peaks = $state(NO_PEAKS)

  const serverNow = $derived(now - live.offsetMs)
  const trail = $derived(gTrail(live.history, serverNow))
  // The peaks grow from the trail; reading them untracked, or this effect would rerun itself forever.
  $effect(() => { const next = trail; peak = peaks(untrack(() => peak), next) })
  const positions = $derived(live.history.filter((p) => p.rec.signal === 'gps.position' && typeof p.rec.lat === 'number'))
  const laps = $derived(lapRows(merge(archived, live.history.map((p) => p.rec as Record<string, unknown>), live.signals).series))
  const timing = $derived(timings(live.history)) // once per batch, not once per widget per tick
  const fresh = (n: string) => freshnessOf(n, timing, live.latest[n], !!live.stopped[n], serverNow)
  const codes = $derived(Array.isArray(live.fault?.codes) ? (live.fault.codes as string[]) : [])

  onMount(() => {
    const s = new EventSource(`/api/cars/${slug}/live`)
    const on = (name: string, apply: (st: LiveState, e: Record<string, unknown>, t: number) => LiveState) =>
      s.addEventListener(name, (ev) => { live = apply(live, JSON.parse((ev as MessageEvent).data), Date.now()) })
    on('snapshot', (_st, e, t) => applySnapshot(e, t))
    on('session', applySession)
    on('records', applyRecords)
    on('status', applyStatus)
    const tick = setInterval(() => (now = Date.now()), 100)
    const load = () => {
      const id = typeof live.session?.id === 'string' ? live.session.id : null
      if (id) fetchSeries(id).then((x) => (archived = x)).catch(() => {})
    }
    const lapsTimer = setInterval(load, 60_000)
    setTimeout(load, 2000)
    return () => { s.close(); clearInterval(tick); clearInterval(lapsTimer) }
  })
</script>

<main>
  <h1>Widgets <span class="muted">· {slug}</span> <UnitsSwitch /></h1>
  <section class="row gauges">
    {#each SLOTS.gauges as n (n)}<Gauge signal={n} unit={unitOf(live, n)} value={valueOf(live.latest[n])} freshness={fresh(n)} />{/each}
  </section>
  <section class="row numbers">
    {#each SLOTS.numbers as n (n)}<Readout signal={n} unit={unitOf(live, n)} value={valueOf(live.latest[n])} freshness={fresh(n)} />{/each}
  </section>
  <section class="row bars">
    {#each SLOTS.bars as n (n)}<Bar signal={n} unit={unitOf(live, n)} value={valueOf(live.latest[n])} freshness={fresh(n)} />{/each}
  </section>
  <section class="row two">
    <GMeter {trail} peaks={peak} stale={trail.length === 0} />
    {#if positions.length > 0}
      <SessionMap t={positions.map((p) => p.t)} lat={positions.map((p) => p.rec.lat as number)} lon={positions.map((p) => p.rec.lon as number)} speeds={positions.map(() => null)} follow />
    {/if}
  </section>
  {#if laps.length > 0}<LapsPanel rows={laps} />{/if}
  <section class="row statuses">
    {#each SLOTS.statuses as n (n)}<Status signal={n} rec={live.latest[n]} freshness={fresh(n)} />{/each}
    <Faults {codes} />
  </section>
</main>

<style>
  h1 { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
  .row { display: grid; gap: 10px; margin: 12px 0; }
  .gauges { grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); }
  .numbers { grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); }
  .bars { grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
  .two { grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); align-items: start; }
  .statuses { grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
</style>
