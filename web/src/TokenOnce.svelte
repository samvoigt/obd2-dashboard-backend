<script lang="ts">
  // A generated token, shown once (decision 10, M6): copied now, or never seen again. Only ever in this banner.
  let { slug, token, done }: { slug: string; token: string; done: () => void } = $props()
  let copied = $state(false)

  async function copy() {
    await navigator.clipboard.writeText(token)
    copied = true
  }
</script>

<section class="once" aria-live="polite">
  <strong>New token for {slug}. It is shown once: copy it now.</strong>
  <code>{token}</code>
  <div class="row">
    <button class="primary" onclick={copy}>{copied ? 'Copied' : 'Copy'}</button>
    <button onclick={done}>Done</button>
  </div>
  <p class="muted small">Put it in the tablet's Cars page. After this, only its last characters are shown.</p>
</section>

<style>
  .once { background: var(--panel); border: 1px solid var(--caution); border-radius: 12px; padding: 12px; margin: 12px 0; display: grid; gap: 8px; }
  .once code { font-size: 1rem; background: var(--bg); padding: 10px; border-radius: 8px; overflow-wrap: anywhere; user-select: all; }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; font-size: 0.95rem; cursor: pointer; }
  button.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
  .small { font-size: 0.85rem; }
  p { margin: 0; }
</style>
