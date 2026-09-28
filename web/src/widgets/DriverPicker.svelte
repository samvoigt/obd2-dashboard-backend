<script lang="ts">
  import { onMount } from 'svelte'

  // Who's driving, set by the crew from the car page (M17.5): the live session's driver.
  let { slug, session, current }: { slug: string; session: string; current?: string } = $props()

  let drivers: { id: string; name: string; code: string }[] = $state.raw([])
  let busy = $state(false)
  let message: string | null = $state(null)

  onMount(async () => {
    drivers = await fetch('/api/drivers').then((r) => (r.ok ? r.json() : [])).catch(() => [])
  })

  async function set(id: string) {
    busy = true
    message = null
    try {
      const r = await fetch(`/api/cars/${slug}/sessions/${session}/driver`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ driver: id || null }),
      })
      if (!r.ok) message = ((await r.json().catch(() => null)) as { message?: string } | null)?.message ?? `Not saved (${r.status}).`
    } catch {
      message = 'Not saved: no connection.'
    } finally {
      busy = false
    }
  }
</script>

<p class="picker">
  <label>Who's driving
    <select disabled={busy || drivers.length === 0} value={drivers.find((d) => d.code === current)?.id ?? ''} onchange={(e) => set(e.currentTarget.value)}>
      <option value="">Not set</option>
      {#each drivers as d (d.id)}<option value={d.id}>{d.name} ({d.code})</option>{/each}
    </select>
  </label>
  {#if message}<span class="error">{message}</span>{/if}
</p>

<style>
  .picker { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin: 0; }
  .error { color: var(--critical); }
</style>
