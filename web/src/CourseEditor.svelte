<script lang="ts">
  import L from 'leaflet'
  import 'leaflet/dist/leaflet.css'
  import '@geoman-io/leaflet-geoman-free'
  import '@geoman-io/leaflet-geoman-free/dist/leaflet-geoman.css'
  import { onMount, untrack } from 'svelte'
  import { api, AdminError, day, type CourseVersion, type CourseView } from './lib/admin'
  import {
    addSector, arrows, emptyCourse, fromGeoJSON, idFrom, makeDefault, metres, moveSector, removeLayout, removeSector,
    sectorsOf, toGeoJSON, type EditCourse, type Pt,
  } from './lib/courseEdit'
  import { fetchSeries } from './lib/sessionPage'
  import { color } from './lib/theme'

  let { id: pathId }: { id: string } = $props()
  const isNew = untrack(() => pathId === 'new')

  // The course being drawn (M12.4). Replaced whole on every change, never changed in place.
  let course: EditCourse = $state.raw(emptyCourse())
  let name = $state('')
  let newId = $state('')
  let idTouched = $state(false)
  let version = $state(0) // the version this drawing replaces; 0 for a new course
  let viewing: number | null = $state(null) // an older version, read-only
  let versions: CourseVersion[] = $state([])
  let problems: string[] = $state([])
  let message: string | null = $state(null)
  let signedOut = $state(false)
  let saving = $state(false)
  let dirty = $state(false)

  type Tool = 'layout' | 'pit_lane' | 'start_finish' | 'sector' | 'pit_in' | 'pit_out' | 'pit_line'
  let tool: Tool | null = $state(null)
  let firstPoint: Pt | null = $state(null)
  let selected: string | null = $state(null) // a layout's id
  // Which many-point line's points are draggable: one at a time, or a hundred handles bury the course.
  let editing: 'layout' | 'pit_lane' | null = $state(null)
  let newLayoutName = $state('')
  let ownStartFinish = $state(false)
  let imagery = $state(false)

  // A session's route under the drawing, to trace a real drive.
  let cars: { slug: string; name: string }[] = $state([])
  let guideCar = $state('')
  let guideSessions: { id: string; started: number }[] = $state([])
  let guide: Pt[] = $state.raw([])

  const courseId = $derived(isNew ? newId : pathId)
  const readOnly = $derived(viewing !== null)

  let box: HTMLDivElement | undefined = $state()
  let map: L.Map | null = $state(null)
  let drawn: L.FeatureGroup | null = null
  let street: L.TileLayer | null = null
  let photo: L.TileLayer | null = null

  function change(next: EditCourse) {
    course = next
    dirty = true
  }

  onMount(() => {
    if (!box) return
    map = L.map(box, { zoomControl: true }).setView([43.3629, -71.4615], 16)
    street = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 20, maxNativeZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(map)
    // USGS orthoimagery: public domain, tiles to zoom 16 here, enlarged beyond (M12 plan).
    photo = L.tileLayer('https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 20, maxNativeZoom: 16, attribution: 'Imagery: USGS The National Map',
    })
    drawn = L.featureGroup().addTo(map)
    map.on('click', (e: L.LeafletMouseEvent) => clicked([e.latlng.lng, e.latlng.lat]))
    map.on('pm:create', (e: { layer: L.Layer }) => created(e.layer))
    void load()
    const resize = new ResizeObserver(() => map?.invalidateSize())
    resize.observe(box)
    return () => { resize.disconnect(); map?.remove(); map = null }
  })

  async function load() {
    try {
      await api('GET', '/me')
      cars = await api<{ slug: string; name: string }[]>('GET', '/cars')
      if (isNew) {
        course = emptyCourse()
        return
      }
      const c = await api<CourseView>('GET', `/courses/${pathId}`)
      show(c)
      versions = await api<CourseVersion[]>('GET', `/courses/${pathId}/versions`)
    } catch (e) {
      if (e instanceof AdminError && e.status === 401) signedOut = true
      else message = e instanceof Error ? e.message : String(e)
    }
  }

  function show(c: CourseView) {
    course = fromGeoJSON(c.geojson)
    name = c.name
    version = c.version
    selected = course.layouts.find((l) => l.default)?.id ?? course.layouts[0]?.id ?? null
    dirty = false
    fit()
  }

  async function view(v: number) {
    if (dirty && !confirm('Leave your unsaved changes?')) return
    const c = await api<CourseView>('GET', `/courses/${pathId}?version=${v}`)
    const latest = versions[0]?.version
    course = fromGeoJSON(c.geojson)
    name = c.name
    viewing = v === latest ? null : v
    version = latest ?? c.version
    dirty = false
    tool = null
    message = null
  }

  function fit() {
    const pts = course.layouts.flatMap((l) => l.path)
    if (map && pts.length > 1) map.fitBounds(L.latLngBounds(pts.map(([lon, lat]) => [lat, lon] as L.LatLngTuple)), { padding: [24, 24] })
  }

  // The imagery.
  $effect(() => {
    const m = map
    if (!m || !street || !photo) return
    if (imagery) { street.remove(); photo.addTo(m) } else { photo.remove(); street.addTo(m) }
  })

  // The rules as you draw: the server's own, asked after each change (M12.4).
  let checkTimer: ReturnType<typeof setTimeout> | undefined
  $effect(() => {
    const body = { name, geojson: toGeoJSON(course), id: isNew ? newId : undefined }
    if (signedOut) return
    clearTimeout(checkTimer)
    checkTimer = setTimeout(async () => {
      try {
        problems = (await api<{ problems: string[] }>('POST', '/courses/check', body)).problems
      } catch { /* the save says it, if it still stands */ }
    }, 400)
  })

  // A new course's id follows its name until you type one.
  $effect(() => {
    const n = name
    if (isNew && !untrack(() => idTouched)) newId = idFrom(n)
  })

  const latlng = ([lon, lat]: Pt): L.LatLngTuple => [lat, lon]
  const pt = (ll: L.LatLng): Pt => [ll.lng, ll.lat]

  /** A two-point line with draggable ends. */
  function drawLine(a: Pt, b: Pt, colour: string, label: string, moved: (a: Pt, b: Pt) => void, direction: L.Direction = 'center') {
    // The start/finish and the pit line sit side by side: their labels go either side (M12.5).
    const offset: L.PointTuple = direction === 'left' ? [-6, 0] : direction === 'right' ? [6, 0] : [0, 0]
    const line = L.polyline([latlng(a), latlng(b)], { color: colour, weight: 5 }).bindTooltip(label, { permanent: true, direction, offset, className: 'course-label' })
    drawn!.addLayer(line)
    if (readOnly) return
    const ends = [a, b].map((p, i) => {
      const handle = L.circleMarker(latlng(p), { radius: 6, color: colour, fillColor: color('bg'), fillOpacity: 1, weight: 2 })
      drawn!.addLayer(handle)
      // Leaflet's circle markers don't drag; a dragging session on the map moves them.
      handle.on('mousedown', (e: L.LeafletMouseEvent) => {
        L.DomEvent.stop(e)
        map!.dragging.disable()
        const move = (m: L.LeafletMouseEvent) => { handle.setLatLng(m.latlng); line.setLatLngs(i === 0 ? [m.latlng, latlng(b)] : [latlng(a), m.latlng]) }
        const up = (m: L.LeafletMouseEvent) => {
          map!.off('mousemove', move); map!.off('mouseup', up); map!.dragging.enable()
          moved(i === 0 ? pt(m.latlng) : a, i === 0 ? b : pt(m.latlng))
        }
        map!.on('mousemove', move); map!.on('mouseup', up)
      })
      return handle
    })
    return ends
  }

  /** A many-point line (a layout, the pit lane), its vertices editable with Geoman. */
  function drawPath(path: Pt[], colour: string, weight: number, editable: boolean, changed: (p: Pt[]) => void) {
    const line = L.polyline(path.map(latlng), { color: colour, weight })
    drawn!.addLayer(line)
    if (editable && !readOnly) {
      line.pm.enable({ allowSelfIntersection: true })
      line.on('pm:edit', () => changed((line.getLatLngs() as L.LatLng[]).map(pt)))
    }
  }

  // Everything drawn again from the model on each change.
  $effect(() => {
    const c = course
    const m = map
    const sel = selected
    const pointsOf = editing
    void readOnly
    if (!m || !drawn) return
    drawn.clearLayers()
    if (guide.length > 1) drawn.addLayer(L.polyline(guide.map(latlng), { color: color('muted'), weight: 3, opacity: 0.6, dashArray: '4 6', interactive: false }))
    if (c.pitLane) drawPath(c.pitLane.path, color('muted'), 4, pointsOf === 'pit_lane', (p) => change({ ...c, pitLane: { ...c.pitLane!, path: p } }))
    for (const l of c.layouts) {
      const mine = l.id === sel
      drawPath(l.path, color('accent'), mine ? 4 : 2, mine && pointsOf === 'layout', (p) => change({ ...c, layouts: c.layouts.map((x) => (x.id === l.id ? { ...x, path: p } : x)) }))
      for (const head of arrows(l.path)) drawn.addLayer(L.polyline(head.map(latlng), { color: color('accent'), weight: 2, opacity: mine ? 1 : 0.5, interactive: false }))
    }
    c.startFinish.forEach((s, i) => {
      if (s.layout !== null && s.layout !== sel) return
      drawLine(s.a, s.b, color('text'), s.layout ? `S/F ${s.layout}` : 'S/F', (a, b) =>
        change({ ...c, startFinish: c.startFinish.map((x, j) => (j === i ? { ...x, a, b } : x)) }), 'left')
    })
    if (sel) {
      for (const s of sectorsOf(c, sel)) {
        drawLine(s.a, s.b, color('in-range'), `S${s.index}`, (a, b) =>
          change({ ...c, sectors: c.sectors.map((x) => (x.layout === sel && x.index === s.index ? { ...x, a, b } : x)) }))
      }
    }
    for (const [key, label] of [['pitIn', 'Pit in'], ['pitOut', 'Pit out'], ['pitLine', 'Pit line']] as const) {
      const line = c[key]
      if (line) drawLine(line.a, line.b, color('critical'), label, (a, b) => change({ ...c, [key]: { ...line, a, b } }), 'right')
    }
    if (firstPoint) drawn.addLayer(L.circleMarker(latlng(firstPoint), { radius: 6, color: color('caution'), weight: 3 }))
  })

  function start(t: Tool) {
    if (readOnly) return
    firstPoint = null
    map?.pm.disableDraw()
    if (t === 'layout' && !newLayoutName.trim()) { message = 'Name the layout first.'; return }
    if (t === 'sector' && !selected) { message = 'Choose a layout for the sector.'; return }
    message = null
    tool = t
    if (t === 'layout' || t === 'pit_lane') map?.pm.enableDraw('Line', { templineStyle: { color: color('caution') }, hintlineStyle: { color: color('caution'), dashArray: '4 4' } })
  }

  function stop() {
    tool = null
    firstPoint = null
    map?.pm.disableDraw()
  }

  /** A two-click line: the first click, then the second makes it. */
  function clicked(p: Pt) {
    const t = tool
    if (!t || t === 'layout' || t === 'pit_lane') return
    if (!firstPoint) { firstPoint = p; return }
    const a = firstPoint
    const b = p
    firstPoint = null
    const c = course
    if (t === 'start_finish') {
      const layout = ownStartFinish ? selected : null
      change({ ...c, startFinish: [...c.startFinish.filter((s) => s.layout !== layout), { a, b, extra: {}, layout }] })
    } else if (t === 'sector' && selected) {
      change(addSector(c, selected, a, b))
    } else if (t === 'pit_in' || t === 'pit_out' || t === 'pit_line') {
      const key = t === 'pit_in' ? 'pitIn' : t === 'pit_out' ? 'pitOut' : 'pitLine'
      change({ ...c, [key]: { a, b, extra: c[key]?.extra ?? {} } })
    }
    if (t !== 'sector') tool = null // sectors, one after another; the rest, once
  }

  /** Geoman finished a many-point line: it becomes a layout or the pit lane, drawn from the model. */
  function created(layer: L.Layer) {
    const path = ((layer as L.Polyline).getLatLngs() as L.LatLng[]).map(pt)
    drawn?.removeLayer(layer)
    map?.removeLayer(layer)
    const c = course
    if (tool === 'layout') {
      const id = idFrom(newLayoutName, c.layouts.map((l) => l.id))
      change({ ...c, layouts: [...c.layouts, { id, name: newLayoutName.trim(), default: c.layouts.length === 0, path, extra: {} }] })
      selected = id
      newLayoutName = ''
    } else if (tool === 'pit_lane') {
      change({ ...c, pitLane: { path, extra: c.pitLane?.extra ?? {} } })
    }
    stop()
    if (course.layouts.length === 1 && path.length > 1) fit()
  }

  async function save() {
    saving = true
    message = null
    try {
      const saved = await api<CourseView>('PUT', `/courses/${courseId}`, { expected: version, name, geojson: toGeoJSON(course) })
      version = saved.version
      dirty = false
      message = `Saved as version ${saved.version}.`
      if (isNew) window.location.assign(`/admin/courses/${saved.id}`)
      versions = await api<CourseVersion[]>('GET', `/courses/${courseId}/versions`)
    } catch (e) {
      message = e instanceof Error ? e.message : String(e)
    } finally {
      saving = false
    }
  }

  async function loadGuideSessions() {
    guideSessions = guideCar ? await api<{ id: string; started: number }[]>('GET', `/cars/${guideCar}/sessions`) : []
  }

  async function showGuide(id: string) {
    const series = id ? await fetchSeries(id) : null
    guide = series ? series.positions.lon.map((lon, i) => [lon, series.positions.lat[i]!] as Pt) : []
    if (guide.length > 1 && course.layouts.length === 0) map?.fitBounds(L.latLngBounds(guide.map(latlng)), { padding: [24, 24] })
  }

  const hint: Record<Tool, string> = {
    layout: 'Click along the line cars take, in the direction they go; click the last point again to finish.',
    pit_lane: 'Click along the pit lane, in the direction cars go; click the last point again to finish.',
    start_finish: 'Click one side of the track, then the other.',
    sector: 'Click one side of the track, then the other, for each sector line in order. Stop when done.',
    pit_in: 'Click across the pit lane where it begins.',
    pit_out: 'Click across the pit lane where it ends.',
    pit_line: 'Click across the pit lane, level with the start/finish.',
  }
