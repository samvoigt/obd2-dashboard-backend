<script lang="ts">
  import { onMount } from 'svelte'
  import { api } from './lib/admin'
  import { codeFrom, type Driver } from './lib/events'
  import { signin } from './lib/signin'

  // Drivers, public (M15.5); added, renamed and removed here when signed in (M21.4).
  let drivers: Driver[] | null = $state(null)
  let error: string | null = $state(null)
  let editError: string | null = $state(null)
  let name = $state('')
  let code = $state('')
  let codeTouched = $state(false)
  let editing: string | null = $state(null)
  let editName = $state('')
  let editCode = $state('')
  let busy = $state(false)
  const signedIn = $derived($signin.state === 'in')

  // A new driver's code follows their name until you type one.
  $effect(() => {
    const n = name
    if (!codeTouched) code = codeFrom(n)
  })

  // Signed out, nothing is half-edited.
  $effect(() => {
    if (!signedIn) {
      editing = null
      editError = null
    }
  })

  onMount(async () => {
    try {
      await load()
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  })

  async function load() {
    const r = await fetch('/api/drivers')
    if (!r.ok) throw new Error(`The server answered ${r.status}.`)
    drivers = (await r.json()) as Driver[]
  }

  /** A change through the admin API, then the list again; its error said beside the list. */
  async function run(action: () => Promise<unknown>) {
    busy = true
    editError = null
    try {
      await action()
      await load()
      return true
    } catch (e) {
      editError = e instanceof Error ? e.message : String(e)
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
  <p class="back"><a href="/">← Home</a></p>
  <h1>Drivers</h1>
  {#if error}
    <p class="error">Could not load the drivers: {error}</p>
  {:else if drivers}
    {#if signedIn}
      <p class="muted">Who drove each session is set on its page, by you or the car's crew. A driver who drove stays; rename them instead.</p>
      <form class="row" onsubmit={add}>
        <label><span>Name</span><input bind:value={name} placeholder="Sam Voigt" /></label>
        <label><span>Code</span><input class="code" bind:value={code} oninput={() => { codeTouched = true; code = code.toUpperCase() }} maxlength="4" /></label>
        <button class="primary" disabled={busy || !name.trim()}>Add a driver</button>
      </form>
      {#if editError}<p class="error">{editError}</p>{/if}
    {/if}
    {#if drivers.length === 0}
      <p class="muted">No drivers yet.</p>
    {:else}
      <ul class="list">
        {#each drivers as d (d.id)}
          <li>
            {#if signedIn && editing === d.id}
              <form class="row" onsubmit={saveEdit}>
                <input bind:value={editName} aria-label="Name" />
                <input class="code" bind:value={editCode} oninput={() => (editCode = editCode.toUpperCase())} aria-label="Code" maxlength="4" />
                <button class="primary" disabled={busy}>Save</button>
                <button type="button" onclick={() => (editing = null)}>Cancel</button>
              </form>
            {:else}
              <a href={`/drivers/${d.id}`}><strong>{d.name}</strong></a> <span class="muted">{d.code}</span>
              {#if signedIn}
                <span class="actions">
                  <button onclick={() => edit(d)}>Rename</button>
                  <button class="danger" onclick={() => remove(d)} disabled={busy}>Remove</button>
                </span>
              {/if}
            {/if}
          </li>
        {/each}
      </ul>
    {/if}
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  main { max-width: 720px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .list { list-style: none; padding: 0; display: grid; gap: 8px; margin-top: 16px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; }
  label { display: grid; gap: 2px; font-size: 0.85rem; color: var(--muted); }
  input { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; }
  .code { width: 5em; text-transform: uppercase; }
  .actions { margin-left: auto; display: flex; gap: 6px; }
  button { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 4px 8px; cursor: pointer; }
  button.primary { border-color: var(--accent); color: var(--accent); font-weight: 700; }
  button.danger { color: var(--critical); border-color: var(--critical); }
  button:disabled { opacity: 0.4; cursor: default; }
  .error { color: var(--critical); }
</style>
