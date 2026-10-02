<script lang="ts">
  import { untrack } from 'svelte'
  import { api, AdminError } from './lib/admin'
  import { addable, addEditor, creatorText, editorProblem, editorsChanged, removeEditor, type Kind, type Sharing } from './lib/access'

  // Who else edits this (M23): its creator, or a master admin, chooses from the invited users. Shown only where
  // the admin list says you may share it; the server decides again.
  let { kind, id }: { kind: Kind; id: string } = $props()

  let sharing = $state<Sharing | null>(null)
  let editors: string[] = $state([])
  let pick = $state('')
  let busy = $state(false)
  let error: string | null = $state(null)
  let saved = $state(false)

  async function load() {
    error = null
    try {
      sharing = await api<Sharing>('GET', `/access/${kind}/${id}`)
      editors = [...sharing.editors].sort()
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  }

  $effect(() => {
    void kind
    void id
    untrack(() => void load())
  })

  const choices = $derived(sharing ? addable(sharing, editors) : [])
  const problem = $derived(sharing && pick ? editorProblem(sharing, editors, pick) : null)
  const changed = $derived(sharing ? editorsChanged(sharing.editors, editors) : false)

  function add() {
    if (!sharing || problem || !pick) return
    editors = addEditor(editors, pick)
    pick = ''
    saved = false
  }

  async function save() {
    busy = true
    error = null
    try {
      await api('PUT', `/access/${kind}/${id}`, { editors })
      await load()
      saved = true
    } catch (e) {
      error = e instanceof AdminError || e instanceof Error ? e.message : String(e)
    } finally {
      busy = false
    }
  }
</script>

<section class="editors">
  <h2>Editors</h2>
  {#if sharing}
    <p class="muted small">{creatorText(sharing.creator)}. Editors change it, tokens included, but only its maker or a master admin deletes it or chooses who edits.</p>
    {#if editors.length === 0}
      <p class="muted small">No editors yet.</p>
    {:else}
      <ul>
        {#each editors as e (e)}
          <li><span>{e}</span> <button class="small danger" onclick={() => { editors = removeEditor(editors, e); saved = false }} disabled={busy}>Remove</button></li>
        {/each}
      </ul>
    {/if}
    {#if choices.length > 0}
      <div class="row">
        <select bind:value={pick} disabled={busy}>
          <option value="">Add a user…</option>
          {#each choices as u (u)}<option value={u}>{u}</option>{/each}
        </select>
        <button onclick={add} disabled={busy || !pick || !!problem}>Add</button>
      </div>
      {#if problem}<p class="hint small">{problem}</p>{/if}
    {:else}
      <p class="muted small">No other users to add. A master admin adds them on Users.</p>
    {/if}
    <div class="row">
      <button class="primary" onclick={save} disabled={busy || !changed}>Save editors</button>
      {#if saved && !changed}<span class="muted small">Saved.</span>{/if}
    </div>
  {/if}
  {#if error}<p class="error small" role="alert">{error}</p>{/if}
</section>

<style>
  .editors { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; margin: 12px 0; display: grid; gap: 8px; }
  h2 { font-size: 0.95rem; margin: 0; }
  p { margin: 0; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; }
  li { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  li span { overflow-wrap: anywhere; }
  .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  select { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; min-width: 0; max-width: 100%; }
  button { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 4px 10px; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  button.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
  button.danger { color: var(--critical); border-color: var(--critical); }
  .small { font-size: 0.85rem; }
  .hint { color: var(--caution); }
  .error { color: var(--critical); }
</style>
