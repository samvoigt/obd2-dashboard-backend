import { describe, expect, it } from 'vitest'
import { route } from './routes'
import { fetchCars } from './api'

describe('route', () => {
  it('reads a car page from its path', () => {
    expect(route('/cars/yaris')).toEqual({ page: 'car', slug: 'yaris' })
    expect(route('/cars/car-42/')).toEqual({ page: 'car', slug: 'car-42' })
  })
  it('reads the admin page', () => {
    expect(route('/admin')).toEqual({ page: 'admin' })
    expect(route('/admin/')).toEqual({ page: 'admin' })
  })
  it('sends anything else to the landing page', () => {
    for (const path of ['/', '/cars', '/cars/', '/cars/Yaris', '/cars/a/b', '/api/cars', '/admin/x', '/administrator']) {
      expect(route(path)).toEqual({ page: 'landing' })
    }
  })
})

describe('fetchCars', () => {
  it('reads unknown states as offline', async () => {
    const fake = (async () => new Response(JSON.stringify([
      { slug: 'yaris', name: 'Yaris', state: 'live' },
      { slug: 'odd', name: 'Odd', state: 'warp' },
    ]))) as unknown as typeof fetch
    expect(await fetchCars(fake)).toEqual([
      { slug: 'yaris', name: 'Yaris', state: 'live' },
      { slug: 'odd', name: 'Odd', state: 'offline' },
    ])
  })
  it('throws on a failed request', async () => {
    const fake = (async () => new Response('', { status: 500 })) as unknown as typeof fetch
    await expect(fetchCars(fake)).rejects.toThrow('500')
  })
})
