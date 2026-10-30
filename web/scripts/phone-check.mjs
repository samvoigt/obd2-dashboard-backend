#!/usr/bin/env node
// Does every page fit a phone (M31.1)? Each path laid out as a 390 × 844 phone (mobile
// emulation, as measure.mjs does: a desktop window won't lay out that narrow), and a page
// fails when it draws nothing (M35.4), scrolls sideways, or its header's links run under its buttons (M34.3). Tables may
// scroll inside their panel; the page never does.
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
  // Controls under 44 px tall (M31.5), visible ones only.
  const small = []
  for (const el of document.querySelectorAll('button, select, input:not([type=checkbox]):not([type=radio]):not([type=hidden]), a.button, summary')) {
    const r = el.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || getComputedStyle(el).visibility === 'hidden') continue
    if (r.height < 43.5) small.push(el.tagName.toLowerCase() + ' ' + Math.round(r.height) + 'px "' + (el.textContent || el.getAttribute('aria-label') || el.placeholder || '').trim().slice(0, 24) + '"')
  }
  // The header's links never run under its buttons (M34.3: the Crew button covered "Courses", and nothing was wide).
  // The links, not the nav: its box shrinks to fit while they spill out of it, unless it clips them.
  const nav = document.querySelector('header nav')
  const who = document.querySelector('header .who')?.getBoundingClientRect()
  const clip = nav && getComputedStyle(nav).overflowX !== 'visible' ? nav.getBoundingClientRect().right : Infinity
  // Only links level with the buttons: on a phone they have their own row under them (M36.4).
  const level = (r) => r.bottom > who.top && r.top < who.bottom
  const overlap = nav && who ? Math.max(0, ...[...nav.querySelectorAll('a')].map((a) => a.getBoundingClientRect()).filter(level).map((r) => Math.round(Math.min(r.right, clip) - who.left))) : 0
  // Nor are they cut off at its edge, to be found by scrolling (M36.4: signed out at 390 px, "Courses" was).
  const cut = nav ? [...nav.querySelectorAll('a')].filter((a) => a.getBoundingClientRect().right > clip + 0.5).map((a) => a.textContent.trim()) : []
  // A page that drew nothing fits any phone (M35.4: a stale Vite cache left every page blank, and all passed).
  const blank = !document.querySelector('main') || (document.querySelector('main').innerText ?? '').trim().length === 0
  return JSON.stringify({ scrollWidth: document.documentElement.scrollWidth, width, offenders: out.slice(0, 5), small, overlap, cut, blank, title: document.title })
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
  const wide = m.scrollWidth > m.width
  // Small controls fail the pages read in the pits (the car's and a session's); elsewhere they're reported.
  const strict = /^\/cars\/[a-z0-9-]+$/.test(path) || /\/sessions\/[0-9a-f-]{36}$/.test(path)
  const tooSmall = strict && m.small.length > 0
  const covered = m.overlap > 0
  const cut = m.cut.length > 0
  // Every page but the landing says what it is in its tab (M35.5).
  const untitled = path !== '/' && !m.title.includes(' · ')
  const ok = !wide && !tooSmall && !covered && !cut && !m.blank && !untitled
  console.log(`${ok ? 'ok  ' : m.blank ? 'NONE' : wide ? 'WIDE' : covered ? 'OVER' : cut ? 'CUT ' : untitled ? 'NAME' : 'TINY'} ${tag.padEnd(4)} ${path}${wide ? `  (${m.scrollWidth} px wide)` : ''}${m.blank ? '  nothing drawn' : ''}${untitled ? `  titled "${m.title}"` : ''}${covered ? `  the header's links run ${m.overlap} px under its buttons` : ''}${cut ? `  the header cuts off ${m.cut.join(', ')}` : ''}${m.small.length ? `  ${m.small.length} control(s) under 44 px` : ''}`)
  if (wide) for (const o of m.offenders) console.log(`       ${o.el}  right ${o.right}, width ${o.width}${o.text ? `  "${o.text}"` : ''}`)
  if (m.small.length) for (const c of m.small.slice(0, 5)) console.log(`       ${c}`)
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

console.log(failed === 0 ? 'every page fits' : `${failed} page(s) scroll sideways or have small controls where they mustn't; screenshots in ${out}`)
await close()
process.exit(failed === 0 ? 0 : 1)
