/**
 * Pure edits and lookups for a game block file (`game_blocks/<id>.json`,
 * docs/format/game-block.md): the game's blocks it names, and what a field
 * left empty falls back to.
 */
import type { GameBlockFile } from '@/core/format'

/** Adds one of the game's blocks (an id or a `#tag`) to the end of the list. */
export function addBlock(file: GameBlockFile, block: string): void {
  file.blocks = [...(file.blocks ?? []), block]
}

/** Sets the block at [index] as written, trimmed. */
export function setBlock(file: GameBlockFile, index: number, block: string): void {
  const next = [...(file.blocks ?? [])]
  next[index] = block.trim()
  file.blocks = next
}

/** Removes the block at [index]. The list stays, empty if it's the last: `blocks` is required. */
export function removeBlock(file: GameBlockFile, index: number): void {
  file.blocks = (file.blocks ?? []).filter((_, at) => at !== index)
}

/**
 * What a block field suggests: the game's block tags as `#tag`, then its blocks, each in full
 * (`minecraft:stone`); none until game data is exported.
 */
export function blockChoices(blocks: readonly string[], tags: readonly string[]): string[] {
  return [...tags.map((it) => `#${it}`).sort(), ...[...blocks].sort()]
}

/** Whether the server mines the blocks (a hardness, tool or tool rule of the project's), as `GameBlockFile.minedByServer`. */
export function minedByServer(file: GameBlockFile): boolean {
  return file.hardness !== undefined || file.tool !== undefined || file.requiresTool !== undefined
}

/** One entry of a file's `blocks` as the preview draws it: an id is itself, a `#tag` the blocks in it (null when unknown). */
export interface PreviewEntry {
  entry: string
  blocks: string[] | null
}

/** A namespaced id (`minecraft:stone`), as the game data writes them. */
function fullId(id: string): string {
  return id.includes(':') ? id : `minecraft:${id}`
}

/**
 * What a file's `blocks` draw: each written entry once, a `#tag` with the game data's blocks in it (exported
 * flattened, so a tag's own tags are already in), null for a tag it doesn't know (none exported yet).
 */
export function previewEntries(
  blocks: readonly unknown[],
  tags: Readonly<Record<string, readonly string[]>>,
): PreviewEntry[] {
  const seen = new Set<string>()
  const entries: PreviewEntry[] = []
  for (const it of blocks) {
    if (typeof it !== 'string' || it === '' || seen.has(it)) continue
    seen.add(it)
    if (it.startsWith('#')) {
      const tag = tags[fullId(it.slice(1))]
      entries.push({ entry: it, blocks: tag ? [...tag] : null })
    } else {
      entries.push({ entry: it, blocks: [fullId(it)] })
    }
  }
  return entries
}

/** The first [count] blocks [entries] draw, each once: the thumbnail's. */
export function firstBlocks(entries: readonly PreviewEntry[], count: number): string[] {
  const out = new Set<string>()
  for (const { blocks } of entries) {
    for (const block of blocks ?? []) {
      if (out.size >= count) return [...out]
      out.add(block)
    }
  }
  return [...out]
}
