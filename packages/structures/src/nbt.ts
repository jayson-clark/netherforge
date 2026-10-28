/**
 * Minecraft's NBT, through NBTify: read (and written back, for the one change
 * the editor makes to a captured `level.dat`). Java Edition's files are
 * big-endian with a named root compound, usually gzipped; structures and
 * `level.dat` are both that, a region file's chunks the same once inflated.
 *
 * It lives here, in TypeScript, rather than in the Rust backend because
 * everything that reads it is UI: the structure preview turns the palette and
 * blocks straight into geometry, and the template screen shows a few fields.
 * Parsing in Rust would mean shipping the whole tree over IPC as JSON (a
 * structure's block list is most of the file) and a second NBT model, and the
 * memory backend, Playwright and vitest couldn't use it. Decompression is the
 * platform's own `DecompressionStream`, in the webview and in Node alike.
 *
 * Tags are NBTify's values, which keep their type so a file is written back
 * as it was read: `Int8`/`Int16`/`Int32`/`Float32` wrappers, doubles as plain
 * numbers, longs as `bigint` (a seed doesn't fit a double), strings, arrays
 * (lists), objects (compounds) and typed arrays. `nbtify` is patched
 * (`patches/nbtify@*.patch`) so an int or long array's length is checked
 * against the bytes left before it's allocated: a crafted ten-byte file could
 * otherwise ask for gigabytes.
 */
import {
  Float32,
  Int16,
  Int32,
  Int8,
  NBTData,
  TAG,
  getTagType,
  read,
  write,
  type CompoundTag,
  type RootTag,
  type Tag,
} from 'nbtify'

/** A file's root: its name, and the compound it holds (`.data`). */
export type NbtRoot = NBTData<CompoundTag>

export class NbtError extends Error {}

/**
 * Reads a Java Edition NBT file: gzipped, zlib-wrapped or raw (each
 * recognised by its header), or exactly as [compression] says. Anything that
 * isn't one named root compound is an `NbtError` with a sentence.
 */
export async function readNbt(
  bytes: Uint8Array,
  compression?: 'gzip' | 'deflate' | null,
): Promise<NbtRoot> {
  let root: NBTData<RootTag>
  try {
    root = await read(bytes, {
      endian: 'big',
      rootName: true,
      bedrockLevel: false,
      ...(compression !== undefined ? { compression } : {}),
    })
  } catch (error) {
    throw new NbtError(error instanceof Error ? error.message : String(error))
  }
  if (getTagType(root.data) !== TAG.COMPOUND) {
    throw new NbtError('the root tag is a list, not a compound')
  }
  return root as NbtRoot
}

/** A root compound named [name] (files the game writes have it empty), gzipped when written. */
export function nbtRoot(data: CompoundTag, name = ''): NbtRoot {
  return new NBTData(data, { rootName: name, endian: 'big', compression: 'gzip' })
}

/** [root] as bytes, compressed as it was read (gzip for one made by `nbtRoot`), or as [compression] says. */
export function writeNbt(
  root: NbtRoot,
  compression?: 'gzip' | 'deflate' | null,
): Promise<Uint8Array> {
  return write(root, compression !== undefined ? { compression } : {})
}

// ---- reading values --------------------------------------------------------

/** The compound [tag] is, if it is one. */
export function nbtCompound(tag: Tag | undefined): CompoundTag | undefined {
  return tag !== undefined && getTagType(tag) === TAG.COMPOUND ? (tag as CompoundTag) : undefined
}

/** The tag at [path] inside [compound], or undefined anywhere along the way. */
export function nbtAt(compound: CompoundTag | undefined, ...path: string[]): Tag | undefined {
  let tag: Tag | undefined = compound
  for (const key of path) {
    tag = nbtCompound(tag)?.[key]
    if (tag === undefined) return undefined
  }
  return tag
}

/** A number tag's value (a long as a number when it fits exactly, else undefined). */
export function nbtNumber(tag: Tag | undefined): number | undefined {
  if (
    tag instanceof Int8 ||
    tag instanceof Int16 ||
    tag instanceof Int32 ||
    tag instanceof Float32
  ) {
    return tag.valueOf()
  }
  if (typeof tag === 'number') return tag
  if (typeof tag === 'bigint') return Number.isSafeInteger(Number(tag)) ? Number(tag) : undefined
  return undefined
}

export function nbtString(tag: Tag | undefined): string | undefined {
  return typeof tag === 'string' ? tag : undefined
}

export function nbtList(tag: Tag | undefined): Tag[] {
  return Array.isArray(tag) ? (tag as Tag[]) : []
}

/** Three numbers from an int array or a list (block positions, sizes). */
export function nbtVec3(tag: Tag | undefined): [number, number, number] | undefined {
  const values =
    tag instanceof Int32Array ? [...tag] : Array.isArray(tag) ? tag.map((it) => nbtNumber(it)) : []
  if (values.length !== 3 || values.some((it) => it === undefined)) return undefined
  return values as [number, number, number]
}
