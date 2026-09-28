<script lang="ts">
  import { onMount } from 'svelte'
  import type { PublicEvent } from './lib/eventResults'

  // Events, public (M14.5), newest first.
  let events: PublicEvent[] | null = $state(null)
  let error: string | null = $state(null)

  onMount(async () => {
    try {
      const r = await fetch('/api/events')
      if (!r.ok) throw new Error(`The server answered ${r.status}.`)
      events = (await r.json()) as PublicEvent[]
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  })
</script>

<main>
  <p class="back"><a href="/">← Cars</a></p>
  <h1>Events</h1>
  {#if error}
    <p class="error">Could not load the events: {error}</p>
  {:else if events && events.length === 0}
    <p class="muted">No events yet.</p>
  {:else if events}
    <ul class="list">
      {#each events as e (e.id)}
        <li>
          <a href={`/events/${e.id}`}><strong>{e.name}</strong></a>
          <span class="muted">{e.date} · {e.courseName}, {e.layoutName} · {e.cars.map((c) => c.name).join(', ')}</span>
        </li>
      {/each}
    </ul>
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  main { max-width: 960px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .list { list-style: none; padding: 0; display: grid; gap: 8px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; display: grid; gap: 4px; }
  .error { color: var(--critical); }
</style>
