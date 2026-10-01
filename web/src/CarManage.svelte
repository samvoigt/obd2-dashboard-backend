<script lang="ts">
  import { signin } from './lib/signin'
  import {
    AdminError, api, clockNote, confirmed, day, passcodeProblem, stateText, tokenProblem, twiceProblem,
    type AdminCar, type CarWithToken,
  } from './lib/admin'
  import TokenOnce from './TokenOnce.svelte'

  // A car's management (M21.5): what the admin page did for one car, signed in only, from `/api/admin/cars`.
  let { slug }: { slug: string } = $props()

  type Action = 'rename' | 'token' | 'passcode' | 'remove'

  let car: AdminCar | null = $state(null)
  let missing = $state(false)
  let error: string | null = $state(null)
  let busy = $state(false)
  let open: Action | null = $state(null)
  let name = $state('')
  let choose = $state(false)
  let token = $state('')
  let token2 = $state('')
  let passcode = $state('')
  let passcode2 = $state('')
  let typed = $state('')
  // A generated token, shown once: it lives only here, so it is gone on reload.
  let shown: string | null = $state(null)

  const signedIn = $derived($signin.state === 'in')

  // Loaded when signed in, and forgotten when signed out, with no reload either way.
  $effect(() => {
    if (signedIn) void load()
    else { car = null; shown = null; open = null; missing = false }
  })

  async function load() {
    try {
      const all = await api<AdminCar[]>('GET', '/cars')
      car = all.find((c) => c.slug === slug) ?? null
      missing = car === null
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    }
  }

  /** Runs a change, showing its error; a lapsed sign-in signs the page out (`api` tells the header). */
  async function run(action: () => Promise<void>) {
    busy = true
    error = null
    try {
      await action()
    } catch (e) {
      error = e instanceof AdminError || e instanceof Error ? e.message : String(e)
    }
    busy = false
  }

  function begin(action: Action) {
    open = open === action ? null : action
    name = car?.name ?? ''
    choose = false
    token = token2 = passcode = passcode2 = typed = ''
    error = null
  }

  const rename = () => run(async () => {
    await api('PATCH', `/cars/${slug}`, { name: name.trim() })
    open = null
    await load()
  })

  const replaceToken = () => run(async () => {
    const result = await api<CarWithToken>('POST', `/cars/${slug}/token`, { token: choose ? token : undefined })
    if (result.token) shown = result.token
    open = null
    await load()
  })

  const setPasscode = () => run(async () => {
    await api('PUT', `/cars/${slug}/passcode`, { passcode })
    open = null
    await load()
  })

  const remove = () => run(async () => {
    await api('DELETE', `/cars/${slug}`)
    window.location.assign('/cars')
  })
</script>

