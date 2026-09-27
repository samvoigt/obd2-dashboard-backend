<script lang="ts">
  import Landing from './Landing.svelte'
  import CarPage from './CarPage.svelte'
  import Admin from './Admin.svelte'
  import SessionsPage from './SessionsPage.svelte'
  import SessionPage from './SessionPage.svelte'
  import { route } from './lib/routes'

  // Pages are separate URLs served by the server (`/`, `/cars/{slug}`, `/admin`), so links are plain links.
  const current = route(window.location.pathname)
</script>

{#if current.page === 'car'}
  <CarPage slug={current.slug} />
{:else if current.page === 'sessions'}
  <SessionsPage slug={current.slug} />
{:else if current.page === 'session'}
  <SessionPage slug={current.slug} id={current.id} />
{:else if current.page === 'admin'}
  <Admin />
{:else}
  <Landing />
{/if}
