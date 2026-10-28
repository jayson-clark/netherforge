/** A pack's most telling picture: its first skin's, else an item model's or glyph's, else any. */
import type { ResourcePackFile } from '@/core/format'
import { mainFileIn, RESOURCE_PACKS, TEXTURES } from '@/core/paths'
import { useProjectFileUrl } from '@/state/useResourcePacks'
import { useWorkspace } from '@/state/providers'
import type { ThumbnailProps } from '@/editors/contributions'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import styles from './ResourcePackThumbnail.module.css'

export function ResourcePackThumbnail({ id, fallback }: ThumbnailProps) {
  const prefix = `${RESOURCE_PACKS}/${id}/${TEXTURES}/`
  const pack = parseCached<ResourcePackFile>(
    useWorkspace((s) => currentText(s, mainFileIn(s.files, 'resource_pack', id))),
  )
  const anyTexture = useWorkspace((s) =>
    s.files.find((it) => it.startsWith(prefix) && it.endsWith('.png')),
  )
  const named = [pack?.skins, pack?.items, pack?.glyphs]
    .flatMap((entries) => Object.values(entries ?? {}))
    .map((entry) => entry.texture)
    .find((texture): texture is string => typeof texture === 'string')
  const first = named ? `${prefix}${named}` : anyTexture
  const url = useProjectFileUrl()
  return first ? (
    <img className={styles.image} src={url(first)} alt="" draggable={false} />
  ) : (
    <>{fallback}</>
  )
}
