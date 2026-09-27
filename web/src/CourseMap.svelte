<script lang="ts">
  import L from 'leaflet'
  import 'leaflet/dist/leaflet.css'
  import { onMount } from 'svelte'
  import { arrows, sectorsOf, type EditCourse, type Pt } from './lib/courseEdit'
  import { color } from './lib/theme'

  // A course, read-only (M12.5): its layouts and their direction, the start/finish,
  // the chosen layout's sectors numbered, the pit lines.
  let { course, layout }: { course: EditCourse; layout: string | null } = $props()

  let box: HTMLDivElement
  let map = $state<L.Map | null>(null)
  let drawn: L.FeatureGroup | null = null
  let fitted = false

  onMount(() => {
    map = L.map(box, { zoomControl: true })
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(map)
    drawn = L.featureGroup().addTo(map)
    const resize = new ResizeObserver(() => map?.invalidateSize())
    resize.observe(box)
    return () => { resize.disconnect(); map?.remove(); map = null }
  })

  const ll = ([lon, lat]: Pt): L.LatLngTuple => [lat, lon]

  $effect(() => {
    const m = map
    const c = course
    const sel = layout
    if (!m || !drawn) return
    drawn.clearLayers()
    const points = c.layouts.flatMap((l) => l.path)
    if (points.length < 2) return
    // The view first: Leaflet can't place a line on a map that has none (M8.2).
    if (!fitted) { m.fitBounds(L.latLngBounds(points.map(ll)), { padding: [16, 16] }); fitted = true }
    // The start/finish and the pit line sit side by side (22 m apart at NHMS): their labels go either side, not on top of each other.
    const label = (line: L.Polyline, text: string, direction: L.Direction = 'center') =>
      line.bindTooltip(text, { permanent: true, direction, offset: direction === 'left' ? [-6, 0] : direction === 'right' ? [6, 0] : [0, 0], className: 'course-label' })
    if (c.pitLane) drawn.addLayer(L.polyline(c.pitLane.path.map(ll), { color: color('muted'), weight: 4 }))
    for (const l of c.layouts) {
      const mine = l.id === sel
      drawn.addLayer(L.polyline(l.path.map(ll), { color: color('accent'), weight: mine ? 4 : 2, opacity: mine ? 1 : 0.45 }))
      if (mine) for (const head of arrows(l.path)) drawn.addLayer(L.polyline(head.map(ll), { color: color('accent'), weight: 2 }))
    }
    for (const s of c.startFinish) {
      if (s.layout === null || s.layout === sel) drawn.addLayer(label(L.polyline([ll(s.a), ll(s.b)], { color: color('text'), weight: 5 }), 'S/F', 'left'))
    }
    if (sel) for (const s of sectorsOf(c, sel)) drawn.addLayer(label(L.polyline([ll(s.a), ll(s.b)], { color: color('in-range'), weight: 5 }), `S${s.index}`))
    for (const [line, text] of [[c.pitIn, 'Pit in'], [c.pitOut, 'Pit out'], [c.pitLine, 'Pit line']] as const) {
      if (line) drawn.addLayer(label(L.polyline([ll(line.a), ll(line.b)], { color: color('critical'), weight: 4 }), text, 'right'))
    }
  })
</script>

<div class="map" bind:this={box}></div>

<style>
  .map { width: 100%; height: 480px; border-radius: 10px; overflow: hidden; background: var(--panel); }
  :global(.course-label) { background: var(--panel); color: var(--text); border: 1px solid var(--line); font-size: 0.75rem; padding: 0 4px; box-shadow: none; }
</style>
