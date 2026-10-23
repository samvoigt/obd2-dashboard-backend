#!/usr/bin/env node
// Does every page fit a phone (M30.1)? Each path laid out as a 390 × 844 phone (mobile
// emulation, as measure.mjs does: a desktop window won't lay out that narrow), and a page
// fails when it scrolls sideways. Tables may scroll inside their panel; the page never does.
//
//   node web/scripts/phone-check.mjs [base] [extra paths…]
//   node web/scripts/phone-check.mjs http://localhost:5173 /events/m29-check
//
// Against the dev server (./gradlew :server:devServer, and npm run dev for the newest code).
// Signs in with the dev sign-in for the signed-in paths. Screenshots go to web/build/phone/.
// Exits 1 if any page overflows. Needs Google Chrome in /Applications.
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { sleep, startChrome } from './chrome.mjs'

const base = (process.argv[2] ?? 'http://localhost:5173').replace(/\/$/, '')
const extra = process.argv.slice(3)
const WIDTH = 390
const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'build', 'phone')
mkdirSync(out, { recursive: true })

/** Every page a fresh dev server has; then those only someone signed in sees. */
const signedOut = ['/', '/cars', '/cars/dev-car', '/cars/dev-car/sessions', '/cars/dev-car/manage', '/events', '/drivers',
  '/courses', '/courses/nhms', '/compare', '/privacy', '/terms', ...extra]
const signedIn = ['/', '/cars/dev-car/manage', '/courses/nhms/edit', '/courses/new', '/events/new', '/users', ...extra]

const { send, on, close } = await startChrome('phone-')
await send('Page.enable')
await send('Network.enable')
await send('Emulation.setDeviceMetricsOverride', { width: WIDTH, height: 844, deviceScaleFactor: 2, mobile: true })

// "Quiet": no new request for 2 s. Not "nothing open": a live page keeps its stream open.
let lastRequest = Date.now()
on('Network.requestWillBeSent', () => { lastRequest = Date.now() })
async function settle() {
  const start = Date.now()
  await sleep(500)
  while (Date.now() - lastRequest < 2000 && Date.now() - start < 15_000) await sleep(200)
}

/** Measured in the page: whether it scrolls sideways, and what pokes past its right edge. */
// Against the phone's width, not innerWidth: a mobile page wider than its screen is zoomed out
// to fit, and innerWidth then grows with it, so it would always "fit".
const MEASURE = `(() => {
  const width = ${WIDTH}
  const scrolls = (el) => { for (let p = el.parentElement; p; p = p.parentElement) {
    const o = getComputedStyle(p).overflowX; if (o === 'auto' || o === 'scroll' || o === 'hidden') return true } return false }
  const out = []
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect()
    if (r.width === 0 || r.right <= width + 1 || scrolls(el)) continue
    out.push({ el: el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\\s+/).join('.') : ''),
      right: Math.round(r.right), width: Math.round(r.width), text: (el.textContent ?? '').trim().slice(0, 40) })
  }
  out.sort((a, b) => b.right - a.right)
  return JSON.stringify({ scrollWidth: document.documentElement.scrollWidth, width, offenders: out.slice(0, 5) })
})()`

async function check(path, tag) {
  await send('Page.navigate', { url: base + path })
  await settle()
  const { result } = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true })
  const m = JSON.parse(result.value)
  const { cssContentSize: size } = await send('Page.getLayoutMetrics')
  const shot = await send('Page.captureScreenshot', {
    format: 'png', captureBeyondViewport: true,
    clip: { x: 0, y: 0, width: Math.max(size.width, 390), height: Math.min(size.height, 12000), scale: 1 },
  })
  const file = `${tag}${path === '/' ? '-home' : path.replaceAll('/', '-')}.png`
  writeFileSync(join(out, file), Buffer.from(shot.data, 'base64'))
  const ok = m.scrollWidth <= m.width
  console.log(`${ok ? 'ok  ' : 'WIDE'} ${tag.padEnd(4)} ${path}${ok ? '' : `  (${m.scrollWidth} px wide)`}`)
  if (!ok) for (const o of m.offenders) console.log(`       ${o.el}  right ${o.right}, width ${o.width}${o.text ? `  "${o.text}"` : ''}`)
  return ok
}

let failed = 0
for (const path of signedOut) if (!(await check(path, 'out'))) failed++
// The dev sign-in, from the page so its cookie lands in this profile; the server checks Origin.
await send('Page.navigate', { url: base + '/' })
await settle()
const { result: login } = await send('Runtime.evaluate', {
  expression: `fetch('/api/admin/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"credential":"dev"}' }).then((r) => r.status)`,
  awaitPromise: true, returnByValue: true,
})
if (login.value !== 200) console.log(`dev sign-in answered ${login.value}: is this the dev server?`)
else for (const path of signedIn) if (!(await check(path, 'in'))) failed++

console.log(failed === 0 ? 'every page fits' : `${failed} page(s) scroll sideways; screenshots in ${out}`)
await close()
process.exit(failed === 0 ? 0 : 1)
