<script lang="ts">
  import { lapSummary } from '../lib/dashboard'
  import type { LapRow } from '../lib/sessionPage'
  import { lapTime } from '../lib/sessions'
  import { sectorBests } from '../lib/standing'
  import type { LapSpeed } from '../lib/lapSpeeds'
  import { shownUnit, toShown } from '../lib/units'
  import { units } from '../lib/unitsState.svelte'

  // The laps as the tablet sends them, with their sectors (M17.5); each sector's best marked, the event's if known.
  // Each lap's top and average speed (km/h) by when it ended, where known.
  let { rows, bestSectors, speeds }: { rows: LapRow[]; bestSectors?: (number | null)[]; speeds?: Map<number, LapSpeed | null> } = $props()

  const s = $derived(lapSummary(rows))
  // Every lap, the newest first by when they ended, and keyed so: lap numbers repeat within a session (app 1.0.1,
  // STREAM off and on). About eight show; the rest scroll (Sam, 2026-10-16).
  const recent = $derived([...rows].sort((a, b) => b.end - a.end))
  const bests = $derived(sectorBests(rows, bestSectors))
  const hasSpeeds = $derived(speeds !== undefined && [...speeds.values()].some((x) => x !== null))
  const speedUnit = $derived(shownUnit('km/h', units.system))
  const speedText = (v: number | null | undefined) => (v == null ? '' : Math.round(toShown(v, 'km/h', units.system)).toString())
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
  <div class="scroll">
  <table>
    <thead><tr><th>Lap</th><th>Time</th>{#each bests as _, i (i)}<th>S{i + 1}</th>{/each}{#if hasSpeeds}<th title="Top speed">Top</th><th title="Average speed">Avg</th>{/if}<th></th></tr></thead>
    <tbody>
      {#each recent as lap (lap.end)}
        {@const sp = speeds?.get(lap.end)}
        <tr class:best={lap.best}><td>{lap.lap}</td><td class="t">{lapTime(lap.time)}</td>
          {#each bests as b, i (i)}<td class="s" class:sbest={b !== null && lap.sectors?.[i] === b}>{lap.sectors?.[i]?.toFixed(3) ?? ''}</td>{/each}
          {#if hasSpeeds}<td class="v">{speedText(sp?.max)}</td><td class="v">{speedText(sp?.avg)}</td>{/if}
          <td class="muted">{lap.best ? 'Best' : lap.pitIn ? 'Into the pits' : lap.pitOut ? 'Out of the pits' : ''}</td></tr>
      {/each}
    </tbody>
  </table>
  </div>
  {#if hasSpeeds}<p class="unit">Top and average speed in {speedUnit}.</p>{/if}
</section>

<style>
  .laps { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
  .big { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 8px; margin-bottom: 8px; }
  .name { color: var(--muted); font-size: 0.8rem; }
  .time { font-size: 1.6rem; font-weight: 700; font-variant-numeric: tabular-nums; }
  .time.best { color: var(--in-range); }
  .time.slower { color: var(--caution); }
  .scroll { max-height: 16.5rem; overflow-y: auto; overscroll-behavior: contain; }
  table { border-collapse: collapse; width: 100%; font-size: 0.95rem; }
  td { padding: 4px 6px; border-top: 1px solid var(--line); }
  .t { font-variant-numeric: tabular-nums; font-weight: 600; }
  tr.best .t { color: var(--in-range); }
  .s { font-variant-numeric: tabular-nums; color: var(--muted); font-size: 0.85rem; }
  th { text-align: left; color: var(--muted); font-weight: 600; font-size: 0.75rem; padding: 2px 6px; position: sticky; top: 0; background: var(--panel); }
  .v { font-variant-numeric: tabular-nums; font-size: 0.85rem; }
  .unit { color: var(--muted); font-size: 0.75rem; margin: 6px 0 0; }
  .s.sbest { color: var(--in-range); font-weight: 600; }
  /* A phone (M30.5): what the crew reads in a hurry is never under 0.95rem. */
  @media (max-width: 600px) {
    .name, .s, .v, th, .unit { font-size: 0.95rem; }
  }
</style>
