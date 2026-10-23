<script lang="ts">
  import { setTheme, themeState } from '../lib/themeState.svelte'
  // TODO(M30.2): the header's account menu adds this switch too, so every page can change it.
</script>

<span class="switch" role="group" aria-label="Theme">
  <button class:on={themeState.theme === 'dark'} onclick={() => setTheme('dark')}>Dark</button>
  <button class:on={themeState.theme === 'bright'} onclick={() => setTheme('bright')}>Bright</button>
</span>

<style>
  .switch { display: inline-flex; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; }
  button { background: var(--bg); color: var(--muted); border: none; padding: 5px 10px; font-size: 0.85rem; cursor: pointer; }
  button.on { background: var(--panel); color: var(--text); font-weight: 600; }
</style>
