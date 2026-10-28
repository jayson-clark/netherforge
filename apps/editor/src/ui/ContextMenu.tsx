/**
 * Right-click menus: `openMenu(event, items)` from any handler, drawn by the
 * one `ContextMenuHost` the app mounts, as a react-aria `Menu` in a
 * `Popover` anchored at the pointer. react-aria does the rest: arrow keys,
 * Home/End and typeahead move, Enter or Space runs, Escape, Tab or a click
 * elsewhere closes, focus goes back where it was, and the menu flips to stay
 * on screen.
 */
import { useRef } from 'react'
import { Menu, MenuItem as AriaMenuItem, Popover, Separator, Text } from 'react-aria-components'
import { create } from 'zustand'
import styles from './ContextMenu.module.css'
import { cx } from './cx'
import { Keys } from './Keys'

export interface MenuAction {
  label: string
  run: () => void
  /** Shown on the right, as the menus write it: `F2`, `Mod+D`. */
  shortcut?: string
  danger?: boolean
  disabled?: boolean
}
export type MenuItem = MenuAction | 'separator'

interface OpenMenu {
  x: number
  y: number
  items: MenuItem[]
}

const useMenu = create<{ current: OpenMenu | null }>(() => ({ current: null }))

/** Opens [items] at the pointer (or under the focused element, for the keyboard's menu key). */
export function openMenu(
  event: { clientX: number; clientY: number; preventDefault(): void },
  items: MenuItem[],
) {
  event.preventDefault()
  if (items.length === 0) return
  let { clientX: x, clientY: y } = event
  const active = document.activeElement instanceof HTMLElement ? document.activeElement : null
  if (x === 0 && y === 0 && active) {
    const rect = active.getBoundingClientRect()
    x = rect.left + 12
    y = rect.bottom
  }
  useMenu.setState({ current: { x, y, items } })
}

export const closeMenu = () => useMenu.setState({ current: null })

export function ContextMenuHost() {
  const menu = useMenu((s) => s.current)
  if (!menu) return null
  return <ContextMenu key={`${menu.x},${menu.y}`} menu={menu} />
}

function ContextMenu({ menu }: { menu: OpenMenu }) {
  // The popover hangs from a point: an empty box where the pointer was.
  const anchor = useRef<HTMLSpanElement>(null)
  const actions = new Map<string, MenuAction>()
  menu.items.forEach((item, index) => item !== 'separator' && actions.set(String(index), item))
  return (
    <>
      <span ref={anchor} className={styles.anchor} style={{ left: menu.x, top: menu.y }} />
      <Popover
        triggerRef={anchor}
        isOpen
        onOpenChange={(open) => !open && closeMenu()}
        placement="bottom start"
        offset={0}
        containerPadding={4}
        className={styles.popover}
      >
        <Menu
          aria-label="Context menu"
          autoFocus="first"
          className={styles.menu}
          disabledKeys={[...actions].flatMap(([key, item]) => (item.disabled ? [key] : []))}
          onAction={(key) => {
            closeMenu()
            actions.get(String(key))?.run()
          }}
          onContextMenu={(event) => event.preventDefault()}
        >
          {menu.items.map((item, index) =>
            item === 'separator' ? (
              <Separator key={index} className={styles.separator} />
            ) : (
              <AriaMenuItem
                key={index}
                id={String(index)}
                textValue={item.label}
                className={cx(styles.item, item.danger && styles.danger)}
              >
                <Text slot="label">{item.label}</Text>
                {item.shortcut && <Keys shortcut={item.shortcut} />}
              </AriaMenuItem>
            ),
          )}
        </Menu>
      </Popover>
    </>
  )
}
