// A headless Chrome of its own (a throwaway profile, never anyone's), driven over the
// DevTools protocol with Node's built-in WebSocket, so nothing is installed (M8.4).
// Shared by measure.mjs and phone-check.mjs (M30.1). Needs Google Chrome in /Applications.
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/**
 * Starts Chrome, connects to its page, and answers `{ send, on, close }`: `send(method, params)`
 * resolves with a command's result, `on(method, fn)` hears an event, `close()` stops Chrome and
 * removes its profile.
 */
export async function startChrome(prefix = 'chrome-') {
  const profile = mkdtempSync(join(tmpdir(), prefix))
  const port = 9300 + Math.floor(Math.random() * 500)
  const proc = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--disable-background-timer-throttling',
    '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows', 'about:blank',
  ], { stdio: 'ignore' })

  let url = null
  for (let i = 0; i < 50 && !url; i++) {
    try {
      const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json()
      url = pages.find((p) => p.type === 'page')?.webSocketDebuggerUrl ?? null
    } catch { /* not up yet */ }
    if (!url) await sleep(200)
  }
  if (!url) { proc.kill(); throw new Error('Chrome did not start') }

  const ws = new WebSocket(url)
  await new Promise((r) => ws.addEventListener('open', r, { once: true }))
  let id = 0
  const pending = new Map()
  const listeners = new Map()
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
    else if (msg.method) for (const fn of listeners.get(msg.method) ?? []) fn(msg.params)
  })
  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const n = ++id
    pending.set(n, (msg) => (msg.error ? reject(new Error(`${method}: ${msg.error.message}`)) : resolve(msg.result)))
    ws.send(JSON.stringify({ id: n, method, params }))
  })
  const on = (method, fn) => listeners.set(method, [...(listeners.get(method) ?? []), fn])
  const close = async () => {
    ws.close()
    proc.kill()
    await sleep(500)
    rmSync(profile, { recursive: true, force: true })
  }
  return { send, on, close }
}
