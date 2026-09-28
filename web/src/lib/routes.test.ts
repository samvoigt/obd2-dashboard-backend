import { describe, expect, it } from 'vitest'
import { route } from './routes'
import { fetchCars } from './api'

describe('route', () => {
  it('reads a car page from its path', () => {
    expect(route('/cars/yaris')).toEqual({ page: 'car', slug: 'yaris' })
    expect(route('/cars/car-42/')).toEqual({ page: 'car', slug: 'car-42' })
  })
  it("reads a car's sessions, and one session, its id in lower case", () => {
    expect(route('/cars/yaris/sessions')).toEqual({ page: 'sessions', slug: 'yaris' })
    expect(route('/cars/yaris/sessions/')).toEqual({ page: 'sessions', slug: 'yaris' })
    expect(route('/cars/yaris/sessions/7D4C9B1E-2F6A-4E8B-9C3D-5A1B2C3D4E5F')).toEqual({
      page: 'session', slug: 'yaris', id: '7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f',
    })
  })
  it('reads the admin page', () => {
    expect(route('/admin')).toEqual({ page: 'admin' })
    expect(route('/admin/')).toEqual({ page: 'admin' })
  })
  it('reads the admin page\u2019s drivers and events (M14.3)', () => {
    expect(route('/admin/drivers')).toEqual({ page: 'admin-drivers' })
    expect(route('/admin/events/')).toEqual({ page: 'admin-events' })
    expect(route('/admin/events/nhms-october')).toEqual({ page: 'admin-event', id: 'nhms-october' })
    expect(route('/admin/events/new')).toEqual({ page: 'admin-event', id: 'new' })
    expect(route('/admin/events/Bad')).toEqual({ page: 'landing' })
  })
  it('reads the drivers, public (M15.5)', () => {
    expect(route('/drivers')).toEqual({ page: 'drivers' })
    expect(route('/drivers/d-0a1b2c3d')).toEqual({ page: 'driver', id: 'd-0a1b2c3d' })
    expect(route('/drivers/sam')).toEqual({ page: 'landing' })
  })
  it('reads the events, public (M14.5)', () => {
    expect(route('/events')).toEqual({ page: 'events' })
    expect(route('/events/box-day/')).toEqual({ page: 'event', id: 'box-day' })
  })
  it('sends anything else to the landing page', () => {
    for (const path of ['/', '/cars', '/cars/', '/cars/Yaris', '/cars/a/b', '/api/cars', '/admin/x', '/administrator', '/cars/yaris/sessions/nope', '/cars/yaris/sessions/a/b']) {
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

describe('the course editor (M12.4)', () => {
  it('has a list and a page per course, "new" for one not yet saved', () => {
    expect(route('/admin/courses')).toEqual({ page: 'admin-courses' })
    expect(route('/admin/courses/')).toEqual({ page: 'admin-courses' })
    expect(route('/admin/courses/nhms')).toEqual({ page: 'admin-course', id: 'nhms' })
    expect(route('/admin/courses/new')).toEqual({ page: 'admin-course', id: 'new' })
    expect(route('/admin/courses/NHMS')).toEqual({ page: 'landing' }) // not an id
    expect(route('/admin')).toEqual({ page: 'admin' })
  })
})

describe('courses, public (M12.5)', () => {
  it('has a list and a page per course', () => {
    expect(route('/courses')).toEqual({ page: 'courses' })
    expect(route('/courses/nhms/')).toEqual({ page: 'course', id: 'nhms' })
    expect(route('/courses/NHMS')).toEqual({ page: 'landing' })
  })
})

