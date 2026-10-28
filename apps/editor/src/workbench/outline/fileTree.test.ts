import { describe, expect, it } from 'vitest'
import { buildFileTree, copyPathFor, moveTarget, nameProblem, requireNameOf } from './fileTree'

const FILES = [
  'centities/tower/centity.json',
  'centities/tower/script.lua',
  'centities/tower/lib/steps.lua',
  'centities/tower/lib/deep/more.lua',
  'centities/lamp/centity.json',
]
const ROOT = 'centities/tower'

describe('buildFileTree', () => {
  it('nests a resource’s files in folders, folders first', () => {
    const tree = buildFileTree(FILES, ROOT)
    expect(tree.map((it) => it.label)).toEqual(['lib', 'centity.json', 'script.lua'])
    const lib = tree[0]!
    expect(lib.id).toBe('centities/tower/lib')
    expect(lib.children!.map((it) => it.label)).toEqual(['deep', 'steps.lua'])
    expect(lib.children![0]!.children!.map((it) => it.id)).toEqual([
      'centities/tower/lib/deep/more.lua',
    ])
  })

  it('shows folders the user just made, before they hold anything', () => {
    const tree = buildFileTree(FILES, ROOT, ['centities/tower/util', 'centities/lamp/other'])
    expect(tree.map((it) => it.label)).toEqual(['lib', 'util', 'centity.json', 'script.lua'])
    expect(tree[1]!.children).toEqual([])
  })
})

describe('naming files', () => {
  const check = (name: string, kind: 'file' | 'folder' = 'file', parent = ROOT) =>
    nameProblem(name, parent, ROOT, FILES, kind)

  it('takes plain names and nested paths, and refuses what exists', () => {
    expect(check('util.lua')).toBeNull()
    expect(check('more/util.lua')).toBeNull()
    expect(check('notes.md')).toBeNull()
    expect(check('script.lua')).toMatch(/already/)
    expect(check('lib', 'folder')).toMatch(/already/)
    expect(check('')).toMatch(/name/)
    expect(check('../escape.lua')).toMatch(/Letters/)
  })

  it('holds Lua files to the require rule', () => {
    expect(check('my-util.lua')).toMatch(/Lua file/)
    expect(check('my.util.lua')).toMatch(/Lua file/)
  })

  it('lets a rename keep its own name', () => {
    expect(
      nameProblem('script.lua', ROOT, ROOT, FILES, 'file', 'centities/tower/script.lua'),
    ).toBeNull()
  })
})

describe('copies, moves and require names', () => {
  it('names a copy beside the original', () => {
    expect(copyPathFor('centities/tower/script.lua', FILES)).toBe('centities/tower/script_copy.lua')
    expect(copyPathFor('centities/tower/lib', FILES)).toBe('centities/tower/lib_copy')
    expect(
      copyPathFor('centities/tower/script.lua', [...FILES, 'centities/tower/script_copy.lua']),
    ).toBe('centities/tower/script_copy2.lua')
  })

  it('moves into a folder, never into itself or where it is', () => {
    expect(moveTarget('centities/tower/script.lua', 'centities/tower/lib')).toBe(
      'centities/tower/lib/script.lua',
    )
    expect(moveTarget('centities/tower/lib', 'centities/tower/lib/deep')).toBeNull()
    expect(moveTarget('centities/tower/lib/steps.lua', 'centities/tower/lib')).toBeNull()
  })

  it('says what a script requires a file by', () => {
    expect(requireNameOf('modules/greeter/init.lua')).toBe('greeter')
    expect(requireNameOf('modules/greeter/lib/util.lua')).toBe('greeter.lib.util')
    expect(requireNameOf('centities/tower/lib/steps.lua')).toBe('lib.steps')
    expect(requireNameOf('centities/tower/centity.json')).toBeNull()
  })
})