<main>
  <p class="back"><a href="/cars">← Cars</a></p>
  <h1>Manage {car?.name ?? slug}</h1>

  {#if !signedIn}
    <p class="muted">Sign in (top right) to manage this car.</p>
  {:else if missing}
    <p class="muted">No car “{slug}”.</p>
  {:else if car}
    <p class="links"><a href={`/cars/${slug}`}>Live</a> <a href={`/cars/${slug}/sessions`}>Sessions ({car.sessions})</a></p>
    {#if error}<p class="error" role="alert">{error}</p>{/if}
    {#if shown}<TokenOnce {slug} token={shown} done={() => (shown = null)} />{/if}

    <section class="panel">
      <dl>
        <dt>State</dt><dd><span class={`dot ${car.state}`}></span>{stateText(car.state)}</dd>
        <dt>Token</dt><dd>…{car.tokenHint} <span class="muted">since {day(car.tokenIssued)}</span></dd>
        <dt>Crew passcode</dt><dd>{car.passcodeSet ? 'Set' : 'Not set'}</dd>
        {#if clockNote(car.clockOffsetMs)}<dt>Tablet</dt><dd class="clock">{clockNote(car.clockOffsetMs)} <span class="muted">(its times on the site are off by as much)</span></dd>{/if}
      </dl>
      {#if car.liveSession}
        <p class="live-now"><span class="dot live"></span>Streaming a session now. <a href={`/cars/${slug}`}>Watch live →</a></p>
      {/if}
      <div class="row">
        <button onclick={() => begin('rename')}>Rename</button>
        <button onclick={() => begin('token')}>Replace token</button>
        <button onclick={() => begin('passcode')}>{car.passcodeSet ? 'Change passcode' : 'Set passcode'}</button>
        <button class="danger" onclick={() => begin('remove')}>Remove</button>
      </div>

      {#if open}
        <div class="form inner">
          {#if open === 'rename'}
            <label><span>New name</span> <input bind:value={name} /></label>
            <div class="row">
              <button class="primary" disabled={busy || name.trim() === '' || name.trim() === car.name} onclick={rename}>Rename</button>
            </div>
          {:else if open === 'token'}
            <p class="warn">The tablet stops sending until it's given the new token.</p>
            <label class="check"><input type="checkbox" bind:checked={choose} /> Choose the token myself</label>
            {#if choose}
              <label><span>Token</span> <input class="secret" type="text" bind:value={token} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
              <label><span>Again</span> <input class="secret" type="text" bind:value={token2} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
              {#if token}<p class="hint">{twiceProblem(token, token2, tokenProblem) ?? ''}</p>{/if}
            {/if}
            <label><span>Type <strong>Replace</strong> to confirm</span> <input bind:value={typed} autocomplete="off" /></label>
            <div class="row">
              <button
                class="primary"
                disabled={busy || !confirmed(typed, 'Replace') || (choose && !!twiceProblem(token, token2, tokenProblem))}
                onclick={replaceToken}
              >Replace token</button>
            </div>
          {:else if open === 'passcode'}
            {#if car.passcodeSet}<p class="warn">Every crew member is signed out and needs the new one.</p>{/if}
            <label><span>Passcode</span> <input class="secret" type="text" bind:value={passcode} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
            <label><span>Again</span> <input class="secret" type="text" bind:value={passcode2} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
            {#if passcode}<p class="hint">{twiceProblem(passcode, passcode2, passcodeProblem) ?? ''}</p>{/if}
            <div class="row">
              <button class="primary" disabled={busy || !!twiceProblem(passcode, passcode2, passcodeProblem)} onclick={setPasscode}>Save passcode</button>
            </div>
          {:else if open === 'remove'}
            {#if car.sessions > 0}
              <p class="warn">{car.name} still has {car.sessions} session(s). Delete them first, on its <a href={`/cars/${slug}/sessions`}>sessions page</a>.</p>
            {:else}
              <p class="warn">Its token stops working at once, and its crew messages are deleted.</p>
              <label><span>Type <strong>{slug}</strong> to confirm</span> <input bind:value={typed} autocapitalize="off" autocomplete="off" /></label>
              <div class="row">
                <button class="danger" disabled={busy || !confirmed(typed, slug)} onclick={remove}>Remove {car.name}</button>
              </div>
            {/if}
          {/if}
        </div>
      {/if}
    </section>
  {:else if error}
    <p class="error" role="alert">{error}</p>
  {:else}
    <p class="muted">Loading…</p>
  {/if}
</main>

<style>
  .back { margin: 0 0 8px; }
  .back a { color: var(--muted); text-decoration: none; }
  .links { display: flex; gap: 16px; }
  .links a { color: var(--accent); }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; margin: 12px 0; }
  .form { display: grid; gap: 10px; }
  .inner { border-top: 1px solid var(--line); margin-top: 10px; padding-top: 10px; }
  label { display: grid; gap: 4px; font-size: 0.95rem; }
  label.check { display: flex; align-items: center; gap: 8px; }
  input:not([type='checkbox']) { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 8px 10px; font-size: 1rem; min-width: 0; width: 100%; max-width: 420px; }
  /* Tokens and passcodes are typed in the open (Sam), in a face where 0 and O, l and 1 differ. */
  input.secret { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; font-size: 0.95rem; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  button.primary { border-color: var(--accent); color: var(--accent); font-weight: 600; }
  button.danger { border-color: var(--critical); color: var(--critical); }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; margin: 0 0 10px; font-size: 0.95rem; }
  dt { color: var(--muted); }
  dd { margin: 0; }
  .hint, .warn, .clock { color: var(--caution); }
  .hint, .warn { margin: 0; }
  .hint { font-size: 0.9rem; }
  .error { color: var(--critical); }
  .live-now { margin: 0 0 10px; }
</style>
