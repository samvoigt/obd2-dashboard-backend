/** The site's pages, from the path. Anything else is the landing page. */
export type Route =
  | { page: 'landing' }
  | { page: 'cars' }
  | { page: 'car'; slug: string }
  | { page: 'manage'; slug: string }
  | { page: 'sessions'; slug: string }
  | { page: 'session'; slug: string; id: string }
  | { page: 'courses' }
  | { page: 'course'; id: string }
  | { page: 'course-edit'; id: string }
  | { page: 'events' }
  | { page: 'event'; id: string }
  | { page: 'event-edit'; id: string }
  | { page: 'drivers' }
  | { page: 'driver'; id: string }
  | { page: 'compare' }
  | { page: 'users' }
  | { page: 'preview'; slug: string }

const ID = '([a-z][a-z0-9-]{1,31})'
const CARS = /^\/cars\/?$/
const CAR = new RegExp(`^/cars/${ID}/?$`)
const MANAGE = new RegExp(`^/cars/${ID}/manage/?$`)
const SESSIONS = new RegExp(`^/cars/${ID}/sessions/?$`)
const SESSION = new RegExp(`^/cars/${ID}/sessions/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/?$`)
const COURSES = /^\/courses\/?$/
const COURSE = new RegExp(`^/courses/${ID}/?$`)
const COURSE_EDIT = new RegExp(`^/courses/${ID}/edit/?$`)
const EVENTS = /^\/events\/?$/
const EVENT = new RegExp(`^/events/${ID}/?$`)
const EVENT_EDIT = new RegExp(`^/events/${ID}/edit/?$`)
const DRIVERS = /^\/drivers\/?$/
const DRIVER = /^\/drivers\/(d-[0-9a-f]{8})\/?$/
const COMPARE = /^\/compare\/?$/
const USERS = /^\/users\/?$/

export function route(path: string): Route {
  // Courses (M12.5); edited where they're shown when signed in (M21.3), `new` for one not yet saved.
  if (COURSES.test(path)) return { page: 'courses' }
  const courseEdit = COURSE_EDIT.exec(path)
  if (courseEdit?.[1]) return { page: 'course-edit', id: courseEdit[1] }
  if (path.replace(/\/$/, '') === '/courses/new') return { page: 'course-edit', id: 'new' }
  const course = COURSE.exec(path)
  if (course?.[1]) return { page: 'course', id: course[1] }
  // Events and their results (M14.5), edited the same way (M21.3).
  if (EVENTS.test(path)) return { page: 'events' }
  const eventEdit = EVENT_EDIT.exec(path)
  if (eventEdit?.[1]) return { page: 'event-edit', id: eventEdit[1] }
  if (path.replace(/\/$/, '') === '/events/new') return { page: 'event-edit', id: 'new' }
  const event = EVENT.exec(path)
  if (event?.[1]) return { page: 'event', id: event[1] }
  // Drivers (M15.5).
  if (DRIVERS.test(path)) return { page: 'drivers' }
  const driver = DRIVER.exec(path)
  if (driver?.[1]) return { page: 'driver', id: driver[1] }
  // Two laps compared (M16.4); the laps are in the query.
  if (COMPARE.test(path)) return { page: 'compare' }
  // The invited users (M23), for a master admin.
  if (USERS.test(path)) return { page: 'users' }
  // Every dashboard widget on one page (M8.2), in the dev server only: the build drops it.
  const preview = import.meta.env.DEV ? /^\/dev\/widgets\/([a-z][a-z0-9-]{1,31})\/?$/.exec(path) : null
  if (preview?.[1]) return { page: 'preview', slug: preview[1] }
  // The cars (M21.5), and each car's live feed, management and sessions.
  if (CARS.test(path)) return { page: 'cars' }
  const manage = MANAGE.exec(path)
  if (manage?.[1]) return { page: 'manage', slug: manage[1] }
  const list = SESSIONS.exec(path)
  if (list?.[1]) return { page: 'sessions', slug: list[1] }
  const one = SESSION.exec(path)
  if (one?.[1] && one[2]) return { page: 'session', slug: one[1], id: one[2].toLowerCase() }
  const m = CAR.exec(path)
  return m?.[1] ? { page: 'car', slug: m[1] } : { page: 'landing' }
}
