<script lang="ts">
  import Landing from './Landing.svelte'
  import CarPage from './CarPage.svelte'
  import SessionsPage from './SessionsPage.svelte'
  import SessionPage from './SessionPage.svelte'
  import SiteHeader from './SiteHeader.svelte'
  import SiteFooter from './SiteFooter.svelte'
  import { route } from './lib/routes'

  // Pages are separate URLs served by the server (`/`, `/cars/{slug}`, …), so links are plain links.
  const current = route(window.location.pathname)
</script>

<SiteHeader page={current.page} />
{#if current.page === 'car'}
  <CarPage slug={current.slug} />
{:else if current.page === 'sessions'}
  <SessionsPage slug={current.slug} />
{:else if current.page === 'session'}
  <SessionPage slug={current.slug} id={current.id} />
{:else if import.meta.env.DEV && current.page === 'preview'}
  <!-- Loaded only in the dev server: in the build this branch is false, and the preview is dropped. -->
  {#await import('./WidgetsPreview.svelte') then m}<m.default slug={current.slug} />{/await}
{:else if current.page === 'cars'}
  {#await import('./CarsPage.svelte') then m}<m.default />{/await}
{:else if current.page === 'manage'}
  {#await import('./CarManage.svelte') then m}<m.default slug={current.slug} />{/await}
{:else if current.page === 'courses'}
  {#await import('./CoursesPage.svelte') then m}<m.default />{/await}
{:else if current.page === 'course'}
  {#await import('./CoursePage.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'course-edit'}
  {#await import('./CourseEditor.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'events'}
  {#await import('./EventsPage.svelte') then m}<m.default />{/await}
{:else if current.page === 'event'}
  {#await import('./EventPage.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'event-edit'}
  {#await import('./EventEditor.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'drivers'}
  {#await import('./DriversPage.svelte') then m}<m.default />{/await}
{:else if current.page === 'driver'}
  {#await import('./DriverPage.svelte') then m}<m.default id={current.id} />{/await}
{:else if current.page === 'users'}
  {#await import('./UsersPage.svelte') then m}<m.default />{/await}
{:else if current.page === 'compare'}
  {#await import('./ComparePage.svelte') then m}<m.default />{/await}
{:else if current.page === 'privacy'}
  {#await import('./PrivacyPage.svelte') then m}<m.default />{/await}
{:else if current.page === 'terms'}
  {#await import('./TermsPage.svelte') then m}<m.default />{/await}
{:else}
  <Landing />
{/if}
<SiteFooter />
