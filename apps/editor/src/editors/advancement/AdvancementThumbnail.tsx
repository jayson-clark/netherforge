/** An advancement's picture: its display's icon, as the advancement screen draws it, as it is now (unsaved edits too). */
import type { AdvancementFile } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useWorkspace } from '@/state/providers'

export function AdvancementThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'advancement', id)))
  const icon = parseCached<AdvancementFile>(text)?.display?.icon
  if (typeof icon !== 'object' || icon === null || (!icon.kind && !icon.item))
    return <>{fallback}</>
  return <ItemIcon item={icon} size={size} />
}
