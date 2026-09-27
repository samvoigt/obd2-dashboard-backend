<script lang="ts">
  import { setSystem, units } from '../lib/unitsState.svelte'
</script>

<span class="switch" role="group" aria-label="Units">
  <button class:on={units.system === 'metric'} onclick={() => setSystem('metric')}>Metric</button>
  <button class:on={units.system === 'us'} onclick={() => setSystem('us')}>US</button>
</span>

<style>
  .switch { display: inline-flex; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
  button { background: var(--bg); color: var(--muted); border: none; padding: 5px 10px; font-size: 0.85rem; cursor: pointer; }
  button.on { background: var(--panel); color: var(--text); font-weight: 600; }
</style>
