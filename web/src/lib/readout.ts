/** How the dashboard writes a reading (M8): decimals by unit, "—" for none. */
import { shownUnit, toShown, type System } from './units'

const DECIMALS: Record<string, number> = { 'V': 1, 'L/h': 1, 'gal/h': 1, 'g/s': 1, 'km': 1, 'mi': 1, 'mA': 0 }

export function readout(value: number | null, unit: string, system: System): { text: string; unit: string } {
  const shown = shownUnit(unit, system)
  if (value === null || !Number.isFinite(value)) return { text: '—', unit: shown }
  const v = toShown(value, unit, system)
  return { text: v.toFixed(DECIMALS[shown] ?? 0), unit: shown }
}
