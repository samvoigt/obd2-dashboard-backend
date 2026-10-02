<script lang="ts">
  import { api, AdminError, confirmed, when } from './lib/admin'
  import { inviteProblem, thingLink, type InvitedUser } from './lib/access'
  import { isMaster, signin } from './lib/signin'

  // The invited users (M23): a master admin invites them by email, sees what each made, and removes them.
  let users: InvitedUser[] | null = $state.raw(null)
  let error: string | null = $state(null)
  let email = $state('')
  let busy = $state(false)
  let removing: string | null = $state(null)
  let typed = $state('')

  const master = $derived(isMaster($signin))
  const problem = $derived(users ? inviteProblem(email, users) : null)

  async function load() {
    try {
      users = await api<InvitedUser[]>('GET', '/users')
      error = null
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  }

  // Loaded for a master admin, forgotten on signing out, with no reload either way.
  $effect(() => {
    if (master) void load()
    else { users = null; removing = null }
  })

  async function run(action: () => Promise<unknown>) {
    busy = true
    error = null
    try {
      await action()
      await load()
      return true
    } catch (e) {
      error = e instanceof AdminError || e instanceof Error ? e.message : String(e)
      return false
    } finally {
      busy = false
    }
  }

  async function invite(e: SubmitEvent) {
    e.preventDefault()
    if (problem || !email.trim()) return
    if (await run(() => api('POST', '/users', { email: email.trim().toLowerCase() }))) email = ''
  }

  async function remove(u: InvitedUser) {
    if (await run(() => api('DELETE', `/users/${encodeURIComponent(u.email)}`))) { removing = null; typed = '' }
  }
</script>

<main>
  <p class="back"><a href="/">← Home</a></p>
  <h1>Users</h1>
  {#if $signin.state === 'unknown'}
    <p class="muted">Loading…</p>
  {:else if !master}
    <p class="muted">Only a master admin can manage users.</p>
  {:else}
    <p class="muted">An invited user signs in with that Google account. They can create cars, events, courses and drivers, and choose who else edits them; they delete only what they made. A removed user loses access at once. What they made stays, with them as its maker, and a master admin can still delete it.</p>

    <form class="row panel" onsubmit={invite}>
      <label><span>Invite by email</span><input bind:value={email} type="email" placeholder="someone@example.com" autocapitalize="off" autocomplete="off" spellcheck="false" /></label>
      <button class="primary" disabled={busy || !email.trim() || !!problem}>Invite</button>
    </form>
    {#if problem}<p class="hint">{problem}</p>{/if}
    {#if error}<p class="error" role="alert">{error}</p>{/if}

    {#if users && users.length === 0}
      <p class="muted">No users yet.</p>
    {:else if users}
      <ul class="list">
        {#each users as u (u.email)}
          <li>
            <div class="head">
              <strong>{u.email}</strong>
              <span class="muted small">invited by {u.invitedBy}, {when(u.invited)}</span>
            </div>
            {#if u.created.length > 0}
              <p class="small">Made:
                {#each u.created as key, i (key)}
                  {@const link = thingLink(key)}
                  {#if link}<a href={link.href}>{link.label}</a>{:else}{key}{/if}{i < u.created.length - 1 ? ', ' : ''}
                {/each}
              </p>
            {:else}
              <p class="muted small">Hasn't made anything yet.</p>
            {/if}
            {#if removing === u.email}
              <div class="confirm">
                <label><span>Type <strong>{u.email}</strong> to remove them</span><input bind:value={typed} autocapitalize="off" autocomplete="off" spellcheck="false" /></label>
                <div class="row">
                  <button class="danger" disabled={busy || !confirmed(typed.toLowerCase(), u.email.toLowerCase())} onclick={() => remove(u)}>Remove {u.email}</button>
                  <button onclick={() => (removing = null)}>Cancel</button>
                </div>
              </div>
            {:else}
              <p><button class="danger small" onclick={() => { removing = u.email; typed = '' }}>Remove…</button></p>
            {/if}
          </li>
        {/each}
      </ul>
    {:else}
      <p class="muted">Loading…</p>
    {/if}
  {/if}
</main>

<style>
  .back { margin: 0 0 8px; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; margin: 12px 0; }
  .row { display: flex; gap: 8px; align-items: flex-end; flex-wrap: wrap; }
  label { display: grid; gap: 4px; font-size: 0.95rem; flex: 1; min-width: 0; }
  input { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 8px 10px; font-size: 1rem; min-width: 0; width: 100%; max-width: 420px; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; font-size: 0.95rem; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  button.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
  button.danger { color: var(--critical); border-color: var(--critical); }
  button.small { padding: 4px 10px; font-size: 0.85rem; }
  .list { list-style: none; padding: 0; margin: 0; display: grid; gap: 10px; }
  .list li { background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 12px; display: grid; gap: 6px; }
  .list p { margin: 0; }
  .head { display: flex; gap: 8px 12px; flex-wrap: wrap; align-items: baseline; }
  .head strong { overflow-wrap: anywhere; }
  .confirm { display: grid; gap: 8px; }
  .small { font-size: 0.85rem; }
  .hint { color: var(--caution); margin: 0; }
  .error { color: var(--critical); }
</style>
