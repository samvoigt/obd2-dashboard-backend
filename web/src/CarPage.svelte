<script lang="ts">
  // The car's live page is M4.7; for now, a way back.
  let { slug }: { slug: string } = $props()
</script>

<main>
  <p><a href="/">← Cars</a></p>
  <h1>{slug}</h1>
</main>
