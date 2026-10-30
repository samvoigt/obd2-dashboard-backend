/**
 * **Every page says what it is in its tab** (M35.5): "<what> · Bad News
 * Bears". The router names a page at once by its kind; a page whose name
 * comes with its data (a session, an event, a driver, a course) names itself
 * once it has it. Pure, but for [setTitle].
 */
import type { Route } from './routes'

export const SITE = 'Bad News Bears'

/** "<parts> · Bad News Bears", the empty parts left out. */
export function titleOf(...parts: (string | null | undefined)[]): string {
  return [...parts.filter((p): p is string => !!p && p.trim() !== ''), SITE].join(' · ')
}

export function setTitle(...parts: (string | null | undefined)[]): void {
  document.title = titleOf(...parts)
}

/** A page's title before its data: what the address alone says. */
export function routeTitle(r: Route): string {
  switch (r.page) {
    case 'landing': return titleOf()
    case 'cars': return titleOf('Live')
    case 'car': return titleOf(r.slug, 'live')
    case 'manage': return titleOf('Manage', r.slug)
    case 'sessions': return titleOf('Sessions', r.slug)
    case 'session': return titleOf('Session', r.slug)
    case 'courses': return titleOf('Courses')
    case 'course': return titleOf('Course')
    case 'course-edit': return titleOf(r.id === 'new' ? 'New course' : 'Edit course')
    case 'events': return titleOf('Results')
    case 'event': return titleOf('Event')
    case 'event-edit': return titleOf(r.id === 'new' ? 'New event' : 'Edit event')
    case 'drivers': return titleOf('Drivers')
    case 'driver': return titleOf('Driver')
    case 'users': return titleOf('Users')
    case 'compare': return titleOf('Compare laps')
    case 'privacy': return titleOf('Privacy')
    case 'terms': return titleOf('Terms')
    default: return titleOf()
  }
}
