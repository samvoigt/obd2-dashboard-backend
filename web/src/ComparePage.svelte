<script lang="ts">
  import L from 'leaflet'
  import 'leaflet/dist/leaflet.css'
  import { onMount } from 'svelte'
  import { fromGeoJSON } from './lib/courseEdit'
  import {
    delta, grid, lapTrace, readLapRef, resample, speedFromTrace, timeAt, trackOf, type LapRef, type Trace, type Track,
  } from './lib/compare'
  import { lapLink } from './lib/laps'
  import { fetchSeries, speedSignal, unitOf, type Series } from './lib/sessionPage'
  import { lapTime } from './lib/sessions'
  import { color, seriesColors } from './lib/theme'
  import DistanceChart from './DistanceChart.svelte'

  // Two laps compared (M16.4), public: both in the address, with the course and layout they're on.
  const q = new URLSearchParams(window.location.search)
  const refs = [readLapRef(q.get('a')), readLapRef(q.get('b'))]
  const courseId = q.get('course') ?? ''
  const layoutId = q.get('layout') ?? ''

  interface Loaded { ref: LapRef; series: Series; trace: Trace; label: string }
  let laps = $state.raw<Loaded[]>([])
  let track = $state.raw<Track | null>(null)
  let error: string | null = $state(null)
  let metre: number | null = $state(null)

  const STEP = 1
  const at = $derived(track ? grid(track.length, STEP) : [])
  const deltas = $derived(laps.length === 2 && track ? delta(laps[0]!.trace, laps[1]!.trace, at) : [])
  /** Each signal both laps have, speed first; speed from the laps themselves if neither has a speed signal. */
  const charts = $derived.by(() => {
    if (laps.length !== 2) return []
    const [a, b] = laps as [Loaded, Loaded]
    const both = Object.keys(a.series.numbers).filter((n) => n in b.series.numbers && n !== 'gps.position')
    const speed = speedSignal(a.series)
    const names = [...(speed && both.includes(speed) ? [speed] : []), ...both.filter((n) => n !== speed).sort()]
    const signals = names.map((n) => ({
      name: n,
      unit: unitOf(a.series, n),
      ys: [a, b].map((l) => resample(l.trace, l.series.numbers[n]!.t.map((t) => t + l.series.t0), l.series.numbers[n]!.v, at)),
    }))
    return speed && both.includes(speed) ? signals : [{ name: 'Speed, from the laps', unit: 'km/h', ys: [a, b].map((l) => speedFromTrace(l.trace, at)) }, ...signals]
  })

  onMount(async () => {
    try {
      if (!refs[0] || !refs[1] || !courseId) throw new Error('This page needs two laps and a course in its address.')
      const r = await fetch(`/api/courses/${courseId}`)
      if (!r.ok) throw new Error(`No such course (${r.status}).`)
      const course = fromGeoJSON(((await r.json()) as { geojson: unknown }).geojson)
      const layout = course.layouts.find((l) => l.id === layoutId) ?? course.layouts.find((l) => l.default) ?? course.layouts[0]
      const sf = course.startFinish.find((s) => s.layout === layout?.id) ?? course.startFinish.find((s) => s.layout === null)
      if (!layout || !sf) throw new Error('That course has no such layout, or no start/finish.')
      const t = trackOf(layout.path, sf)
      if (!t) throw new Error('That layout has no line to measure along.')
      const loaded: Loaded[] = []
      for (const ref of refs as LapRef[]) {
        const series = await fetchSeries(ref.session)
        if (!series) throw new Error('A lap’s session has nothing to show yet.')
        const pt = series.positions.t.map((x) => x + series.t0)
        loaded.push({ ref, series, trace: lapTrace(t, pt, series.positions.lat, series.positions.lon, ref.start, ref.end), label: '' })
      }
      loaded[0]!.label = `Lap A, ${lapTime((refs[0]!.end - refs[0]!.start) / 1000)}`
      loaded[1]!.label = `Lap B, ${lapTime((refs[1]!.end - refs[1]!.start) / 1000)}`
      track = t
      laps = loaded
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  })

  // The map: both laps' lines, and where each was at the cursor's metre.
  let mapBox: HTMLDivElement | undefined = $state()
  let dots: L.CircleMarker[] = []
  $effect(() => {
    if (!mapBox || laps.length !== 2) return
    const map = L.map(mapBox, { preferCanvas: true })
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(map)
    const COLORS = seriesColors()
    const bounds = L.latLngBounds([])
    dots = laps.map((l, i) => {
      const s = l.series
      const pts: L.LatLngTuple[] = []
      s.positions.t.forEach((x, k) => {
        const ms = x + s.t0
        if (ms >= l.ref.start && ms <= l.ref.end) pts.push([s.positions.lat[k]!, s.positions.lon[k]!])
      })
      pts.forEach((p) => bounds.extend(p))
      L.polyline(pts, { color: COLORS[i % COLORS.length], weight: 3, opacity: 0.9 }).addTo(map)
      return L.circleMarker(pts[0] ?? [0, 0], { radius: 6, color: color('text'), weight: 2, fillColor: COLORS[i % COLORS.length], fillOpacity: 1 }).addTo(map)
    })
    if (bounds.isValid()) map.fitBounds(bounds, { padding: [16, 16] })
    return () => map.remove()
  })

  /** Where lap [l] was when it reached metre [d]: its position nearest that moment. */
  function positionAt(l: Loaded, d: number): L.LatLngTuple | null {
    const ms = timeAt(l.trace, d)
    const s = l.series
    let best = -1
    let gap = Infinity
    s.positions.t.forEach((x, k) => {
      const g = Math.abs(x + s.t0 - ms)
      if (g < gap) { gap = g; best = k }
    })
    return best < 0 ? null : [s.positions.lat[best]!, s.positions.lon[best]!]
  }

  $effect(() => {
    const d = metre
    laps.forEach((l, i) => {
      const dot = dots[i]
      if (!dot) return
      const p = d === null ? null : positionAt(l, d)
      if (p) dot.setLatLng(p)
    })
  })
</script>

<main>
  <p class="back"><a href="/events">← Events</a></p>
  <h1>Two laps compared</h1>
  {#if error}
    <p class="error">{error}</p>
  {:else if laps.length === 2 && track}
    <p class="laps">
      {#each laps as l, i (i)}
        <span class={`lap lap${i}`}>{l.label}</span>
        <a class="small" href={lapLink(l.ref.car, l.ref.session, l.ref.start, l.ref.end)}>its session</a>
      {/each}
    </p>
    <p class="muted small">By distance round the lap, measured along the course's own line ({Math.round(track.length)} m), so both laps line up whatever line each took.</p>
    <section class="panel">
      <h2>Time gained or lost</h2>
      <p class="muted small">Above zero, lap B is behind lap A at that point; below, ahead. It ends at the difference in lap time.</p>
      <DistanceChart x={at} ys={[deltas]} labels={['B behind A']} unit="s" height={180} zeroLine onCursor={(m) => (metre = m)} />
    </section>
    {#each charts as c (c.name)}
      <section class="panel">
        <h2>{c.name}</h2>
        <DistanceChart x={at} ys={c.ys} labels={['Lap A', 'Lap B']} unit={c.unit} onCursor={(m) => (metre = m)} />
      </section>
    {/each}
    <section class="panel">
      <h2>Where they went</h2>
      <div class="map" bind:this={mapBox}></div>
    </section>
  {:else}
    <p class="muted">Loading both laps…</p>
  {/if}
</main>

<style>
  main { max-width: 1100px; margin: 0 auto; padding: 16px; display: grid; gap: 12px; }
  .back { margin: 0; }
  h1 { margin: 0; }
  h2 { margin: 0 0 6px; font-size: 1.05rem; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; }
  .laps { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: baseline; margin: 0; }
  .lap { font-weight: 700; }
  .map { height: 360px; border-radius: 8px; }
  .error { color: var(--critical); }
</style>
