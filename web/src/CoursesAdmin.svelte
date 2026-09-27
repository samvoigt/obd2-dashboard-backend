<script lang="ts">
  import { onMount } from 'svelte'
  import { api, AdminError, day, type CourseSummary } from './lib/admin'

  // The courses (M12.4): signed in on the admin page, as cars are; the same cookie.
  let courses: CourseSummary[] | null = $state(null)
  let signedOut = $state(false)
  let error: string | null = $state(null)

  onMount(async () => {
    try {
      await api('GET', '/me')
      courses = await api<CourseSummary[]>('GET', '/courses')
    } catch (e) {
      if (e instanceof AdminError && e.status === 401) signedOut = true
      else error = e instanceof Error ? e.message : String(e)
    }
  })
</script>

<main>
  <p class="back"><a href="/admin">← Admin</a></p>
  <h1>Courses</h1>
  {#if signedOut}
    <p>Sign in on the <a href="/admin">admin page</a> first, then come back.</p>
  {:else if error}
    <p class="error">{error}</p>
  {:else if courses}
    <p class="muted">Drawn here, and sent to every tablet, which times laps on them. Every save is a new version.</p>
    <p><a class="button primary" href="/admin/courses/new">New course</a></p>
    {#if courses.length === 0}
      <p class="muted">No courses yet.</p>
    {:else}
      <ul class="list">
        {#each courses as c (c.id)}
          <li>
            <a href={`/admin/courses/${c.id}`}><strong>{c.name}</strong></a>
            <span class="muted">{c.id} · version {c.version}, {day(c.saved)}</span>
            <span class="layouts">
              {#each c.layouts as l (l.id)}
                <span>{l.name}{l.default ? ' (default)' : ''}: {l.sectors} sector{l.sectors === 1 ? '' : 's'}</span>
              {/each}
            </span>
          </li>
        {/each}
      </ul>
    {/if}
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  main { max-width: 960px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .list { list-style: none; padding: 0; display: grid; gap: 8px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; display: grid; gap: 4px; }
  .layouts { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 0.9rem; }
  .button { display: inline-block; border: 1px solid var(--accent); color: var(--accent); border-radius: 8px; padding: 6px 12px; text-decoration: none; font-weight: 600; }
  .error { color: var(--critical); }
</style>
