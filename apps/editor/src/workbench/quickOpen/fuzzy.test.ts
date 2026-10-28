import { describe, expect, it } from 'vitest'
import { fuzzyMatch } from './fuzzy'

const rank = (query: string, texts: string[]) =>
  texts
    .map((text) => ({ text, match: fuzzyMatch(query, text) }))
    .filter((it) => it.match)
    .sort((a, b) => b.match!.score - a.match!.score)
    .map((it) => it.text)

describe('fuzzyMatch', () => {
  it("matches characters in order, and nothing when they aren't there", () => {
    expect(fuzzyMatch('twr', 'centities/tower/script.lua')?.positions).toBeDefined()
    expect(fuzzyMatch('xyz', 'centities/tower/script.lua')).toBeNull()
    expect(fuzzyMatch('', 'anything')).toEqual({ score: 0, positions: [] })
  })

  it('prefers the file name, word starts and runs of characters', () => {
    expect(
      rank('script', [
        'centities/tower/lib/scripted_steps.lua',
        'centities/tower/script.lua',
        'modules/s/c/r/i/p/t.lua',
      ]),
    ).toEqual([
      'centities/tower/script.lua',
      'centities/tower/lib/scripted_steps.lua',
      'modules/s/c/r/i/p/t.lua',
    ])
    expect(
      rank('menu', ['menus/shop/menu.json', 'centities/mega_enemy_unit/centity.json'])[0],
    ).toBe('menus/shop/menu.json')
  })

  it('reports where it matched', () => {
    expect(fuzzyMatch('tl', 'tower/lib.lua')?.positions).toEqual([0, 6])
  })
})
