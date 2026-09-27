<script lang="ts">
  let { slug, id }: { slug: string; id: string } = $props()
</script>

<!-- The session page is M7.5; this holds its place so the list's links land somewhere. -->
<main>
  <p class="back"><a href={`/cars/${slug}/sessions`}>← Past sessions</a></p>
  <h1>Session</h1>
  <p class="muted">{id}</p>
</main>

<style>
  .back { margin: 0 0 8px; }
  .back a { color: var(--muted); text-decoration: none; }
</style>
