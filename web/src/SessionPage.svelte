<script lang="ts">
  import { setTitle } from './lib/title'
  import Skeleton from './widgets/Skeleton.svelte'
  import { clockText, gpsGaps, lengthText } from './lib/gps'
  import { onDestroy, onMount } from 'svelte'
  import Chart from './Chart.svelte'
  import SessionMap from './SessionMap.svelte'
  import {
    bestSectors, countsForBest, defaultSignals, events, fetchMergedSeries, fetchSeries, joined, loadSessionData, membersIn, lapNote, lapRows, LONG_SESSION_MS, messageMarkers, needsDetail, speedsAtPositions,
    standingRows, unitOf,
    type LapRow, type Member, type Series, type SessionLaps, type SessionMessage,
  } from './lib/sessionPage'
  import { fromGeoJSON } from './lib/courseEdit'
  import { badge, clockOf, dayOf, duration, lapTime, sourceLabel, trackOf, type SessionItem } from './lib/sessions'
  import { setSessionName, whoCanSet, type Driver, type DriverSetter } from './lib/events'
  import {
    changeAt, changeEntry, driverOfLap, driversText, driverTimeline, removeEntry, replacedText, saveDriverChanges, saveSessionDriver,
    type DriverChangeDetail, type DriverEdit, type DriverMember,
  } from './lib/driverChanges'
  import { signin } from './lib/signin'
  import { indexesIn, readWindow, withWindow } from './lib/laps'
  import type { LapPick } from './lib/comparePick'
  import ComparePick from './ComparePick.svelte'
  import { merge } from './lib/merge'
  import { columnShown, shownUnit, toShown } from './lib/units'
  import { lapSpeeds, seriesSamples } from './lib/lapSpeeds'
  import { units } from './lib/unitsState.svelte'
  import UnitsSwitch from './widgets/UnitsSwitch.svelte'
  import ThemeSwitch from './widgets/ThemeSwitch.svelte'
  import { color, translucent } from './lib/theme'
  import { themeState } from './lib/themeState.svelte'

  let { slug, id }: { slug: string; id: string } = $props()
  // The session shown (M33.7): a member's page, or an unmerged merge's, moves to where it now lives.
  // svelte-ignore state_referenced_locally
  let sid = $state(id)

  interface Detail {
    session: SessionItem
    car: string
    carName: string
    gaps: number
    missed: number
    /** Who drove it (M14.4); from its start, if the driver changed within it (M27). */
    driver?: Driver | null
    /** Where the driver changed within it (M27). */
    driverChanges?: DriverChangeDetail[]
    /** A merged session's members (M33.5), in order; absent or empty for a session. */
    members?: (Member & { driver?: Driver | null; driverChanges?: DriverChangeDetail[] })[]
    /** A member's: the merged session whose page shows it (M33.5). */
    mergedInto?: string | null
  }

  let detail = $state<Detail | null>(null)
  let drivers: Driver[] = $state.raw([])
  let setter: DriverSetter = $state(null)
  let settingDriver = $state(false)
  let driverError: string | null = $state(null)

  // Its name (M18.3), for the admin or the car's crew.
  let naming = $state(false)
  let nameDraft = $state('')
  let nameError: string | null = $state(null)

  async function saveName() {
    const d = detail
    if (!d || !setter) return
    nameError = null
    try {
      const name = await setSessionName(setter, slug, d.session.id, nameDraft.trim() || null)
      detail = { ...d, session: { ...d.session, name } }
      naming = false
    } catch (e) {
      nameError = e instanceof Error ? e.message : String(e)
    }
  }

  // Its tab (M35.5): its name, or when it began, and the car.
  $effect(() => { if (detail) setTitle(detail.session.name ?? `${dayOf(detail.session.started)}, ${clockOf(detail.session.started)}`, detail.carName) })

  // Who drove (M34.5): one timeline over the session's members (one, unless merged), edited in one block, each save
  // on the member its moment falls in (decision 49).
  const members: DriverMember[] = $derived(
    !detail ? [] : (detail.members ?? []).length > 0 ? detail.members!
      : [{ id: detail.session.id, started: detail.session.started, driver: detail.driver ?? null, driverChanges: detail.driverChanges ?? [] }],
  )
  let editing: number | null = $state(null)
  let addLap = $state('')
  let addDriver = $state('')
  let savedNote: string | null = $state(null)

  async function apply(edit: DriverEdit | { error: string } | null) {
    if (!edit || !setter) return
    if ('error' in edit) { driverError = edit.error; return }
    settingDriver = true
    driverError = null
    savedNote = null
    try {
      const saved = edit.kind === 'driver'
        ? await saveSessionDriver(setter, slug, edit.member, edit.driver)
        : await saveDriverChanges(setter, slug, edit.member, edit.changes)
      const replaced = replacedText(saved, drivers, (wall) => clockOf(wall))
      if (replaced) savedNote = `Saved, and ${replaced}: under a minute, or no lap, after the entry before.`
      editing = null
      addLap = ''
      // As stored, a merged session's timeline included: read again.
      const r = await fetch(`/api/sessions/${sid}`)
      if (r.ok) detail = (await r.json()) as Detail
    } catch (e) {
      driverError = e instanceof Error ? e.message : String(e)
    } finally {
      settingDriver = false
    }
  }

  let series = $state.raw<Series | null>(null)
  // Stretches with no GPS while the car moved (M24): the tablet's GPS, so no route or laps then.
  const gaps = $derived(series ? gpsGaps(series) : [])
  let missing = $state(false)
  let error = $state<string | null>(null)

  let chosen: string[] = $state([])
  let range = $state<[number, number] | null>(null)
  // A long session opens on its thinned series; narrowed to half an hour or less, it loads the detail (M19.6).
  let thin = $state(false)
  let viewed = $state<[number, number] | null>(null)
  let loadingDetail = false
  // A merged session's members loaded in full so far (M33.7): only those a narrow view covers are.
  let fullMembers = new Set<string>()
  $effect(() => {
    const view = viewed ?? range
    if (!needsDetail(thin, view) || loadingDetail) return
    const members = detail?.members ?? []
    if (members.length > 0) {
      const want = new Set([...fullMembers, ...membersIn(members, view!)])
      if (want.size === fullMembers.size) return
      loadingDetail = true
      void fetchMergedSeries(members, fetch, true, want).then((joinedSeries) => {
        if (joinedSeries) {
          series = joinedSeries
          fullMembers = want
          range = view
        }
      }).finally(() => { loadingDetail = false })
      return
    }
    loadingDetail = true
    void fetchSeries(sid).then((full) => {
      if (full) {
        series = full
        thin = false
        range = view // kept where it was
      }
    }).finally(() => { loadingDetail = false })
  })
  /** The stretch the map shows, on `wall` (M16.1): a lap chosen or linked; null for the whole session. */
  let focus = $state<[number, number] | null>(null)
  let chosenLap = $state<number | null>(null)
  let cursor = $state<number | null>(null)

  // A session being driven (M7.6): live records after the archive's end, merged once a second.
  // Kept out of Svelte's state, since they arrive every 200 ms; `tick` says when to look again.
  let liveRecords: Record<string, unknown>[] = []
  let liveSignals: Series['signals'] = []
  let tick = $state(0)
  const merged = $derived.by(() => {
    void tick
    if (detail?.session.state !== 'live') return { series, provisionalFrom: null as number | null }
    const m = merge(series, liveRecords, liveSignals)
    // Nothing archived and nothing live yet: still loading.
    return { series: series || liveRecords.length > 0 ? m.series : null, provisionalFrom: m.provisionalFrom }
  })
  const view = $derived(merged.series)

  const names = $derived(view ? (chosen.length > 0 ? chosen : defaultSignals(view)) : [])
  const data = $derived.by(() => {
    if (!view) return null
    const [x, ...ys] = joined(view, names)
    // In the viewer's units (M8.3), as the car's page is.
    return [x, ...ys.map((y, i) => columnShown(y, unitOf(view!, names[i] ?? ''), units.system))] as [number[], ...(number | null | undefined)[][]]
  })
  // Coloured in the theme's colours (M31.6): read here, so a change of theme colours them again.
  const bands = $derived(
    themeState.theme && merged.provisionalFrom !== null && data && data[0].length > 0
      ? [{ from: merged.provisionalFrom / 1000, to: data[0][data[0].length - 1]!, color: translucent(color('caution'), 0.1) }]
      : [],
  )
  const chartUnits = $derived(view ? names.map((n) => shownUnit(unitOf(view!, n), units.system)) : [])
  const numbers = $derived(view ? Object.keys(view.numbers).sort() : [])
  // The laps as they stand (M13.5): the server's, where it has them (the tablet's, re-timed where a line moved); else the series'.
  let standing: SessionLaps | null = $state.raw(null)
  const laps: LapRow[] = $derived(standing ? standingRows(standing) : view ? lapRows(view) : [])
  const timeline = $derived(driverTimeline(members, laps))
  // Each sector's best on track (M12.7): its column's highlight.
  const sectorBests = $derived(bestSectors(laps))
  // Each lap's top, lowest and average speed (km/h) from the series shown; a thinned one keeps the peaks.
  const speedOf = $derived(view ? lapSpeeds(laps, [seriesSamples(view)]) : new Map())
  const hasSpeeds = $derived([...speedOf.values()].some((x) => x !== null))
  const speedText = (v: number | null | undefined) => (v == null ? '' : Math.round(toShown(v, 'km/h', units.system)).toString())

  // The course the laps were at (M12.7): its name, and its layouts' names, since new laps name a layout by id.
  let courseNames: { name: string; layouts: Record<string, string> } | null = $state(null)
  $effect(() => {
    const track = detail?.session.track
    if (!track || !/^[a-z][a-z0-9-]{1,31}$/.test(track)) return
    fetch(`/api/courses/${track}`)
      .then((r) => (r.ok ? r.json() : null))
      .then((c: { name: string; geojson: unknown } | null) => {
        if (!c) return
        const layouts = Object.fromEntries(fromGeoJSON(c.geojson).layouts.map((l) => [l.id, l.name]))
        courseNames = { name: c.name, layouts }
      })
      .catch(() => {})
  })
  const whereLabel = (s: SessionItem): string | null =>
    !s.track ? null : courseNames ? `${courseNames.name}${s.layout ? ` · ${courseNames.layouts[s.layout] ?? s.layout}` : ''}` : trackOf(s)
  let messages: SessionMessage[] = $state.raw([])
  const happened = $derived([...(view ? events(view) : []), ...messageMarkers(messages)].sort((a, b) => a.t - b.t))
  const markers = $derived(themeState.theme ? [
    ...happened.map((m) => ({ t: m.t, color: color(m.kind === 'fault' ? 'critical' : m.kind === 'gap' ? 'caution' : m.kind === 'message' ? 'accent' : 'muted') })),
    // Where one member of a merged session ends and the next begins (M33.7).
    ...(detail?.members ?? []).slice(1).map((m) => ({ t: m.started / 1000, color: color('muted') })),
  ] : [])
  const positions = $derived(view ? view.positions.t.map((t) => view!.t0 + t) : [])
  const speeds = $derived(view ? speedsAtPositions(view) : [])
  // The map's stretch (M16.1): the positions inside the window, or all of them.
  const shown = $derived(indexesIn(positions, focus))
  const mapT = $derived(shown.map((i) => positions[i]!))
  const mapLat = $derived(view ? shown.map((i) => view!.positions.lat[i]!) : [])
  const mapLon = $derived(view ? shown.map((i) => view!.positions.lon[i]!) : [])
  const mapSpeeds = $derived(shown.map((i) => speeds[i] ?? null))

  onMount(async () => {
    try {
      const load = async (at: string): Promise<Detail | null> => {
        const response = await fetch(`/api/sessions/${at}`)
        if (response.status === 404) return null
        if (!response.ok) throw new Error(`The server answered ${response.status}.`)
        return (await response.json()) as Detail
      }
      let loaded = await load(sid)
      // A member's page shows its merged session; an unmerged one's, its first member (M33.7). The address follows, its lap window kept.
      const moved = loaded?.mergedInto ?? (loaded && loaded.session.id !== sid ? loaded.session.id : null)
      if (loaded && moved) {
        sid = moved
        history.replaceState(null, '', `/cars/${slug}/sessions/${moved}${window.location.search}`)
        if (loaded.mergedInto) loaded = await load(moved)
      }
      if (!loaded) {
        missing = true
        return
      }
      detail = loaded
      // Who drove (M14.4): everyone sees it; the admin or the car's crew can set it. Never on test data, though
      // test data can be named (M18.3).
      if (detail.session.source !== 'fake') {
        void fetch('/api/drivers').then((r) => (r.ok ? r.json() : [])).then((d: Driver[]) => (drivers = d)).catch(() => {})
      }
      // Crew messages sent while it ran (M18.4).
      void fetch(`/api/sessions/${sid}/messages`).then((r) => (r.ok ? r.json() : [])).then((m: SessionMessage[]) => (messages = m)).catch(() => {})
      // Laps first, then the series (M35.3): the table shows while the chart's data is still coming.
      thin = detail.session.state === 'complete' && detail.session.ended - detail.session.started > LONG_SESSION_MS
      await loadSessionData(sid, detail.session, detail.members ?? [], {
        laps: (l) => (standing = l),
        series: (s) => {
          series = s
          // A lap's link (M16.1): the chart zoomed to its window, the map showing that stretch.
          const linked = readWindow(window.location.search)
          if (linked) {
            focus = linked
            range = [linked[0] / 1000, linked[1] / 1000]
          }
        },
      })
      if (detail.session.state === 'live') follow()
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  })

  /** While the session is driven: the car's live stream, and the archive re-checked each minute. */
  function follow() {
    const source = new EventSource(`/api/cars/${slug}/live`)
    let current: string | null = null
    const ours = () => current === sid
    source.addEventListener('snapshot', (ev) => {
      const e = JSON.parse((ev as MessageEvent).data) as { session?: { id?: string; signals?: Series['signals'] }; history?: { record: Record<string, unknown> }[] }
      current = e.session?.id ?? null
      if (!ours()) return
      liveSignals = e.session?.signals ?? liveSignals
      liveRecords = (e.history ?? []).map((h) => h.record) // a snapshot is the whole live truth (M4.4)
    })
    source.addEventListener('session', (ev) => {
      current = (JSON.parse((ev as MessageEvent).data) as { session?: { id?: string } }).session?.id ?? null
    })
    source.addEventListener('records', (ev) => {
      if (!ours()) return
      liveRecords = liveRecords.concat((JSON.parse((ev as MessageEvent).data) as { records: Record<string, unknown>[] }).records)
    })
    const ticker = setInterval(() => tick++, 1000)
    const refresh = setInterval(async () => {
      const next = await fetchSeries(sid).catch(() => null)
      if (next) {
        series = next
        const cutoff = next.lastSeq ?? -Infinity
        liveRecords = liveRecords.filter((r) => typeof r.seq === 'number' && r.seq > cutoff) // the archive has these now
      }
    }, 60_000)
    stopFollowing = () => { source.close(); clearInterval(ticker); clearInterval(refresh) }
  }
  let stopFollowing = () => {}
  onDestroy(() => stopFollowing())

  function pick(index: number, value: string) {
    const next = [...names]
    if (value) next[index] = value
    else next.splice(index, 1)
    chosen = [...new Set(next.filter(Boolean))]
  }

  function showLap(lap: LapRow) {
    chosenLap = lap.end // by when it ended: two laps can share a number (app 1.0.1)
    range = [lap.start / 1000, lap.end / 1000]
    showWindow([lap.start, lap.end])
  }

  function showAll() {
    chosenLap = null
    if (data && data[0].length > 0) range = [data[0][0]!, data[0][data[0].length - 1]!]
    showWindow(null)
  }

  /** A lap of this session as a compare pick (M16.4), on the course its laps were timed on. */
  function pickOf(lap: LapRow, on: SessionLaps): LapPick {
    return { ref: { car: slug, session: lap.session ?? sid, start: lap.start, end: lap.end }, course: on.course, layout: on.layout, label: `${detail?.carName ?? slug} lap ${lap.lap}, ${lapTime(lap.time)}` }
  }

  /** The map to [w] (or everything), and the address with it, so what's shown can be shared (M16.1). */
  function showWindow(w: [number, number] | null) {
    focus = w
    history.replaceState(null, '', window.location.pathname + withWindow(window.location.search, w))
  }

  // Who may name it and set who drove (M14.4, M18.3): asked again when the header signs in or out (M21.1).
  $effect(() => {
    void $signin
    void whoCanSet(slug).then((w) => (setter = w))
  })
</script>

<main>
  <p class="back"><a href={`/cars/${slug}/sessions`}>← Past sessions</a></p>

  {#if missing}
    <h1>No such session</h1>
  {:else if error}
    <p class="error">Could not load the session: {error}</p>
  {:else if detail}
    {@const s = detail.session}
    {@const b = badge(s.state)}
    <h1>{#if s.name}{s.name} <span class="muted">· {detail.carName}, {dayOf(s.started)}, {clockOf(s.started)}</span>{:else}{detail.carName} <span class="muted">· {dayOf(s.started)}, {clockOf(s.started)}</span>{/if}</h1>
    {#if setter}
      {#if naming}
        <form class="naming" onsubmit={(e) => { e.preventDefault(); saveName() }}>
          <label>Name <input bind:value={nameDraft} maxlength="60" placeholder="e.g. Practice 2, wet" /></label>
          <button class="primary" type="submit">Save</button>
          <button type="button" onclick={() => (naming = false)}>Cancel</button>
          {#if nameError}<span class="error">{nameError}</span>{/if}
        </form>
      {:else}
        <p class="naming"><button class="link" onclick={() => { nameDraft = s.name ?? ''; nameError = null; naming = true }}>{s.name ? 'Rename' : 'Name this session'}</button></p>
      {/if}
    {/if}
    {@const src = sourceLabel(s.source)}
    <p class="facts">
      <span>{duration(s.ended - s.started)}</span>
      {#if src}<span class={`source ${src.kind}`}>{src.text}</span>{/if}
      {#if whereLabel(s)}<a href={`/courses/${s.track}`}>{whereLabel(s)}</a>{/if}
      {#if s.event}<a href={`/events/${s.event.id}`}>{s.event.name}, {s.event.part}</a>{/if}
      {#if s.bestLap}<span>Best <strong>{lapTime(s.bestLap.time)}</strong> <span class="muted">(lap {s.bestLap.lap})</span></span>{/if}
      {#if s.faults.length > 0}<span class="fault">{s.faults.join(', ')}</span>{/if}
      {#if b}<span class={`badge ${b.kind}`}><span class={`dot ${b.kind}`}></span>{b.text}</span>{/if}
      {#if s.state === 'live'}<a href={`/cars/${slug}`}>Watch live →</a>{/if}
      <span class="spacer"></span><span class="switches"><UnitsSwitch /><ThemeSwitch /></span>
    </p>
    {#each gaps as g (g.from)}
      <p class="gps-lost">
        GPS lost for {lengthText(g.to - g.from)} ({clockText(g.from)}–{clockText(g.to)}) while the car was moving: no route or laps then.
      </p>
    {/each}
    {#if (detail.members ?? []).length > 0}
      {@const ms = detail.members ?? []}
      <p class="members muted small">{ms.length} sessions merged: {ms.map((m) => clockOf(m.started)).join(', ')}</p>
    {/if}
    {#if setter && drivers.length > 0}
      <section class="drivers" aria-label="Drivers">
        <div class="name">Drivers</div>
        {#each timeline as e, i (`${e.member}-${e.kind}-${e.from}`)}
          <p class="driver">
            <span>{i === 0 ? 'From the start' : e.kind === 'start' ? `From ${e.lap ? `lap ${e.lap}` : clockOf(e.from)} (next session)` : `From lap ${e.lap ?? '—'}`}:</span>
            {#if editing === i}
              <select value={e.driver?.id ?? ''} onchange={(ev) => apply(changeEntry(members, e, ev.currentTarget.value || null))} disabled={settingDriver} aria-label="Driver">
                <option value="">Nobody yet</option>
                {#each drivers as d (d.id)}<option value={d.id}>{d.name} ({d.code})</option>{/each}
              </select>
              <button class="small" onclick={() => (editing = null)}>Cancel</button>
            {:else}
              <strong>{e.driver?.name ?? 'Nobody yet'}</strong>
              <button class="small" disabled={settingDriver} onclick={() => (editing = i)}>Change</button>
              {#if i > 0}<button class="small" disabled={settingDriver} onclick={() => apply(removeEntry(members, timeline, e))}>Remove</button>{/if}
            {/if}
          </p>
        {/each}
        {#if laps.length > 1}
          <p class="driver">
            <label>Add a change at lap <input class="lapno" bind:value={addLap} inputmode="numeric" /></label>
            <label>to
              <select bind:value={addDriver}>
                <option value="">Nobody yet</option>
                {#each drivers as d (d.id)}<option value={d.id}>{d.name} ({d.code})</option>{/each}
              </select>
            </label>
            <button disabled={settingDriver || !addLap.trim()} onclick={() => apply(changeAt(members, laps, Number(addLap), addDriver || null))}>Add</button>
          </p>
        {/if}
        {#if savedNote}<p class="muted small">{savedNote}</p>{/if}
        {#if driverError}<p class="error">{driverError}</p>{/if}
      </section>
    {:else if timeline.some((e) => e.driver)}
      <p class="driver">Driven by <strong>{driversText(timeline[0]?.driver, timeline.slice(1))}</strong>{#if timeline.length === 1 && timeline[0]?.driver} <span class="muted">({timeline[0].driver.code})</span>{/if}</p>
    {/if}
    {#if s.state === 'uploading'}
      <p class="muted small">Still being uploaded: this shows what has arrived so far. Reload for more.</p>
    {:else if s.state === 'live'}
      <p class="muted small">Being driven now: this follows it live. The shaded part is live data the archive doesn't cover yet; it fills in as the tablet uploads.</p>
    {/if}

    {#if !view}
      <Skeleton title={false} panels={[340, 400]} label="Loading the session's chart and map" />
    {:else}
      <section class="panel">
        <div class="pickers">
          {#each [0, 1] as i (i)}
            <select value={names[i] ?? ''} onchange={(e) => pick(i, (e.currentTarget as HTMLSelectElement).value)}>
              {#if i > 0}<option value="">(none)</option>{/if}
              {#each numbers as n (n)}<option value={n}>{n}</option>{/each}
            </select>
          {/each}
          <span class="muted small">Drag across the chart to zoom in; double-click to zoom out.</span>
        </div>
        {#if data && data[0].length > 0}
          <Chart {data} {names} units={chartUnits} zoom {range} {markers} {bands} onCursor={(t) => (cursor = t === null ? null : t * 1000)} onRange={(a, b) => (viewed = [a, b])} />
        {:else}
          <p class="muted">No readings to chart.</p>
        {/if}
      </section>

      {#if positions.length > 0}
        <section class="panel">
          <h2>Where it went</h2>
          <SessionMap t={mapT} lat={mapLat} lon={mapLon} speeds={mapSpeeds} {cursor} focusKey={focus ? focus.join('-') : ''} />
          <p class="muted small">Coloured by speed, blue slow to pink fast. The dot follows the chart's cursor.</p>
          {#if (view?.positions.dropped ?? 0) > 0}{@const n = view?.positions.dropped ?? 0}<p class="muted small">{n.toLocaleString()} GPS position{n === 1 ? '' : 's'} that didn’t fit with the one before (too far, too soon) {n === 1 ? 'is' : 'are'} left out.</p>{/if}
        </section>
      {/if}

    {/if}

    <!-- Laps first (M35.3): asked before the series and shown as soon as they come, speeds filled in once it's here. -->
      {#if laps.length > 0}
        <section class="panel">
          <h2>Laps</h2>
          <div class="table-scroll">
          <table class="laps">
            <thead><tr><th>Lap</th>{#if timeline.length > 1}<th>Driver</th>{/if}<th>Time</th>{#each sectorBests as _, i (i)}<th>S{i + 1}</th>{/each}{#if hasSpeeds}{@const u = shownUnit('km/h', units.system)}<th title="Top speed, {u}">Top</th><th title="Lowest speed, {u}">Low</th><th title="Average speed, {u}">Avg</th>{/if}<th></th></tr></thead>
            <tbody>
              {#each laps as lap (lap.end)}
                <tr class:best={lap.best} class:chosen={chosenLap === lap.end} onclick={() => showLap(lap)}>
                  <td>{lap.lap}</td>
                  {#if timeline.length > 1}<td>{driverOfLap(timeline, lap.end)?.code ?? '—'}</td>{/if}
                  <td class="time">{lapTime(lap.time)}</td>
                  {#each sectorBests as bestOf, i (i)}
                    {@const t = lap.sectors?.[i]}
                    <td class="sector" class:bestsector={t !== undefined && t === bestOf && countsForBest(lap, i)}>{t === undefined ? '' : t.toFixed(3)}</td>
                  {/each}
                  {#if hasSpeeds}
                    {@const sp = speedOf.get(lap.end)}
                    <td class="speed">{speedText(sp?.max)}</td><td class="speed">{speedText(sp?.min)}</td><td class="speed">{speedText(sp?.avg)}</td>
                  {/if}
                  <td class="muted">
                    {lap.best ? 'Best' : lap.pitIn ? 'Into the pits' : lap.pitOut ? 'Out of the pits' : ''}
                    {#if lapNote(lap)}<span class="note" class:flag={!!lap.flag}>{lapNote(lap)}</span>{/if}
                  </td>
                  {#if standing}
                    {@const on = standing}
                    {@const fastest = laps.find((l) => l.best)}
                    <td onclick={(e) => e.stopPropagation()}>
                      <ComparePick lap={pickOf(lap, on)} best={fastest ? pickOf(fastest, on) : null} />
                    </td>
                  {/if}
                </tr>
              {/each}
            </tbody>
          </table>
          </div>
          <p class="muted small">{#if hasSpeeds}Top, lowest and average speed in {shownUnit('km/h', units.system)}.{/if} Choose a lap to zoom the chart to it. {#if chosenLap !== null || focus !== null}<button class="link" onclick={showAll}>Show the whole session</button>{/if}</p>
        </section>
      {/if}

    {#if view}
      {#if happened.length > 0}
        <section class="panel">
          <h2>What happened</h2>
          <ul class="events">
            {#each happened as m, i (i)}
              <li class={m.kind}><span class="muted">{clockOf(m.t * 1000)}</span> {m.text}</li>
            {/each}
          </ul>
        </section>
      {/if}
    {/if}
  {:else}
    <Skeleton panels={[24, 44, 340, 400]} label="Loading the session" />
  {/if}
</main>

<style>
  .switches { display: inline-flex; gap: 8px; flex-wrap: wrap; }
  .back { margin: 0 0 8px; }
  .back a { color: var(--muted); text-decoration: none; }
  h1 { overflow-wrap: anywhere; }
  h2 { font-size: 1rem; margin: 0 0 10px; }
  .facts { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: center; margin: 0 0 8px; }
  .facts .spacer { flex: 1; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; margin: 16px 0; }
  .pickers { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 8px; }
  select { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-size: 0.95rem; max-width: 100%; }
  .laps { border-collapse: collapse; width: 100%; max-width: 760px; }
  .laps th { text-align: left; color: var(--muted); font-weight: 600; font-size: 0.85rem; padding: 4px 8px; }
  .laps td { padding: 6px 8px; border-top: 1px solid var(--line); }
  .laps tbody tr { cursor: pointer; }
  .laps tbody tr:hover td { background: var(--bg); }
  .laps .time { font-variant-numeric: tabular-nums; font-weight: 600; }
  .laps tr.best .time { color: var(--in-range); }
  .laps .sector, .laps .speed { font-variant-numeric: tabular-nums; }
  .laps .bestsector { color: var(--in-range); font-weight: 700; }
  .laps tr.chosen td { background: var(--bg); }
  .driver { margin: 0 0 8px; }
  .naming { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 0 0 8px; }
  .driver label { display: inline-flex; gap: 8px; align-items: center; }
  .lapno { width: 4em; }
  .drivers { margin: 0 0 8px; }
  .drivers .name { color: var(--muted); font-size: 0.85rem; margin-bottom: 4px; }
  .driver { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; }
  .laps .note { display: block; font-size: 0.8rem; white-space: nowrap; }
  .laps .note.flag { color: var(--caution); }
  .events { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; font-size: 0.95rem; }
  .events li.fault { color: var(--critical); }
  .gps-lost { color: var(--caution); border: 1px solid var(--caution); border-radius: 8px; padding: 8px 10px; margin: 8px 0; }
  .events li.gap { color: var(--caution); }
  .badge { display: inline-flex; align-items: center; font-size: 0.85rem; font-weight: 600; }
  .badge.live { color: var(--in-range); }
  .badge.stale { color: var(--caution); }
  .badge.offline { color: var(--muted); }
  .source { font-size: 0.85rem; color: var(--muted); border: 1px solid var(--line); border-radius: 6px; padding: 0 6px; }
  .source.test { color: var(--caution); border-color: var(--caution); font-weight: 600; }
  .fault { color: var(--critical); font-weight: 600; }
  .small { font-size: 0.85rem; }
  .error { color: var(--critical); }
  button.link { background: none; border: none; color: var(--accent); text-decoration: underline; padding: 0; cursor: pointer; font-size: inherit; }
</style>
