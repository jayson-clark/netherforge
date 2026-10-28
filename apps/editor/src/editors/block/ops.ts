/**
 * Pure edits and lookups for a block (`blocks/<id>/block.json`, docs/format/block.md):
 * its sounds.
 */
import type { BlockFile, BlockSounds } from '@/core/format'

type SoundKey = keyof BlockSounds

/**
 * Sets (or with nothing, removes) one of a block's sounds; a block left with
 * none has no `sounds` at all.
 */
export function setSound(block: BlockFile, key: SoundKey, value: string | undefined): void {
  const sounds: BlockSounds = { ...(block.sounds ?? {}) }
  if (value) sounds[key] = value
  else delete sounds[key]
  if (Object.keys(sounds).length === 0) delete block.sounds
  else block.sounds = sounds
}

/** Every sound of every pack, as a block's `sounds` name one: `<pack>/<key>`. */
export function soundRefs(packs: Record<string, { sounds: string[] }>): string[] {
  return Object.entries(packs)
    .flatMap(([pack, outline]) => outline.sounds.map((key) => `${pack}/${key}`))
    .sort()
}
