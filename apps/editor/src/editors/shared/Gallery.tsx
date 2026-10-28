/**
 * A gallery split into sections (a pack's skins, glyphs, textures and
 * sounds): each section a header with its count and actions over a grid of
 * cards, a picture over a name, one of them picked (the inspector shows
 * it). Each section is react-aria's `GridList` in a grid layout: one tab
 * stop, the arrow keys between cards, Enter or Space picks, typing jumps to
 * a name.
 */
import type { ReactNode } from 'react'
import { GridList, GridListItem, type Selection } from 'react-aria-components'
import { cx } from '@/ui/cx'
import { PanelHeader } from '@/ui/layout'
import { Stage } from './EditorLayout'
import styles from './Gallery.module.css'

export function Gallery({ children }: { children: ReactNode }) {
  return <Stage className={styles.gallery}>{children}</Stage>
}

/** A gallery's card. */
export interface GalleryCard {
  key: string
  /** Its accessible name (`skin shop`). */
  label: string
  /** Under the picture. */
  caption: ReactNode
  picture: ReactNode
  /** Its tooltip. */
  title?: string
}

export function GallerySection({
  title,
  cards,
  picked,
  onPick,
  actions,
  checkered = true,
}: {
  /** Its header, with the number of cards after it. */
  title: string
  cards: GalleryCard[]
  /** The picked card's key, when it's in this section. */
  picked: string | null
  onPick: (key: string) => void
  actions?: ReactNode
  /** Pictures over a chequerboard, so transparency shows. */
  checkered?: boolean
}) {
  const select = (selection: Selection) => {
    if (selection === 'all') return
    const [key] = selection
    if (key !== undefined) onPick(String(key))
  }
  return (
    <section aria-label={title}>
      <PanelHeader
        className={styles.header}
        title={`${title} (${cards.length})`}
        actions={actions}
      />
      <GridList
        aria-label={title}
        layout="grid"
        className={styles.grid}
        selectionMode="single"
        disallowEmptySelection
        selectedKeys={picked !== null ? [picked] : []}
        onSelectionChange={select}
        items={cards}
        dependencies={[checkered]}
      >
        {(card) => (
          <GridListItem
            id={card.key}
            textValue={card.key}
            aria-label={card.label}
            className={styles.card}
            render={(props) => <div {...props} title={card.title} />}
          >
            <span className={cx(styles.picture, checkered && styles.checker)}>{card.picture}</span>
            <span className={styles.caption}>{card.caption}</span>
          </GridListItem>
        )}
      </GridList>
    </section>
  )
}
