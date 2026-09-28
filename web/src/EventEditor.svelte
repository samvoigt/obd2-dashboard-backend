<script lang="ts">
  import { onMount, untrack } from 'svelte'
  import { api, AdminError, type AdminCar, type CourseSummary } from './lib/admin'
  import { idFrom } from './lib/courseEdit'
  import {
    addSession, fromLocalInput, heardText, newPart, removeSession, saveBody, toLocalInput,
    type AdminEvent, type Driver, type Part, type PartKind, type SessionBrief,
  } from './lib/events'

  let { id: pathId }: { id: string } = $props()
  const isNew = untrack(() => pathId === 'new')

  // The event as drawn (M14.3); `saved` is what the server holds, to tell what's unsaved.
  let name = $state('')
  let newId = $state('')
  let idTouched = $state(false)
  let date = $state(toLocalInput(Date.now()).slice(0, 10)) // today where you are, not in UTC
  let course = $state('')
  let layout = $state('')
  let cars: string[] = $state([])
  let parts: Part[] = $state.raw([])
  let revision = $state(0)
  let saved = $state('')
  let page: AdminEvent | null = $state.raw(null)

  let allCourses: CourseSummary[] = $state.raw([])
  let allCars: AdminCar[] = $state.raw([])
  let drivers: Driver[] = $state.raw([])
  let signedOut = $state(false)
  let loaded = $state(false)
  let busy = $state(false)
  let message: string | null = $state(null)
  let problems: string[] = $state([])

  const eventId = $derived(isNew ? newId : pathId)
  const draft = $derived({ name, date, course, layout, cars, parts })
  const changed = $derived(JSON.stringify(draft) !== saved)
  const layouts = $derived(allCourses.find((c) => c.id === course)?.layouts ?? [])
  const driverName = (id: string | null | undefined) => drivers.find((d) => d.id === id)?.code ?? '—'

  // A new event's id follows its name until you type one.
  $effect(() => {
    const n = name
    if (isNew && !idTouched) newId = idFrom(n)
  })
  // A course picked: its default layout, unless the one chosen is its.
  $effect(() => {
    const ls = layouts
    if (ls.length > 0 && !ls.some((l) => l.id === untrack(() => layout))) layout = ls.find((l) => l.default)?.id ?? ls[0]!.id
  })

  onMount(async () => {
    try {
      await api('GET', '/me')
      ;[allCourses, allCars, drivers] = await Promise.all([
        api<CourseSummary[]>('GET', '/courses'),
        api<AdminCar[]>('GET', '/cars'),
        api<Driver[]>('GET', '/drivers'),
      ])
      if (isNew) {
        course = allCourses[0]?.id ?? ''
        cars = allCars.length === 1 ? [allCars[0]!.slug] : []
        saved = JSON.stringify(draft)
      } else {
        await reload()
      }
      loaded = true
    } catch (e) {
      if (e instanceof AdminError && e.status === 401) signedOut = true
      else message = e instanceof Error ? e.message : String(e)
    }
  })

  async function reload() {
    const p = await api<AdminEvent>('GET', `/events/${pathId}`)
    page = p
    name = p.event.name
    date = p.event.date
    course = p.event.course
    layout = p.event.layout
    cars = p.event.cars
    parts = p.event.parts
    revision = p.event.revision
    saved = JSON.stringify({ name, date, course, layout, cars, parts })
  }

  async function save() {
    busy = true
    message = null
    problems = []
    try {
      await api('PUT', `/events/${eventId}`, saveBody(draft, revision))
      if (isNew) {
        window.location.assign(`/admin/events/${eventId}`)
        return
      }
      await reload()
      message = 'Saved.'
    } catch (e) {
      message = e instanceof Error ? e.message : String(e)
      if (e instanceof AdminError) problems = e.problems
    } finally {
      busy = false
    }
  }

  /** Adding or taking out a session saves at once: nothing else may be unsaved. */
  async function move(next: Part[]) {
    parts = next
    await save()
  }

  function setPart(i: number, change: Partial<Part>) {
    parts = parts.map((p, j) => (j === i ? { ...p, ...change } : p))
  }

  function addPart(kind: PartKind) {
    parts = [...parts, newPart(parts, kind, date)]
  }

  async function remove() {
    if (!confirm(`Remove ${name}? Its sessions stay.`)) return
    await api('DELETE', `/events/${pathId}`)
    window.location.assign('/admin/events')
  }

  let target: Record<string, string> = $state({}) // the part each offered session would go to
  const partName = (id: string | undefined) => parts.find((p) => p.id === id)?.name ?? ''
</script>

