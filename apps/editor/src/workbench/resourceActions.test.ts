import { describe, expect, it } from 'vitest'
import { copyId, idProblem } from './resourceActions'

describe('resource ids', () => {
  it('names a copy after the original, past the ids already taken', () => {
    expect(copyId('tower', ['tower'])).toBe('tower_copy')
    expect(copyId('tower', ['tower', 'tower_copy', 'tower_copy2'])).toBe('tower_copy3')
    expect(copyId('a'.repeat(64), []).length).toBeLessThanOrEqual(64)
  })

  it("checks the id rule and what's taken, letting an id keep itself", () => {
    expect(idProblem('Tower', [])).toMatch(/lowercase/i)
    expect(idProblem('lamp', ['lamp'])).toBe('That id is taken')
    expect(idProblem('lamp', ['lamp'], 'lamp')).toBeNull()
  })
})
