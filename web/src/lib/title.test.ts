import { describe, expect, it } from 'vitest'
import { routeTitle, titleOf } from './title'
import { route } from './routes'

describe('page titles (M35.5)', () => {
  it('says what the page is, then the site, the empty parts left out', () => {
    expect(titleOf('NHMS Lemons 2026')).toBe('NHMS Lemons 2026 · Bad News Bears')
    expect(titleOf('Sat 17 Oct, 12:00', 'Tha Bearmobile')).toBe('Sat 17 Oct, 12:00 · Tha Bearmobile · Bad News Bears')
    expect(titleOf(null, '', undefined)).toBe('Bad News Bears')
  })

  it('names every page by its address before its data', () => {
    expect(routeTitle(route('/'))).toBe('Bad News Bears')
    expect(routeTitle(route('/cars/tha-bearmobile'))).toBe('tha-bearmobile · live · Bad News Bears')
    expect(routeTitle(route('/cars/tha-bearmobile/sessions'))).toBe('Sessions · tha-bearmobile · Bad News Bears')
    expect(routeTitle(route('/events'))).toBe('Events · Bad News Bears')
    expect(routeTitle(route('/courses/new'))).toBe('New course · Bad News Bears')
    const plain = ['/cars', '/courses', '/drivers', '/users', '/compare', '/privacy', '/terms', '/events/nhms-lemsons-2026', '/drivers/d-9421c351', '/courses/nhms']
      .filter((path) => routeTitle(route(path)) === 'Bad News Bears')
    expect(plain).toEqual([])
  })
})
