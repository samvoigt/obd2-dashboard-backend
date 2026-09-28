<script lang="ts">
  import { onMount } from 'svelte'
  import { api, AdminError } from './lib/admin'
  import { codeFrom, type Driver } from './lib/events'

  // Drivers (M14.3): one list for every event and car, signed in on the admin page.
  let drivers: Driver[] | null = $state(null)
  let signedOut = $state(false)
  let error: string | null = $state(null)
  let name = $state('')
  let code = $state('')
  let codeTouched = $state(false)
  let editing: string | null = $state(null)
  let editName = $state('')
  let editCode = $state('')
  let busy = $state(false)

  // A new driver's code follows their name until you type one.
  $effect(() => {
    const n = name
    if (!codeTouched) code = codeFrom(n)
  })

  onMount(load)

  async function load() {
    try {
      await api('GET', '/me')
      drivers = await api<Driver[]>('GET', '/drivers')
    } catch (e) {
      if (e instanceof AdminError && e.status === 401) signedOut = true
      else error = e instanceof Error ? e.message : String(e)
    }
  }

  async function run(action: () => Promise<unknown>) {
    busy = true
    error = null
    try {
      await action()
      drivers = await api<Driver[]>('GET', '/drivers')
      return true
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
      return false
    } finally {
      busy = false
    }
  }

  async function add(e: SubmitEvent) {
    e.preventDefault()
    if (await run(() => api('POST', '/drivers', { name, code }))) {
      name = ''
      code = ''
      codeTouched = false
    }
  }

  function edit(d: Driver) {
    editing = d.id
    editName = d.name
    editCode = d.code
  }

  async function saveEdit(e: SubmitEvent) {
    e.preventDefault()
    const id = editing
    if (id && (await run(() => api('PUT', `/drivers/${id}`, { name: editName, code: editCode })))) editing = null
  }

  async function remove(d: Driver) {
    if (!confirm(`Remove ${d.name}?`)) return
    await run(() => api('DELETE', `/drivers/${d.id}`))
  }
</script>

<main>
  <p class="back"><a href="/admin">← Admin</a></p>
  <h1>Drivers</h1>
  {#if signedOut}
    <p>Sign in on the <a href="/admin">admin page</a> first, then come back.</p>
  {:else if drivers}
    <p class="muted">Who drove each session is set on its page, by you or the car's crew. A driver who drove stays; rename them instead.</p>
    <form class="row" onsubmit={add}>
      <label><span>Name</span><input bind:value={name} placeholder="Sam Voigt" /></label>
      <label><span>Code</span><input class="code" bind:value={code} oninput={() => { codeTouched = true; code = code.toUpperCase() }} maxlength="4" /></label>
      <button class="primary" disabled={busy || !name.trim()}>Add a driver</button>
    </form>
    {#if error}<p class="error">{error}</p>{/if}
    {#if drivers.length === 0}
      <p class="muted">No drivers yet.</p>
    {:else}
      <ul class="list">
        {#each drivers as d (d.id)}
          <li>
            {#if editing === d.id}
              <form class="row" onsubmit={saveEdit}>
                <input bind:value={editName} aria-label="Name" />
                <input class="code" bind:value={editCode} oninput={() => (editCode = editCode.toUpperCase())} aria-label="Code" maxlength="4" />
                <button class="primary" disabled={busy}>Save</button>
                <button type="button" onclick={() => (editing = null)}>Cancel</button>
              </form>
            {:else}
              <span class="tag">{d.code}</span>
              <strong>{d.name}</strong>
              <span class="actions">
                <button onclick={() => edit(d)}>Rename</button>
                <button class="danger" onclick={() => remove(d)} disabled={busy}>Remove</button>
              </span>
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  {:else if error}
    <p class="error">{error}</p>
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  main { max-width: 720px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; }
  label { display: grid; gap: 2px; font-size: 0.85rem; color: var(--muted); }
  .code { width: 5em; text-transform: uppercase; }
  .list { list-style: none; padding: 0; display: grid; gap: 8px; margin-top: 16px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 8px 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .tag { font-weight: 700; font-variant-numeric: tabular-nums; color: var(--accent); min-width: 3.5em; }
  .actions { margin-left: auto; display: flex; gap: 6px; }
  .error { color: var(--critical); }
</style>
