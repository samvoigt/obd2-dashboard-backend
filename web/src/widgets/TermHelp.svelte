<script lang="ts">
  import { onMount } from 'svelte'
  import { TERMS, type TermKey } from '../lib/terms'

  // A term and its meaning (M36.9): the word, with a small "?" that shows the glossary's sentence on a tap
  // (a title isn't there on a phone). [word] overrides the term's own words, as a table's short heading.
  // The sentence is placed on the window, not in the table, whose scrolling box would cut it off.
  let { term, word }: { term: TermKey; word?: string } = $props()
  let at: { left: number; top: number } | null = $state(null)
  let button: HTMLButtonElement | undefined = $state()
  const t = $derived(TERMS[term])

  function toggle(e: MouseEvent) {
    e.stopPropagation()
    if (at || !button) return void (at = null)
    const r = button.getBoundingClientRect()
    at = { left: Math.max(8, Math.min(r.left, window.innerWidth - 288)), top: r.bottom + 4 }
  }

  onMount(() => {
    const close = () => (at = null)
    window.addEventListener('click', close)
    window.addEventListener('scroll', close, true)
    window.addEventListener('keydown', close)
    return () => { window.removeEventListener('click', close); window.removeEventListener('scroll', close, true); window.removeEventListener('keydown', close) }
  })
</script>

<span class="term" title={t.means}>{word ?? t.word}<button bind:this={button} class="q" aria-label={`What is ${t.word}?`} aria-expanded={at !== null} onclick={toggle}><span class="dot">?</span></button></span>{#if at}<span class="means" role="note" style:left={`${at.left}px`} style:top={`${at.top}px`}>{t.means}</span>{/if}

<style>
  .term { white-space: nowrap; }
  /* A 44 px target (M31.5) around a small circle, its margins taken back so the line keeps its height. */
  .q {
    display: inline-flex; align-items: center; justify-content: center; vertical-align: middle;
    min-width: 30px; min-height: 44px; margin: -14px -4px -14px -2px; padding: 0; border: none; background: none; cursor: pointer;
  }
  .dot {
    display: inline-block; width: 1.3em; height: 1.3em; line-height: 1.2em; font-size: 0.72rem; text-align: center;
    border-radius: 50%; border: 1px solid var(--line); background: var(--bg); color: var(--muted);
  }
  .q:hover .dot, .q[aria-expanded='true'] .dot { color: var(--text); border-color: var(--muted); }
  .means {
    position: fixed; z-index: 1100; width: max-content; max-width: min(280px, calc(100vw - 16px));
    white-space: normal; font-size: 0.85rem; font-weight: 400; text-align: left; color: var(--text);
    background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 6px 8px;
  }
</style>
