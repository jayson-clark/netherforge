/** A project item's picture: its icon, as a stack naming it draws it. */
import type { ItemDef } from '@/core/format'
import type { ThumbnailProps } from '@/editors/contributions'
import { ItemIcon } from '@/minecraft/item/ItemIcon'

export function ItemThumbnail({ id, size }: ThumbnailProps) {
  return <ItemIcon item={{ item: id } as ItemDef} size={size} />
}
