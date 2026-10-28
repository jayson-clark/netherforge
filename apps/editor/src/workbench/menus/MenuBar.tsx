/**
 * File, Edit, View… in the title bar, for Windows and Linux (macOS has the
 * system menu bar). Each menu is a react-aria `MenuTrigger` with its `Menu`
 * in a non-modal `Popover` (so the bar stays under the pointer, and moving
 * along it switches menus, as native menu bars do); react-aria handles the
 * keys inside a menu (arrows, Home/End, typeahead, Enter, Escape, submenus).
 * What react-aria has no component for is the bar itself: Left and Right
 * move between its menus, and releasing Alt on its own opens the first, as
 * Windows does.
 *
 * Like a native menu bar it acts on what had focus before it opened: its
 * buttons never take focus, and a chosen item runs only once focus is back
 * where it was, so Edit's commands reach the focused field or code editor.
 */
import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  Menu,
  MenuItem,
  MenuSection,
  MenuTrigger,
  Popover,
  Pressable,
  Separator,
  SubmenuTrigger,
  Text,
  type Key,
} from 'react-aria-components'
import type { MenuCommandEntry, MenuEntry } from '@/core/backend/types'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { Keys } from '@/ui/Keys'
import { IS_MAC } from '@/workbench/shortcuts'
import styles from './MenuBar.module.css'
import { useMenus } from './useAppMenus'

/** The open menu, and whether it opened from the keyboard (its first item is focused). */
interface OpenMenu {
  menu: number
  keyboard: boolean
}

export function MenuBar() {
  const menus = useMenus((s) => s.menus)
  const runItem = useMenus((s) => s.run)
  const [open, setOpen] = useState<OpenMenu | null>(null)
  const bar = useRef<HTMLDivElement>(null)
  // What had focus before the bar opened, and the item chosen, run once focus is back there.
  const restoreTo = useRef<Element | null>(null)
  const chosen = useRef<string | null>(null)

  // Before a press can move focus to the bar (and before the menu opens).
  const remember = () => {
    if (!open) restoreTo.current ??= document.activeElement
  }
  const show = (menu: number | null, keyboard = false) => {
    if (menu === null) return setOpen(null)
    remember()
    setOpen({ menu: (menu + menus.length) % menus.length, keyboard })
  }

  // Closed: once the menu has left the page (a frame later, after react-aria's
  // own focus restore), give focus back unless the user put it somewhere else,
  // then run what was chosen.
  useEffect(() => {
    if (open || (!restoreTo.current && !chosen.current)) return
    const target = restoreTo.current
    const id = chosen.current
    restoreTo.current = null
    chosen.current = null
    requestAnimationFrame(() => {
      const active = document.activeElement
      const lost = !active || active === document.body || !!bar.current?.contains(active)
      if (lost && target instanceof HTMLElement && target.isConnected) target.focus()
      if (id) runItem(id)
    })
  }, [open, runItem])

  // Alt pressed and released on its own opens the first menu (or closes the open one).
  useEffect(() => {
    if (IS_MAC) return
    let altAlone = false
    const down = (event: globalThis.KeyboardEvent) => {
      altAlone = event.key === 'Alt' && !event.repeat
    }
    const up = (event: globalThis.KeyboardEvent) => {
      if (event.key !== 'Alt' || !altAlone) return
      altAlone = false
      if (open) setOpen(null)
      else {
        restoreTo.current ??= document.activeElement
        setOpen({ menu: 0, keyboard: true })
      }
    }
    window.addEventListener('keydown', down, true)
    window.addEventListener('keyup', up, true)
    return () => {
      window.removeEventListener('keydown', down, true)
      window.removeEventListener('keyup', up, true)
    }
  }, [open])

  const choose = (key: Key) => {
    chosen.current = String(key)
    setOpen(null)
  }

  if (menus.length === 0) return null
  return (
    <div
      className={styles.bar}
      ref={bar}
      role="menubar"
      aria-label="Menu"
      onPointerDownCapture={remember}
    >
      {menus.map((menu, index) => {
        const isOpen = open?.menu === index
        return (
          <MenuTrigger
            key={menu.label}
            isOpen={isOpen}
            onOpenChange={(next) => show(next ? index : null)}
          >
            <Pressable preventFocusOnPress>
              <button
                type="button"
                role="menuitem"
                className={cx(styles.top, isOpen && styles.topOpen)}
                onPointerEnter={() => open && !isOpen && show(index)}
              >
                {menu.label}
              </button>
            </Pressable>
            <Popover
              isNonModal
              placement="bottom start"
              offset={2}
              className={styles.panel}
              // A press on the bar is the bar's: it switches or closes the menu itself.
              shouldCloseOnInteractOutside={(element) => !bar.current?.contains(element)}
            >
              <div
                onKeyDown={(event) => {
                  // Left and Right walk the bar. Right on a submenu opens it and Left in
                  // one closes it (react-aria's): a submenu isn't inside this element.
                  const target = event.target as Element
                  if (event.key === 'ArrowRight' && !target.closest('[aria-haspopup]')) {
                    show(index + 1, true)
                  } else if (event.key === 'ArrowLeft' && event.currentTarget.contains(target)) {
                    show(index - 1, true)
                  }
                }}
              >
                <Panel
                  label={menu.label}
                  items={menu.items}
                  autoFocus={open?.keyboard ? 'first' : true}
                  onAction={choose}
                />
              </div>
            </Popover>
          </MenuTrigger>
        )
      })}
    </div>
  )
}

