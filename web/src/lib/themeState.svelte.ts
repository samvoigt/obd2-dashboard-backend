/**
 * The viewer's theme (M30.6), shared by every page, remembered in their browser.
 * What draws on a canvas reads [themeState] and draws again when it changes.
 */
import { applyTheme, loadTheme, saveTheme, type Theme } from './theme'

export const themeState = $state<{ theme: Theme }>({ theme: loadTheme() })

export function setTheme(theme: Theme): void {
  themeState.theme = theme
  saveTheme(theme)
  applyTheme(theme)
}
