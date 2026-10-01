<script lang="ts">
  import { onMount, untrack } from 'svelte'
  import {
    driverLabel, gap, partOn, REFRESH_MS, sectorGap, windowText, type DriverBest, type DriverConsistency, type EventResults, type SectorRow, type SessionResult,
  } from './lib/eventResults'
  import { lapTime } from './lib/sessions'
  import RaceSection from './RaceSection.svelte'
  import { signin } from './lib/signin'
  import { lapLink } from './lib/laps'
  import type { LapPick } from './lib/comparePick'
  import ComparePick from './ComparePick.svelte'

  let { id }: { id: string } = $props()

  // An event and its practice results (M14.5), public. The race's results come in M15.
  let results: EventResults | null = $state.raw(null)
  let missing = $state(false)
  let error: string | null = $state(null)

  // While a part is on, the page asks again every 30 s (M17.6): the stint being driven counts, marked live.
  onMount(() => {
    load()
    const timer = setInterval(() => { if (results && partOn(results.event.parts, Date.now())) load() }, REFRESH_MS)
    return () => clearInterval(timer)
  })

  async function load() {
    try {
      const r = await fetch(`/api/events/${untrack(() => id)}`)
      if (r.status === 404) { missing = true; return }
      if (!r.ok) throw new Error(`The server answered ${r.status}.`)
      results = (await r.json()) as EventResults
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  }

  /** A driver's best as a compare pick (M16.4), on the event's course and layout. */
  const pickOfBest = (b: DriverBest): LapPick => ({
    ref: { car: b.car, session: b.session, start: b.lap.start, end: b.lap.end },
    course: results!.event.course, layout: results!.event.layout, label: `${driverLabel(b.driver)} ${lapTime(b.lap.time)}`,
  })
  const carName = (slug: string) => results?.event.cars.find((c) => c.slug === slug)?.name ?? slug
  const heard = (s: SessionResult) => new Date(s.heardFrom).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false })
</script>

