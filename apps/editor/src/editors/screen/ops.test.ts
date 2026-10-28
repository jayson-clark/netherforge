import { describe, expect, it } from 'vitest'
import type { ScreenFile } from '@/core/format'
import { blocksOf, NEW_PLACE, setPlace, setPlaceField, setSize, setVideo } from './ops'

const screen = (): ScreenFile => ({ width: 2, height: 1 })

describe("the screen editor's ops", () => {
  it('holds a side to 1 to 16 maps', () => {
    const d = screen()
    setSize(d, 'width', 40)
    setSize(d, 'height', 0)
    expect(d).toEqual({ width: 16, height: 1 })
  })

  it('hangs it somewhere and nowhere, and moves it', () => {
    const d = screen()
    setPlaceField(d, 'x', 4)
    expect(d.place).toBeUndefined()
    setPlace(d, { ...NEW_PLACE })
    setPlaceField(d, 'facing', 'north')
    expect(d.place).toEqual({ world: 'world', x: 0, y: 64, z: 0, facing: 'north' })
    setPlace(d, null)
    expect(d).not.toHaveProperty('place')
  })

  it('plays a video, keeping how, and none', () => {
    const d: ScreenFile = { ...screen(), video: { video: 'a', loop: false } }
    setVideo(d, 'b')
    expect(d.video).toEqual({ video: 'b', loop: false })
    setVideo(d, null)
    expect(d).not.toHaveProperty('video')
  })

  it('works out the blocks its maps hang in as format does: right, then down, as a player facing it sees it', () => {
    const blocks = blocksOf({
      width: 2,
      height: 2,
      place: { world: 'w', x: 0, y: 64, z: 0, facing: 'north' },
    })
    // Facing north, a player looking at it faces south, and their right is west.
    expect(blocks.get('1,0')).toEqual([-1, 64, 0])
    expect(blocks.get('0,1')).toEqual([0, 63, 0])
    expect(
      blocksOf({
        width: 2,
        height: 1,
        place: { world: 'w', x: 0, y: 64, z: 0, facing: 'east' },
      }).get('1,0'),
    ).toEqual([0, 64, -1])
    expect(blocksOf(screen()).size).toBe(0)
  })
})
