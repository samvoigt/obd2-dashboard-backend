/** The site's pages, from the path. Anything else is the landing page. */
export type Route = { page: 'landing' } | { page: 'car'; slug: string } | { page: 'admin' }

const CAR = /^\/cars\/([a-z][a-z0-9-]{1,31})\/?$/
const ADMIN = /^\/admin\/?$/

export function route(path: string): Route {
  if (ADMIN.test(path)) return { page: 'admin' }
  const m = CAR.exec(path)
  return m?.[1] ? { page: 'car', slug: m[1] } : { page: 'landing' }
}
