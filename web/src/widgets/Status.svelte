<script lang="ts">
  import type { Freshness } from '../lib/dashboard'
  import { label, type Rec } from '../lib/live'

  let { signal, rec, freshness }: { signal: string; rec: Rec | undefined; freshness: Freshness } = $props()

  // A flag is a light: the MIL on is critical, off is fine. A state is its words.
  const flag = $derived(typeof rec?.flag === 'boolean' ? rec.flag : null)
  const text = $derived(
    flag !== null ? (flag ? 'On' : 'Off') : typeof rec?.text === 'string' ? rec.text : rec?.code !== undefined ? `Code ${String(rec.code)}` : '—',
  )
  const kind = $derived(flag === true ? 'critical' : flag === false ? 'ok' : 'state')
</script>

<div class={`status ${kind} ${freshness}`}>
  {#if flag !== null}<span class="light"></span>{/if}
  <span class="name">{label(signal)}</span>
  <span class="text">{text}</span>
  {#if freshness === 'stopped'}<span class="note">stopped</span>{/if}
</div>

<style>
  .status { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; }
  .light { width: 14px; height: 14px; border-radius: 50%; background: var(--no-data); flex: none; }
  .ok .light { background: var(--in-range); }
  .critical .light { background: var(--critical); box-shadow: 0 0 10px var(--critical); }
  .name { color: var(--muted); font-size: 0.85rem; }
  .text { font-weight: 700; margin-left: auto; overflow-wrap: anywhere; }
  .critical .text { color: var(--critical); }
  .stale, .stopped, .never { opacity: 0.5; }
  .note { color: var(--muted); font-size: 0.8rem; }
</style>
