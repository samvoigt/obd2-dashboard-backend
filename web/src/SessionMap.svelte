<script lang="ts">
  import L from 'leaflet'
  import 'leaflet/dist/leaflet.css'
  import { onMount } from 'svelte'
  import { nearest, speedColor } from './lib/sessionPage'
  import { color, speedStops } from './lib/theme'

  let {
    t,
    lat,
    lon,
    speeds,
    cursor = null,
    follow = false,
    focusKey = '',
  }: {
    /** Epoch milliseconds, ascending. */
    t: number[]
    lat: number[]
    lon: number[]
    speeds: (number | null)[]
    /** The chart's cursor, epoch milliseconds: where the car was then. */
    cursor?: number | null
    /** Live (M8): keep the car in view, with a dot on it, until the viewer moves the map. */
    follow?: boolean
    /** The stretch shown (M16.1), as a key: when it changes, the view is fitted to it again. */
    focusKey?: string
  } = $props()

  let box: HTMLDivElement
  let map = $state<L.Map | null>(null)
  let dot: L.CircleMarker | null = null
  let trace: L.LayerGroup | null = null
  let fitted = false
  let drawn = '' // the trace last drawn, by its ends and length
  // Following (M8): the page's own moves are marked, so any other move is the viewer's.
  let moving = false
  let viewerMoved = $state(false)

  /** Speeds in 16 steps, so a long trace is a few hundred lines, not thousands. */
  const STEPS = 16

  onMount(() => {
    // Canvas: a 3-hour trace at 1 Hz is thousands of points (M7.5).
    map = L.map(box, { preferCanvas: true, zoomControl: true })
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(map)

    trace = L.layerGroup().addTo(map)
    // Following with no position yet (M10): the world, zoomed out, until the first fix. Set
    // before the listener below, so it can't read as the viewer's move and stop the following.
    if (follow && t.length === 0) map.setView([20, 0], 1, { animate: false })
    map.on('movestart', () => { if (!moving) viewerMoved = true })
    dot = L.circleMarker([lat[0] ?? 0, lon[0] ?? 0], { radius: 7, color: color('text'), weight: 2, fillColor: color('bg'), fillOpacity: 1 })

    const resize = new ResizeObserver(() => map?.invalidateSize())
    resize.observe(box)
    return () => {
      resize.disconnect()
      map?.remove()
      map = null
    }
  })

  let fittedTo = ''

  // The trace, drawn again whenever positions come (a session being driven, M7.6).
  $effect(() => {
    const m = map
    const layer = trace
    void t.length
    // A lap chosen or linked (M16.1): the view fitted to its stretch, and drawn again.
    if (focusKey !== fittedTo) {
      fittedTo = focusKey
      fitted = false
      drawn = ''
    }
    if (!m || !layer) return
    if (t.length === 0) {
      // Following, positions can drain from the 5-minute history (M10): the old trail goes,
      // the view stays where it was, and the next fix is drawn afresh.
      if (follow && drawn !== '') {
        layer.clearLayers()
        dot?.remove()
        drawn = ''
      }
      return
    }

    // The view first: Leaflet can't place a line on a map that has none yet (found in M8.2).
    const car: L.LatLngTuple = [lat[t.length - 1]!, lon[t.length - 1]!]
    if (follow) {
      // Following, the car stays in view until the viewer moves the map (M8).
      if (!viewerMoved) {
        // Unanimated, so the move is over when the call returns. Marked around the call, not
        // until a moveend: setView first stops the viewer's drag inertia, whose moveend came
        // before this move's movestart and read as the viewer's (found in M8.4).
        moving = true
        try {
          if (fitted) m.panTo(car, { animate: false })
          else m.setView(car, 16, { animate: false })
        } finally {
          moving = false
        }
        fitted = true
      }
    } else if (!fitted) {
      // The view is fitted once; after that it's the viewer's, as the trace grows (M7.6).
      m.fitBounds(L.latLngBounds(t.map((_, i) => [lat[i]!, lon[i]!] as L.LatLngTuple)), { padding: [16, 16] })
      fitted = true
    }
    if (!fitted) return // the viewer moved it before it was ever set: wait for one

    // Live, a batch comes 5 times a second but a position about once: the trace is drawn
    // again only when it changed, the map's largest cost per batch otherwise (M8.4).
    const key = `${t.length}:${t[0]}:${t[t.length - 1]}:${speeds[speeds.length - 1]}`
    if (key === drawn) return
    drawn = key

    layer.clearLayers()
    const known = speeds.filter((s): s is number => s !== null)
    const min = known.length > 0 ? Math.min(...known) : 0
    const max = known.length > 0 ? Math.max(...known) : 0
    const step = (s: number | null) => (s === null || max === min ? -1 : Math.min(STEPS - 1, Math.floor(((s - min) / (max - min)) * STEPS)))
    const stops = speedStops()
    const unknown = follow ? color('accent') : color('no-data')
    const colorOf = (k: number) => (k < 0 ? unknown : speedColor(min + ((k + 0.5) / STEPS) * (max - min), min, max, stops) ?? unknown)

    // Runs of one speed step, each run sharing its end point with the next so the trace is unbroken.
    let run: L.LatLngExpression[] = []
    let current = step(speeds[0] ?? null)
    for (let i = 0; i < t.length; i++) {
      const k = step(speeds[i] ?? null)
      if (k !== current && run.length > 0) {
        run.push([lat[i]!, lon[i]!])
        L.polyline(run, { color: colorOf(current), weight: 4, opacity: 0.9 }).addTo(layer)
        run = []
        current = k
      }
      run.push([lat[i]!, lon[i]!])
    }
    if (run.length > 1) L.polyline(run, { color: colorOf(current), weight: 4, opacity: 0.9 }).addTo(layer)

    if (follow && dot) {
      dot.setLatLng(car)
      if (!m.hasLayer(dot)) dot.addTo(m)
    }
  })

  $effect(() => {
    const at = cursor
    if (!map || !dot || follow) return // following, the dot is the car, not the cursor
    const i = at === null ? -1 : nearest(t, at)
    if (i < 0) {
      dot.remove()
      return
    }
    dot.setLatLng([lat[i]!, lon[i]!])
    if (!map.hasLayer(dot)) dot.addTo(map)
  })
</script>

<div class="wrap">
  <div class="map" bind:this={box}></div>
  {#if follow && t.length === 0}<p class="waiting">Waiting for GPS</p>{/if}
  {#if follow && viewerMoved && t.length > 0}<button class="recentre" onclick={() => (viewerMoved = false)}>Follow the car</button>{/if}
</div>

<style>
  .wrap { position: relative; }
  .recentre { position: absolute; right: 10px; bottom: 24px; z-index: 1000; background: var(--panel); color: var(--text); border: 1px solid var(--accent); border-radius: 8px; padding: 6px 10px; cursor: pointer; }
  .waiting { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); z-index: 1000; margin: 0; pointer-events: none; background: var(--panel); color: var(--muted); border: 1px solid var(--line); border-radius: 8px; padding: 6px 12px; }
  .map { width: 100%; height: 360px; border-radius: 10px; overflow: hidden; background: var(--panel); }
  .map :global(.leaflet-control-attribution) { font-size: 0.7rem; }
</style>