</script>

<main>
  <p class="back"><a href="/admin/courses">← Courses</a></p>
  {#if signedOut}
    <p>Sign in on the <a href="/admin">admin page</a> first, then come back.</p>
  {:else}
    <div class="editor">
      <div class="mapwrap">
        <div class="map" bind:this={box}></div>
        {#if tool}<p class="hint">{hint[tool]} <button onclick={stop}>Stop</button></p>{/if}
      </div>
      <aside>
        <label><span>Name</span><input bind:value={name} disabled={readOnly} /></label>
        {#if isNew}
          <label><span>Id</span><input bind:value={newId} oninput={() => (idTouched = true)} /></label>
          <p class="muted small">Permanent: it's in every lap timed here.</p>
        {:else}
          <p class="muted small">{pathId} · {viewing ? `viewing version ${viewing}` : `version ${version}`}{dirty ? ' · unsaved changes' : ''}</p>
        {/if}
        <label class="check"><input type="checkbox" bind:checked={imagery} /> Aerial imagery</label>

        {#if readOnly}
          <p class="warn">An older version, to look at. <button onclick={() => view(versions[0]!.version)}>Back to the latest</button></p>
        {:else}
          <h2>Layouts</h2>
          {#each course.layouts as l (l.id)}
            <div class="row" class:chosen={l.id === selected}>
              <button class="link" onclick={() => { selected = l.id; editing = null }}>{l.name}</button>
              <span class="muted small">{l.id} · {Math.round(l.path.slice(1).reduce((s, p, i) => s + metres(l.path[i]!, p), 0))} m</span>
              {#if l.default}<span class="small">default</span>{:else}<button class="small" onclick={() => change(makeDefault(course, l.id))}>Make default</button>{/if}
              <button class="small danger" onclick={() => { change(removeLayout(course, l.id)); if (selected === l.id) selected = course.layouts[0]?.id ?? null }}>Remove</button>
            </div>
          {/each}
          {#if selected}
            <label class="check small"><input type="checkbox" checked={editing === 'layout'} onchange={(e) => (editing = (e.currentTarget as HTMLInputElement).checked ? 'layout' : null)} /> Move the points of {course.layouts.find((l) => l.id === selected)?.name}</label>
          {/if}
          <div class="row">
            <input placeholder="New layout's name" bind:value={newLayoutName} />
            <button onclick={() => start('layout')}>Draw</button>
          </div>

          <h2>Timing lines</h2>
          <div class="row">
            <button onclick={() => start('start_finish')}>{course.startFinish.length ? 'Redraw' : 'Draw'} start/finish</button>
            {#if course.layouts.length > 1}<label class="check small"><input type="checkbox" bind:checked={ownStartFinish} /> only for {selected}</label>{/if}
          </div>
          {#if selected}
            <p class="small">Sectors of <strong>{course.layouts.find((l) => l.id === selected)?.name}</strong>:</p>
            {#each sectorsOf(course, selected) as s (s.index)}
              <div class="row small">
                <span>Line {s.index} <span class="muted">({Math.round(metres(s.a, s.b))} m)</span></span>
                <button onclick={() => change(moveSector(course, selected!, s.index, -1))} disabled={s.index === 1}>↑</button>
                <button onclick={() => change(moveSector(course, selected!, s.index, 1))} disabled={s.index === sectorsOf(course, selected!).length}>↓</button>
                <button class="danger" onclick={() => change(removeSector(course, selected!, s.index))}>Remove</button>
              </div>
            {/each}
            <button onclick={() => start('sector')}>Add sector lines</button>
          {/if}

          <h2>Pit</h2>
          <div class="row wrap">
            <button onclick={() => start('pit_lane')}>{course.pitLane ? 'Redraw' : 'Draw'} pit lane</button>
            <button onclick={() => start('pit_in')}>Pit in</button>
            <button onclick={() => start('pit_out')}>Pit out</button>
            <button onclick={() => start('pit_line')}>Pit line</button>
          </div>
          <div class="row wrap small">
            {#if course.pitLane}
              <label class="check"><input type="checkbox" checked={editing === 'pit_lane'} onchange={(e) => (editing = (e.currentTarget as HTMLInputElement).checked ? 'pit_lane' : null)} /> Move its points</label>
              <button class="danger" onclick={() => change({ ...course, pitLane: null })}>Remove pit lane</button>
            {/if}
            {#each [['pitIn', 'pit in'], ['pitOut', 'pit out'], ['pitLine', 'pit line']] as const as [key, label] (key)}
              {#if course[key]}<button class="danger" onclick={() => change({ ...course, [key]: null })}>Remove {label}</button>{/if}
            {/each}
          </div>

          {#if problems.length > 0}
            <h2>To fix before saving</h2>
            <ul class="problems">{#each problems as p (p)}<li>{p}</li>{/each}</ul>
          {/if}
          <p><button class="primary" onclick={save} disabled={saving || problems.length > 0 || !dirty}>Save as version {version + 1}</button></p>
        {/if}
        {#if message}<p class="message">{message}</p>{/if}

        <h2>A session's route</h2>
        <p class="muted small">Drawn faintly under the course, to trace a real drive.</p>
        <div class="row wrap">
          <select bind:value={guideCar} onchange={loadGuideSessions}>
            <option value="">Car…</option>
            {#each cars as c (c.slug)}<option value={c.slug}>{c.name}</option>{/each}
          </select>
          {#if guideSessions.length > 0}
            <select onchange={(e) => showGuide((e.currentTarget as HTMLSelectElement).value)}>
              <option value="">Session…</option>
              {#each guideSessions as s (s.id)}<option value={s.id}>{day(s.started)} {new Date(s.started).toLocaleTimeString()}</option>{/each}
            </select>
          {/if}
        </div>

        {#if versions.length > 1}
          <h2>Versions</h2>
          {#each versions as v (v.version)}
            <div class="row small"><button class="link" onclick={() => view(v.version)}>Version {v.version}</button><span class="muted">{v.name}, {day(v.saved)}</span></div>
          {/each}
        {/if}
      </aside>
    </div>
  {/if}
</main>

<style>
  main { max-width: 1400px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 8px; }
  .editor { display: grid; grid-template-columns: 1fr 340px; gap: 16px; align-items: start; }
  @media (max-width: 900px) { .editor { grid-template-columns: 1fr; } }
  .mapwrap { position: relative; }
  .map { height: 78vh; min-height: 420px; border-radius: 10px; overflow: hidden; background: var(--panel); }
  .hint { position: absolute; left: 12px; right: 12px; bottom: 12px; z-index: 1000; margin: 0; background: var(--panel); border: 1px solid var(--caution); border-radius: 8px; padding: 8px 10px; }
  aside { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; display: grid; gap: 8px; }
  h2 { font-size: 0.95rem; margin: 10px 0 0; }
  label { display: grid; gap: 4px; }
  label.check { display: flex; align-items: center; gap: 6px; }
  input, select { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; min-width: 0; }
  .row { display: flex; align-items: center; gap: 6px; flex-wrap: nowrap; }
  .row.wrap { flex-wrap: wrap; }
  .row.chosen .link { color: var(--accent); font-weight: 700; }
  .row input { flex: 1; }
  button { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 4px 8px; cursor: pointer; }
  button.primary { border-color: var(--accent); color: var(--accent); font-weight: 700; padding: 8px 12px; }
  button.danger { color: var(--critical); border-color: var(--critical); }
  button.link { background: none; border: none; padding: 0; color: var(--text); text-decoration: underline; }
  button:disabled { opacity: 0.4; cursor: default; }
  .small { font-size: 0.85rem; }
  .warn { color: var(--caution); }
  .problems { color: var(--caution); margin: 0; padding-left: 18px; font-size: 0.9rem; }
  .message { font-weight: 600; }
  :global(.course-label) { background: var(--panel); color: var(--text); border: 1px solid var(--line); font-size: 0.75rem; padding: 0 4px; box-shadow: none; }
</style>
