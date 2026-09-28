<script lang="ts">
  import { pickAction, type LapPick } from './lib/comparePick'
  import { picking, setPick } from './lib/comparePickState.svelte'
  import { compareLink } from './lib/compare'

  // "Compare" on a lap (M16.4): the first pick is held, the second opens both; "vs best" in one click.
  let { lap, best = null }: { lap: LapPick; best?: LapPick | null } = $props()

  const action = $derived(pickAction(picking.pending, lap))
  const vsBest = $derived(best && (best.ref.session !== lap.ref.session || best.ref.start !== lap.ref.start)
    ? compareLink(best.ref, lap.ref, lap.course, lap.layout) : null)

  function onPick() {
    const a = action
    if (a.kind === 'compare') {
      setPick(null)
      window.location.assign(a.href)
    } else setPick(a.kind === 'pick' ? lap : null)
  }
</script>

<span class="pick">
  <button class="link" onclick={onPick}>
    {action.kind === 'compare' ? `Compare with ${picking.pending?.label}` : action.kind === 'unpick' ? 'Picked (let go)' : 'Compare…'}
  </button>
  {#if vsBest}<a class="small" href={vsBest}>vs best</a>{/if}
</span>

<style>
  .pick { display: inline-flex; gap: 8px; align-items: baseline; white-space: nowrap; }
</style>
