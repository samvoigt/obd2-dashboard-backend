<script lang="ts">
  import { level, needleAngle, profile, zoneBands, SWEEP, type Freshness } from '../lib/dashboard'
  import { label } from '../lib/live'
  import { readout } from '../lib/readout'
  import { toShown } from '../lib/units'
  import { units } from '../lib/unitsState.svelte'

  let { signal, unit, value, freshness }: { signal: string; unit: string; value: number | null; freshness: Freshness } = $props()

  const p = $derived(profile(signal, unit))
  const lv = $derived(level(value, p))
  const shown = $derived(readout(value, unit, units.system))
  const angle = $derived(value === null ? -SWEEP / 2 : needleAngle(value, p))
  const bands = $derived(zoneBands(p))
  const ticks = $derived([0, 0.25, 0.5, 0.75, 1].map((f) => ({ f, text: Math.round(toShown(p.min + f * (p.max - p.min), unit, units.system)).toString() })))

  // A dial of radius 80 around (100, 100); 0° straight up, clockwise.
  const R = 80
  function point(deg: number, r: number): [number, number] {
    const a = ((deg - 90) * Math.PI) / 180
    return [100 + r * Math.cos(a), 100 + r * Math.sin(a)]
  }
  function arc(from: number, to: number, r = R): string {
    const a = -SWEEP / 2 + from * SWEEP
    const b = -SWEEP / 2 + to * SWEEP
    const [x0, y0] = point(a, r)
    const [x1, y1] = point(b, r)
    return `M ${x0} ${y0} A ${r} ${r} 0 ${b - a > 180 ? 1 : 0} 1 ${x1} ${y1}`
  }
</script>

<figure class={`gauge ${lv} ${freshness}`}>
  <svg viewBox="0 0 200 170" role="img" aria-label={`${label(signal)}: ${shown.text} ${shown.unit}`}>
    <path class="track" d={arc(0, 1)} />
    {#each bands as b (b.level + b.from)}<path class={`band ${b.level}`} d={arc(b.from, b.to)} />{/each}
    {#each ticks as t (t.f)}
      {@const [x, y] = point(-SWEEP / 2 + t.f * SWEEP, R - 20)}
      <text class="tick" {x} {y} text-anchor="middle" dominant-baseline="middle">{t.text}</text>
    {/each}
    {#if value !== null}
      <g transform={`rotate(${angle} 100 100)`}><line class="needle" x1="100" y1="108" x2="100" y2={100 - R + 6} /></g>
      <circle class="hub" cx="100" cy="100" r="5" />
    {/if}
    <text class="value" x="100" y="146" text-anchor="middle">{shown.text}</text>
    <text class="unit" x="100" y="164" text-anchor="middle">{shown.unit}</text>
  </svg>
  <figcaption>{label(signal)}{freshness === 'stopped' ? ' · stopped' : freshness === 'stale' ? ' · not updating' : ''}</figcaption>
</figure>

<style>
  .gauge { margin: 0; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 8px 8px 10px; }
  svg { display: block; width: 100%; height: auto; }
  .track { fill: none; stroke: var(--line); stroke-width: 10; stroke-linecap: round; }
  .band { fill: none; stroke-width: 10; }
  .band.caution { stroke: var(--caution); }
  .band.critical { stroke: var(--critical); }
  .tick { fill: var(--muted); font-size: 11px; }
  .needle { stroke: var(--text); stroke-width: 3; stroke-linecap: round; }
  .hub { fill: var(--text); }
  .value { fill: var(--text); font-size: 28px; font-weight: 700; font-variant-numeric: tabular-nums; }
  .unit { fill: var(--muted); font-size: 12px; }
  .caution .value { fill: var(--caution); }
  .critical .value { fill: var(--critical); }
  figcaption { text-align: center; color: var(--muted); font-size: 0.85rem; }
  .stale svg, .stopped svg, .never svg { opacity: 0.4; }
</style>
