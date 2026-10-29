import { describe, expect, it } from 'vitest'
import { get } from 'svelte/store'
import { createCrew } from './crew'

function server(crewOf: Record<string, boolean>, passcodes: Record<string, string>) {
  const calls: string[] = []
  const fetcher = (async (url: string, init?: RequestInit) => {
    calls.push(`${init?.method ?? 'GET'} ${url}`)
    const slug = url.split('/')[3]!
    if (url.endsWith('/crew')) return new Response(JSON.stringify({ crew: crewOf[slug] === true }))
    if (init?.method === 'POST') {
      const { passcode } = JSON.parse(init.body as string) as { passcode: string }
      if (passcodes[slug] !== passcode) return new Response(JSON.stringify({ message: 'Wrong passcode.' }), { status: 401 })
      crewOf[slug] = true
      return new Response('{}')
    }
    crewOf[slug] = false
    return new Response(null, { status: 204 })
  }) as unknown as typeof fetch
  return { calls, fetcher }
}

function memory(): Storage {
  const m = new Map<string, string>()
  return { getItem: (k) => m.get(k) ?? null, setItem: (k, v) => void m.set(k, v), removeItem: (k) => void m.delete(k), clear: () => m.clear(), key: () => null, length: 0 }
}

describe('the crew logins (M34.3)', () => {
  it('asks each car, and marks the ones logged in', async () => {
    const { fetcher, calls } = server({ outback: true }, {})
    const crew = createCrew(() => fetcher, () => memory())
    await crew.check(['outback', 'yaris'])
    expect(get(crew).cars).toEqual({ outback: true, yaris: false })
    expect(calls).toEqual(['GET /api/cars/outback/crew', 'GET /api/cars/yaris/crew'])
  })

  it('a refused passcode says why; a good one logs in and is remembered; logout clears that car only', async () => {
    const { fetcher } = server({ outback: true }, { yaris: 'pit-lane' })
    const storage = memory()
    const crew = createCrew(() => fetcher, () => storage)
    await crew.check(['outback'])
    await expect(crew.login('yaris', 'nope')).rejects.toThrow('Wrong passcode.')
    expect(get(crew).cars.yaris).toBeUndefined()
    await crew.login('yaris', 'pit-lane')
    expect(get(crew).cars).toEqual({ outback: true, yaris: true })
    expect(crew.remembered()).toBe('yaris')
    await crew.logout('yaris')
    expect(get(crew).cars).toEqual({ outback: true, yaris: false })
  })

  it('a browser that keeps nothing still logs in, and remembers no car', async () => {
    const { fetcher } = server({}, { yaris: 'pit-lane' })
    const throwing = { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('denied') } } as unknown as Storage
    const crew = createCrew(() => fetcher, () => throwing)
    await crew.login('yaris', 'pit-lane')
    expect(get(crew).cars.yaris).toBe(true)
    expect(crew.remembered()).toBe(null)
  })
})
