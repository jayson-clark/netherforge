/**
 * A game block file's picture: the first few of the game's blocks it lists, a `#tag`'s from the game data, as the
 * inventory draws their items (redrawn as the project's resource packs redraw them), in a collage.
 */
import { useMemo } from 'react'
import { BLOCK_REGISTRY, type GameBlockFile, type ItemDef } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useWorkspace } from '@/state/providers'
import { Collage, COLLAGE_CELLS } from '@/ui/Collage'
import { firstBlocks, previewEntries } from './ops'

const NO_TAGS: Record<string, string[]> = {}

export function GameBlockThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'game_block', id)))
  const tags = useWorkspace((s) => s.gameData?.tags?.[BLOCK_REGISTRY] ?? NO_TAGS)
  const blocks = parseCached<GameBlockFile>(text)?.blocks
  const items = useMemo(
    () =>
      firstBlocks(previewEntries(Array.isArray(blocks) ? blocks : [], tags), COLLAGE_CELLS).map(
        (kind): ItemDef => ({ kind }),
      ),
    [blocks, tags],
  )
  if (items.length === 0) return <>{fallback}</>
  return (
    <Collage
      items={items}
      keyOf={(item) => item.kind!}
      render={(item, cell) => <ItemIcon item={item} size={Math.max(8, cell - 4)} />}
      size={size}
    />
  )
}
