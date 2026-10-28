import { describe, expect, it } from 'vitest'
import { Float32 } from 'nbtify'
import { nbtAt, nbtRoot, nbtVec3, readNbt, writeNbt } from '@netherforge/structures/nbt'
import { gameRulesRoot, levelRoot, nbt, worldGenRoot } from '@/testing/nbtFixtures'
import { dimensionsOf, readWorldInfo, withSpawn } from './level'

describe('maps', () => {
  it('reads a 26.x world: level.dat, and the seed and game rules from its dimension', () => {
    const info = readWorldInfo(
      levelRoot({ name: 'arena', spawn: [4, 70, -8], dataVersion: 5023 }),
      worldGenRoot(7210487422168262260n),
      gameRulesRoot({ 'minecraft:keep_inventory': false, 'minecraft:random_tick_speed': 3 }),
    )
    expect(info).toEqual({
      name: 'arena',
      dataVersion: 5023,
      version: '26.3',
      seed: '7210487422168262260',
      spawn: [4, 70, -8],
      gameRules: [
        { rule: 'minecraft:keep_inventory', value: 'false' },
        { rule: 'minecraft:random_tick_speed', value: '3' },
      ],
    })
  })

  it('reads an older save, where level.dat held it all', () => {
    const level = nbtRoot({
      Data: nbt.compound({
        LevelName: nbt.string('Old'),
        DataVersion: nbt.int(3700),
        SpawnX: nbt.int(1),
        SpawnY: nbt.int(64),
        SpawnZ: nbt.int(2),
        WorldGenSettings: nbt.compound({ seed: nbt.long(-5n) }),
        GameRules: nbt.compound({ doDaylightCycle: nbt.string('false') }),
      }),
    })
    const info = readWorldInfo(level)
    expect(info.seed).toBe('-5')
    expect(info.spawn).toEqual([1, 64, 2])
    expect(info.gameRules).toEqual([{ rule: 'doDaylightCycle', value: 'false' }])
  })

  it('lists the dimensions a folder has, in either layout', () => {
    expect(
      dimensionsOf([
        'level.dat',
        'dimensions/minecraft/overworld/region/r.0.0.mca',
        'dimensions/minecraft/overworld/data/minecraft/game_rules.dat',
        'dimensions/test/arena/region/r.0.0.mca',
        'region/r.0.0.mca',
        'DIM-1/region/r.0.0.mca',
        'datapacks/x.zip',
      ]),
    ).toEqual([
      'DIM-1/ (older layout)',
      'minecraft:overworld',
      'region/ (older layout)',
      'test:arena',
    ])
  })

  it("moves a level.dat's spawn and keeps everything else", async () => {
    const level = levelRoot({ name: 'world', spawn: [0, 88, 0] })
    const moved = await readNbt(
      await writeNbt(withSpawn(level, { x: 3, y: 71, z: -9, yaw: 90, pitch: 0 })),
    )
    expect(nbtVec3(nbtAt(moved.data, 'Data', 'spawn', 'pos'))).toEqual([3, 71, -9])
    expect(nbtAt(moved.data, 'Data', 'spawn', 'yaw')).toEqual(new Float32(90))
    expect(nbtAt(moved.data, 'Data', 'spawn', 'dimension')).toEqual(
      nbt.string('minecraft:overworld'),
    )
    expect(nbtAt(moved.data, 'Data', 'LevelName')).toEqual(nbt.string('world'))
    // An older level.dat keeps its own keys.
    const old = nbtRoot({ Data: nbt.compound({ SpawnX: nbt.int(0) }) })
    expect(nbtAt(withSpawn(old, { x: 5, y: 6, z: 7 }).data, 'Data', 'SpawnZ')).toEqual(nbt.int(7))
  })
})
