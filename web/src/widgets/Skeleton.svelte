<script lang="ts">
  // A page's layout before its data (M35.4): a title's line and its panels at their usual heights, so nothing jumps
  // when the data lands. [panels]: each panel's height, px.
  let { title = true, panels = [160], label = 'Loading' }: { title?: boolean; panels?: number[]; label?: string } = $props()
</script>

<div class="skeletons" role="status" aria-busy="true" aria-label={label}>
  {#if title}<div class="skeleton line"></div>{/if}
  {#each panels as h, i (i)}<div class="skeleton" style={`height: ${h}px`}></div>{/each}
</div>

<style>
  .skeletons { display: grid; gap: 14px; margin: 8px 0 16px; }
  .line { width: min(60%, 320px); height: 1.8rem; }
</style>
