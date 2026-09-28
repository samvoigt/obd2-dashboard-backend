<script lang="ts">
  import { onMount } from 'svelte'
  import type { Driver } from './lib/events'

  // Drivers, public (M15.5).
  let drivers: Driver[] | null = $state(null)
  let error: string | null = $state(null)

  onMount(async () => {
    try {
      const r = await fetch('/api/drivers')
      if (!r.ok) throw new Error(`The server answered ${r.status}.`)
      drivers = (await r.json()) as Driver[]
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  })
</script>

<main>
  <p class="back"><a href="/">← Cars</a></p>
  <h1>Drivers</h1>
  {#if error}
    <p class="error">Could not load the drivers: {error}</p>
  {:else if drivers && drivers.length === 0}
    <p class="muted">No drivers yet.</p>
  {:else if drivers}
    <ul class="list">
      {#each drivers as d (d.id)}
        <li><a href={`/drivers/${d.id}`}><strong>{d.name}</strong></a> <span class="muted">{d.code}</span></li>
      {/each}
    </ul>
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  main { max-width: 720px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .list { list-style: none; padding: 0; display: grid; gap: 8px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; }
  .error { color: var(--critical); }
</style>
