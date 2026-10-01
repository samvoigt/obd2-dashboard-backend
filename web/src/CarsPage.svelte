<script lang="ts">
  import { onMount } from 'svelte'
  import { fetchCars, type CarSummary } from './lib/api'
  import { stateLabel } from './lib/state'
  import { signin } from './lib/signin'
  import { AdminError, api, slugProblem, tokenProblem, twiceProblem, type CarWithToken } from './lib/admin'
  import TokenOnce from './TokenOnce.svelte'

  // Every car (M21.5): its live feed, its sessions and, signed in, its management. Signed in, a car can be added here.
  let cars: CarSummary[] = $state([])
  let error: string | null = $state(null)
  let loaded = $state(false)

  // Adding a car, signed in.
  let adding = $state(false)
  let busy = $state(false)
  let addError: string | null = $state(null)
  let newSlug = $state('')
  let newName = $state('')
  let newChoose = $state(false)
  let newToken = $state('')
  let newToken2 = $state('')
  // A generated token, shown once: it lives only here, so it is gone on reload.
  let shown: { slug: string; token: string } | null = $state(null)

  const signedIn = $derived($signin.state === 'in')
  const addProblem = $derived(
    slugProblem(newSlug.trim()) ??
      (newName.trim() === '' ? 'A car needs a name' : null) ??
      (newChoose ? twiceProblem(newToken, newToken2, tokenProblem) : null),
  )

  async function refresh() {
    try {
      cars = await fetchCars()
      error = null
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    } finally {
      loaded = true
    }
  }

  onMount(() => {
    void refresh()
    const timer = setInterval(refresh, 10_000)
    return () => clearInterval(timer)
  })

  // Signed out, nothing of the admin's stays on the page.
  $effect(() => {
    if (!signedIn) { adding = false; shown = null }
  })

  async function add(e: SubmitEvent) {
    e.preventDefault()
    if (addProblem) return
    busy = true
    addError = null
    try {
      const slug = newSlug.trim()
      const result = await api<CarWithToken>('POST', '/cars', { slug, name: newName.trim(), token: newChoose ? newToken : undefined })
      if (result.token) shown = { slug, token: result.token }
      adding = false
      newSlug = newName = newToken = newToken2 = ''
      newChoose = false
      await refresh()
    } catch (e) {
      addError = e instanceof AdminError || e instanceof Error ? e.message : String(e)
    } finally {
      busy = false
    }
  }
</script>

<main>
  <h1>Cars</h1>
  {#if error}
    <p class="error">Could not load the cars: {error}</p>
  {:else if loaded && cars.length === 0}
    <p class="muted">No cars are registered yet.</p>
  {/if}

  {#if signedIn && shown}
    <TokenOnce slug={shown.slug} token={shown.token} done={() => (shown = null)} />
  {/if}

  <ul class="cars">
    {#each cars as car (car.slug)}
      <li>
        <div class="head">
          <a class="name" href={`/cars/${car.slug}`}>{car.name}</a>
          <span class="state"><span class={`dot ${car.state}`}></span>{stateLabel[car.state]}</span>
        </div>
        <div class="links">
          <a href={`/cars/${car.slug}`}>Live</a>
          <a href={`/cars/${car.slug}/sessions`}>Sessions</a>
          {#if signedIn}<a href={`/cars/${car.slug}/manage`}>Manage</a>{/if}
        </div>
      </li>
    {/each}
  </ul>

  {#if signedIn}
    {#if adding}
      <form class="panel form" onsubmit={add}>
        <strong>Add a car</strong>
        <label><span>Slug <span class="muted small">(its permanent address: /cars/…)</span></span>
          <input bind:value={newSlug} autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="outback" />
        </label>
        <label><span>Name</span> <input bind:value={newName} placeholder="Outback" /></label>
        <label class="check"><input type="checkbox" bind:checked={newChoose} /> Choose the token myself</label>
        {#if newChoose}
          <label><span>Token</span> <input class="secret" type="text" bind:value={newToken} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
          <label><span>Again</span> <input class="secret" type="text" bind:value={newToken2} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
        {/if}
        {#if addProblem && (newSlug || newName)}<p class="hint">{addProblem}</p>{/if}
        {#if addError}<p class="error" role="alert">{addError}</p>{/if}
        <div class="row">
          <button class="primary" type="submit" disabled={busy || !!addProblem}>Add</button>
          <button type="button" onclick={() => (adding = false)}>Cancel</button>
        </div>
      </form>
    {:else}
      <p><button class="primary" onclick={() => { adding = true; addError = null }}>Add a car</button></p>
    {/if}
  {/if}
</main>

<style>
  .cars { list-style: none; padding: 0; margin: 0; display: grid; gap: 10px; }
  .cars li { padding: 14px 18px; background: var(--panel); border: 1px solid var(--line); border-radius: 10px; display: grid; gap: 8px; }
  .head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
  .name { font-weight: 600; font-size: 1.15rem; text-decoration: none; }
  .state { color: var(--muted); font-size: 0.95rem; white-space: nowrap; }
  .links { display: flex; gap: 16px; flex-wrap: wrap; }
  .links a { color: var(--accent); }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; margin: 12px 0; }
  .form { display: grid; gap: 10px; }
  label { display: grid; gap: 4px; font-size: 0.95rem; }
  label.check { display: flex; align-items: center; gap: 8px; }
  input:not([type='checkbox']) { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 8px 10px; font-size: 1rem; min-width: 0; width: 100%; max-width: 420px; }
  /* Tokens are typed in the open (Sam), in a face where 0 and O, l and 1 differ. */
  input.secret { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; font-size: 0.95rem; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  button.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  .hint { color: var(--caution); margin: 0; font-size: 0.9rem; }
  .error { color: var(--critical); }
  .small { font-size: 0.85rem; }
</style>
