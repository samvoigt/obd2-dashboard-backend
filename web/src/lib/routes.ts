/** The site's two pages, from the path. Anything else is the landing page. */
export type Route = { page: 'landing' } | { page: 'car'; slug: string }

const CAR = /^\/cars\/([a-z][a-z0-9-]{1,31})\/?$/

export function route(path: string): Route {
  const m = CAR.exec(path)
  return m?.[1] ? { page: 'car', slug: m[1] } : { page: 'landing' }
}
