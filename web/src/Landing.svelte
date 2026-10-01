<script lang="ts">
  import { onMount } from 'svelte'
  import CourseMap from './CourseMap.svelte'
  import { fetchCars, type CarSummary } from './lib/api'
  import type { CourseSummary, CourseView } from './lib/admin'
  import { fromGeoJSON, type EditCourse } from './lib/courseEdit'
  import type { PublicEvent } from './lib/eventResults'
  import type { Driver } from './lib/events'
  import { eventsShown, latestSession, layoutsLine, sessionLine, underWay } from './lib/landing'
  import { badge, fetchDrives, type SessionItem } from './lib/sessions'
  import { stateLabel } from './lib/state'
  import logo from './assets/logo.webp'

  // Every page the site has, in short (M21.6): each section a summary that links to its page.
  // Cars every 10 s, as before; the rest, and each car's latest session, once a minute.
  let cars: CarSummary[] = $state([])
  let carsError: string | null = $state(null)
  let carsLoaded = $state(false)
  let latest: Record<string, SessionItem | null | undefined> = $state.raw({}) // undefined: not known (yet)
  let events: PublicEvent[] | null = $state.raw(null)
  let eventsError: string | null = $state(null)
  let drivers: Driver[] | null = $state.raw(null)
  let driversError: string | null = $state(null)
  let courses: CourseSummary[] | null = $state.raw(null)
  let coursesError: string | null = $state(null)
  let maps: Record<string, EditCourse> = $state.raw({}) // each course's drawing, for its small map
  let now = $state(Date.now())

  const message = (e: unknown) => (e instanceof Error ? e.message : String(e))

  async function json<T>(path: string): Promise<T> {
    const r = await fetch(path)
    if (!r.ok) throw new Error(`The server answered ${r.status}.`)
    return (await r.json()) as T
  }

  async function refreshCars() {
    try {
      cars = await fetchCars()
      carsError = null
    } catch (e) {
      carsError = message(e)
    } finally {
      carsLoaded = true
    }
  }

  /** Each car's latest session: one request a car, so once a minute, not with the cars. */
  async function refreshLatest() {
    const slugs = cars.map((c) => c.slug)
    const found = await Promise.all(slugs.map((s) => fetchDrives(s).then(latestSession).catch(() => undefined)))
    latest = Object.fromEntries(slugs.map((s, i) => [s, found[i]]))
  }

  async function refreshRest() {
    now = Date.now()
    await Promise.all([
      json<PublicEvent[]>('/api/events').then((e) => { events = e; eventsError = null }).catch((e) => (eventsError = message(e))),
      json<Driver[]>('/api/drivers').then((d) => { drivers = d; driversError = null }).catch((e) => (driversError = message(e))),
      json<CourseSummary[]>('/api/courses').then((c) => { courses = c; coursesError = null }).catch((e) => (coursesError = message(e))),
      refreshLatest(),
    ])
    await refreshMaps()
  }

  /** A course's drawing is fetched once a version: a saved course gets a new version, and a new drawing. */
  let mapVersions: Record<string, number> = {}
  async function refreshMaps() {
    const stale = (courses ?? []).filter((c) => mapVersions[c.id] !== c.version)
    if (stale.length === 0) return
    const views = await Promise.all(stale.map((c) => json<CourseView>(`/api/courses/${c.id}`).catch(() => null)))
    const next = { ...maps }
    for (const v of views) {
      if (!v) continue
      next[v.id] = fromGeoJSON(v.geojson)
      mapVersions[v.id] = v.version
    }
    maps = next
  }

  onMount(() => {
    void refreshCars().then(refreshRest)
    const fast = setInterval(refreshCars, 10_000)
    const slow = setInterval(refreshRest, 60_000)
    return () => { clearInterval(fast); clearInterval(slow) }
  })

  const defaultLayout = (c: EditCourse) => c.layouts.find((l) => l.default)?.id ?? c.layouts[0]?.id ?? null
</script>

