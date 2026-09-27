#!/usr/bin/env node
// How a page holds up (M8.4): a headless Chrome of its own (a throwaway profile,
// never anyone's), the CPU slowed, a phone-sized screen, and each minute's
// frames per second, long tasks and JS heap. Speaks the DevTools protocol over
// Node's built-in WebSocket, so it needs nothing installed.
//
//   node web/scripts/measure.mjs [url] [minutes] [cpu slowdown]
//   node web/scripts/measure.mjs http://localhost:5173/cars/dev-car 30 4
//
// Prints one line a minute, then a summary. Needs Google Chrome in /Applications.
// Run it (and the replay feeding it) under `caffeinate -s`: the Mac's sleep (a closed
// lid's too, which `-i` lets through) freezes both, and a frozen minute reads as a slow one. Edit nothing under src/
// while it runs: a hot reload resets the page's counters.
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const url = process.argv[2] ?? 'http://localhost:5173/cars/dev-car'
const minutes = Number(process.argv[3] ?? 30)
const slowdown = Number(process.argv[4] ?? 4)
const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const profile = mkdtempSync(join(tmpdir(), 'measure-'))
const port = 9300 + Math.floor(Math.random() * 500)

const proc = spawn(chrome, [
  '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--disable-background-timer-throttling',
  '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows', 'about:blank',
], { stdio: 'ignore' })

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function target() {
  for (let i = 0; i < 50; i++) {
    try {
      const pages = await (await fetch(`http://127.0.0.1:${port}/json`)).json()
      const page = pages.find((p) => p.type === 'page')
      if (page) return page.webSocketDebuggerUrl
    } catch { /* not up yet */ }
    await sleep(200)
  }
  throw new Error('Chrome did not start')
}

const ws = new WebSocket(await target())
await new Promise((r) => ws.addEventListener('open', r, { once: true }))
let id = 0
const pending = new Map()
ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data)
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
})
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const n = ++id
  pending.set(n, (msg) => (msg.error ? reject(new Error(`${method}: ${msg.error.message}`)) : resolve(msg.result)))
  ws.send(JSON.stringify({ id: n, method, params }))
})

await send('Page.enable')
await send('Performance.enable')
await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true })
await send('Emulation.setCPUThrottlingRate', { rate: slowdown })
// Counters in the page, read once a minute: frames drawn, and tasks over 50 ms.
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: `window.__m = { frames: 0, long: 0, longMs: 0 };
    (function tick() { window.__m.frames++; requestAnimationFrame(tick) })();
    try { new PerformanceObserver((l) => { for (const e of l.getEntries()) { window.__m.long++; window.__m.longMs += e.duration } })
      .observe({ type: 'longtask', buffered: true }) } catch (e) {}`,
})
await send('Page.navigate', { url })
await sleep(8000) // loaded, and a snapshot in

const read = async () => {
  // With a sign the page is really live: the banner's word and the first gauge's reading.
  const { result } = await send('Runtime.evaluate', {
    expression: `JSON.stringify({ ...window.__m, banner: document.querySelector('.banner-text')?.textContent ?? null, reading: document.querySelector('.gauge .value')?.textContent ?? null })`,
    returnByValue: true,
  })
  const counters = JSON.parse(result.value)
  const { metrics } = await send('Performance.getMetrics')
  const m = Object.fromEntries(metrics.map((x) => [x.name, x.value]))
  return { ...counters, heap: m.JSHeapUsedSize, nodes: m.Nodes, t: Date.now() }
}

console.log(`measuring ${url} for ${minutes} min, CPU ${slowdown}× slower, 390×844`)
const rows = []
let prev = await read()
for (let i = 1; i <= minutes; i++) {
  await sleep(60_000)
  const now = await read()
  const secs = (now.t - prev.t) / 1000
  const row = {
    minute: i,
    fps: +((now.frames - prev.frames) / secs).toFixed(1),
    longTasks: now.long - prev.long,
    longMs: Math.round(now.longMs - prev.longMs),
    heapMB: +(now.heap / 1048576).toFixed(1),
    nodes: now.nodes,
    banner: now.banner,
    reading: now.reading,
  }
  rows.push(row)
  console.log(JSON.stringify(row))
  prev = now
}

const fps = rows.map((r) => r.fps)
const heap = rows.map((r) => r.heapMB)
console.log(JSON.stringify({
  summary: true,
  fpsMin: Math.min(...fps), fpsMedian: fps.sort((a, b) => a - b)[Math.floor(fps.length / 2)],
  longTasks: rows.reduce((a, r) => a + r.longTasks, 0), busiestMinuteLongMs: Math.max(...rows.map((r) => r.longMs)),
  heapFirstMB: rows[0]?.heapMB, heapLastMB: rows[rows.length - 1]?.heapMB, heapMaxMB: Math.max(...heap),
}))
ws.close()
proc.kill()
await sleep(500)
rmSync(profile, { recursive: true, force: true })
