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
{:else if import.meta.env.DEV && current.page === 'preview'}
  <!-- Loaded only in the dev server: in the build this branch is false, and the preview is dropped. -->
  {#await import('./WidgetsPreview.svelte') then m}<m.default slug={current.slug} />{/await}
{:else if current.page === 'courses'}
  {#await import('./CoursesPage.svelte') then m}<m.default />{/await}
{:else if current.page === 'course'}
  {#await import('./CoursePage.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'events'}
  {#await import('./EventsPage.svelte') then m}<m.default />{/await}
{:else if current.page === 'event'}
  {#await import('./EventPage.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'admin-courses'}
  {#await import('./CoursesAdmin.svelte') then m}<m.default />{/await}
{:else if current.page === 'admin-course'}
  {#await import('./CourseEditor.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'admin-drivers'}
  {#await import('./DriversAdmin.svelte') then m}<m.default />{/await}
{:else if current.page === 'admin-events'}
  {#await import('./EventsAdmin.svelte') then m}<m.default />{/await}
{:else if current.page === 'admin-event'}
  {#await import('./EventEditor.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'admin'}
  <Admin />
{:else}
  <Landing />
{/if}
