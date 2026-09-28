<script lang="ts">
  import { onMount, untrack } from 'svelte'
  import { driverLabel, gap, windowText, type DriverBest, type EventResults, type SessionResult } from './lib/eventResults'
  import { lapTime } from './lib/sessions'
  import RaceSection from './RaceSection.svelte'

  let { id }: { id: string } = $props()

  // An event and its practice results (M14.5), public. The race's results come in M15.
  let results: EventResults | null = $state.raw(null)
  let missing = $state(false)
  let error: string | null = $state(null)

  onMount(load)

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
    <p class="muted">{e.date} · <a href={`/courses/${e.course}`}>{e.courseName}</a>, {e.layoutName} · {e.cars.map((c) => c.name).join(', ')}</p>

    {#if results.parts.some((p) => p.part.kind === 'practice')}
      <section class="panel">
        <h2>Practice, every part</h2>
        {@render bests(results.practiceBests, results.practiceBestSectors)}
      </section>
    {/if}

    {#if results.race}
      <RaceSection race={results.race} eventId={e.id} revision={e.revision} {carName} onChanged={load} />
    {/if}

    {#each results.parts as p (p.part.id)}
      <section class="panel">
        <h2>{p.part.name} <span class="muted small">{windowText(p.part)}</span></h2>
        {#if p.part.kind === 'race'}
          <p class="muted small">The race as one timeline is above; its sessions are here.</p>
        {:else}
          {@render bests(p.bests, p.bestSectors)}
        {/if}
        {#if p.sessions.length === 0}
          <p class="muted small">No sessions.</p>
        {:else}
          <h3>Sessions</h3>
          <ul class="sessions">
            {#each p.sessions as s (s.id)}
              <li>
                <a href={`/cars/${s.car}/sessions/${s.id}`}>{carName(s.car)}, {heard(s)}</a>
                <span>{driverLabel(s.driver)}</span>
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
            <td class="time"><a href={`/cars/${b.car}/sessions/${b.session}`}>{lapTime(b.lap.time)}</a></td>
            <td class="muted">{gap(b.lap.time, fastest)}</td>
            {#each sectors as best, j (j)}
              {@const t = b.lap.sectors[j]}
              <td class="sector" class:bestsector={t !== undefined && t === best}>{t === undefined ? '' : t.toFixed(3)}</td>
            {/each}
            <td class="muted">{carName(b.car)}</td>
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
</style>
