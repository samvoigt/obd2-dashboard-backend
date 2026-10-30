<script lang="ts">
  import type { Snippet } from 'svelte'
  import type { Crumb } from './lib/routes'

  // Where the page is (M36.5): "Live › Tha Bearmobile › Sessions › Sun 18 Oct, 12:00", the last unlinked.
  // Anything after it (a link onward, "Past sessions →") sits at the right.
  let { items, children }: { items: Crumb[]; children?: Snippet } = $props()
</script>

<div class="crumbs">
  <nav aria-label="Breadcrumb">
    <ol>
      {#each items as c, i (i)}
        <li>{#if c.href && i < items.length - 1}<a href={c.href}>{c.label}</a>{:else}<span aria-current={i === items.length - 1 ? 'page' : undefined}>{c.label}</span>{/if}</li>
      {/each}
    </ol>
  </nav>
  {#if children}<span class="aside">{@render children()}</span>{/if}
</div>

<style>
  .crumbs { display: flex; justify-content: space-between; align-items: baseline; gap: 4px 12px; flex-wrap: wrap; margin: 0 0 8px; }
  ol { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 0; color: var(--muted); }
  li { min-width: 0; overflow-wrap: anywhere; }
  li + li::before { content: '›'; margin: 0 0.45em; }
  a { color: var(--muted); text-decoration: none; }
  a:hover { color: var(--text); text-decoration: underline; text-underline-offset: 3px; }
  [aria-current] { color: var(--text); }
  .aside :global(a) { color: var(--muted); text-decoration: none; }
  .aside :global(a:hover) { color: var(--text); }
</style>
