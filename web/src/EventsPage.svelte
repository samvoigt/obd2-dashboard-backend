<script lang="ts">
  import Skeleton from './widgets/Skeleton.svelte'
  import { onMount } from 'svelte'
  import { signin } from './lib/signin'
  import { dateText, whereText, type PublicEvent } from './lib/eventResults'
  import HiddenBadge from './HiddenBadge.svelte'

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
  <p class="back"><a href="/">← Home</a></p>
  <h1>Events</h1>
  <!-- Signed in (M21.3): events are made here, and edited from their own page. -->
  {#if $signin.state === 'in'}<p><a class="button" href="/events/new">New event</a></p>{/if}
  {#if error}
    <p class="error">Could not load the events: {error}</p>
  {:else if events && events.length === 0}
    <p class="muted">No events yet.</p>
  {:else if events}
    <ul class="list">
      {#each events as e (e.id)}
        <li>
          <a href={`/events/${e.id}`}><strong>{e.name}</strong></a>{#if e.hidden}<HiddenBadge />{/if}
          <span class="muted">{[dateText(e), whereText(e), e.cars.map((c) => c.name).join(', ')].filter(Boolean).join(' · ')}</span>
        </li>
      {/each}
    </ul>
  {:else}
    <Skeleton title={false} panels={[72, 72, 72]} label="Loading the events" />
  {/if}
</main>

<style>
  main { max-width: 960px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .list { list-style: none; padding: 0; display: grid; gap: 8px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; display: grid; gap: 4px; }
  .error { color: var(--critical); }
</style>
