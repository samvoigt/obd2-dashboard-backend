/** A car's live state, as the server names it (`/api/cars`, SSE `status`). */
export type State = 'live' | 'stale' | 'no_session' | 'offline'

export const stateLabel: Record<State, string> = {
  live: 'Live',
  stale: 'Behind',
  no_session: 'Connected, no session',
  offline: 'Offline',
}

export function isState(s: string): s is State {
  return s in stateLabel
}
