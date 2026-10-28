import { describe, expect, it } from 'vitest'
import { subjectOf } from './subject'

describe('subjectOf', () => {
  it("is the resource a file belongs to, from its own file or a script's", () => {
    expect(subjectOf('centities/tower/centity.json')).toEqual({
      kind: 'centity',
      id: 'tower',
      group: '',
      pkg: null,
      location: 'centities/tower',
      main: 'centities/tower/centity.json',
      hasFiles: true,
      companion: null,
    })
    expect(subjectOf('centities/tower/lib/steps.lua')?.main).toBe('centities/tower/centity.json')
    expect(subjectOf('modules/greeter/init.lua')).toMatchObject({ kind: 'module', main: null })
    expect(subjectOf('recipes/ruby.json')).toMatchObject({ kind: 'recipe', hasFiles: false })
    expect(subjectOf('terrain/vale.lua')).toMatchObject({
      kind: 'terrain',
      id: 'vale',
      main: 'terrain/vale.json',
      companion: 'terrain/vale.lua',
    })
    expect(subjectOf('structures/house.nbt')?.companion).toBeNull()
    expect(subjectOf('maps/arena')).toMatchObject({ kind: 'map', id: 'arena' })
    // In group folders, wherever its files are.
    expect(subjectOf('terrain/(worlds)/vale.lua')).toMatchObject({
      group: '(worlds)',
      main: 'terrain/(worlds)/vale.json',
      companion: 'terrain/(worlds)/vale.lua',
    })
    expect(subjectOf('netherforge.json')).toBeNull()
    expect(subjectOf(null)).toBeNull()
  })

  it("is a package's resource with the package's paths, so the outline reads its files and not the project's", () => {
    expect(subjectOf('chess:centities/chess_king/script.lua')).toEqual({
      kind: 'centity',
      id: 'chess_king',
      group: '',
      pkg: 'chess',
      location: 'chess:centities/chess_king',
      main: 'chess:centities/chess_king/centity.json',
      hasFiles: true,
      companion: null,
    })
    expect(subjectOf('library:resource_packs/gems/pack.json')).toMatchObject({
      pkg: 'library',
      main: 'library:resource_packs/gems/pack.json',
    })
    expect(subjectOf('library:terrain/hills.json')).toMatchObject({
      companion: 'library:terrain/hills.lua',
    })
  })
})
