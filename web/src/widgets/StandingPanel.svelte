<script lang="ts">
  import { duration, secondsSince, type Standing } from '../lib/standing'
  import { lapTime } from '../lib/sessions'

  // Where the car stands (M17.5): the server's, counted on by the page between events.
  let { standing, serverNow }: { standing: Standing; serverNow: number } = $props()

  const stint = $derived(secondsSince(standing.driver?.since, serverNow))
  const sinceStop = $derived(secondsSince(standing.race?.leftPits, serverNow))
</script>

<section class="standing" aria-label="Where the car stands">
  <div>
    <div class="name">Driver</div>
    <div class="value">{standing.driver?.name ?? 'Not set'}{#if standing.driver?.code}{' '}<span class="muted">{standing.driver.code}</span>{/if}</div>
    {#if stint !== null}<div class="muted small">stint {duration(stint)}</div>{/if}
  </div>
  {#if standing.race}
    <div>
      <div class="name">Race lap</div>
      <div class="value">{standing.race.lap}</div>
      <div class="muted small">{sinceStop !== null ? `${duration(sinceStop)} since the stop` : 'In the pits'}</div>
    </div>
  {/if}
  {#if standing.best}
    <div>
      <div class="name">Best</div>
      <div class="value best">{lapTime(standing.best.time)}</div>
      <div class="muted small">{[standing.best.driver, standing.theoretical ? `theoretical ${lapTime(standing.theoretical)}` : ''].filter(Boolean).join(' · ')}</div>
    </div>
  {/if}
</section>

<style>
  .standing { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 8px; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
  .name { color: var(--muted); font-size: 0.8rem; }
  .value { font-size: 1.4rem; font-weight: 700; font-variant-numeric: tabular-nums; }
  .value.best { color: var(--in-range); }
  .small { font-size: 0.85rem; font-variant-numeric: tabular-nums; }
</style>
