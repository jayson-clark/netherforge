/** A loot table's picture: the first few items it gives, in a collage, as it is now (unsaved edits too). */
import { useMemo } from 'react'
import type { ItemDef, LootTableFile } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useWorkspace } from '@/state/providers'
import { Collage, COLLAGE_CELLS } from '@/ui/Collage'
import { lootItems } from './ops'

export function LootThumbnail({ id, size, fallback }: ThumbnailProps) {
  // A string, so the selector's answer is the same while the items are.
  const looks = useWorkspace((s) => {
    const home = s.outline?.namespace
    const read = (name: string) => {
      const local = home && name.startsWith(`${home}:`) ? name.slice(home.length + 1) : name
      // Another namespace's table is a package's: what's in it isn't read here.
      if (local.includes(':')) return null
      return parseCached<LootTableFile>(currentText(s, mainFileIn(s.files, 'loot_table', local)))
    }
    const table = read(id)
    return JSON.stringify(table ? lootItems(table, read, COLLAGE_CELLS) : [])
  })
  const items = useMemo(() => JSON.parse(looks) as ItemDef[], [looks])
  if (items.length === 0) return <>{fallback}</>
  return (
    <Collage
      items={items}
      keyOf={(item) => JSON.stringify(item)}
      render={(item, cell) => <ItemIcon item={item} size={Math.max(8, cell - 4)} />}
      size={size}
    />
  )
}
