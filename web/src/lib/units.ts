/**
 * Metric or US, as each viewer chooses (M8, Sam): the tablet always sends the
 * contract's canonical units (§4.1), and the page converts for display. A unit
 * with no US counterpart is shown as sent.
 */

export type System = 'metric' | 'us'

interface Conversion {
  unit: string
  to: (v: number) => number
  from: (v: number) => number
}

const scale = (unit: string, k: number): Conversion => ({ unit, to: (v) => v * k, from: (v) => v / k })

const US: Record<string, Conversion> = {
  'km/h': scale('mph', 0.621371),
  'km': scale('mi', 0.621371),
  '°C': { unit: '°F', to: (v) => v * 9 / 5 + 32, from: (v) => (v - 32) * 5 / 9 },
  'kPa': scale('psi', 0.1450377),
  'L/h': scale('gal/h', 0.2641721),
  'm': scale('ft', 3.28084),
}

/** The unit a reading in [unit] is shown in. */
export function shownUnit(unit: string, system: System): string {
  return system === 'us' ? US[unit]?.unit ?? unit : unit
}

/** A reading in [unit], as shown. */
export function toShown(value: number, unit: string, system: System): number {
  return system === 'us' && US[unit] ? US[unit].to(value) : value
}

/** A shown value back in [unit]: for ranges and zones, which are kept as the tablet sends. */
export function fromShown(value: number, unit: string, system: System): number {
  return system === 'us' && US[unit] ? US[unit].from(value) : value
}

const KEY = 'units'

/** The viewer's choice, remembered in their browser; metric if none, or if storage is refused. */
export function loadSystem(): System {
  try {
    return globalThis.localStorage?.getItem(KEY) === 'us' ? 'us' : 'metric'
  } catch {
    return 'metric'
  }
}

export function saveSystem(system: System): void {
  try {
    globalThis.localStorage?.setItem(KEY, system)
  } catch {
    // A private window, or storage refused: the choice lasts this page only.
  }
}

/** A chart's column in the viewer's units; gaps (null) and holes (undefined) kept as they are. */
export function columnShown(values: (number | null | undefined)[], unit: string, system: System): (number | null | undefined)[] {
  const conversion = system === 'us' ? US[unit] : undefined
  if (!conversion) return values
  return values.map((v) => (typeof v === 'number' ? conversion.to(v) : v))
}

