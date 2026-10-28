/**
 * The picture a resource folder carries for the game to show (a datapack's `pack.png`, a world's `icon.png`),
 * as a thumbnail; the kind's icon when it has none.
 */
import type { KindId } from '@/core/format'
import { locationIn } from '@/core/paths'
import { useProjectFileUrl } from '@/state/useResourcePacks'
import { useWorkspace } from '@/state/providers'
import type { ThumbnailProps } from '@/editors/contributions'
import styles from './FolderPicture.module.css'

export function folderPicture(kind: KindId, file: string) {
  return function FolderPicture({ id, size, fallback }: ThumbnailProps) {
    const path = useWorkspace((s) => {
      const picture = `${locationIn(s.files, kind, id)}/${file}`
      return s.files.includes(picture) ? picture : null
    })
    const url = useProjectFileUrl()
    if (!path) return <>{fallback}</>
    return (
      <img
        className={styles.picture}
        src={url(path)}
        alt=""
        draggable={false}
        style={{ width: size, height: size }}
      />
    )
  }
}
