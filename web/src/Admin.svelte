<script lang="ts">
  import { onMount } from 'svelte'
  import {
    AdminError, api, clockNote, confirmed, day, deleteBlocked, passcodeProblem, sessionConfirmation, sessionStateText, slugProblem,
    stateText, tokenProblem, twiceProblem, when,
    type AdminCar, type AdminConfig, type AdminSession, type CarWithToken,
  } from './lib/admin'

  type Action = 'sessions' | 'rename' | 'token' | 'passcode' | 'remove'

  let config: AdminConfig | null = $state(null)
  let email: string | null = $state(null)
  let cars: AdminCar[] = $state([])
  let loading = $state(true)
  let error: string | null = $state(null)
  let busy = $state(false)

  // A generated token, shown once: it lives only here, so it is gone on reload.
  let shown: { slug: string; token: string } | null = $state(null)
  let copied = $state(false)

  // Adding a car.
  let adding = $state(false)
  let newSlug = $state('')
  let newName = $state('')
  let newChoose = $state(false)
  let newToken = $state('')
  let newToken2 = $state('')

  // One action open at a time, on one car.
  let open: { slug: string; action: Action } | null = $state(null)
  let name = $state('')
  let choose = $state(false)
  let token = $state('')
  let token2 = $state('')
  let passcode = $state('')
  let passcode2 = $state('')
  let typed = $state('')
  let carSessions: AdminSession[] = $state([])
  let deleting: string | null = $state(null)

  let googleButton: HTMLDivElement | undefined = $state()

  const addProblem = $derived(
    slugProblem(newSlug.trim()) ??
      (newName.trim() === '' ? 'A car needs a name' : null) ??
      (newChoose ? twiceProblem(newToken, newToken2, tokenProblem) : null),
  )

  onMount(async () => {
    try {
      config = await api<AdminConfig>('GET', '/config')
      if (config.enabled) {
        email = await api<{ email: string }>('GET', '/me').then((m) => m.email).catch(() => null)
        if (email) await load()
      }
    } catch (e) {
      error = message(e)
    }
    loading = false
  })

  // Google's button, once we know we need it (never locally: the dev button shows there).
  $effect(() => {
    const clientId = config?.googleClientId
    if (!clientId || email || !googleButton) return
    const target = googleButton
    const script = document.createElement('script')
    script.src = 'https://accounts.google.com/gsi/client'
    script.async = true
    script.onload = () => {
      const id = (window as unknown as { google: GoogleId }).google.accounts.id
      id.initialize({ client_id: clientId, callback: (r) => void signIn(r.credential) })
      id.renderButton(target, { theme: 'filled_black', size: 'large', text: 'signin_with' })
    }
    document.head.appendChild(script)
  })

  interface GoogleId {
    accounts: {
      id: {
        initialize(o: { client_id: string; callback: (r: { credential: string }) => void }): void
        renderButton(el: HTMLElement, o: Record<string, string>): void
      }
    }
  }

  function message(e: unknown): string {
    return e instanceof Error ? e.message : String(e)
  }

  /** Runs a change, showing its error; a lapsed sign-in returns to the sign-in. */
  async function run(action: () => Promise<void>) {
    busy = true
    error = null
    try {
      await action()
    } catch (e) {
      if (e instanceof AdminError && e.status === 401) email = null
      error = message(e)
    }
    busy = false
  }

  async function load() {
    cars = await api<AdminCar[]>('GET', '/cars')
  }

  async function signIn(credential: string) {
    await run(async () => {
      email = (await api<{ email: string }>('POST', '/login', { credential })).email
      await load()
    })
  }

  async function signOut() {
    await run(async () => {
      await api('DELETE', '/login')
      email = null
      cars = []
      shown = null
    })
  }

  function show(slug: string, result: CarWithToken) {
    if (result.token) {
      shown = { slug, token: result.token }
      copied = false
    }
  }

  async function copy() {
    if (!shown) return
    await navigator.clipboard.writeText(shown.token)
    copied = true
  }

  async function add(e: SubmitEvent) {
    e.preventDefault()
    if (addProblem) return
    await run(async () => {
      const slug = newSlug.trim()
      const result = await api<CarWithToken>('POST', '/cars', {
        slug, name: newName.trim(), token: newChoose ? newToken : undefined,
      })
      show(slug, result)
      adding = false
      newSlug = newName = newToken = newToken2 = ''
      newChoose = false
      await load()
    })
  }

  function begin(car: AdminCar, action: Action) {
    if (open?.slug === car.slug && open.action === action) {
      open = null
      return
    }
    open = { slug: car.slug, action }
    name = car.name
    choose = false
    token = token2 = passcode = passcode2 = typed = ''
    error = null
    carSessions = []
    deleting = null
    if (action === 'sessions') void loadSessions(car.slug)
  }

  async function loadSessions(slug: string) {
    await run(async () => {
      carSessions = await api<AdminSession[]>('GET', `/cars/${slug}/sessions`)
    })
  }

  async function deleteSession(car: AdminCar, session: AdminSession) {
    await run(async () => {
      await api('DELETE', `/sessions/${session.id}`)
      deleting = null
      typed = ''
      await load()
      await loadSessions(car.slug)
    })
  }

  async function rename(car: AdminCar) {
    await run(async () => {
      await api('PATCH', `/cars/${car.slug}`, { name: name.trim() })
      open = null
      await load()
    })
  }

  async function replaceToken(car: AdminCar) {
    await run(async () => {
      show(car.slug, await api<CarWithToken>('POST', `/cars/${car.slug}/token`, { token: choose ? token : undefined }))
      open = null
      await load()
    })
  }

  async function setPasscode(car: AdminCar) {
    await run(async () => {
      await api('PUT', `/cars/${car.slug}/passcode`, { passcode })
      open = null
      await load()
    })
  }

  async function remove(car: AdminCar) {
    await run(async () => {
      await api('DELETE', `/cars/${car.slug}`)
      open = null
      if (shown?.slug === car.slug) shown = null
      await load()
    })
  }