/** Consecutive toggles share a section, so they're `menuitemcheckbox`es and the rest stay plain items. */
type Group =
  | { kind: 'separator'; key: string }
  | { kind: 'item'; entry: Exclude<MenuEntry, { kind: 'separator' } | { kind: 'native' }> }
  | { kind: 'toggles'; key: string; entries: MenuCommandEntry[] }

function groupsOf(items: MenuEntry[]): Group[] {
  const out: Group[] = []
  items.forEach((entry, index) => {
    if (entry.kind === 'separator') out.push({ kind: 'separator', key: `separator:${index}` })
    // The system's own items (macOS only) have nothing to draw here.
    else if (entry.kind === 'native') return
    else if (entry.kind === 'item' && entry.checked !== undefined) {
      const last = out[out.length - 1]
      if (last?.kind === 'toggles') last.entries.push(entry)
      else out.push({ kind: 'toggles', key: `toggles:${index}`, entries: [entry] })
    } else out.push({ kind: 'item', entry })
  })
  return out
}

const disabledKeys = (items: MenuEntry[]): string[] =>
  items.flatMap((entry) =>
    entry.kind === 'item' && !entry.enabled
      ? [entry.id]
      : entry.kind === 'submenu' && !entry.enabled
        ? [`submenu:${entry.label}`]
        : [],
  )

function Panel({
  label,
  items,
  autoFocus,
  onAction,
}: {
  label: string
  items: MenuEntry[]
  autoFocus?: 'first' | true
  onAction: (key: Key) => void
}) {
  return (
    <Menu
      aria-label={label}
      className={styles.menu}
      autoFocus={autoFocus}
      disabledKeys={disabledKeys(items)}
      onAction={onAction}
    >
      {groupsOf(items).map((group) => {
        if (group.kind === 'separator') {
          return <Separator key={group.key} className={styles.separator} />
        }
        if (group.kind === 'toggles') {
          return (
            <MenuSection
              key={group.key}
              selectionMode="multiple"
              selectedKeys={group.entries.filter((it) => it.checked).map((it) => it.id)}
              shouldCloseOnSelect
            >
              {group.entries.map((entry) => (
                <Item
                  key={entry.id}
                  id={entry.id}
                  label={entry.label}
                  shortcut={entry.shortcut}
                  check={entry.checked && <Icon name="check" />}
                />
              ))}
            </MenuSection>
          )
        }
        const { entry } = group
        if (entry.kind === 'submenu') {
          return (
            <SubmenuTrigger key={`submenu:${entry.label}`}>
              <Item id={`submenu:${entry.label}`} label={entry.label} submenu />
              <Popover className={cx(styles.panel, styles.nested)} offset={2} crossOffset={-5}>
                <Panel label={entry.label} items={entry.items} onAction={onAction} />
              </Popover>
            </SubmenuTrigger>
          )
        }
        return <Item key={entry.id} id={entry.id} label={entry.label} shortcut={entry.shortcut} />
      })}
    </Menu>
  )
}

function Item({
  id,
  label,
  shortcut,
  submenu,
  check,
}: {
  id: string
  label: string
  shortcut?: string
  submenu?: boolean
  /** What goes in the check column. */
  check?: ReactNode
}) {
  return (
    <MenuItem id={id} textValue={label} className={styles.item}>
      <span className={styles.check}>{check}</span>
      <Text slot="label" className={styles.label}>
        {label}
      </Text>
      {shortcut && <Keys shortcut={shortcut} />}
      {submenu && <Icon name="chevronRight" />}
    </MenuItem>
  )
}
