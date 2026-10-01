<script lang="ts">
  import { onMount } from 'svelte'
  import { signin } from './lib/signin'
  import logo from './assets/logo.webp'

  // Every page's header (M21.1): home, the site's pages, and the one sign-in that turns on each page's edits.
  let { page }: { page: string } = $props()

  let open = $state(false) // Google's button, shown on demand
  let busy = $state(false)
  let error: string | null = $state(null)
  let googleButton: HTMLDivElement | undefined = $state()
  let googleLoaded = false

  onMount(() => void signin.check())

  const links = [
    { href: '/cars', label: 'Cars', pages: ['cars', 'car', 'manage', 'sessions', 'session'] },
    { href: '/events', label: 'Events', pages: ['events', 'event', 'event-edit'] },
    { href: '/drivers', label: 'Drivers', pages: ['drivers', 'driver'] },
    { href: '/courses', label: 'Courses', pages: ['courses', 'course', 'course-edit'] },
  ]

  async function signIn(credential: string) {
    busy = true
    error = null
    try {
      await signin.signIn(credential)
      open = false
    } catch (e) {
      error = e instanceof Error ? e.message : String(e)
    } finally {
      busy = false
    }
  }

  async function signOut() {
    busy = true
    await signin.signOut()
    busy = false
  }

  /** Sign in: the dev server's own at once; else Google's button, its script loaded only now. */
  function start() {
    const config = $signin.state === 'out' ? $signin.config : null
    if (config?.dev) return void signIn('dev')
    open = !open
  }

  $effect(() => {
    const clientId = $signin.state === 'out' ? $signin.config?.googleClientId : null
    if (!open || !clientId || !googleButton) return
    const target = googleButton
    const render = () => {
      const id = (window as unknown as { google: GoogleId }).google.accounts.id
      id.initialize({ client_id: clientId, callback: (r) => void signIn(r.credential) })
      id.renderButton(target, { theme: 'filled_black', size: 'large', text: 'signin_with' })
    }
    if (googleLoaded) return render()
    const script = document.createElement('script')
    script.src = 'https://accounts.google.com/gsi/client'
    script.async = true
    script.onload = () => { googleLoaded = true; render() }
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
</script>

<header>
  <a class="home" href="/" aria-label="Home"><img src={logo} alt="" width="28" height="28" /><span>Bad News Bears</span></a>
  <nav>
    {#each links as l (l.href)}
      <a href={l.href} class:here={l.pages.includes(page)}>{l.label}</a>
    {/each}
  </nav>
  <div class="who">
    {#if $signin.state === 'in'}
      <span class="email muted" title="Signed in: every page shows its edits">{$signin.email}</span>
      <button onclick={signOut} disabled={busy}>Sign out</button>
    {:else if $signin.state === 'out' && $signin.config?.enabled}
      <button onclick={start} disabled={busy}>{$signin.config.dev ? 'Dev sign-in' : 'Sign in'}</button>
    {/if}
  </div>
  {#if open && $signin.state === 'out'}
    <div class="panel">
      <div bind:this={googleButton}></div>
      <p class="muted small">Signing in turns on the edits on every page. Only the allowlisted admin can.</p>
    </div>
  {/if}
  {#if error}<p class="error small">{error}</p>{/if}
</header>

<style>
  header {
    max-width: 1200px; margin: 0 auto; padding: 10px 16px 0;
    display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px;
  }
  .home { display: flex; align-items: center; gap: 8px; text-decoration: none; font-weight: 700; }
  .home img { width: 28px; height: 28px; }
  nav { display: flex; gap: 14px; flex-wrap: wrap; flex: 1; }
  nav a { text-decoration: none; color: var(--muted); }
  nav a:hover, nav a.here { color: var(--text); }
  nav a.here { text-decoration: underline; text-underline-offset: 4px; }
  .who { display: flex; align-items: center; gap: 8px; margin-left: auto; }
  .email { font-size: 0.85rem; max-width: 40vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  button { background: var(--bg); color: var(--accent); border: 1px solid var(--accent); border-radius: 6px; padding: 4px 10px; cursor: pointer; }
  button:disabled { opacity: 0.5; cursor: default; }
  .panel { flex-basis: 100%; display: grid; justify-items: end; gap: 4px; }
  .panel p { margin: 0; }
  .small { font-size: 0.85rem; }
  .error { flex-basis: 100%; text-align: right; color: var(--critical); margin: 0; }
  @media (max-width: 520px) { .home span { display: none; } }
</style>