</script>

<main>
  <p class="top"><a href="/">← Cars</a></p>
  <h1>Admin</h1>

  {#if error}<p class="error" role="alert">{error}</p>{/if}

  {#if loading}
    <p class="muted">Loading…</p>
  {:else if config && !config.enabled}
    <p class="muted">The admin page is not set up yet.</p>
  {:else if !email}
    <section class="panel signin">
      <p>Sign in to manage cars and tokens.</p>
      {#if config?.dev}
        <button class="primary" disabled={busy} onclick={() => signIn('dev')}>Dev sign-in (local only)</button>
      {:else}
        <div bind:this={googleButton}></div>
      {/if}
    </section>
  {:else}
    <div class="who">
      <span class="muted">Signed in as <strong>{email}</strong></span>
      <button class="link" onclick={signOut}>Sign out</button>
    </div>

    {#if shown}
      <section class="panel once" aria-live="polite">
        <strong>New token for {shown.slug}. It is shown once: copy it now.</strong>
        <code>{shown.token}</code>
        <div class="row">
          <button class="primary" onclick={copy}>{copied ? 'Copied' : 'Copy'}</button>
          <button onclick={() => (shown = null)}>Done</button>
        </div>
        <p class="muted small">Put it in the tablet's Cars page. After this, only its last characters are shown.</p>
      </section>
    {/if}

    {#if adding}
      <form class="panel form" onsubmit={add}>
        <strong>Add a car</strong>
        <label><span>Slug <span class="muted small">(its permanent address: /cars/…)</span></span>
          <input bind:value={newSlug} autocapitalize="off" autocomplete="off" spellcheck="false" placeholder="outback" />
        </label>
        <label><span>Name</span> <input bind:value={newName} placeholder="Outback" /></label>
        <label class="check"><input type="checkbox" bind:checked={newChoose} /> Choose the token myself</label>
        {#if newChoose}
          <label><span>Token</span> <input class="secret" type="text" bind:value={newToken} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
          <label><span>Again</span> <input class="secret" type="text" bind:value={newToken2} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
        {/if}
        {#if addProblem && (newSlug || newName)}<p class="hint">{addProblem}</p>{/if}
        <div class="row">
          <button class="primary" type="submit" disabled={busy || !!addProblem}>Add</button>
          <button type="button" onclick={() => (adding = false)}>Cancel</button>
        </div>
      </form>
    {:else}
      <button class="primary add" onclick={() => { adding = true; error = null }}>Add a car</button>
    {/if}

    {#if cars.length === 0}
      <p class="muted">No cars yet.</p>
    {/if}

    <ul class="cars">
      {#each cars as car (car.slug)}
        <li class="panel">
          <div class="head">
            <div>
              <a class="name" href={`/cars/${car.slug}`}>{car.name}</a>
              <span class="muted">/cars/{car.slug}</span>
            </div>
            <span><span class={`dot ${car.state}`}></span>{stateText(car.state)}</span>
          </div>
          <dl>
            <dt>Token</dt><dd>…{car.tokenHint} <span class="muted">since {day(car.tokenIssued)}</span></dd>
            <dt>Crew passcode</dt><dd>{car.passcodeSet ? 'Set' : 'Not set'}</dd>
            {#if clockNote(car.clockOffsetMs)}<dt>Tablet</dt><dd class="clock">{clockNote(car.clockOffsetMs)} <span class="muted">(its times on the site are off by as much)</span></dd>{/if}
          </dl>
          {#if car.liveSession}
            <p class="live-now"><span class="dot live"></span>Streaming a session now. <a href={`/cars/${car.slug}`}>Watch live →</a></p>
          {/if}
          <div class="row actions">
            <button onclick={() => begin(car, 'sessions')}>Sessions ({car.sessions})</button>
            <button onclick={() => begin(car, 'rename')}>Rename</button>
            <button onclick={() => begin(car, 'token')}>Replace token</button>
            <button onclick={() => begin(car, 'passcode')}>{car.passcodeSet ? 'Change passcode' : 'Set passcode'}</button>
            <button class="danger" onclick={() => begin(car, 'remove')}>Remove</button>
          </div>

          {#if open?.slug === car.slug}
            <div class="form inner">
              {#if open.action === 'sessions'}
                {#if carSessions.length === 0}
                  <p class="muted">No sessions.</p>
                {/if}
                <ul class="sessions">
                  {#each carSessions as s (s.id)}
                    <li>
                      <div class="srow">
                        <span>
                          <a href={`/cars/${car.slug}/sessions/${s.id}`}><strong>{when(s.started)}</strong></a>
                          <span class="muted"> · {s.lines.toLocaleString()} {s.lines === 1 ? 'line' : 'lines'} · </span>
                          <span class:live-text={s.state === 'live'}>{sessionStateText(s.state)}</span>
                        </span>
                        <span class="row">
                          {#if s.state === 'live'}<a href={`/cars/${car.slug}`}>Watch live →</a>{/if}
                          <button class="danger small-btn" onclick={() => { deleting = deleting === s.id ? null : s.id; typed = '' }}>Delete</button>
                        </span>
                      </div>
                      <div class="muted small mono">{s.id}</div>
                      {#if deleting === s.id}
                        {@const blocked = deleteBlocked(s)}
                        {#if blocked}
                          <p class="warn">{blocked}</p>
                        {:else}
                          <p class="warn">Its data and record are deleted. This can't be undone.</p>
                          <label><span>Type <strong>{sessionConfirmation(s.id)}</strong> (the start of its id) to confirm</span>
                            <input bind:value={typed} autocapitalize="off" autocomplete="off" spellcheck="false" />
                          </label>
                          <div class="row">
                            <button class="danger" disabled={busy || !confirmed(typed, sessionConfirmation(s.id))} onclick={() => deleteSession(car, s)}>Delete session</button>
                          </div>
                        {/if}
                      {/if}
                    </li>
                  {/each}
                </ul>
              {:else if open.action === 'rename'}
                <label><span>New name</span> <input bind:value={name} /></label>
                <div class="row">
                  <button class="primary" disabled={busy || name.trim() === '' || name.trim() === car.name} onclick={() => rename(car)}>Rename</button>
                </div>
              {:else if open.action === 'token'}
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
                    onclick={() => replaceToken(car)}
                  >Replace token</button>
                </div>
              {:else if open.action === 'passcode'}
                {#if car.passcodeSet}<p class="warn">Every crew member is signed out and needs the new one.</p>{/if}
                <label><span>Passcode</span> <input class="secret" type="text" bind:value={passcode} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
                <label><span>Again</span> <input class="secret" type="text" bind:value={passcode2} autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" /></label>
                {#if passcode}<p class="hint">{twiceProblem(passcode, passcode2, passcodeProblem) ?? ''}</p>{/if}
                <div class="row">
                  <button class="primary" disabled={busy || !!twiceProblem(passcode, passcode2, passcodeProblem)} onclick={() => setPasscode(car)}>Save passcode</button>
                </div>
              {:else if open.action === 'remove'}
                {#if car.sessions > 0}
                  <p class="warn">{car.name} still has {car.sessions} session(s). Delete them first.</p>
                {:else}
                  <p class="warn">Its token stops working at once, and its crew messages are deleted.</p>
                  <label><span>Type <strong>{car.slug}</strong> to confirm</span> <input bind:value={typed} autocapitalize="off" autocomplete="off" /></label>
                  <div class="row">
                    <button class="danger" disabled={busy || !confirmed(typed, car.slug)} onclick={() => remove(car)}>Remove {car.name}</button>
                  </div>
                {/if}
              {/if}
            </div>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</main>

<style>
  .top { margin: 0; }
  .panel { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; margin: 12px 0; }
  .signin { display: grid; gap: 12px; justify-items: start; }
  .who { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
  .once { border-color: var(--caution); display: grid; gap: 8px; }
  .once code { font-size: 1rem; background: var(--bg); padding: 10px; border-radius: 8px; overflow-wrap: anywhere; user-select: all; }
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
  button.link { background: none; border: none; color: var(--muted); text-decoration: underline; padding: 0; }
  .add { margin: 12px 0 4px; }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  .cars { list-style: none; margin: 0; padding: 0; }
  .head { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; flex-wrap: wrap; }
  .name { font-weight: 700; font-size: 1.1rem; margin-right: 8px; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; margin: 10px 0; font-size: 0.95rem; }
  dt { color: var(--muted); }
  dd { margin: 0; }
  .actions button { font-size: 0.9rem; }
  .hint { color: var(--caution); margin: 0; font-size: 0.9rem; }
  .warn { color: var(--caution); margin: 0; }
  .clock { color: var(--caution); }
  .error { color: var(--critical); }
  .small { font-size: 0.85rem; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; overflow-wrap: anywhere; }
  .live-now { margin: 0 0 10px; }
  .live-text { color: var(--in-range); font-weight: 600; }
  .sessions { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
  .sessions li { display: grid; gap: 6px; border-top: 1px solid var(--line); padding-top: 8px; }
  .sessions li:first-child { border-top: none; padding-top: 0; }
  .srow { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
  .small-btn { padding: 4px 10px; font-size: 0.85rem; }
</style>
