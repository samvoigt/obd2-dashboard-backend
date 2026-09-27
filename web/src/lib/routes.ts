/** The site's pages, from the path. Anything else is the landing page. */
export type Route =
  | { page: 'landing' }
  | { page: 'car'; slug: string }
  | { page: 'sessions'; slug: string }
  | { page: 'session'; slug: string; id: string }
  | { page: 'admin' }

const CAR = /^\/cars\/([a-z][a-z0-9-]{1,31})\/?$/
const SESSIONS = /^\/cars\/([a-z][a-z0-9-]{1,31})\/sessions\/?$/
const SESSION = /^\/cars\/([a-z][a-z0-9-]{1,31})\/sessions\/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\/?$/
const ADMIN = /^\/admin\/?$/

export function route(path: string): Route {
  if (ADMIN.test(path)) return { page: 'admin' }
  const list = SESSIONS.exec(path)
  if (list?.[1]) return { page: 'sessions', slug: list[1] }
  const one = SESSION.exec(path)
  if (one?.[1] && one[2]) return { page: 'session', slug: one[1], id: one[2].toLowerCase() }
  const m = CAR.exec(path)
  return m?.[1] ? { page: 'car', slug: m[1] } : { page: 'landing' }
}
