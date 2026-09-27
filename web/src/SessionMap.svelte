<script lang="ts">
  import L from 'leaflet'
  import 'leaflet/dist/leaflet.css'
  import { onMount } from 'svelte'
  import { nearest, speedColor } from './lib/sessionPage'

  let {
    t,
    lat,
    lon,
    speeds,
    cursor = null,
  }: {
    /** Epoch milliseconds, ascending. */
    t: number[]
    lat: number[]
    lon: number[]
    speeds: (number | null)[]
    /** The chart's cursor, epoch milliseconds: where the car was then. */
    cursor?: number | null
  } = $props()

  let box: HTMLDivElement
  let map: L.Map | null = null
  let dot: L.CircleMarker | null = null

  /** Speeds in 16 steps, so a long trace is a few hundred lines, not thousands. */
  const STEPS = 16

  onMount(() => {
    // Canvas: a 3-hour trace at 1 Hz is thousands of points (M7.5).
    map = L.map(box, { preferCanvas: true, zoomControl: true })
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(map)

    const known = speeds.filter((s): s is number => s !== null)
    const min = known.length > 0 ? Math.min(...known) : 0
    const max = known.length > 0 ? Math.max(...known) : 0
    const step = (s: number | null) => (s === null || max === min ? -1 : Math.min(STEPS - 1, Math.floor(((s - min) / (max - min)) * STEPS)))
    const colorOf = (k: number) => (k < 0 ? speedColor(null, 0, 0) : speedColor(min + ((k + 0.5) / STEPS) * (max - min), min, max))

    // Runs of one speed step, each run sharing its end point with the next so the trace is unbroken.
    let run: L.LatLngExpression[] = []
    let current = step(speeds[0] ?? null)
    for (let i = 0; i < t.length; i++) {
      const k = step(speeds[i] ?? null)
      if (k !== current && run.length > 0) {
        run.push([lat[i]!, lon[i]!])
        L.polyline(run, { color: colorOf(current), weight: 4, opacity: 0.9 }).addTo(map)
        run = []
        current = k
      }
      run.push([lat[i]!, lon[i]!])
    }
    if (run.length > 1) L.polyline(run, { color: colorOf(current), weight: 4, opacity: 0.9 }).addTo(map)

    if (t.length > 0) map.fitBounds(L.latLngBounds(t.map((_, i) => [lat[i]!, lon[i]!] as L.LatLngTuple)), { padding: [16, 16] })
    dot = L.circleMarker([lat[0] ?? 0, lon[0] ?? 0], { radius: 7, color: '#ffffff', weight: 2, fillColor: '#111', fillOpacity: 1 })

    const resize = new ResizeObserver(() => map?.invalidateSize())
    resize.observe(box)
    return () => {
      resize.disconnect()
      map?.remove()
      map = null
    }
  })

  $effect(() => {
    const at = cursor
    if (!map || !dot) return
    const i = at === null ? -1 : nearest(t, at)
    if (i < 0) {
      dot.remove()
      return
    }
    dot.setLatLng([lat[i]!, lon[i]!])
    if (!map.hasLayer(dot)) dot.addTo(map)
  })
</script>

<div class="map" bind:this={box}></div>

<style>
  .map { width: 100%; height: 360px; border-radius: 10px; overflow: hidden; background: #1a1f26; }
  .map :global(.leaflet-control-attribution) { font-size: 0.7rem; }
</style>
