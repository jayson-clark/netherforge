/** A block's picture: its look as the inventory draws a block's item, as it is now (unsaved edits too). */
import { useMemo } from 'react'
import type { BlockFile } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { LookIcon, useLookPicture } from '@/minecraft/item/ItemIcon'
import { resourcePackBlockLook } from '@/minecraft/item/icon'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useWorkspace } from '@/state/providers'
import {
  useCompiledResourcePacks,
  useHomeNamespace,
  useReferenceNamespace,
  useResourcePackFiles,
  useResourcePackModels,
} from '@/state/useResourcePacks'

export function BlockThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'block', id)))
  const model = parseCached<BlockFile>(text)?.model
  const packs = useResourcePackFiles()
  const compiled = useCompiledResourcePacks()
  const namespace = useReferenceNamespace()
  const home = useHomeNamespace()
  const models = useResourcePackModels()
  const look = useMemo(
    () => resourcePackBlockLook(model, packs, compiled, namespace, home, models),
    [model, packs, compiled, namespace, home, models],
  )
  // No look is the game's note block; nothing to draw but the kind's icon.
  const layers = useLookPicture(look, undefined)
  if (!look || !layers) return <>{fallback}</>
  return <LookIcon layers={layers} size={size} />
}
