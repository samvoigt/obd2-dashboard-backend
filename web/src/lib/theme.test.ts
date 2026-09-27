/**
 * The Bad News Bears look (M8.1): every colour is in `app.css`, and each reads.
 * The same checks the tablet app's Colours page makes (its decision 100):
 * WCAG contrast of 3:1 or more on the background and on a panel, and the
 * colours that mean something at least 25 apart in CIE Lab, so a caution
 * never passes for a critical.
 */
import { describe, expect, it } from 'vitest'
import css from '../app.css?raw'
import { translucent } from './theme'

/** Every component and script, as text, through Vite (no Node APIs in the site's types). */
const sources = import.meta.glob(['../**/*.svelte', '../**/*.ts', '!../**/*.test.ts', '!../assets/**'], {
  query: '?raw', import: 'default', eager: true,
}) as Record<string, string>

function role(name: string): string {
  const m = new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})`).exec(css)
  if (!m) throw new Error(`app.css has no --${name}`)
  return m[1]!.toLowerCase()
}

function rgb(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function luminance(hex: string): number {
  const [r, g, b] = rgb(hex).map((c) => {
    const s = c / 255
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r! + 0.7152 * g! + 0.0722 * b!
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi! + 0.05) / (lo! + 0.05)
}

/** CIE Lab (D65), and the plain distance between two colours in it (ΔE 1976). */
function lab(hex: string): [number, number, number] {
  const [r, g, b] = rgb(hex).map((c) => {
    const s = c / 255
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  }) as [number, number, number]
  const x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
  const y = 0.2126 * r + 0.7152 * g + 0.0722 * b
  const z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
  const f = (t: number) => (t > 216 / 24389 ? Math.cbrt(t) : (24389 / 27 * t + 16) / 116)
  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))]
}

function deltaE(a: string, b: string): number {
  const [p, q] = [lab(a), lab(b)]
  return Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2])
}

describe('the colours are the logo’s', () => {
  it('uses the four the tablet app reads from the logo', () => {
    expect(role('critical')).toBe('#ff0099')
    expect(role('accent')).toBe('#01b7f9')
    expect(role('in-range')).toBe('#67efe6')
    expect(role('caution')).toBe('#ffa9de')
  })
})

describe('every colour reads', () => {
  const grounds = ['bg', 'panel']
  const foregrounds = ['text', 'muted', 'accent', 'in-range', 'caution', 'critical']
  for (const ground of grounds) {
    for (const fg of foregrounds) {
      it(`${fg} on ${ground}: 3:1 or more`, () => {
        expect(contrast(role(fg), role(ground))).toBeGreaterThanOrEqual(3)
      })
    }
  }
  it('the colours that mean something are 25 or more apart in Lab', () => {
    const meaningful = ['accent', 'in-range', 'caution', 'critical']
    for (const a of meaningful) {
      for (const b of meaningful) {
        if (a < b) expect(deltaE(role(a), role(b)), `${a} and ${b}`).toBeGreaterThanOrEqual(25)
      }
    }
  })
  it('the checks themselves are right: black on white is 21:1, and a colour is 0 from itself', () => {
    expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5)
    expect(deltaE('#ff0099', '#ff0099')).toBe(0)
    expect(deltaE('#000000', '#ffffff')).toBeCloseTo(100, 1)
  })
})

describe('no colour is written outside app.css', () => {
  const literal = /#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?(?:[0-9a-fA-F]{2})?\b|\b(?:rgba?|hsla?)\(\s*\d/
  it('finds none in the components or the scripts', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(10) // it really looked
    const found = Object.entries(sources).flatMap(([path, text]) =>
      text.split('\n').flatMap((line, i) => (literal.test(line) ? [`${path}:${i + 1}: ${line.trim()}`] : [])),
    )
    expect(found).toEqual([])
  })
})

describe('shading', () => {
  it('is a role made translucent', () => {
    expect(translucent('#ffa9de', 0.1)).toBe('rgba(255, 169, 222, 0.1)')
    expect(translucent('not a colour', 0.5)).toBe('not a colour')
  })
})
