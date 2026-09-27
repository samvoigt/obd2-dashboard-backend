<script lang="ts">
  import { level, profile, type Freshness } from '../lib/dashboard'
  import { label } from '../lib/live'
  import { readout } from '../lib/readout'
  import { units } from '../lib/unitsState.svelte'

  let { signal, unit, value, freshness }: { signal: string; unit: string; value: number | null; freshness: Freshness } = $props()

  const lv = $derived(level(value, profile(signal, unit)))
  const shown = $derived(readout(value, unit, units.system))
</script>

<div class={`readout ${lv} ${freshness}`}>
  <div class="name">{label(signal)}</div>
  <div class="value">{shown.text}<span class="unit">{shown.unit}</span></div>
  {#if freshness === 'stopped'}<div class="note">stopped</div>{:else if freshness === 'stale'}<div class="note">not updating</div>{/if}
</div>

<style>
  .readout { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; }
  .name { color: var(--muted); font-size: 0.8rem; }
  .value { font-size: 1.9rem; font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1.2; }
  .unit { font-size: 0.9rem; color: var(--muted); font-weight: 400; margin-left: 4px; }
  .caution .value { color: var(--caution); }
  .critical .value { color: var(--critical); }
  .stale .value, .stopped .value, .never .value { opacity: 0.4; }
  .note { color: var(--muted); font-size: 0.8rem; }
</style>
