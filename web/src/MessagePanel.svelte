<script lang="ts">
  import {
    age, current, describe, length, LIFETIMES, MAX_TEXT, PRESETS, sendable, type CrewMessage,
  } from './lib/messages'

  let {
    slug,
    crew,
    list,
    offsetMs,
    now,
    onCrewChange,
  }: {
    slug: string
    crew: boolean
    list: CrewMessage[]
    offsetMs: number
    now: number
    onCrewChange: () => void
  } = $props()

  let passcode = $state('')
  let text = $state('')
  let lifetime = $state(LIFETIMES[0]!.seconds)
  let error: string | null = $state(null)
  let busy = $state(false)

  const shown = $derived(current(list, now, offsetMs))
  const recent = $derived(list.slice(0, 8))

  async function call(method: string, path: string, body?: unknown): Promise<Response> {
    return fetch(`/api/cars/${slug}${path}`, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  }

  async function explain(r: Response): Promise<string> {
    try {
      return ((await r.json()) as { message?: string }).message ?? `${r.status}`
    } catch {
      return `${r.status}`
    }
  }

  async function login(e: SubmitEvent) {
    e.preventDefault()
    busy = true
    error = null
    const r = await call('POST', '/login', { passcode })
    passcode = ''
    busy = false
    if (r.ok) onCrewChange()
    else error = await explain(r)
  }

  async function logout() {
    await call('DELETE', '/login')
    onCrewChange()
  }

  async function send(messageText: string, preset?: string) {
    busy = true
    error = null
    const r = await call('POST', '/messages', { text: messageText, preset, ttlSeconds: lifetime })
    busy = false
    if (r.ok) {
      if (!preset) text = ''
    } else {
      error = await explain(r)
    }
  }

  async function clear(id: string) {
    const r = await call('DELETE', `/messages/${id}`)
    if (!r.ok && r.status !== 404) error = await explain(r)
  }
</script>

<section class="crew" class:boxed={crew}>
  {#if !crew}
    <form class="login" onsubmit={login}>
      <span class="muted">Crew messages</span>
      <input type="password" autocomplete="current-password" placeholder="Crew passcode" bind:value={passcode} aria-label="Crew passcode" />
      <button type="submit" disabled={busy || passcode.length === 0}>Log in</button>
    </form>
  {:else}
    <div class="head">
      <strong>Message the driver</strong>
      <button class="link" onclick={logout}>Log out</button>
    </div>

    {#if shown}
      <div class={`current ${shown.state}`}>
        <div class="text">{shown.text}</div>
        <div class="meta">
          <span class="state">{describe(shown, now, offsetMs)}</span>
          <span class="muted">· sent {age(shown, now, offsetMs)} ago</span>
          <button class="clear" onclick={() => clear(shown.id)}>Clear</button>
        </div>
      </div>
    {:else}
      <div class="current none muted">Nothing on the driver's screen.</div>
    {/if}

    <div class="presets">
      {#each PRESETS as p (p.preset)}
        <button class={`preset ${p.preset}`} disabled={busy} onclick={() => send(p.text, p.preset)}>{p.text}</button>
      {/each}
    </div>

    <form class="free" onsubmit={(e) => { e.preventDefault(); if (sendable(text)) send(text.trim()) }}>
      <input bind:value={text} placeholder="Or type a message" aria-label="Message text" />
      <span class:over={length(text) > MAX_TEXT} class="count">{length(text)}/{MAX_TEXT}</span>
      <button type="submit" disabled={busy || !sendable(text)}>Send</button>
    </form>

    <label class="lifetime">
      <span class="muted">Stays up</span>
      <select bind:value={lifetime}>
        {#each LIFETIMES as l (l.seconds)}<option value={l.seconds}>{l.label}</option>{/each}
      </select>
    </label>

    {#if recent.length > 0}
      <ul class="recent">
        {#each recent as m (m.id)}
          <li><span class="rtext">{m.text}</span><span class="muted">{describe(m, now, offsetMs)} · {age(m, now, offsetMs)} ago</span></li>
        {/each}
      </ul>
    {/if}
  {/if}
  {#if error}<p class="error">{error}</p>{/if}
</section>

<style>
  .crew { display: grid; gap: 12px; margin: 16px 0; }
  .boxed { background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
  .login, .free, .head, .lifetime { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  .head { justify-content: space-between; }
  input, select { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 8px 10px; font-size: 1rem; min-width: 0; }
  .free input { flex: 1 1 200px; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 8px 12px; font-size: 0.95rem; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  button.link { background: none; border: none; color: var(--muted); text-decoration: underline; padding: 0; }
  .presets { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 8px; }
  .preset { font-weight: 800; font-size: 1.05rem; padding: 14px 8px; letter-spacing: 0.03em; }
  .preset.pit, .preset.box { border-color: var(--danger); color: var(--danger); }
  .preset.slow { border-color: var(--stale); color: var(--stale); }
  .current { border: 2px solid var(--line); border-radius: 10px; padding: 12px 14px; }
  .current.displayed { border-color: var(--live); }
  .current.received { border-color: var(--idle); }
  .current.queued { border-color: var(--stale); }
  .current .text { font-size: 1.6rem; font-weight: 800; overflow-wrap: anywhere; }
  .current .meta { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 6px; }
  .current .state { font-weight: 600; }
  .clear { margin-left: auto; }
  .count { font-variant-numeric: tabular-nums; color: var(--muted); font-size: 0.85rem; }
  .count.over { color: var(--danger); }
  .recent { list-style: none; margin: 0; padding: 0; display: grid; gap: 4px; font-size: 0.9rem; }
  .recent li { display: flex; justify-content: space-between; gap: 12px; border-top: 1px solid var(--line); padding-top: 4px; }
  .rtext { font-weight: 600; overflow-wrap: anywhere; }
  .error { color: var(--danger); margin: 0; }
</style>
