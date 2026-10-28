import { describe, expect, it } from 'vitest'
import { decideExternalChange } from './external'

const clean = { savedText: 'a', dirty: false, deleted: false }
const dirty = { savedText: 'a', dirty: true, deleted: false }

describe('decideExternalChange', () => {
  it('ignores our own writes', () => {
    expect(decideExternalChange(clean, 'a')).toBe('ignore')
    expect(decideExternalChange(dirty, 'a')).toBe('ignore')
  })

  it('reloads a clean document silently', () => {
    expect(decideExternalChange(clean, 'b')).toBe('reload')
  })

  it('asks before touching unsaved edits', () => {
    expect(decideExternalChange(dirty, 'b')).toBe('conflict')
  })

  it('closes a clean deleted document and marks a dirty one', () => {
    expect(decideExternalChange(clean, null)).toBe('close')
    expect(decideExternalChange(dirty, null)).toBe('mark-deleted')
    expect(decideExternalChange({ ...dirty, deleted: true }, null)).toBe('ignore')
  })

  it('treats a deleted file coming back as a change', () => {
    expect(decideExternalChange({ ...dirty, deleted: true }, 'a')).toBe('conflict')
    expect(decideExternalChange({ ...clean, deleted: true }, 'a')).toBe('reload')
  })
})
