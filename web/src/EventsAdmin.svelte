<script lang="ts">
  import { onMount } from 'svelte'
  import { api, AdminError } from './lib/admin'
  import type { EventView } from './lib/events'

  // Events (M14.3): signed in on the admin page, as courses are.
  let events: EventView[] | null = $state(null)
  let signedOut = $state(false)
  let error: string | null = $state(null)

  onMount(async () => {
    try {
      await api('GET', '/me')
      events = await api<EventView[]>('GET', '/events')
    } catch (e) {
      if (e instanceof AdminError && e.status === 401) signedOut = true
      else error = e instanceof Error ? e.message : String(e)
    }
  })
</script>

<main>
  <p class="back"><a href="/admin">← Admin</a></p>
  <h1>Events</h1>
  {#if signedOut}
    <p>Sign in on the <a href="/admin">admin page</a> first, then come back.</p>
  {:else if error}
    <p class="error">{error}</p>
  {:else if events}
    <p class="muted">An event is at a course, with practice sessions and a race. Sessions join a part when the server heard them during it, or by hand.</p>
    <p><a class="button primary" href="/admin/events/new">New event</a> <a class="drivers" href="/admin/drivers">Drivers →</a></p>
    {#if events.length === 0}
      <p class="muted">No events yet.</p>
    {:else}
      <ul class="list">
        {#each events as e (e.id)}
          <li>
            <a href={`/admin/events/${e.id}`}><strong>{e.name}</strong></a>
            <span class="muted">{e.date} · {e.course}, {e.layout} · {e.cars.join(', ')}</span>
            <span class="muted">{e.parts.map((p) => p.name).join(' · ') || 'No parts yet'}</span>
          </li>
        {/each}
      </ul>
    {/if}
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  main { max-width: 960px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .list { list-style: none; padding: 0; display: grid; gap: 8px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; display: grid; gap: 4px; }
  .button { display: inline-block; border: 1px solid var(--accent); color: var(--accent); border-radius: 8px; padding: 6px 12px; text-decoration: none; font-weight: 600; }
  .drivers { margin-left: 12px; }
  .error { color: var(--critical); }
</style>
