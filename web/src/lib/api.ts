import { readStanding, type Standing } from './standing'
import { isState, type State } from './state'

export interface CarSummary {
  slug: string
  name: string
  state: State
  /** Said only to someone signed in (M25). */
  hidden?: boolean
  /** Where it stands, only while it streams (M36.1): the same as its stream's `timing`. */
  standing?: Standing | null
}

/** The landing page's list. Unknown states read as offline rather than breaking the page. */
export async function fetchCars(fetcher: typeof fetch = fetch): Promise<CarSummary[]> {
  const response = await fetcher('/api/cars')
  if (!response.ok) throw new Error(`GET /api/cars: ${response.status}`)
  const cars = (await response.json()) as Array<{ slug: string; name: string; state: string; hidden?: boolean; standing?: unknown }>
  return cars.map((c) => ({
    slug: c.slug, name: c.name, state: isState(c.state) ? c.state : 'offline', hidden: c.hidden === true, standing: readStanding(c.standing),
  }))
}

let names: Promise<Map<string, string>> | null = null

/**
 * **Each car's name by slug** (M36.5), from `/api/cars`, asked once a page
 * load: the car pages name their car by it. Asked again after a failure.
 */
export function carNames(fetcher: typeof fetch = fetch): Promise<Map<string, string>> {
  names ??= fetchCars(fetcher).then((cars) => new Map(cars.map((c) => [c.slug, c.name])))
  names.catch(() => (names = null))
  return names
}

/** A car's name, or null when it can't be had (the page keeps the slug). */
export async function carName(slug: string, fetcher: typeof fetch = fetch): Promise<string | null> {
  return (await carNames(fetcher).catch(() => null))?.get(slug) ?? null
}
