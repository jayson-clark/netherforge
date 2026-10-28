import { describe, expect, it } from 'vitest'
import type { PackMeta } from '@/core/format'
import { addOverlay, fileGroups, formatText, parseFormat, rangeText, removeOverlay } from './ops'

const meta: PackMeta = {
  pack: { min_format: 94, max_format: 121 },
  overlays: {
    entries: [
      { min_format: 94, max_format: [94, 1], directory: 'mc1_21' },
      { min_format: 101, max_format: 107, directory: 'mc26_1' },
    ],
  },
}

describe('datapack formats', () => {
  it('reads a format as the game writes one', () => {
    expect(formatText(94)).toBe('94')
    expect(formatText([94, 1])).toBe('94.1')
    expect(formatText([94])).toBe('94')
    expect(formatText('94')).toBeNull()
    expect(formatText([94, 1, 2])).toBeNull()
    expect(rangeText(94, 121)).toBe('94 to 121')
    expect(rangeText(121, 121)).toBe('121')
    expect(rangeText(94, 'x')).toContain('not data pack formats')
  })
})

describe('datapack files', () => {
  it('groups files by the pack and its overlays, in the order the game applies them', () => {
    const groups = fileGroups(meta, [
      'pack.mcmeta',
      'mc26_1/data/basic/worldgen/configured_feature/rock.json',
      'data/basic/worldgen/placed_feature/rocks.json',
      'mc1_21/data/basic/worldgen/configured_feature/rock.json',
      'notes.json',
      'mc1_21/rock.json',
    ])
    expect(groups.map((group) => [group.folder, group.formats, group.files])).toEqual([
      ['data', '94 to 121', ['data/basic/worldgen/placed_feature/rocks.json']],
      ['mc1_21', '94 to 94.1', ['mc1_21/data/basic/worldgen/configured_feature/rock.json']],
      ['mc26_1', '101 to 107', ['mc26_1/data/basic/worldgen/configured_feature/rock.json']],
      ['', '', ['mc1_21/rock.json', 'notes.json']],
    ])
  })
})

describe('editing pack.mcmeta', () => {
  it('reads formats back as the game writes them', () => {
    expect(parseFormat('94')).toBe(94)
    expect(parseFormat(' 94.1 ')).toEqual([94, 1])
    expect(parseFormat('94.1.2')).toBeNull()
    expect(parseFormat('soon')).toBeNull()
  })

  it('adds an overlay for the pack’s formats and drops overlays once none is left', () => {
    const next: PackMeta = { pack: { min_format: 94, max_format: 121 } }
    addOverlay(next, 'mc26_3')
    expect(next.overlays).toEqual({
      entries: [{ min_format: 94, max_format: 121, directory: 'mc26_3' }],
    })
    removeOverlay(next, 0)
    expect('overlays' in next).toBe(false)
  })
})
