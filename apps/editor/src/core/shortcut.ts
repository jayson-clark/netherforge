/**
 * Keyboard shortcuts as the menus write them (`Mod+Shift+S`): matched
 * against key events, shown in the in-app menus, and turned into Tauri
 * accelerators for the macOS menu bar. Mod is Cmd on macOS and Ctrl
 * elsewhere; Ctrl is the Control key on every OS.
 */

export interface Shortcut {
  mod: boolean
  ctrl: boolean
  shift: boolean
  alt: boolean
  /** As `KeyboardEvent.key` spells it, a letter in upper case: `S`, `=`, `Tab`. */
  key: string
}

export function parseShortcut(text: string): Shortcut {
  // The key is whatever follows the last `+` (so `Mod++` would be the plus key).
  const at = text.lastIndexOf('+', text.length - 2)
  const mods = at < 0 ? [] : text.slice(0, at).split('+')
  const key = text.slice(at + 1)
  return {
    mod: mods.includes('Mod'),
    ctrl: mods.includes('Ctrl'),
    shift: mods.includes('Shift'),
    alt: mods.includes('Alt'),
    key: key.length === 1 ? key.toUpperCase() : key,
  }
}

/** Keys whose shifted character is the same key (`=`/`+`): Shift doesn't count for them. */
const SHIFT_FREE: Record<string, string[]> = { '=': ['=', '+'], '-': ['-', '_'] }

/** Whether [event] is [shortcut] (Mod as [mac] spells it). */
export function matchesShortcut(
  event: Pick<KeyboardEvent, 'key' | 'code' | 'metaKey' | 'ctrlKey' | 'shiftKey' | 'altKey'>,
  shortcut: Shortcut,
  mac: boolean,
): boolean {
  const meta = mac && shortcut.mod
  const ctrl = shortcut.ctrl || (!mac && shortcut.mod)
  if (event.metaKey !== meta || event.ctrlKey !== ctrl || event.altKey !== shortcut.alt) {
    return false
  }
  const { key } = shortcut
  const shiftFree = SHIFT_FREE[key]
  if (shiftFree) return shiftFree.includes(event.key)
  if (event.shiftKey !== shortcut.shift) return false
  // Letters and digits by the physical key: Alt changes event.key on a Mac (Alt+B is ∫).
  if (/^[A-Z]$/.test(key)) return event.code === `Key${key}`
  if (/^[0-9]$/.test(key)) return event.code === `Digit${key}` || event.key === key
  return event.key === key
}

/** Whether this is a Mac, where Mod is Cmd and shortcuts are written as symbols. */
export const IS_MAC = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform)

/** How each key is written, where it differs from its name. */
const KEY_LABELS: Record<string, [mac: string, other: string]> = {
  '-': ['−', '−'],
  '=': ['+', '+'],
  Backspace: ['⌫', 'Backspace'],
  Delete: ['Del', 'Del'],
  Enter: ['↩', 'Enter'],
  Escape: ['Esc', 'Esc'],
}

/** Its keys one label each, in the order the OS writes them: `⇧ ⌘ S` on macOS, `Ctrl Shift S` elsewhere. */
export function shortcutKeys(shortcut: Shortcut, mac: boolean): string[] {
  const label = KEY_LABELS[shortcut.key]
  const key = label ? label[mac ? 0 : 1] : shortcut.key
  const mods = mac
    ? [shortcut.ctrl && '⌃', shortcut.alt && '⌥', shortcut.shift && '⇧', shortcut.mod && '⌘']
    : [(shortcut.ctrl || shortcut.mod) && 'Ctrl', shortcut.alt && 'Alt', shortcut.shift && 'Shift']
  return [...mods.filter((it): it is string => !!it), key]
}

/** As plain text, for tooltips: `⇧⌘S` on macOS, `Ctrl+Shift+S` elsewhere. */
export function formatShortcut(shortcut: Shortcut, mac: boolean): string {
  return shortcutKeys(shortcut, mac).join(mac ? '' : '+')
}

/** The Tauri accelerator for it (`CmdOrCtrl+Shift+S`), which the macOS menu bar shows and answers. */
export function acceleratorOf(shortcut: Shortcut): string {
  return [
    shortcut.mod && 'CmdOrCtrl',
    shortcut.ctrl && 'Ctrl',
    shortcut.alt && 'Alt',
    shortcut.shift && 'Shift',
    shortcut.key,
  ]
    .filter(Boolean)
    .join('+')
}
