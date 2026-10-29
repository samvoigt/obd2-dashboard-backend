<script lang="ts">
  import { onMount } from 'svelte'
  import { crew } from '../lib/crew'

  // The crew's login, on every page (M34.3; Sam: "A Crew button in the header"): pick the car, its passcode,
  // Log in; the cars this browser is crew for, each with Log out. Asked of each car once, when opened.
  let menu: HTMLDetailsElement | undefined = $state()
  let cars: { slug: string; name: string }[] = $state.raw([])
  let car = $state('')
  let passcode = $state('')
  let busy = $state(false)
  let error: string | null = $state(null)

  const logged = $derived(cars.filter((c) => $crew.cars[c.slug]))
  const anyIn = $derived(Object.values($crew.cars).some(Boolean))

  async function opened() {
    if (!menu?.open) return
    error = null
    if (cars.length === 0) cars = await fetch('/api/cars').then((r) => (r.ok ? r.json() : [])).catch(() => [])
    await crew.check(cars.map((c) => c.slug))
    const remembered = crew.remembered()
    if (!car) car = cars.find((c) => c.slug === remembered && !$crew.cars[c.slug])?.slug ?? cars.find((c) => !$crew.cars[c.slug])?.slug ?? ''
  }

  async function login(e: SubmitEvent) {
    e.preventDefault()
    busy = true
    error = null
    try {
      await crew.login(car, passcode)
      passcode = ''
    } catch (err) {
      error = err instanceof Error ? err.message : String(err)
    } finally {
      busy = false
    }
  }

  onMount(() => {
    const close = (e: Event) => {
      if (!menu?.open) return
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !menu.contains(e.target as Node)) menu.open = false
    }
    window.addEventListener('keydown', close)
    window.addEventListener('click', close)
    return () => { window.removeEventListener('keydown', close); window.removeEventListener('click', close) }
  })
</script>

<details class="crew" bind:this={menu} ontoggle={opened}>
  <summary class:in={anyIn} aria-label={anyIn ? 'Crew: logged in' : 'Crew: log in'}>Crew{#if anyIn} ✓{/if}</summary>
  <div class="menu">
    {#each logged as c (c.slug)}
      <div class="row"><span>{c.name}</span><button onclick={() => crew.logout(c.slug)}>Log out</button></div>
    {/each}
    {#if cars.some((c) => !$crew.cars[c.slug])}
      <form onsubmit={login}>
        <label>Car
          <select bind:value={car}>
            {#each cars.filter((c) => !$crew.cars[c.slug]) as c (c.slug)}<option value={c.slug}>{c.name}</option>{/each}
          </select>
        </label>
        <input type="password" autocomplete="current-password" placeholder="Crew passcode" bind:value={passcode} aria-label="Crew passcode" />
        <button type="submit" disabled={busy || !car || passcode.length === 0}>Log in</button>
      </form>
    {/if}
    {#if error}<p class="error" role="alert">{error}</p>{/if}
  </div>
</details>

<style>
  .crew { position: relative; }
  summary {
    list-style: none; cursor: pointer; color: var(--accent); border: 1px solid var(--accent); border-radius: 6px; padding: 4px 10px;
  }
  summary::-webkit-details-marker { display: none; }
  summary.in { color: var(--in-range); border-color: var(--in-range); }
  .menu {
    position: absolute; right: 0; top: calc(100% + 6px); z-index: 1000; min-width: 240px;
    display: grid; gap: 10px; padding: 10px; background: var(--panel); border: 1px solid var(--line); border-radius: 8px;
  }
  .row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  form { display: grid; gap: 8px; }
  label { display: grid; gap: 4px; color: var(--muted); font-size: 0.9rem; }
  select, input { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; }
  button { background: var(--bg); color: var(--accent); border: 1px solid var(--accent); border-radius: 6px; padding: 4px 10px; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  .error { color: var(--critical); margin: 0; font-size: 0.9rem; }
  @media (max-width: 600px) {
    summary { padding: 4px 8px; }
  }
</style>