<main>
  <p class="back"><a href="/events">← Events</a></p>
  {#if missing}
    <h1>No such event</h1>
  {:else if error}
    <p class="error">Could not load the event: {error}</p>
  {:else if results}
    {@const e = results.event}
    <h1>{e.name}</h1>
    {#if $signin.state === 'in'}<p><a class="button" href={`/events/${id}/edit`}>Edit</a></p>{/if}
    <p class="muted">{e.date} · <a href={`/courses/${e.course}`}>{e.courseName}</a>, {e.layoutName} · {e.cars.map((c) => c.name).join(', ')}</p>

    {#if results.parts.some((p) => p.part.kind === 'practice')}
      <section class="panel">
        <h2>Practice, every part</h2>
        {@render bests(results.practiceBests, results.practiceBestSectors)}
        {@render analysis(results.practiceSectorRows, results.practiceConsistency, results.practiceTheoretical)}
      </section>
    {/if}

    {#if results.race}
      <RaceSection race={results.race} eventId={e.id} revision={e.revision} {carName} onChanged={load} course={e.course} layout={e.layout} />
    {/if}

    {#each results.parts as p (p.part.id)}
      <section class="panel">
        <h2>{p.part.name} <span class="muted small">{windowText(p.part)}</span></h2>
        {#if p.part.kind === 'race'}
          <p class="muted small">The race as one timeline is above; its sessions are here.</p>
        {:else}
          {@render bests(p.bests, p.bestSectors)}
          {@render analysis(p.sectorRows, p.consistency, p.theoretical)}
        {/if}
        {#if p.sessions.length === 0}
          <p class="muted small">No sessions.</p>
        {:else}
          <h3>Sessions</h3>
          <ul class="sessions">
            {#each p.sessions as s (s.id)}
              <li>
                <a href={`/cars/${s.car}/sessions/${s.id}`}>{#if s.name}{s.name} · {/if}{carName(s.car)}, {heard(s)}</a>
                <span>{driverLabel(s.driver)}</span>
                {#if s.live}<span class="live">live</span>{/if}
                {#if s.otherLayout}
                  <span class="muted">Timed on another layout ({s.otherLayout}): not counted here</span>
                {:else}
                  <span class="muted">{s.laps.length} lap{s.laps.length === 1 ? '' : 's'}{s.best ? `, best ${lapTime(s.best.time)}` : ''}{s.laps.some((l) => l.source === 'retimed') ? ' · re-timed' : ''}</span>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      </section>
    {/each}
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

{#snippet analysis(rows: SectorRow[], consistency: DriverConsistency[], theoretical: number | null | undefined)}
  {#if theoretical}<p class="small">Theoretical best <strong>{lapTime(theoretical)}</strong> <span class="muted">(the best of each sector added up)</span></p>{/if}
  {#if rows.length > 1 && rows[0]!.sectors.length > 0}
    <details>
      <summary>Sectors, driver by driver</summary>
      <table class="bests">
        <thead><tr><th>Driver</th>{#each rows[0]!.sectors as _, i (i)}<th>S{i + 1}</th>{/each}<th>Theoretical</th></tr></thead>
        <tbody>
          {#each rows as r (r.driver?.id ?? 'none')}
            <tr><td>{driverLabel(r.driver)}</td>
              {#each r.sectors as t, i (i)}<td class="sector">{t === null ? '' : t.toFixed(3)} <span class="muted small">{sectorGap(r.gaps[i])}</span></td>{/each}
              <td>{r.theoretical ? lapTime(r.theoretical) : '—'}</td></tr>
          {/each}
        </tbody>
      </table>
    </details>
  {/if}
  {#if consistency.length > 0}
    <details>
      <summary>Consistency (laps on track)</summary>
      <table class="bests">
        <thead><tr><th>Driver</th><th>Laps</th><th>Best</th><th>Median</th><th>Spread</th><th>Within 1%</th></tr></thead>
        <tbody>
          {#each consistency as c (c.driver?.id ?? 'none')}
            <tr><td>{driverLabel(c.driver)}</td><td>{c.consistency.laps}</td><td>{lapTime(c.consistency.best)}</td>
              <td>{lapTime(c.consistency.median)}</td><td>± {c.consistency.spread.toFixed(3)} s</td><td>{c.consistency.withinOnePercent}</td></tr>
          {/each}
        </tbody>
      </table>
    </details>
  {/if}
{/snippet}

{#snippet bests(list: DriverBest[], sectors: (number | null)[])}
  {#if list.length === 0}
    <p class="muted small">No laps on track yet.</p>
  {:else}
    {@const fastest = list[0]!.lap.time}
    <table class="bests">
      <thead><tr><th></th><th>Driver</th><th>Best</th><th></th>{#each sectors as _, i (i)}<th>S{i + 1}</th>{/each}<th>Car</th></tr></thead>
      <tbody>
        {#each list as b, i (b.session + b.lap.lap)}
          <tr>
            <td class="pos">{i + 1}</td>
            <td>{#if b.driver}<a href={`/drivers/${b.driver.id}`}>{driverLabel(b.driver)}</a>{:else}{driverLabel(b.driver)}{/if}</td>
            <td class="time"><a href={lapLink(b.car, b.session, b.lap.start, b.lap.end)}>{lapTime(b.lap.time)}</a></td>
            <td class="muted">{gap(b.lap.time, fastest)}</td>
            {#each sectors as best, j (j)}
              {@const t = b.lap.sectors[j]}
              <td class="sector" class:bestsector={t !== undefined && t === best}>{t === undefined ? '' : t.toFixed(3)}</td>
            {/each}
            <td class="muted">{carName(b.car)}</td>
            <td><ComparePick lap={pickOfBest(b)} best={pickOfBest(list[0]!)} /></td>
          </tr>
        {/each}
      </tbody>
    </table>
    {#if sectors.length > 0}
      <p class="muted small">Best of each sector: {sectors.map((s) => (s === null ? '—' : s.toFixed(3))).join(' · ')}</p>
    {/if}
  {/if}
{/snippet}

<style>
  main { max-width: 960px; margin: 0 auto; padding: 16px; display: grid; gap: 12px; }
  .back { margin: 0; }
  h1 { margin: 0; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; overflow-x: auto; }
  h2 { margin: 0 0 8px; }
  h2 .small { font-size: 0.9rem; font-weight: 400; }
  .bests { border-collapse: collapse; }
  .bests th { text-align: left; color: var(--muted); font-weight: 600; font-size: 0.85rem; padding: 4px 8px; }
  .bests td { padding: 6px 8px; border-top: 1px solid var(--line); font-variant-numeric: tabular-nums; }
  .pos { color: var(--muted); }
  .time { font-weight: 600; }
  .bestsector { color: var(--in-range); font-weight: 700; }
  .sessions { list-style: none; padding: 0; display: grid; gap: 6px; }
  .sessions li { display: grid; gap: 2px; }
  .error { color: var(--critical); }
  .live { color: var(--in-range); font-weight: 600; font-size: 0.85rem; }
  .button { display: inline-block; border: 1px solid var(--accent); color: var(--accent); border-radius: 8px; padding: 6px 12px; text-decoration: none; font-weight: 600; }
</style>
