/**
 * Hand-built NBT for tests: structure files and `level.dat`s shaped like the
 * game's (made-up blocks and values, no game data), for vitest and for
 * Playwright to drop into a memory project.
 */
import { Float32, Int16, Int32, Int8, TAG, TAG_TYPE, type CompoundTag, type Tag } from 'nbtify'
import { nbtRoot, type NbtRoot } from '@netherforge/structures/nbt'

/** NBTify's tag values, named by tag; a list carries its element type, so an empty one writes back as it was. */
export const nbt = {
  byte: (value: number): Tag => new Int8(value),
  short: (value: number): Tag => new Int16(value),
  int: (value: number): Tag => new Int32(value),
  long: (value: bigint): Tag => value,
  float: (value: number): Tag => new Float32(value),
  double: (value: number): Tag => value,
  string: (value: string): Tag => value,
  list: (element: TAG, value: Tag[]): Tag =>
    Object.defineProperty([...value], TAG_TYPE, {
      value: element,
      writable: true,
      configurable: true,
    }),
  compound: (value: CompoundTag): Tag => value,
  ints: (...values: number[]): Tag => Int32Array.from(values),
  intList: (...values: number[]): Tag =>
    nbt.list(
      TAG.INT,
      values.map((value) => new Int32(value)),
    ),
}

export interface FixtureBlock {
  pos: [number, number, number]
  state: number
  nbt?: boolean
}

/** A structure file's root, as a structure block saves one. */
export function structureRoot(options: {
  size: [number, number, number]
  palette: { Name: string; Properties?: Record<string, string> }[]
  blocks: FixtureBlock[]
  entities?: number
  dataVersion?: number
}): NbtRoot {
  return nbtRoot({
    DataVersion: nbt.int(options.dataVersion ?? 5023),
    size: nbt.intList(...options.size),
    palette: nbt.list(
      TAG.COMPOUND,
      options.palette.map(({ Name, Properties }) =>
        nbt.compound({
          Name: nbt.string(Name),
          ...(Properties
            ? {
                Properties: nbt.compound(
                  Object.fromEntries(
                    Object.entries(Properties).map(([key, value]) => [key, nbt.string(value)]),
                  ),
                ),
              }
            : {}),
        }),
      ),
    ),
    blocks: nbt.list(
      TAG.COMPOUND,
      options.blocks.map((block) =>
        nbt.compound({
          pos: nbt.intList(...block.pos),
          state: nbt.int(block.state),
          ...(block.nbt ? { nbt: nbt.compound({ id: nbt.string('minecraft:chest') }) } : {}),
        }),
      ),
    ),
    entities: nbt.list(
      TAG.COMPOUND,
      Array.from({ length: options.entities ?? 0 }, () =>
        nbt.compound({
          pos: nbt.list(TAG.DOUBLE, [nbt.double(0.5), nbt.double(1), nbt.double(0.5)]),
        }),
      ),
    ),
  })
}

/** Every cell of a box filled with palette entry [state]. */
export function filledBlocks(size: [number, number, number], state = 0): FixtureBlock[] {
  const blocks: FixtureBlock[] = []
  for (let x = 0; x < size[0]; x += 1)
    for (let y = 0; y < size[1]; y += 1)
      for (let z = 0; z < size[2]; z += 1) blocks.push({ pos: [x, y, z], state })
  return blocks
}

/** A 26.x `level.dat`: name, spawn and data version (the seed and game rules live in the dimension's own files). */
export function levelRoot(options: {
  name: string
  spawn: [number, number, number]
  dataVersion?: number
}): NbtRoot {
  return nbtRoot({
    Data: nbt.compound({
      LevelName: nbt.string(options.name),
      DataVersion: nbt.int(options.dataVersion ?? 5023),
      Version: nbt.compound({
        Name: nbt.string('26.3'),
        Id: nbt.int(options.dataVersion ?? 5023),
      }),
      spawn: nbt.compound({
        pos: nbt.ints(...options.spawn),
        yaw: nbt.float(0),
        pitch: nbt.float(0),
        dimension: nbt.string('minecraft:overworld'),
      }),
    }),
  })
}

/** A dimension's `data/minecraft/world_gen_settings.dat`. */
export function worldGenRoot(seed: bigint): NbtRoot {
  return nbtRoot({
    DataVersion: nbt.int(5023),
    data: nbt.compound({ seed: nbt.long(seed), generate_structures: nbt.byte(1) }),
  })
}

/** A dimension's `data/minecraft/game_rules.dat`. */
export function gameRulesRoot(rules: Record<string, number | boolean>): NbtRoot {
  return nbtRoot({
    DataVersion: nbt.int(5023),
    data: nbt.compound(
      Object.fromEntries(
        Object.entries(rules).map(([key, value]) => [
          key,
          typeof value === 'boolean' ? nbt.byte(value ? 1 : 0) : nbt.int(value),
        ]),
      ),
    ),
  })
}
