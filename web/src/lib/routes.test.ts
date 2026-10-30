import { describe, expect, it } from 'vitest'
import { crumbs, route } from './routes'
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
  it('reads the Cars page and a car\u2019s management (M21.5)', () => {
    expect(route('/cars')).toEqual({ page: 'cars' })
    expect(route('/cars/')).toEqual({ page: 'cars' })
    expect(route('/cars/yaris/manage')).toEqual({ page: 'manage', slug: 'yaris' })
    expect(route('/cars/yaris/manage/')).toEqual({ page: 'manage', slug: 'yaris' })
  })
  it('reads the editors where courses and events are shown (M21.3)', () => {
    expect(route('/courses/new')).toEqual({ page: 'course-edit', id: 'new' })
    expect(route('/courses/nhms/edit')).toEqual({ page: 'course-edit', id: 'nhms' })
    expect(route('/courses/nhms')).toEqual({ page: 'course', id: 'nhms' })
    expect(route('/events/new/')).toEqual({ page: 'event-edit', id: 'new' })
    expect(route('/events/nhms-october/edit')).toEqual({ page: 'event-edit', id: 'nhms-october' })
    expect(route('/events/Bad/edit')).toEqual({ page: 'landing' })
  })
  it('reads the users page (M23)', () => {
    expect(route('/users')).toEqual({ page: 'users' })
    expect(route('/users/')).toEqual({ page: 'users' })
  })
  it('reads the privacy policy and the terms', () => {
    expect(route('/privacy')).toEqual({ page: 'privacy' })
    expect(route('/terms/')).toEqual({ page: 'terms' })
  })
  it('reads the compare page (M16.4)', () => {
    expect(route('/compare')).toEqual({ page: 'compare' })
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
    // The admin page is gone (M21): its paths are anything else now.
    for (const path of ['/', '/cars/Yaris', '/cars/a/b', '/api/cars', '/admin', '/admin/courses/nhms', '/admin/x', '/administrator', '/cars/yaris/sessions/nope', '/cars/yaris/sessions/a/b']) {
      expect(route(path)).toEqual({ page: 'landing' })
    }
  })
})

describe('fetchCars', () => {
  it('reads unknown states as offline', async () => {
    const fake = (async () => new Response(JSON.stringify([
      { slug: 'yaris', name: 'Yaris', state: 'live' },
      { slug: 'odd', name: 'Odd', state: 'warp', hidden: true },
    ]))) as unknown as typeof fetch
    expect(await fetchCars(fake)).toEqual([
      { slug: 'yaris', name: 'Yaris', state: 'live', hidden: false, standing: null },
      { slug: 'odd', name: 'Odd', state: 'offline', hidden: true, standing: null }, // hidden: said only to someone signed in (M25)
    ])
  })
  it("carries a streaming car's standing as its stream's timing is read (M36.1)", async () => {
    const fake = (async () => new Response(JSON.stringify([
      { slug: 'yaris', name: 'Yaris', state: 'live', standing: { driver: { name: 'Sam', code: 'SAM' }, race: { lap: 58 }, best: { time: 94.5 } } },
    ]))) as unknown as typeof fetch
    expect((await fetchCars(fake))[0]?.standing).toEqual({ driver: { name: 'Sam', code: 'SAM', since: undefined }, race: { lap: 58, leftPits: undefined }, best: { time: 94.5, driver: undefined } })
  })
  it('throws on a failed request', async () => {
    const fake = (async () => new Response('', { status: 500 })) as unknown as typeof fetch
    await expect(fetchCars(fake)).rejects.toThrow('500')
  })
})

describe('the course editor (M12.4), where courses are shown (M21.3)', () => {
  it('has a page per course, "new" for one not yet saved', () => {
    expect(route('/courses/nhms/edit/')).toEqual({ page: 'course-edit', id: 'nhms' })
    expect(route('/courses/new')).toEqual({ page: 'course-edit', id: 'new' })
    expect(route('/courses/NHMS/edit')).toEqual({ page: 'landing' }) // not an id
  })
})

describe('courses, public (M12.5)', () => {
  it('has a list and a page per course', () => {
    expect(route('/courses')).toEqual({ page: 'courses' })
    expect(route('/courses/nhms/')).toEqual({ page: 'course', id: 'nhms' })
    expect(route('/courses/NHMS')).toEqual({ page: 'landing' })
  })
})


describe('breadcrumbs (M36.5)', () => {
  const session = '7d4c9b1e-2f6a-4e8b-9c3d-5a1b2c3d4e5f'
  const labels = (path: string, names = {}) => crumbs(route(path), names).map((c) => (c.href ? `${c.label}<${c.href}>` : c.label)).join(' › ')

  it("names a car's pages by the car, its slug until the name comes", () => {
    expect(labels('/cars/yaris', { car: 'Tha Bearmobile' })).toBe('Live</cars> › Tha Bearmobile')
    expect(labels('/cars/yaris')).toBe('Live</cars> › yaris')
    expect(labels('/cars/yaris/sessions', { car: 'Tha Bearmobile' })).toBe('Live</cars> › Tha Bearmobile</cars/yaris> › Sessions')
    expect(labels(`/cars/yaris/sessions/${session}`, { car: 'Tha Bearmobile', session: 'Sun 18 Oct, 12:00' }))
      .toBe('Live</cars> › Tha Bearmobile</cars/yaris> › Sessions</cars/yaris/sessions> › Sun 18 Oct, 12:00')
    expect(labels(`/cars/yaris/sessions/${session}`)).toBe('Live</cars> › yaris</cars/yaris> › Sessions</cars/yaris/sessions> › Session')
    expect(labels('/cars/yaris/manage', { car: 'Tha Bearmobile' })).toBe('Live</cars> › Tha Bearmobile</cars/yaris> › Manage')
  })

  it('puts events under Results, drivers and courses under theirs, editors under what they edit', () => {
    expect(labels('/events/nhms', { event: 'NHMS October' })).toBe('Results</events> › NHMS October')
    expect(labels('/events/nhms/edit', { event: 'NHMS October' })).toBe('Results</events> › NHMS October</events/nhms> › Edit')
    expect(labels('/events/new')).toBe('Results</events> › New event')
    expect(labels('/compare')).toBe('Results</events> › Two laps compared')
    expect(labels('/drivers/d-0000000a', { driver: 'Sam Voigt' })).toBe('Drivers</drivers> › Sam Voigt')
    expect(labels('/courses/nhms', { course: 'NHMS' })).toBe('Courses</courses> › NHMS')
    expect(labels('/courses/nhms/edit')).toBe('Courses</courses> › nhms</courses/nhms> › Edit')
    expect(labels('/courses/new')).toBe('Courses</courses> › New course')
  })

  it('has none on a top-level page', () => {
    for (const path of ['/', '/cars', '/events', '/drivers', '/courses', '/users']) expect(crumbs(route(path))).toEqual([])
  })
})
