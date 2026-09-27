<script lang="ts">
  import type { Peaks } from '../lib/dashboard'

  let { trail, peaks, stale }: { trail: { lat: number; lon: number }[]; peaks: Peaks; stale: boolean } = $props()

  /** 1.5 g to the edge; lateral across (right is right), longitudinal up (speeding up is up). */
  const FULL = 1.5
  const R = 90
  const xy = (p: { lat: number; lon: number }) => [100 + (p.lat / FULL) * R, 100 - (p.lon / FULL) * R] as const
  const clamp = (p: { lat: number; lon: number }) => {
    const r = Math.hypot(p.lat, p.lon)
    return r > FULL ? { lat: (p.lat / r) * FULL, lon: (p.lon / r) * FULL } : p
  }
  const now = $derived(trail.length > 0 ? clamp(trail[trail.length - 1]!) : null)
  const path = $derived(trail.map((p) => xy(clamp(p)).join(',')).join(' '))
  const g = (v: number) => v.toFixed(2)
</script>

<figure class="gmeter" class:stale>
  <svg viewBox="0 0 200 200" role="img" aria-label={now ? `${g(now.lat)} g lateral, ${g(now.lon)} g longitudinal` : 'No readings'}>
    {#each [0.5, 1, 1.5] as ring (ring)}<circle class="ring" cx="100" cy="100" r={(ring / FULL) * R} />{/each}
    <line class="axis" x1="10" y1="100" x2="190" y2="100" /><line class="axis" x1="100" y1="10" x2="100" y2="190" />
    <text class="label" x="100" y="100" dx="3" dy={-((1 / FULL) * R) - 2}>1 g</text>
    {#if path}<polyline class="trail" points={path} />{/if}
    {#if now}{@const [x, y] = xy(now)}<circle class="dot" cx={x} cy={y} r="7" />{/if}
  </svg>
  <figcaption>
    {#if now}<span><strong>{g(Math.hypot(now.lat, now.lon))} g</strong></span>{:else}<span class="muted">No readings</span>{/if}
    <span class="muted">Peaks: left {g(peaks.left)} · right {g(peaks.right)} · accel {g(peaks.accel)} · brake {g(peaks.brake)}</span>
  </figcaption>
</figure>

<style>
  .gmeter { margin: 0; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 8px; }
  svg { display: block; width: 100%; max-width: 320px; height: auto; margin: 0 auto; }
  .ring { fill: none; stroke: var(--line); stroke-width: 1.5; }
  .axis { stroke: var(--line); stroke-width: 1; }
  .label { fill: var(--muted); font-size: 10px; }
  .trail { fill: none; stroke: var(--accent); stroke-width: 2; stroke-opacity: 0.6; stroke-linejoin: round; }
  .dot { fill: var(--in-range); stroke: var(--bg); stroke-width: 2; }
  .stale svg { opacity: 0.4; }
  figcaption { display: grid; gap: 2px; text-align: center; font-size: 0.85rem; font-variant-numeric: tabular-nums; }
</style>
