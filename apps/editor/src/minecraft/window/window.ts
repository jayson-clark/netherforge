/**
 * Where things are in a vanilla container window, in GUI pixels from its
 * top-left corner: the window's size, its title, its slots and the player's
 * inventory below them. These are the numbers the game's container screens
 * and menus use (`ChestMenu`, `HopperMenu`, `DispenserMenu`, …), written
 * down as layout, not data: no texture or game fact comes from here.
 *
 * And where a skin lands. A skin is a picture in a font, at the front of the
 * title (see docs/format/resource-pack.md): the title is drawn at `title`, the
 * prefix's space characters move the cursor `offset` pixels, and a bitmap
 * glyph is drawn `ascent` pixels above the text baseline, which sits
 * [BASELINE] below the title's top. `height` sets the drawn height and the
 * width follows the picture's aspect. Then the prefix moves back by all of
 * that, so it's zero wide and the words start at `title`, over the art. The
 * plugin sends `titlePrefix` from format's `compileResourcePacks`, so the preview
 * uses those same numbers.
 */
import { MENU_ROWS, MENU_TYPES, type MenuFile, type MenuType } from '@/core/format'

export const WINDOW_WIDTH = 176

export interface WindowShape {
  type: MenuType
  columns: number
  rows: number
  size: number
}

/** The grid a menu file describes (rows clamped the way format clamps them). */
export function shapeOf(file: Pick<MenuFile, 'type' | 'rows'>): WindowShape {
  const type = file.type ?? 'chest'
  const { columns, fixedSlots } = MENU_TYPES[type]
  if (type === 'chest') {
    const rows = Math.min(MENU_ROWS.max, Math.max(1, Math.round(file.rows ?? MENU_ROWS.default)))
    return { type, columns, rows, size: rows * columns }
  }
  return { type, columns, rows: fixedSlots / columns, size: fixedSlots }
}

/** Pixels from the top of a line of text to its baseline (the default font's ascent). */
export const BASELINE = 7

/** One slot square: the item's top-left (the 18-pixel square starts one pixel up and left). */
export interface SlotPosition {
  index: number
  x: number
  y: number
}

export interface WindowLayout {
  shape: WindowShape
  width: number
  height: number
  title: { x: number; y: number; centered: boolean }
  slots: SlotPosition[]
  /** Extra squares the window draws that aren't slots of the inventory (the crafter's result). */
  extras: { x: number; y: number }[]
  /** The 27 storage slots, then the 9 hotbar slots, of whoever opens it. */
  player: { x: number; y: number }[]
  playerLabel: { x: number; y: number }
}

function grid(columns: number, rows: number, left: number, top: number, first = 0): SlotPosition[] {
  const out: SlotPosition[] = []
  for (let row = 0; row < rows; row += 1) {
    for (let column = 0; column < columns; column += 1) {
      out.push({ index: first + row * columns + column, x: left + column * 18, y: top + row * 18 })
    }
  }
  return out
}

function playerSlots(top: number) {
  return [
    ...grid(9, 3, 8, top).map(({ x, y }) => ({ x, y })),
    ...grid(9, 1, 8, top + 58).map(({ x, y }) => ({ x, y })),
  ]
}

export function windowLayout(type: MenuType | undefined, rows?: number): WindowLayout {
  const shape = shapeOf({ type, rows })
  let height: number
  let slots: SlotPosition[]
  let playerTop: number
  let centered = false
  let extras: { x: number; y: number }[] = []
  switch (shape.type) {
    case 'chest':
    case 'barrel':
      height = 114 + shape.rows * 18
      slots = grid(9, shape.rows, 8, 18)
      playerTop = 31 + shape.rows * 18
      break
    case 'shulker_box':
      height = 167
      slots = grid(9, 3, 8, 18)
      playerTop = 84
      break
    case 'hopper':
      height = 133
      slots = grid(5, 1, 44, 20)
      playerTop = 51
      break
    case 'dispenser':
    case 'dropper':
      height = 166
      slots = grid(3, 3, 62, 17)
      playerTop = 84
      // The dispenser screen centres its title.
      centered = true
      break
    case 'crafter':
      height = 166
      slots = grid(3, 3, 26, 17)
      playerTop = 84
      extras = [{ x: 134, y: 35 }]
      // So does the crafter's.
      centered = true
      break
  }
  return {
    shape,
    width: WINDOW_WIDTH,
    height,
    title: { x: 8, y: 6, centered },
    slots,
    extras,
    player: playerSlots(playerTop),
    playerLabel: { x: 8, y: height - 94 },
  }
}

export interface SkinNumbers {
  /** From `compileResourcePacks`: where the picture starts, from where the title starts. */
  offset: number
  height: number
  ascent: number
}

export interface TitlePlacement {
  /** Where the title starts: its words, since the skin's prefix is zero wide. */
  titleX: number
  titleY: number
  /** The skin picture's top-left and drawn height; width follows its aspect. */
  skin: { left: number; top: number; height: number } | null
}

/**
 * Where the skin and the words of a title land. The skin's prefix nets to
 * zero width (format's `CompiledResourcePack.Skin.titlePrefix`), so the words start
 * at the title position and the picture at `offset` from it. [textWidth]
 * (the words' width, format's `layoutText`) matters only for a centred
 * title, which the game centres on its whole width: the words alone. The
 * server moves a centred title's skin back by as much (format's
 * `MenuType.skinShift`), so it lands where it would on a left-aligned
 * window, whenever it can measure the words ([measured]: the project has a
 * current `fonts/default.json`) or there are none; otherwise the skin moves
 * with the title's length.
 */
export function placeTitle(
  layout: Pick<WindowLayout, 'width' | 'title'>,
  skin: SkinNumbers | null,
  textWidth: number,
  measured = true,
): TitlePlacement {
  const titleX = layout.title.centered ? Math.trunc((layout.width - textWidth) / 2) : layout.title.x
  const titleY = layout.title.y
  const skinX = layout.title.centered && (measured || textWidth === 0) ? layout.title.x : titleX
  return {
    titleX,
    titleY,
    skin: skin
      ? { left: skinX + skin.offset, top: titleY + BASELINE - skin.ascent, height: skin.height }
      : null,
  }
}
