/**
 * What a resource looks like in the explorer, the palette, the outline's
 * header and its tab: its kind's thumbnail (a project item's icon, a
 * recipe's result, a pack's first picture, a centity drawn, a loot table's
 * items, a menu's window, a biome's colours…), over or instead of the kind's
 * icon.
 */
import { Suspense, type ReactNode } from 'react'
import { Collage } from '@/ui/Collage'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { usePendingPicture } from '@/ui/Reveal'
import type { ExplorerFolder } from './folders'
import styles from './Thumbnail.module.css'

export function Thumbnail({
  folder,
  id,
  size,
}: {
  folder: ExplorerFolder
  id: string
  size: number
}) {
  const picture = Math.round(size * 0.62)
  const icon = <KindIcon folder={folder} size={picture} />
  const Picture = folder.contribution.thumbnail
  return (
    <span className={styles.thumb} aria-hidden="true">
      {Picture ? (
        <Suspense fallback={<Loading>{icon}</Loading>}>
          <Picture id={id} size={picture} fallback={icon} />
        </Suspense>
      ) : (
        icon
      )}
    </span>
  )
}

/**
 * A group folder's tile: up to four of its resources' thumbnails in a 2×2 collage (one alone fills it), with a
 * folder in the corner so it still reads as a group.
 */
export function GroupThumbnail({
  folder,
  ids,
  size,
}: {
  folder: ExplorerFolder
  ids: string[]
  size: number
}) {
  return (
    <Collage
      items={ids}
      keyOf={(id) => id}
      render={(id, cell) => <Thumbnail folder={folder} id={id} size={cell} />}
      size={size}
      badge={<Icon name="folder" size={Math.max(12, Math.round(size * 0.22))} />}
    />
  )
}

/** The icon shown while a thumbnail's code loads, holding back the page it's on. */
function Loading({ children }: { children: ReactNode }) {
  usePendingPicture(true)
  return children
}

const toneOf = (folder: ExplorerFolder) => {
  const tone = folder.contribution.tone
  return tone && styles[tone]
}

/** The kind's own small icon, in its colour (the explorer's folder list). */
export function FolderIcon({ folder }: { folder: ExplorerFolder }) {
  return <Icon name={folder.icon} className={cx(styles.icon, toneOf(folder))} />
}

function KindIcon({ folder, size }: { folder: ExplorerFolder; size: number }) {
  return (
    <Icon
      name={folder.icon}
      size={Math.max(16, Math.round(size * 0.7))}
      className={cx(styles.icon, toneOf(folder))}
    />
  )
}