<main>
  <img class="logo" src={logo} alt="Bad News Bears, 2026 World Tour" width="200" height="198" />

  <section>
    <h2><a href="/cars">Cars →</a></h2>
    {#if carsError}
      <p class="error">Could not load the cars: {carsError}</p>
    {:else if carsLoaded && cars.length === 0}
      <p class="muted">No cars are registered yet.</p>
    {/if}
    <ul class="cars">
      {#each cars as car (car.slug)}
        {@const s = latest[car.slug]}
        <li>
          <a href={`/cars/${car.slug}`}>
            <span class="top">
              <span class="name">{car.name}</span>
              <span class="state"><span class={`dot ${car.state}`}></span>{stateLabel[car.state]}</span>
            </span>
            {#if s}
              {@const b = badge(s.state)}
              <span class="muted small">Latest: {s.name ? `${s.name} · ` : ''}{sessionLine(s)}{b ? ` · ${b.text}` : ''}</span>
            {:else if s === null}
              <span class="muted small">No sessions yet.</span>
            {/if}
          </a>
        </li>
      {/each}
    </ul>
  </section>

  <section>
    <h2><a href="/events">Events →</a></h2>
    {#if eventsError}
      <p class="error">Could not load the events: {eventsError}</p>
    {:else if events && events.length === 0}
      <p class="muted">No events yet.</p>
    {:else if events}
      <ul class="list">
        {#each eventsShown(events) as e (e.id)}
          <li>
            <a href={`/events/${e.id}`}><strong>{e.name}</strong></a>
            {#if underWay(e, now)}<span class="under-way"><span class="dot live"></span>Under way</span>{/if}
            <span class="muted small">{e.date} · {e.courseName}, {e.layoutName}{e.cars.length ? ` · ${e.cars.map((c) => c.name).join(', ')}` : ''}</span>
          </li>
        {/each}
      </ul>
      {#if events.length > eventsShown(events).length}<p class="all"><a href="/events">All {events.length} events →</a></p>{/if}
    {/if}
  </section>

  <section>
    <h2><a href="/drivers">Drivers →</a></h2>
    {#if driversError}
      <p class="error">Could not load the drivers: {driversError}</p>
    {:else if drivers && drivers.length === 0}
      <p class="muted">No drivers yet.</p>
    {:else if drivers}
      <ul class="drivers">
        {#each drivers as d (d.id)}
          <li><a href={`/drivers/${d.id}`}><strong>{d.name}</strong> <span class="muted">{d.code}</span></a></li>
        {/each}
      </ul>
    {/if}
  </section>

  <section>
    <h2><a href="/courses">Courses →</a></h2>
    {#if coursesError}
      <p class="error">Could not load the courses: {coursesError}</p>
    {:else if courses && courses.length === 0}
      <p class="muted">No courses yet.</p>
    {:else if courses}
      <ul class="courses">
        {#each courses as c (c.id)}
          {@const drawing = maps[c.id]}
          <li>
            <a href={`/courses/${c.id}`}>
              <!-- A picture, not a map to move: the page scrolls past it, and the card is the link. -->
              <span class="mini" aria-hidden="true">{#if drawing}<CourseMap course={drawing} layout={defaultLayout(drawing)} />{/if}</span>
              <strong>{c.name}</strong>
              <span class="muted small">{layoutsLine(c)}</span>
            </a>
          </li>
        {/each}
      </ul>
    {/if}
  </section>
</main>

<style>
  main { max-width: 960px; }
  .logo { display: block; width: min(200px, 50vw); height: auto; margin: 4px auto 8px; }
  section { margin: 0 0 22px; }
  h2 { font-size: 1.15rem; margin: 0 0 10px; }
  h2 a { text-decoration: none; }
  h2 a:hover { color: var(--accent); }
  ul { list-style: none; padding: 0; margin: 0; }
  .cars, .list, .courses { display: grid; gap: 8px; }
  .cars a {
    display: grid; gap: 4px; padding: 14px 16px; background: var(--panel); border: 1px solid var(--line);
    border-radius: 10px; text-decoration: none;
  }
  .cars a:hover, .courses a:hover { border-color: var(--muted); }
  .top { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-size: 1.1rem; }
  .name { font-weight: 600; min-width: 0; overflow-wrap: anywhere; }
  .state { color: var(--muted); font-size: 0.95rem; white-space: nowrap; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; }
  .list li .small { flex-basis: 100%; }
  .under-way { color: var(--in-range); font-size: 0.9rem; white-space: nowrap; }
  .drivers { display: flex; flex-wrap: wrap; gap: 8px; }
  .drivers a { display: block; background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 8px 12px; text-decoration: none; }
  .courses { grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr)); }
  .courses a { display: grid; gap: 4px; background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 8px; text-decoration: none; height: 100%; }
  .mini { display: block; height: 160px; pointer-events: none; border-radius: 8px; overflow: hidden; background: var(--bg); }
  .mini :global(div.map) { height: 160px; border-radius: 8px; }
  /* A small map can't be moved, so no zoom buttons. */
  .mini :global(.leaflet-control-zoom) { display: none; }
  .all { margin: 8px 0 0; }
  .small { font-size: 0.9rem; }
  .error { color: var(--critical); }
</style>
