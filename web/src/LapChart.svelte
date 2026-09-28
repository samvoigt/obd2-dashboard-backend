<script lang="ts">
  import uPlot from 'uplot'
  import 'uplot/dist/uPlot.min.css'
  import { onDestroy, onMount } from 'svelte'
  import { lapChartData } from './lib/lapChart'
  import type { CarRace } from './lib/race'
  import { lapTime } from './lib/sessions'
  import { color, seriesColors, translucent } from './lib/theme'

  // The race's lap times (M16.3): by lap number, a line per car; stints shaded, stops and flags marked.
  let { cars, carName, driverName }: { cars: CarRace[]; carName: (slug: string) => string; driverName: (id: string | null) => string } = $props()

  let box: HTMLDivElement
  let plot: uPlot | null = null
  const chart = $derived(lapChartData(cars))

  function options(width: number): uPlot.Options {
    const COLORS = seriesColors()
    const muted = color('muted')
    const line = color('line')
    const shade = [translucent(color('accent'), 0.1), translucent(color('accent'), 0.03)]
    const markColor = { stop: color('caution'), green: color('in-range'), flag: color('text') }
    const d = chart
    return {
      width,
      height: 260,
      cursor: { drag: { x: false, y: false } },
      scales: { x: { time: false } },
      legend: { show: true },
      hooks: {
        draw: [(u) => {
          const { ctx, bbox } = u
          ctx.save()
          // Stints, shaded in turn, each from half a lap before its first to half after its last.
          d.stints.forEach((s, i) => {
            const x0 = u.valToPos(s.from - 0.5, 'x', true)
            const x1 = u.valToPos(s.to + 0.5, 'x', true)
            ctx.fillStyle = shade[i % 2]!
            ctx.fillRect(Math.max(bbox.left, x0), bbox.top, Math.min(bbox.left + bbox.width, x1) - Math.max(bbox.left, x0), bbox.height)
          })
          ctx.lineWidth = 1
          for (const m of d.marks) {
            const x = Math.round(u.valToPos(m.lap, 'x', true)) + 0.5
            ctx.strokeStyle = markColor[m.kind]
            ctx.setLineDash(m.kind === 'stop' ? [4, 3] : [])
            ctx.beginPath()
            ctx.moveTo(x, bbox.top)
            ctx.lineTo(x, bbox.top + bbox.height)
            ctx.stroke()
          }
          ctx.restore()
        }],
      },
      axes: [
        { stroke: muted, grid: { stroke: line }, ticks: { stroke: line }, label: 'Lap', values: (_u, ticks) => ticks.map((t) => (Number.isInteger(t) ? String(t) : '')) },
        { stroke: muted, grid: { stroke: line }, ticks: { stroke: line }, size: 70, values: (_u, ticks) => ticks.map((t) => lapTime(t)) },
      ],
      series: [
        { label: 'Lap', value: (_u: uPlot, v: number | null) => (v == null ? '--' : String(v)) },
        ...cars.map((c, i) => ({
          label: carName(c.car),
          stroke: COLORS[i % COLORS.length],
          width: 2,
          points: { show: true, size: 6 },
          value: (_u: uPlot, v: number | null, _s: number, idx: number | null) =>
            v == null ? '--' : `${lapTime(v)}${idx != null && chart.capped[i]?.includes(chart.x[idx]!) ? ' (slower, off the scale)' : ''}`,
        })),
      ],
    }
  }

  function draw() {
    plot?.destroy()
    plot = new uPlot(options(box.clientWidth || 600), [chart.x, ...chart.ys] as uPlot.AlignedData, box)
  }

  onMount(() => {
    draw()
    const resize = new ResizeObserver(() => plot?.setSize({ width: box.clientWidth, height: 260 }))
    resize.observe(box)
    return () => resize.disconnect()
  })

  $effect(() => {
    void chart
    if (plot) draw()
  })

  onDestroy(() => plot?.destroy())
</script>

<div class="lapchart" bind:this={box}></div>
{#if chart.stints.length > 0}
  <p class="muted small">
    Stints, shaded in turn: {chart.stints.map((s, i) => `${i + 1} ${driverName(s.driver)} (laps ${s.from}–${s.to})`).join(' · ')}.
    Dashed lines are stops; laps over 130% of the best are drawn at the top.
  </p>
{/if}

<style>
  .lapchart { width: 100%; min-height: 260px; }
  .lapchart :global(.u-legend) { color: var(--text); font-size: 0.9rem; }
</style>
