import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type BlockFile } from '@/core/format'
import { setSound, soundRefs } from './ops'

describe('block ops', () => {
  it('sets and removes sounds, and leaves none when none are left', () => {
    const block: BlockFile = { model: 'ui/ore' }
    setSound(block, 'place', 'ui/thud')
    setSound(block, 'break', 'ui/crumble')
    expect(block.sounds).toEqual({ place: 'ui/thud', break: 'ui/crumble' })
    setSound(block, 'place', undefined)
    expect(block.sounds).toEqual({ break: 'ui/crumble' })
    setSound(block, 'break', undefined)
    expect(block.sounds).toBeUndefined()
    // The canonical writer says what the file is: `break`, not what the model calls it.
    setSound(block, 'break', 'ui/crumble')
    expect(canonicalizeModel('block', 'blocks/ore/block.json', block).text).toContain(
      '"break": "ui/crumble"',
    )
  })

  it("lists every pack's sounds as a block names them", () => {
    expect(soundRefs({ ui: { sounds: ['thud', 'menu/open'] }, art: { sounds: ['pop'] } })).toEqual([
      'art/pop',
      'ui/menu/open',
      'ui/thud',
    ])
  })
})
