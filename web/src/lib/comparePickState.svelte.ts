/** The pending pick, shared by every "Compare" on the page (M16.4). */
import { loadPick, savePick, type LapPick } from './comparePick'

export const picking = $state<{ pending: LapPick | null }>({ pending: loadPick() })

export function setPick(p: LapPick | null): void {
  picking.pending = p
  savePick(p)
}
