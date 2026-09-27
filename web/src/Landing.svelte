<script lang="ts">
  import { onMount } from 'svelte'
  import { fetchCars, type CarSummary } from './lib/api'
  import { stateLabel } from './lib/state'
  import logo from './assets/logo.webp'

  let cars: CarSummary[] = $state([])
  let error: string | null = $state(null)
  let loaded = $state(false)

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
    refresh()
    const timer = setInterval(refresh, 10_000)
    return () => clearInterval(timer)
  })
</script>

<main>
  <img class="logo" src={logo} alt="Bad News Bears, 2026 World Tour" width="360" height="357" />
  <h1>Cars</h1>
  {#if error}
    <p class="error">Could not load the cars: {error}</p>
  {:else if loaded && cars.length === 0}
    <p class="muted">No cars are registered yet.</p>
  {/if}
  <ul class="cars">
    {#each cars as car (car.slug)}
      <li>
        <a href={`/cars/${car.slug}`}>
          <span class="name">{car.name}</span>
          <span class="state"><span class={`dot ${car.state}`}></span>{stateLabel[car.state]}</span>
        </a>
      </li>
    {/each}
  </ul>
</main>

<style>
  .logo { display: block; width: min(360px, 80vw); height: auto; margin: 8px auto 12px; }
  .cars { list-style: none; padding: 0; margin: 0; display: grid; gap: 10px; }
  .cars a {
    display: flex; justify-content: space-between; align-items: center; gap: 12px;
    padding: 18px 20px; background: var(--panel); border: 1px solid var(--line);
    border-radius: 10px; text-decoration: none; font-size: 1.15rem;
  }
  .cars a:hover { border-color: var(--muted); }
  .name { font-weight: 600; }
  .state { color: var(--muted); font-size: 0.95rem; white-space: nowrap; }
  .error { color: var(--critical); }
</style>
