<script lang="ts">
  import Breadcrumbs from './Breadcrumbs.svelte'
  import { crumbs } from './lib/routes'
  import { setTitle } from './lib/title'
  import Skeleton from './widgets/Skeleton.svelte'
  import { onMount, untrack } from 'svelte'
  import { dateText, type DriverRecord } from './lib/eventResults'
  import { raceClock } from './lib/race'
  import { lapTime } from './lib/sessions'
  import { lapLink } from './lib/laps'
  import type { LapPick } from './lib/comparePick'
  import ComparePick from './ComparePick.svelte'

  let { id }: { id: string } = $props()

  // A driver (M15.5), public: their best at each course, and every event they drove in.
  let record: DriverRecord | null = $state.raw(null)
  let missing = $state(false)
  let error: string | null = $state(null)

  onMount(async () => {
    try {
      const r = await fetch(`/api/drivers/${untrack(() => id)}`)
      if (r.status === 404) { missing = true; return }
      if (!r.ok) throw new Error(`The server answered ${r.status}.`)
      record = (await r.json()) as DriverRecord
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  })

  /** A course best as a compare pick (M16.4), on the layout of the event it was set at. */
  function pickOfCourse(r: DriverRecord, c: DriverRecord['courses'][number]): LapPick | null {
    const event = r.events.find((e) => e.event.id === c.event)?.event
    if (!event) return null
    return {
      ref: { car: c.best.car, session: c.best.session, start: c.best.lap.start, end: c.best.lap.end },
      course: c.course, layout: event.layout, label: `${r.driver.name} ${lapTime(c.best.lap.time)}`,
    }
  }

  // Its tab, once its name is known (M35.5).
  $effect(() => { const name = record?.driver.name; if (name) setTitle(name) })
</script>

<main>
  <Breadcrumbs items={crumbs({ page: 'driver', id }, { driver: record?.driver.name })} />
  {#if missing}
    <h1>No such driver</h1>
  {:else if error}
    <p class="error">Could not load the driver: {error}</p>
  {:else if record}
    <h1>{record.driver.name} <span class="muted">{record.driver.code}</span></h1>

    {#if record.courses.length > 0}
      <section class="panel">
        <h2>Best at each course</h2>
        <div class="table-scroll">
          <table>
          <tbody>
            {#each record.courses as c (c.course)}
              <tr><td>{c.courseName}</td><td class="time"><a href={lapLink(c.best.car, c.best.session, c.best.lap.start, c.best.lap.end)}>{lapTime(c.best.lap.time)}</a></td>
                <td class="muted"><a href={`/events/${c.event}`}>the event</a></td>
                <td>{#if pickOfCourse(record, c)}<ComparePick lap={pickOfCourse(record, c)!} best={null} />{/if}</td></tr>
            {/each}
          </tbody>
        </table>
        </div>
      </section>
    {/if}

    {#if record.events.length === 0}
      <p class="muted">No laps yet.</p>
    {/if}
    {#each record.events as e (e.event.id)}
      <section class="panel">
        <h2><a href={`/events/${e.event.id}`}>{e.event.name}</a> <span class="muted small">{dateText(e.event)} · {e.event.courseName}</span></h2>
        {#each e.practice as p (p.part)}
          <p>{p.part}: best <strong>{lapTime(p.best.lap.time)}</strong></p>
        {/each}
        {#if e.stints.length > 0}
          <div class="table-scroll">
            <table>
            <thead><tr><th>Stint</th><th>Car</th><th>Laps</th><th>Time</th><th>Best</th></tr></thead>
            <tbody>
              {#each e.stints as s (s.car + s.stint.number)}
                <tr><td>{s.stint.number}</td><td>{e.event.cars.find((c) => c.slug === s.car)?.name ?? s.car}</td>
                  <td>{s.stint.laps > 0 ? `${s.stint.firstLap}–${s.stint.lastLap} (${s.stint.laps})` : '—'}</td>
                  <td>{raceClock(s.stint.seconds)}</td><td>{s.stint.best ? lapTime(s.stint.best) : '—'}</td></tr>
              {/each}
            </tbody>
          </table>
          </div>
        {/if}
      </section>
    {/each}
  {:else}
    <Skeleton panels={[24, 140, 200]} label="Loading the driver" />
  {/if}
</main>

<style>
  main { max-width: 960px; margin: 0 auto; padding: 16px; display: grid; gap: 12px; }
  h1 { margin: 0; }
  h2 { margin: 0 0 8px; }
  h2 .small { font-size: 0.9rem; font-weight: 400; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; }
  table { border-collapse: collapse; }
  th { text-align: left; color: var(--muted); font-weight: 600; font-size: 0.85rem; padding: 4px 8px; }
  td { padding: 5px 8px; border-top: 1px solid var(--line); font-variant-numeric: tabular-nums; }
  .time { font-weight: 600; }
  .error { color: var(--critical); }
</style>
