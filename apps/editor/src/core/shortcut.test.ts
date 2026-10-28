import { describe, expect, it } from 'vitest'
import {
  acceleratorOf,
  formatShortcut,
  matchesShortcut,
  parseShortcut,
  shortcutKeys,
} from './shortcut'

const key = (
  init: Partial<
    Pick<KeyboardEvent, 'key' | 'code' | 'metaKey' | 'ctrlKey' | 'shiftKey' | 'altKey'>
  >,
) => ({
  key: '',
  code: '',
  metaKey: false,
  ctrlKey: false,
  shiftKey: false,
  altKey: false,
  ...init,
})

describe('parseShortcut', () => {
  it('reads modifiers and the key', () => {
    expect(parseShortcut('Mod+Shift+s')).toEqual({
      mod: true,
      ctrl: false,
      shift: true,
      alt: false,
      key: 'S',
    })
    expect(parseShortcut('Ctrl+Tab').key).toBe('Tab')
    expect(parseShortcut('Mod+=').key).toBe('=')
    expect(parseShortcut('Mod++').key).toBe('+')
  })
})

describe('matchesShortcut', () => {
  const save = parseShortcut('Mod+S')

  it('takes Mod as Cmd on a Mac and Ctrl elsewhere', () => {
    expect(matchesShortcut(key({ key: 's', code: 'KeyS', metaKey: true }), save, true)).toBe(true)
    expect(matchesShortcut(key({ key: 's', code: 'KeyS', ctrlKey: true }), save, true)).toBe(false)
    expect(matchesShortcut(key({ key: 's', code: 'KeyS', ctrlKey: true }), save, false)).toBe(true)
    expect(matchesShortcut(key({ key: 's', code: 'KeyS', metaKey: true }), save, false)).toBe(false)
  })

  it('needs exactly its modifiers', () => {
    const shifted = key({ key: 'S', code: 'KeyS', metaKey: true, shiftKey: true })
    expect(matchesShortcut(shifted, save, true)).toBe(false)
    expect(matchesShortcut(shifted, parseShortcut('Mod+Shift+S'), true)).toBe(true)
  })

  it('reads letters by the physical key, so Alt on a Mac still matches', () => {
    const altB = key({ key: '∫', code: 'KeyB', metaKey: true, altKey: true })
    expect(matchesShortcut(altB, parseShortcut('Mod+Alt+B'), true)).toBe(true)
  })

  it('takes + and _ for zoom with or without Shift', () => {
    const zoomIn = parseShortcut('Mod+=')
    expect(matchesShortcut(key({ key: '=', metaKey: true }), zoomIn, true)).toBe(true)
    expect(matchesShortcut(key({ key: '+', metaKey: true, shiftKey: true }), zoomIn, true)).toBe(
      true,
    )
    expect(
      matchesShortcut(
        key({ key: '_', ctrlKey: true, shiftKey: true }),
        parseShortcut('Mod+-'),
        false,
      ),
    ).toBe(true)
  })

  it('keeps Ctrl apart from Mod on a Mac', () => {
    const next = parseShortcut('Ctrl+Tab')
    expect(matchesShortcut(key({ key: 'Tab', ctrlKey: true }), next, true)).toBe(true)
    expect(matchesShortcut(key({ key: 'Tab', metaKey: true }), next, true)).toBe(false)
    expect(matchesShortcut(key({ key: 'Tab', ctrlKey: true }), next, false)).toBe(true)
  })
})

describe('formatShortcut and acceleratorOf', () => {
  it('writes each OS its own way', () => {
    expect(formatShortcut(parseShortcut('Mod+Shift+S'), true)).toBe('⇧⌘S')
    expect(formatShortcut(parseShortcut('Mod+Shift+S'), false)).toBe('Ctrl+Shift+S')
    expect(formatShortcut(parseShortcut('Mod+-'), false)).toBe('Ctrl+−')
    expect(formatShortcut(parseShortcut('Ctrl+Tab'), true)).toBe('⌃Tab')
  })

  it('gives each key its own label, for spacing them apart', () => {
    expect(shortcutKeys(parseShortcut('Mod+Alt+B'), true)).toEqual(['⌥', '⌘', 'B'])
    expect(shortcutKeys(parseShortcut('Mod+Alt+B'), false)).toEqual(['Ctrl', 'Alt', 'B'])
    expect(shortcutKeys(parseShortcut('Mod+Backspace'), true)).toEqual(['⌘', '⌫'])
    expect(shortcutKeys(parseShortcut('Mod+Backspace'), false)).toEqual(['Ctrl', 'Backspace'])
    expect(shortcutKeys(parseShortcut('Delete'), true)).toEqual(['Del'])
  })

  it('gives Tauri CmdOrCtrl for Mod', () => {
    expect(acceleratorOf(parseShortcut('Mod+Alt+B'))).toBe('CmdOrCtrl+Alt+B')
    expect(acceleratorOf(parseShortcut('Ctrl+Shift+Tab'))).toBe('Ctrl+Shift+Tab')
    expect(acceleratorOf(parseShortcut('Mod+='))).toBe('CmdOrCtrl+=')
  })
})
