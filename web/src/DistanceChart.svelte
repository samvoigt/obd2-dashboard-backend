<script lang="ts">
  import uPlot from 'uplot'
  import 'uplot/dist/uPlot.min.css'
  import { onDestroy, onMount } from 'svelte'
  import { color, seriesColors } from './lib/theme'

  // Signals by distance round the lap (M16.4): the two laps overlaid, the cursor's metre passed out.
  let { x, ys, labels, unit, height = 200, onCursor, zeroLine = false }: {
    x: number[]
    ys: (number | null)[][]
    labels: string[]
    unit: string
    height?: number
    onCursor?: (metre: number | null) => void
    /** A line at zero (the delta's). */
    zeroLine?: boolean
  } = $props()

  let box: HTMLDivElement
  let plot: uPlot | null = null

  function options(width: number): uPlot.Options {
    const COLORS = seriesColors()
    const muted = color('muted')
    const line = color('line')
    return {
      width,
      height,
      scales: { x: { time: false } },
      cursor: { drag: { x: true, y: false }, sync: { key: 'lap-compare' } },
      legend: { show: true },
      hooks: {
        setCursor: [(u) => {
          const i = u.cursor.idx
          onCursor?.(i == null ? null : (u.data[0][i] ?? null))
        }],
        draw: zeroLine ? [(u) => {
          const y = Math.round(u.valToPos(0, 'y', true)) + 0.5
          const { ctx, bbox } = u
          if (y < bbox.top || y > bbox.top + bbox.height) return
          ctx.save()
          ctx.strokeStyle = muted
          ctx.beginPath()
          ctx.moveTo(bbox.left, y)
          ctx.lineTo(bbox.left + bbox.width, y)
          ctx.stroke()
          ctx.restore()
        }] : [],
      },
      axes: [
        { stroke: muted, grid: { stroke: line }, ticks: { stroke: line }, label: 'Metres round the lap' },
        { stroke: muted, grid: { stroke: line }, ticks: { stroke: line }, label: unit },
      ],
      series: [
        { label: 'Metre', value: (_u: uPlot, v: number | null) => (v == null ? '--' : `${Math.round(v)} m`) },
        ...labels.map((l, i) => ({
          label: l,
          stroke: COLORS[i % COLORS.length],
          width: 2,
          spanGaps: false,
          points: { show: false },
          value: (_u: uPlot, v: number | null) => (v == null ? '--' : `${v.toFixed(unit === 's' ? 3 : 1)} ${unit}`),
        })),
      ],
    }
  }

  function draw() {
    plot?.destroy()
    plot = new uPlot(options(box.clientWidth || 600), [x, ...ys] as uPlot.AlignedData, box)
  }

  onMount(() => {
    draw()
    const resize = new ResizeObserver(() => plot?.setSize({ width: box.clientWidth, height }))
    resize.observe(box)
    return () => resize.disconnect()
  })

  $effect(() => {
    void x
    void ys
    if (plot) draw()
  })

  onDestroy(() => plot?.destroy())
</script>

<div class="chart" bind:this={box}></div>

<style>
  .chart { width: 100%; }
  .chart :global(.u-legend) { color: var(--text); font-size: 0.9rem; }
</style>
