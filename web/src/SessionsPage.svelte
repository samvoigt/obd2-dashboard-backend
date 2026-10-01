<script lang="ts">
  import { onMount } from 'svelte'
  import { badge, clockOf, dayOf, duration, fetchDrives, lapTime, sourceLabel, trackOf, type Drive } from './lib/sessions'
  import { signin } from './lib/signin'
  import { AdminError, api, confirmed, deleteBlocked, sessionConfirmation, sessionStateText, type AdminSession } from './lib/admin'

  let { slug }: { slug: string } = $props()

  let drives: Drive[] | null = $state([])
  let loaded = $state(false)
  let error: string | null = $state(null)

  async function refresh() {
    try {
      drives = await fetchDrives(slug)
      error = null
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    } finally {
      loaded = true
    }
  }

  // Signed in (M21.5): each session's admin facts (its state as deleting sees it), from the admin API only.
  const signedIn = $derived($signin.state === 'in')
  let admin: Map<string, AdminSession> = $state.raw(new Map())
  let deleting: string | null = $state(null)
  let typed = $state('')
  let busy = $state(false)
  let adminError: string | null = $state(null)

  async function refreshAdmin() {
    if (!signedIn) return
    try {
      const list = await api<AdminSession[]>('GET', `/cars/${slug}/sessions`)
      admin = new Map(list.map((s) => [s.id, s]))
    } catch (e) {
      if (!(e instanceof AdminError && e.status === 404)) adminError = e instanceof Error ? e.message : String(e)
    }
  }

  $effect(() => {
    if (signedIn) void refreshAdmin()
    else { admin = new Map(); deleting = null; adminError = null }
  })

  async function remove(s: AdminSession) {
    busy = true
    adminError = null
    try {
      await api('DELETE', `/sessions/${s.id}`)
      deleting = null
      typed = ''
      await Promise.all([refresh(), refreshAdmin()])
    } catch (e) {
      adminError = e instanceof Error ? e.message : String(e)
    }
    busy = false
  }

  // A live or uploading session changes as it goes; a finished list doesn't.
  onMount(() => {
    refresh()
    const timer = setInterval(() => { void refresh(); void refreshAdmin() }, 30_000)
    return () => clearInterval(timer)
  })
</script>

