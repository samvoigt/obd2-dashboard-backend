<script lang="ts">
  import uPlot from 'uplot'
  import 'uplot/dist/uPlot.min.css'
  import { onDestroy, onMount } from 'svelte'

  let {
    data,
    names,
    units,
  }: { data: [number[], ...(number | null)[][]]; names: string[]; units: string[] } = $props()

  const COLORS = ['#3ddc84', '#5aa0ff', '#ffb020']
  let box: HTMLDivElement
  let plot: uPlot | null = null
  let shape = ''

  function options(width: number): uPlot.Options {
    // A second axis only when the second signal's unit differs from the first's.
    const twoScales = names.length > 1 && units[1] !== units[0]
    return {
      width,
      height: 280,
      cursor: { drag: { x: false, y: false } },
      legend: { show: true },
      scales: { x: { time: true } },
      axes: [
        // Clock time, 24-hour, to the second: "14:32:05". Short enough, with room between ticks,
        // not to collide on a phone (found looking at the page at 390 px).
        {
          stroke: '#8b97a5', grid: { stroke: '#262e38' }, ticks: { stroke: '#262e38' }, space: 70,
          values: (_u, ticks) =>
            ticks.map((t) => new Date(t * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })),
        },
        { stroke: '#8b97a5', grid: { stroke: '#262e38' }, ticks: { stroke: '#262e38' }, label: units[0] ?? '', scale: 'a' },
        ...(twoScales ? [{ stroke: '#8b97a5', side: 1, grid: { show: false }, label: units[1] ?? '', scale: 'b' } as uPlot.Axis] : []),
      ],
      series: [
        {},
        ...names.map((n, i) => ({
          label: units[i] ? `${n} (${units[i]})` : n,
          stroke: COLORS[i % COLORS.length],
          width: 2,
          scale: twoScales && i === 1 ? 'b' : 'a',
          spanGaps: false, // a gap is a gap, never bridged
          points: { show: false },
        })),
      ],
    }
  }

  function rebuild() {
    plot?.destroy()
    plot = new uPlot(options(box.clientWidth || 600), data as uPlot.AlignedData, box)
    shape = names.join('|') + '/' + units.join('|')
  }

  onMount(() => {
    rebuild()
    const resize = new ResizeObserver(() => plot?.setSize({ width: box.clientWidth, height: 280 }))
    resize.observe(box)
    return () => resize.disconnect()
  })

  $effect(() => {
    // New signals chosen: a new chart. New data: just the data.
    const nextShape = names.join('|') + '/' + units.join('|')
    if (!plot) return
    if (nextShape !== shape) rebuild()
    else plot.setData(data as uPlot.AlignedData)
  })

  onDestroy(() => plot?.destroy())
</script>

<div class="chart" bind:this={box}></div>

<style>
  .chart { width: 100%; min-height: 280px; }
  .chart :global(.u-legend) { color: var(--text); font-size: 0.9rem; }
</style>
