<script lang="ts">
  import { fraction, level, profile, zoneBands, type Freshness } from '../lib/dashboard'
  import { label } from '../lib/live'
  import { readout } from '../lib/readout'
  import { units } from '../lib/unitsState.svelte'

  let { signal, unit, value, freshness }: { signal: string; unit: string; value: number | null; freshness: Freshness } = $props()

  const p = $derived(profile(signal, unit))
  const lv = $derived(level(value, p))
  const shown = $derived(readout(value, unit, units.system))
  const fill = $derived(value === null ? 0 : fraction(value, p))
</script>

<div class={`bar ${lv} ${freshness}`}>
  <div class="head"><span class="name">{label(signal)}</span><span class="value">{shown.text}<span class="unit">{shown.unit}</span></span></div>
  <div class="track">
    {#each zoneBands(p) as b (b.level + b.from)}<div class={`zone ${b.level}`} style:left={`${b.from * 100}%`} style:width={`${(b.to - b.from) * 100}%`}></div>{/each}
    <div class="fill" style:width={`${fill * 100}%`}></div>
  </div>
  {#if freshness === 'stopped'}<div class="note">stopped</div>{:else if freshness === 'stale'}<div class="note">not updating</div>{/if}
</div>

<style>
  .bar { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; }
  .head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
  .name { color: var(--muted); font-size: 0.8rem; }
  .value { font-weight: 700; font-variant-numeric: tabular-nums; }
  .unit { color: var(--muted); font-weight: 400; margin-left: 3px; font-size: 0.85rem; }
  .track { position: relative; height: 12px; margin-top: 8px; background: var(--line); border-radius: 6px; overflow: hidden; }
  .zone { position: absolute; top: 0; bottom: 0; opacity: 0.35; }
  .zone.caution { background: var(--caution); }
  .zone.critical { background: var(--critical); }
  .fill { position: absolute; top: 0; bottom: 0; left: 0; background: var(--in-range); border-radius: 6px; }
  .caution .fill { background: var(--caution); }
  .critical .fill { background: var(--critical); }
  .caution .value { color: var(--caution); }
  .critical .value { color: var(--critical); }
  .stale .track, .stopped .track, .never .track, .stale .value, .stopped .value { opacity: 0.4; }
  .note { color: var(--muted); font-size: 0.8rem; margin-top: 4px; }
</style>