<main>
  <p class="back"><a href="/admin/events">← Events</a></p>
  {#if signedOut}
    <p>Sign in on the <a href="/admin">admin page</a> first, then come back.</p>
  {:else if !loaded}
    {#if message}<p class="error">{message}</p>{:else}<p class="muted">Loading…</p>{/if}
  {:else}
    <h1>{isNew ? 'New event' : name}</h1>
    <section class="panel form">
      <label><span>Name</span><input bind:value={name} placeholder="NHMS October" /></label>
      {#if isNew}
        <label><span>Id (in its address, for good)</span><input bind:value={newId} oninput={() => (idTouched = true)} /></label>
      {/if}
      <label><span>Date</span><input type="date" bind:value={date} /></label>
      <label><span>Course</span>
        <select bind:value={course}>
          {#each allCourses as c (c.id)}<option value={c.id}>{c.name}</option>{/each}
        </select>
      </label>
      <label><span>Layout</span>
        <select bind:value={layout}>
          {#each layouts as l (l.id)}<option value={l.id}>{l.name}</option>{/each}
        </select>
      </label>
      <fieldset>
        <legend>Cars</legend>
        {#each allCars as c (c.slug)}
          <label class="check"><input type="checkbox" value={c.slug} bind:group={cars} /> {c.name}</label>
        {/each}
      </fieldset>
    </section>

    <section class="panel">
      <h2>Parts</h2>
      <p class="muted small">Times are yours ({Intl.DateTimeFormat().resolvedOptions().timeZone}). A session joins the part the server heard it during.</p>
      {#each parts as p, i (p.id ?? `new-${i}`)}
        <div class="part">
          <input class="partname" value={p.name} oninput={(e) => setPart(i, { name: e.currentTarget.value })} aria-label="Part's name" />
          <span class="kind">{p.kind === 'race' ? 'Race' : 'Practice'}</span>
          <input type="datetime-local" value={toLocalInput(p.start)} onchange={(e) => { const ms = fromLocalInput(e.currentTarget.value); if (ms !== null) setPart(i, { start: ms }) }} aria-label="Starts" />
          <span>to</span>
          <input type="datetime-local" value={toLocalInput(p.end)} onchange={(e) => { const ms = fromLocalInput(e.currentTarget.value); if (ms !== null) setPart(i, { end: ms }) }} aria-label="Ends" />
          <button class="danger" onclick={() => (parts = parts.filter((_, j) => j !== i))}>Remove</button>
        </div>
      {/each}
      <p class="row">
        <button onclick={() => addPart('practice')}>Add a practice</button>
        {#if !parts.some((p) => p.kind === 'race')}<button onclick={() => addPart('race')}>Add the race</button>{/if}
      </p>
    </section>

    {#if problems.length > 0}
      <ul class="problems">{#each problems as p (p)}<li>{p}</li>{/each}</ul>
    {/if}
    <p class="row">
      <button class="primary" onclick={save} disabled={busy || !changed}>{isNew ? 'Create the event' : 'Save'}</button>
      {#if changed && !isNew}<span class="muted">Unsaved changes</span>{/if}
      {#if message}<span class="message">{message}</span>{/if}
      {#if !isNew}<button class="danger right" onclick={remove}>Remove the event</button>{/if}
    </p>

    {#if page}
      <section class="panel">
        <h2>Sessions</h2>
        {#if changed}<p class="warn">Save your changes first, then add or take out sessions.</p>{/if}
        {#each parts.filter((p) => p.id) as p (p.id)}
          <h3>{p.name}</h3>
          {@const list = page.sessions[p.id!] ?? []}
          {#if list.length === 0}<p class="muted small">None yet.</p>{/if}
          {#each list as s (s.id)}
            {@render sessionRow(s)}
            <button class="small" disabled={busy || changed} onclick={() => move(removeSession(parts, p.id!, s.id))}>
              Take out
            </button>
          {/each}
        {/each}
        {#if page.others.length > 0}
          <h3>Other sessions around the day</h3>
          <p class="muted small">Heard outside every part (a tablet's session uploads at its end), or taken out.</p>
          {#each page.others as s (s.id)}
            {@render sessionRow(s)}
            <span class="row">
              <select bind:value={target[s.id]} aria-label="Which part">
                {#each parts.filter((p) => p.id) as p (p.id)}<option value={p.id}>{p.name}</option>{/each}
              </select>
              <button class="small" disabled={busy || changed || parts.length === 0}
                onclick={() => move(addSession(parts, target[s.id] ?? parts.find((p) => p.id)!.id!, s.id))}>
                Add to {partName(target[s.id] ?? parts.find((p) => p.id)?.id)}
              </button>
            </span>
          {/each}
        {/if}
      </section>
    {/if}
  {/if}
</main>

{#snippet sessionRow(s: SessionBrief)}
  <div class="session">
    <a href={`/cars/${s.car}/sessions/${s.id}`}>{heardText(s)}</a>
    <span class="muted">{s.car}{s.source === 'tablet' ? ' · tablet only' : ''} · {s.laps} lap{s.laps === 1 ? '' : 's'} · driver {driverName(s.driver)}</span>
  </div>
{/snippet}

<style>
  main { max-width: 960px; margin: 0 auto; padding: 16px; display: grid; gap: 12px; }
  .back { margin: 0; }
  h1 { margin: 0; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; }
  .form { display: grid; gap: 10px; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); }
  label { display: grid; gap: 2px; font-size: 0.85rem; color: var(--muted); }
  fieldset { border: 1px solid var(--line); border-radius: 8px; display: flex; flex-wrap: wrap; gap: 4px 12px; }
  .check { display: flex; gap: 6px; align-items: center; color: var(--text); }
  .part { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 8px; }
  .partname { width: 12em; }
  .kind { color: var(--muted); font-size: 0.85rem; min-width: 5em; }
  .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .right { margin-left: auto; }
  .session { display: grid; gap: 2px; margin-top: 8px; }
  .small { font-size: 0.8rem; }
  .problems { color: var(--critical); margin: 0; }
  .warn { color: var(--caution); }
  .message { color: var(--muted); }
  .error { color: var(--critical); }
</style>
