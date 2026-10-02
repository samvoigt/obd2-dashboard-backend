import { describe, expect, it } from 'vitest'
import {
  accessOf, addable, addEditor, allowed, allowedFor, creatorText, editorProblem, editorsChanged, inviteProblem, mine, NOTHING,
  removeEditor, roleText, thingLink, type InvitedUser, type Sharing,
} from './access'

const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

describe('what a page may show for a thing (M23)', () => {
  it('nothing beyond the public page for a thing not in your admin list', () => {
    expect(allowed(undefined)).toEqual(NOTHING)
    expect(allowed(null)).toEqual(NOTHING)
  })

  it('edit for anything listed; share and delete only where the server says', () => {
    const editor = { creator: 'ann@x.com', editors: ['ed@x.com'], canShare: false, canDelete: false }
    expect(allowed(editor)).toEqual({ edit: true, share: false, delete: false })
    expect(allowed({ ...editor, canShare: true, canDelete: true })).toEqual({ edit: true, share: true, delete: true })
  })

  it('reads an item’s access leniently: missing fields allow nothing; no access at all is a pre-M23 master’s list', () => {
    expect(accessOf({ access: { creator: 'ann@x.com' } })).toEqual({ creator: 'ann@x.com', editors: [], canShare: false, canDelete: false })
    expect(accessOf({ access: { creator: null, editors: ['a', 3 as unknown as string], canShare: true } }))
      .toEqual({ creator: null, editors: ['a'], canShare: true, canDelete: false })
    expect(accessOf({})).toEqual({ creator: null, editors: [], canShare: true, canDelete: true })
  })

  it('lists yours by id, cars by slug, from the admin list', async () => {
    const fetcher = (async (url: string) => {
      if (url === '/api/admin/cars') return reply([{ slug: 'outback', access: { creator: 'a', editors: [], canShare: true, canDelete: true } }])
      if (url === '/api/admin/events') return reply([{ id: 'box-day', access: { creator: 'b', editors: ['a'], canShare: false, canDelete: false } }])
      return reply({ message: 'nope' }, 404)
    }) as typeof fetch
    expect([...(await mine('car', fetcher)).keys()]).toEqual(['outback'])
    expect(await allowedFor('event', 'box-day', fetcher)).toEqual({ edit: true, share: false, delete: false })
    expect(await allowedFor('event', 'other', fetcher)).toEqual(NOTHING)
    expect(await allowedFor('course', 'nhms', fetcher)).toEqual(NOTHING) // unreadable: nothing
  })

  it('says the role and who made a thing', () => {
    expect(roleText('master')).toBe('master admin')
    expect(roleText('user')).toBe('user')
    expect(creatorText('ann@x.com')).toBe('Made by ann@x.com')
    expect(creatorText(null)).toContain('before users existed')
  })
})

describe('the editors panel (M23)', () => {
  const sharing: Sharing = { creator: 'ann@x.com', editors: ['ed@x.com'], invitable: ['ed@x.com', 'fay@x.com', 'gus@x.com'] }

  it('adds only invited users who aren’t already editors or the maker', () => {
    expect(editorProblem(sharing, ['ed@x.com'], 'Fay@X.com ')).toBeNull()
    expect(editorProblem(sharing, ['ed@x.com'], 'ED@x.com')).toBe('Already an editor')
    expect(editorProblem(sharing, ['ed@x.com'], 'ann@x.com')).toContain('made it')
    expect(editorProblem(sharing, ['ed@x.com'], 'zed@x.com')).toContain('Users')
    expect(editorProblem(sharing, [], '  ')).toBe('Choose a user')
    expect(editorProblem({ ...sharing, creator: null }, [], 'fay@x.com')).toBeNull()
  })

  it('adds and removes by email whatever its case, sorted, once each', () => {
    expect(addEditor(['ed@x.com'], ' Fay@X.com')).toEqual(['ed@x.com', 'fay@x.com'])
    expect(addEditor(['ed@x.com'], 'ED@x.com')).toEqual(['ed@x.com'])
    expect(removeEditor(['ed@x.com', 'fay@x.com'], 'FAY@x.com')).toEqual(['ed@x.com'])
  })

  it('offers the invited users not yet editors, and knows when the list changed', () => {
    expect(addable(sharing, ['ed@x.com'])).toEqual(['fay@x.com', 'gus@x.com'])
    expect(editorsChanged(['ed@x.com', 'fay@x.com'], ['FAY@x.com', 'ed@x.com'])).toBe(false)
    expect(editorsChanged(['ed@x.com'], [])).toBe(true)
  })
})

describe('the users page (M23)', () => {
  const users: InvitedUser[] = [{ email: 'ann@x.com', invitedBy: 'sam@x.com', invited: 0, created: ['car:outback'] }]

  it('invites an email not yet a user', () => {
    expect(inviteProblem('', users)).toBeNull()
    expect(inviteProblem('fay@x.com', users)).toBeNull()
    expect(inviteProblem('Ann@X.com', users)).toBe('Already a user')
    expect(inviteProblem('not an email', users)).toContain('email')
  })

  it('links what each made', () => {
    expect(thingLink('car:outback')).toEqual({ href: '/cars/outback', label: 'Car outback' })
    expect(thingLink('event:box-day')?.href).toBe('/events/box-day')
    expect(thingLink('course:palmer')?.href).toBe('/courses/palmer')
    expect(thingLink('driver:d-0a1b2c3d')?.href).toBe('/drivers/d-0a1b2c3d')
    expect(thingLink('boat:x')).toBeNull()
    expect(thingLink('car:')).toBeNull()
  })
})
