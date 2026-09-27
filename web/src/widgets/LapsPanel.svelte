<script lang="ts">
  import { lapSummary } from '../lib/dashboard'
  import type { LapRow } from '../lib/sessionPage'
  import { lapTime } from '../lib/sessions'

  let { rows }: { rows: LapRow[] } = $props()

  const s = $derived(lapSummary(rows))
  const recent = $derived([...rows].sort((a, b) => b.lap - a.lap).slice(0, 8))
</script>

<section class="laps">
  <div class="big">
    <div><div class="name">Last lap{#if s.lastLap !== null} ({s.lastLap}){/if}</div><div class="time">{s.last !== null ? lapTime(s.last) : '—'}</div></div>
    <div><div class="name">Best{#if s.bestLap !== null} ({s.bestLap}){/if}</div><div class="time best">{s.best !== null ? lapTime(s.best) : '—'}</div></div>
    <div>
      <div class="name">Difference</div>
      <div class="time" class:slower={s.delta !== null && s.delta > 0}>{s.delta === null ? '—' : `${s.delta > 0 ? '+' : s.delta < 0 ? '−' : '±'}${Math.abs(s.delta).toFixed(3)}`}</div>
    </div>
  </div>
  <table>
    <tbody>
      {#each recent as lap (lap.lap)}
        <tr class:best={lap.best}><td>{lap.lap}</td><td class="t">{lapTime(lap.time)}</td><td class="muted">{lap.best ? 'Best' : lap.pitIn ? 'Into the pits' : lap.pitOut ? 'Out of the pits' : ''}</td></tr>
      {/each}
    </tbody>
  </table>
</section>

<style>
  .laps { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
  .big { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 8px; margin-bottom: 8px; }
  .name { color: var(--muted); font-size: 0.8rem; }
  .time { font-size: 1.6rem; font-weight: 700; font-variant-numeric: tabular-nums; }
  .time.best { color: var(--in-range); }
  .time.slower { color: var(--caution); }
  table { border-collapse: collapse; width: 100%; font-size: 0.95rem; }
  td { padding: 4px 6px; border-top: 1px solid var(--line); }
  .t { font-variant-numeric: tabular-nums; font-weight: 600; }
  tr.best .t { color: var(--in-range); }
</style>
