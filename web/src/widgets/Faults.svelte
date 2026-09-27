<script lang="ts">
  let { codes }: { codes: string[] } = $props()
</script>

<div class="faults" class:any={codes.length > 0}>
  <span class="name">Trouble codes</span>
  {#if codes.length === 0}<span class="none">None</span>{:else}{#each codes as c (c)}<span class="code">{c}</span>{/each}{/if}
</div>

<style>
  .faults { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; }
  .any { border-color: var(--critical); }
  .name { color: var(--muted); font-size: 0.85rem; margin-right: auto; }
  .none { color: var(--in-range); font-weight: 700; }
  .code { color: var(--critical); font-weight: 700; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
