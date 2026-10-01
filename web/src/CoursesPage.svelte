<script lang="ts">
  import { onMount } from 'svelte'
  import { signin } from './lib/signin'
  import type { CourseSummary } from './lib/admin'

  // Every course (M12.5), public: where laps are timed.
  let courses: CourseSummary[] | null = $state(null)
  let error: string | null = $state(null)

  onMount(async () => {
    const r = await fetch('/api/courses').catch(() => null)
    if (r?.ok) courses = (await r.json()) as CourseSummary[]
    else error = 'The courses could not be loaded.'
  })
</script>

<main>
  <p class="back"><a href="/">← Home</a></p>
  <h1>Courses</h1>
  <!-- Signed in (M21.3): courses are made here, and edited from their own page. -->
  {#if $signin.state === 'in'}<p><a class="button" href="/courses/new">New course</a></p>{/if}
  {#if error}
    <p class="error">{error}</p>
  {:else if courses && courses.length === 0}
    <p class="muted">No courses yet.</p>
  {:else if courses}
    <ul class="list">
      {#each courses as c (c.id)}
        <li>
          <a href={`/courses/${c.id}`}><strong>{c.name}</strong></a>
          <span class="muted small">
            {c.layouts.map((l) => `${l.name}${l.sectors > 0 ? `, ${l.sectors} sector${l.sectors === 1 ? '' : 's'}` : ''}`).join(' · ')}
          </span>
        </li>
      {/each}
    </ul>
  {/if}
</main>

<style>
  main { max-width: 960px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .list { list-style: none; padding: 0; display: grid; gap: 8px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; display: grid; gap: 4px; }
  .small { font-size: 0.9rem; }
  .error { color: var(--critical); }
  .button { display: inline-block; border: 1px solid var(--accent); color: var(--accent); border-radius: 8px; padding: 6px 12px; text-decoration: none; font-weight: 600; }
</style>
