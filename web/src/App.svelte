<script lang="ts">
  import Landing from './Landing.svelte'
  import CarPage from './CarPage.svelte'
  import { route } from './lib/routes'

  // Pages are separate URLs served by the server (`/`, `/cars/{slug}`), so links are plain links.
  const current = route(window.location.pathname)
</script>

{#if current.page === 'car'}
  <CarPage slug={current.slug} />
{:else}
  <Landing />
{/if}
