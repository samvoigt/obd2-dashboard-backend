/**
 * The crew's messages on the page (M5), rebuilt from the crew stream's
 * `messages` and `message` events. Pure, so it is tested without a browser.
 */

export type MessageStateName = 'queued' | 'received' | 'displayed' | 'cleared' | 'expired' | 'replaced'

export interface CrewMessage {
  id: string
  text: string
  preset?: string
  state: MessageStateName
  sentAt: number
  expiresAt: number
  receivedAt?: number
  displayedAt?: number
  endedAt?: number
  replacedBy?: string
}

/** The contract's presets (§5.4), with the words the driver sees. */
export const PRESETS: { preset: string; text: string }[] = [
  { preset: 'pit', text: 'PIT NOW' },
  { preset: 'box', text: 'BOX THIS LAP' },
  { preset: 'fuel', text: 'FUEL' },
  { preset: 'push', text: 'PUSH' },
  { preset: 'slow', text: 'SLOW DOWN' },
]

/** Lifetimes the sender may choose; the first is the default (Sam: until cleared, capped at 30 minutes). */
export const LIFETIMES: { seconds: number; label: string }[] = [
  { seconds: 1800, label: 'Until cleared (30 min)' },
  { seconds: 600, label: '10 minutes' },
  { seconds: 300, label: '5 minutes' },
  { seconds: 120, label: '2 minutes' },
  { seconds: 60, label: '1 minute' },
]

export const MAX_TEXT = 40

/** Characters, not UTF-16 units, as the server counts them. */
export function length(text: string): number {
  return [...text.trim()].length
}

export function sendable(text: string): boolean {
  const n = length(text)
  return n >= 1 && n <= MAX_TEXT
}

const ACTIVE: MessageStateName[] = ['queued', 'received', 'displayed']

/** The `messages` event is the whole list; a `message` event updates one, by id. Newest first. */
export function applyList(list: CrewMessage[]): CrewMessage[] {
  return [...list].sort((a, b) => b.sentAt - a.sentAt)
}

export function applyOne(list: CrewMessage[], m: CrewMessage): CrewMessage[] {
  return applyList([m, ...list.filter((x) => x.id !== m.id)])
}

/**
 * The message the driver should be seeing now, if any: the newest still in an
 * active state and not past its time on the server's clock (the page's clock
 * plus [offsetMs], the page minus the server).
 */
export function current(list: CrewMessage[], localNow: number, offsetMs: number): CrewMessage | null {
  const serverNow = localNow - offsetMs
  return list.find((m) => ACTIVE.includes(m.state) && m.expiresAt > serverNow) ?? null
}

/** What the crew is told about a message's state, in plain words. */
export function describe(m: CrewMessage, localNow: number, offsetMs: number): string {
  const serverNow = localNow - offsetMs
  if (ACTIVE.includes(m.state) && m.expiresAt <= serverNow) return 'Expired'
  switch (m.state) {
    case 'queued': return 'Waiting for the car'
    case 'received': return 'On the tablet, not on screen'
    case 'displayed': return 'On the driver\'s screen'
    case 'cleared': return 'Cleared'
    case 'expired': return 'Expired'
    case 'replaced': return 'Replaced'
  }
}

/** "12 s", "3 min", "1 h 5 min": how long ago the message was sent. */
export function age(m: CrewMessage, localNow: number, offsetMs: number): string {
  const s = Math.max(0, Math.floor((localNow - offsetMs - m.sentAt) / 1000))
  if (s < 60) return `${s} s`
  const min = Math.floor(s / 60)
  if (min < 60) return `${min} min`
  return `${Math.floor(min / 60)} h ${min % 60} min`
}
