/**
 * A vanilla container window drawn at GUI scale, with a skin where the
 * server puts it and the title where the game draws it. The inventory
 * editor puts its slot buttons on top; the pack editor shows a skin over it.
 *
 * Draw order follows the game: the window, then the skin (a title glyph,
 * which covers the window's own squares), then items and text. With
 * [outline], the window's edges and squares are traced over the skin too, to
 * check the artwork lines up.
 */
import type { ReactNode } from 'react'
import type { SkinPreview, MenuType } from '@/core/format'
import { layoutText } from '@/core/format'
import { useMemo } from 'react'
import {
  useCompiledResourcePacks,
  useHomeNamespace,
  useReferenceNamespace,
  useProjectFileUrl,
} from '@/state/useResourcePacks'
import { glyphAdvancesOf, useGlyphMap } from '@/minecraft/text/glyphs'
import { resourcePackTexturePath } from '@/core/paths'
import { useWorkspace } from '@/state/providers'
import { MiniText } from '@/minecraft/text/MiniText'
import { plainText } from '@/minecraft/text/minimessage'
import { splitResourcePackRef } from '@/minecraft/item/item'
import { Hint } from '@/ui/text'
import { placeTitle, windowLayout, type SlotPosition } from './window'
import styles from './WindowFrame.module.css'

/** Vanilla's title and label colour. */
const LABEL_COLOR = '#404040'

export interface ResolvedSkin {
  skin: SkinPreview
  url: string
}

/** A skin reference (`<pack>/<key>`, or a package's `ns:<pack>/<key>`), compiled; null when it names nothing. */
export function useSkin(ref: string | undefined): ResolvedSkin | null {
  const compiled = useCompiledResourcePacks()
  const namespace = useReferenceNamespace()
  const home = useHomeNamespace()
  const url = useProjectFileUrl()
  const parts = splitResourcePackRef('skin', ref, namespace, home)
  const skin = parts ? compiled.resourcePacks[parts[0]]?.skins[parts[1]] : undefined
  if (!parts || !skin) return null
  return { skin, url: url(resourcePackTexturePath(parts[0], skin.texture)) }
}

export function WindowFrame({
  type,
  rows,
  title,
  skin,
  measured = true,
  scale = 2,
  outline = false,
  renderSlot,
  label = 'Window preview',
}: {
  type: MenuType | undefined
  rows: number | undefined
  title: string | undefined
  skin: ResolvedSkin | null
  /** Whether the server can measure the title (see `placeTitle`): false when format reports `font.needed`. */
  measured?: boolean
  scale?: number
  outline?: boolean
  /** Whatever goes in a container slot (the menu editor's buttons). */
  renderSlot?: (slot: SlotPosition) => ReactNode
  label?: string
}) {
  const layout = windowLayout(type, rows)
  const advances = useWorkspace((s) => s.glyphAdvances)
  const glyphs = useGlyphMap()
  const glyphAdvances = useMemo(() => glyphAdvancesOf(glyphs), [glyphs])
  const words = title ?? ''
  const textWidth = words ? (layoutText(words, 0, advances, glyphAdvances)[0]?.width ?? 0) : 0
  const placed = placeTitle(layout, skin?.skin ?? null, textWidth, measured)
  const px = (n: number) => n * scale
  const square = (x: number, y: number, key: string, className = styles.slot) => (
    <div
      key={key}
      className={className}
      style={{ left: px(x - 1), top: px(y - 1), width: px(18), height: px(18) }}
    />
  )
  // The game centres these titles on their words alone (the skin's prefix is zero wide), so the art
  // follows the words unless the server can measure them and move it back.
  const skinMoves =
    !measured &&
    layout.title.centered &&
    skin !== null &&
    (plainText(words) !== '' || words.includes('<glyph:'))

  return (
    <div className={styles.wrap}>
      <div
        className={styles.frame}
        role="img"
        aria-label={label}
        style={{ width: px(layout.width), height: px(layout.height) }}
      >
        <div className={styles.panel} style={{ borderWidth: px(2) }} />
        {layout.slots.map((slot) => square(slot.x, slot.y, `s${slot.index}`))}
        {layout.extras.map((slot, i) => square(slot.x, slot.y, `e${i}`))}
        {layout.player.map((slot, i) => square(slot.x, slot.y, `p${i}`))}
        {skin?.skin && placed.skin && (
          <img
            className={styles.skin}
            data-skin
            src={skin.url}
            alt=""
            draggable={false}
            style={{
              left: px(placed.skin.left),
              top: px(placed.skin.top),
              height: px(placed.skin.height),
            }}
          />
        )}
        {outline && (
          <div className={styles.outline} aria-hidden="true">
            <div className={styles.outlineEdge} />
            {layout.slots.map((slot) =>
              square(slot.x, slot.y, `o${slot.index}`, styles.outlineSlot),
            )}
          </div>
        )}
        {words && (
          <div
            className={styles.title}
            style={{ left: px(placed.titleX), top: px(placed.titleY), color: LABEL_COLOR }}
          >
            <MiniText text={words} glyphs={glyphs} scale={scale} base={{ color: LABEL_COLOR }} />
          </div>
        )}
        <div
          className={styles.title}
          style={{ left: px(layout.playerLabel.x), top: px(layout.playerLabel.y) }}
        >
          <MiniText text="Inventory" scale={scale} base={{ color: LABEL_COLOR }} />
        </div>
        {renderSlot && layout.slots.map((slot) => renderSlot(slot))}
      </div>
      {skinMoves && (
        <Hint tone="warning" role="note">
          A {layout.shape.type.replace('_', ' ')} centres its title on the words alone, so this skin
          is drawn {placed.skin ? placed.skin.left : 0} pixels in and moves with the title&apos;s
          length. The server puts it back when it can measure the title with fonts/default.json,
          which the editor writes once Minecraft is imported. Or leave the title empty.
        </Hint>
      )}
    </div>
  )
}
