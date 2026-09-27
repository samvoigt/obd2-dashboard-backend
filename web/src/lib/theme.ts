/**
 * The site's colours for what draws on a canvas (M8.1): the chart (uPlot) and
 * the map (Leaflet) need colour strings, not `var(--…)`. They come from
 * `app.css`, which holds every colour on the site; nothing is written here.
 */

export type Role = 'bg' | 'panel' | 'line' | 'text' | 'muted' | 'no-data' | 'accent' | 'in-range' | 'caution' | 'critical'

/** A role's colour, as `app.css` sets it; empty outside a browser. */
export function color(role: Role): string {
  if (typeof document === 'undefined') return ''
  return getComputedStyle(document.documentElement).getPropertyValue(`--${role}`).trim()
}

/** `#rrggbb` at [alpha], as `rgba(…)`, for shading drawn over data. */
export function translucent(hex: string, alpha: number): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) return hex
  const n = parseInt(m[1]!, 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`
}

/**
 * The chart's lines, in order: mint, then light pink, then blue. Mint and blue
 * side by side, as 2-pixel lines, were hard to tell apart (seen in M8.1),
 * though far enough apart as solid colours.
 */
export function seriesColors(): string[] {
  return [color('in-range'), color('caution'), color('accent')]
}

/** The map's speed scale, slow to fast: blue, mint, light pink, hot pink. */
export function speedStops(): string[] {
  return [color('accent'), color('in-range'), color('caution'), color('critical')]
}
