/** The viewer's units, shared by every widget on the page, remembered in their browser (M8). */
import { loadSystem, saveSystem, type System } from './units'

export const units = $state<{ system: System }>({ system: loadSystem() })

export function setSystem(system: System): void {
  units.system = system
  saveSystem(system)
}
