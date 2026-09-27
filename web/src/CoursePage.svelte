<script lang="ts">
  import { onMount, untrack } from 'svelte'
  import CourseMap from './CourseMap.svelte'
  import { day, type CourseVersion, type CourseView } from './lib/admin'
  import { fromGeoJSON, metres, sectorsOf, type EditCourse } from './lib/courseEdit'

  // One course (M12.5), public: its map, its layouts and sectors, and its versions.
  let { id }: { id: string } = $props()
  let view: CourseView | null = $state(null)
  let course: EditCourse | null = $state.raw(null)
  let versions: CourseVersion[] = $state([])
  let layout: string | null = $state(null)
  let latest = $state(0)
  let missing = $state(false)

  async function load(version?: number) {
    const cid = untrack(() => id)
    const r = await fetch(`/api/courses/${cid}${version ? `?version=${version}` : ''}`).catch(() => null)
    if (!r?.ok) { missing = true; return }
    view = (await r.json()) as CourseView
    course = fromGeoJSON(view.geojson)
    if (!layout || !course.layouts.some((l) => l.id === layout)) layout = course.layouts.find((l) => l.default)?.id ?? course.layouts[0]?.id ?? null
  }

  onMount(async () => {
    await load()
    latest = view?.version ?? 0
    const r = await fetch(`/api/courses/${untrack(() => id)}/versions`).catch(() => null)
    if (r?.ok) versions = (await r.json()) as CourseVersion[]
  })

  const lengthOf = (path: [number, number][]) => Math.round(path.slice(1).reduce((s, p, i) => s + metres(path[i]!, p), 0))
</script>

<main>
  <p class="back"><a href="/courses">← Courses</a></p>
  {#if missing}
    <h1>No such course</h1>
  {:else if view && course}
    <h1>{view.name}</h1>
    <p class="muted">
      Version {view.version}{view.version !== latest ? ` (the latest is ${latest})` : ''}, {day(view.saved)}
      {#if course.startFinish.some((s) => s.extra.guess === true)} · <span class="guess">start/finish not yet checked at the track</span>{/if}
    </p>
    <div class="layouts">
      {#each course.layouts as l (l.id)}
        <button class:chosen={l.id === layout} onclick={() => (layout = l.id)}>
          {l.name}{l.default ? ' (default)' : ''} · {lengthOf(l.path)} m · {sectorsOf(course, l.id).length} sectors
        </button>
      {/each}
    </div>
    <CourseMap {course} {layout} />
    {#if versions.length > 1}
      <h2>Versions</h2>
      <ul class="versions">
        {#each versions as v (v.version)}
          <li><button class="link" class:chosen={v.version === view.version} onclick={() => load(v.version)}>Version {v.version}</button> <span class="muted">{v.name}, {day(v.saved)}</span></li>
        {/each}
      </ul>
    {/if}
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  main { max-width: 1100px; margin: 0 auto; padding: 16px; }
  .back { margin: 0 0 4px; }
  .layouts { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0 12px; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 6px 10px; cursor: pointer; }
  button.chosen { border-color: var(--accent); color: var(--accent); font-weight: 700; }
  button.link { background: none; border: none; padding: 0; text-decoration: underline; }
  .versions { list-style: none; padding: 0; display: grid; gap: 4px; }
  .guess { color: var(--caution); }
  h2 { font-size: 1rem; margin: 16px 0 6px; }
</style>