<main>
  <p class="back"><a href={`/cars/${slug}`}>← {slug}, live</a>{#if signedIn}<a href={`/cars/${slug}/manage`}>Manage {slug} →</a>{/if}</p>
  <h1>Past sessions</h1>
  {#if adminError}<p class="error" role="alert">{adminError}</p>{/if}

  {#if error}
    <p class="error">Could not load the sessions: {error}</p>
  {:else if drives === null}
    <p class="muted">No car “{slug}”.</p>
  {:else if loaded && drives.length === 0}
    <p class="muted">No sessions yet. They appear here as the tablet uploads them.</p>
  {/if}

  <!-- Keyed by a session: test data is a drive of its own and may start with another (M11). -->
  {#each drives ?? [] as drive (drive.sessions[0]?.id ?? drive.started)}
    <section class="drive">
      <h2>
        {dayOf(drive.started)}
        <span class="muted">· {clockOf(drive.started)}–{clockOf(drive.ended)} · {duration(drive.ended - drive.started)}</span>
      </h2>
      <ul>
        {#each drive.sessions as s (s.id)}
          {@const b = badge(s.state)}
          {@const src = sourceLabel(s.source)}
          <li>
            <a href={`/cars/${slug}/sessions/${s.id}`}>
              <span class="when">
                {#if s.name}<strong class="name">{s.name}</strong>{/if}
                <strong>{clockOf(s.started)}</strong>
                <span class="muted">{duration(s.ended - s.started)}</span>
                {#if b}<span class={`badge ${b.kind}`}><span class={`dot ${b.kind}`}></span>{b.text}</span>{/if}
                {#if src}<span class={`source ${src.kind}`}>{src.text}</span>{/if}
              </span>
              <span class="facts">
                {#if trackOf(s)}<span>{trackOf(s)}</span>{/if}
                <!-- The row is a link to the session, so the event is named, not linked. -->
                {#if s.event}<span>{s.event.name}, {s.event.part}</span>{/if}
                {#if s.bestLap}<span>Best <strong>{lapTime(s.bestLap.time)}</strong> <span class="muted">(lap {s.bestLap.lap} of {s.laps})</span></span>{/if}
                {#if s.faults.length > 0}<span class="fault">{s.faults.join(', ')}</span>{/if}
                {#if s.lines > 0}<span class="muted">{s.lines.toLocaleString()} lines</span>{/if}
              </span>
            </a>
            {#if signedIn && admin.get(s.id)}
              {@const a = admin.get(s.id)!}
              <div class="manage">
                <span class="muted small">{sessionStateText(a.state)}</span>
                {#if a.lines > 0}<a class="small" href={`/api/admin/sessions/${s.id}/download`} download>Download</a>{/if}
                <button class="danger small" onclick={() => { deleting = deleting === s.id ? null : s.id; typed = '' }}>Delete</button>
              </div>
              {#if deleting === s.id}
                {@const blocked = deleteBlocked(a)}
                <div class="confirm">
                  {#if blocked}
                    <p class="warn">{blocked}</p>
                  {:else}
                    <p class="warn">Its data and record are deleted. This can't be undone.</p>
                    <label><span>Type <strong>{sessionConfirmation(s.id)}</strong> (the start of its id) to confirm</span>
                      <input bind:value={typed} autocapitalize="off" autocomplete="off" spellcheck="false" />
                    </label>
                    <div><button class="danger" disabled={busy || !confirmed(typed, sessionConfirmation(s.id))} onclick={() => remove(a)}>Delete session</button></div>
                  {/if}
                </div>
              {/if}
            {/if}
          </li>
        {/each}
      </ul>
    </section>
  {/each}
</main>

<style>
  .back { margin: 0 0 8px; display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
  .back a { color: var(--muted); text-decoration: none; }
  .drive { margin: 18px 0; }
  h2 { font-size: 1.05rem; margin: 0 0 8px; }
  ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
  li a {
    display: flex; justify-content: space-between; align-items: center; gap: 8px 16px; flex-wrap: wrap;
    padding: 12px 14px; background: var(--panel); border: 1px solid var(--line); border-radius: 10px;
    text-decoration: none;
  }
  li a:hover { border-color: var(--muted); }
  .when, .facts { display: flex; align-items: center; gap: 6px 12px; flex-wrap: wrap; }
  .badge { display: inline-flex; align-items: center; font-size: 0.85rem; font-weight: 600; }
  .badge.live { color: var(--in-range); }
  .badge.stale { color: var(--caution); }
  .badge.offline { color: var(--muted); }
  .source { font-size: 0.85rem; color: var(--muted); border: 1px solid var(--line); border-radius: 6px; padding: 0 6px; }
  .source.test { color: var(--caution); border-color: var(--caution); font-weight: 600; }
  .dot.stale { background: var(--caution); }
  .fault { color: var(--critical); font-weight: 600; }
  .error { color: var(--critical); }
  .manage { display: flex; align-items: center; justify-content: flex-end; gap: 12px; padding: 6px 14px 0; }
  .manage a { color: var(--accent); }
  .confirm { display: grid; gap: 8px; padding: 8px 14px 0; }
  .confirm label { display: grid; gap: 4px; font-size: 0.95rem; }
  .confirm input { background: var(--bg); color: var(--text); border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; max-width: 220px; }
  .warn { color: var(--caution); margin: 0; }
  .small { font-size: 0.85rem; }
  button { background: var(--panel); color: var(--text); border: 1px solid var(--line); border-radius: 8px; padding: 4px 10px; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  button.danger { border-color: var(--critical); color: var(--critical); }
</style>
