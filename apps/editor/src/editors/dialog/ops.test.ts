import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type DialogFile } from '@/core/format'
import { exampleFiles } from '@/testing/fixtures'
import {
  entryLabel,
  addBody,
  addButton,
  addInput,
  arrangeButtons,
  canAddButton,
  freeKey,
  moveEntry,
  removeEntry,
  setType,
} from './ops'

const WELCOME = 'dialogs/welcome/dialog.json'
const welcome = () => JSON.parse(exampleFiles[WELCOME]!) as DialogFile

describe('dialog ops', () => {
  it('finds free keys', () => {
    expect(freeKey([], 'ok')).toBe('ok')
    expect(freeKey(['ok', 'ok_2'], 'ok')).toBe('ok_3')
  })

  it('adds, reorders and removes entries, dropping empty lists', () => {
    const file = welcome()
    expect(addInput(file, 'boolean')).toBe(1)
    expect(addInput(file, 'boolean')).toBe(2)
    expect(file.inputs!.map((it) => it.key)).toEqual(['nickname', 'boolean', 'boolean_2'])
    moveEntry(file, 'inputs', 2, 0)
    expect(file.inputs!.map((it) => it.key)).toEqual(['boolean_2', 'nickname', 'boolean'])
    moveEntry(file, 'inputs', 0, 9)
    expect(file.inputs![0]!.key).toBe('boolean_2')
    removeEntry(file, 'inputs', 0)
    removeEntry(file, 'inputs', 0)
    removeEntry(file, 'inputs', 0)
    expect(file.inputs).toBeUndefined()
    expect(addBody(file, { type: 'message', text: 'Hi' })).toBe(1)
  })

  it('respects the button limit of each type', () => {
    const file = welcome()
    expect(canAddButton(file)).toBe(false)
    setType(file, 'confirmation')
    expect(canAddButton(file)).toBe(true)
    expect(addButton(file, 'cancel')).toBe(1)
    expect(file.buttons![1]).toEqual({ key: 'cancel', label: 'cancel' })
    expect(canAddButton(file)).toBe(false)
    setType(file, 'multi_action')
    expect(canAddButton(file)).toBe(true)
  })

  it('drops columns and dialogs when the type no longer has them', () => {
    const file: DialogFile = { title: 't', type: 'multi_action', columns: 3 }
    setType(file, 'dialog_list')
    expect(file.columns).toBeUndefined()
    file.dialogs = ['a']
    setType(file, 'notice')
    expect(file).toEqual({ title: 't' })
  })

  it('arranges buttons where the game puts them', () => {
    const file: DialogFile = {
      title: 't',
      type: 'multi_action',
      columns: 2,
      buttons: [{ key: 'a' }, { key: 'b', width: 100 }, { key: 'c', label: 'C!' }],
    }
    const multi = arrangeButtons(file)
    expect(multi.footer).toEqual([])
    expect(multi.grid.map((row) => row.map((it) => it.label))).toEqual([['a', 'b'], ['C!']])
    expect(multi.grid[0]![1]!.width).toBe(100)

    const list = arrangeButtons(
      { title: 't', type: 'dialog_list', dialogs: ['welcome', 'gone'], buttons: [{ key: 'exit' }] },
      { welcome: { title: 'Welcome!', externalTitle: 'Say hello' } },
    )
    expect(list.grid[0]!.map((it) => [it.label, it.index])).toEqual([
      ['Say hello', null],
      ['gone', null],
    ])
    expect(list.footer.map((it) => it.label)).toEqual(['exit'])
    expect(arrangeButtons(welcome()).footer.map((it) => it.label)).toEqual(['Done'])
  })

  it('writes an edited dialog canonically', () => {
    const file = welcome()
    setType(file, 'confirmation')
    addButton(file, 'cancel')
    const text = canonicalizeModel('dialog', WELCOME, file).text!
    expect(text).toContain('"type": "confirmation"')
    expect(text.indexOf('"done"')).toBeLessThan(text.indexOf('"cancel"'))
  })
})

describe('entryLabel', () => {
  it('names entries by their text without tags', () => {
    expect(entryLabel('body', { type: 'message', text: '<gold>Hello   there' })).toBe('Hello there')
    expect(
      entryLabel('body', { type: 'message', text: 'A very long message that keeps on going' }),
    ).toBe('A very long message that keeps …')
    expect(entryLabel('body', { type: 'item', item: { kind: 'minecraft:apple' } })).toBe(
      'Item: minecraft:apple',
    )
    expect(entryLabel('inputs', { type: 'single_option', key: 'mode', options: [] })).toBe(
      'mode (single option)',
    )
    expect(entryLabel('buttons', { key: 'ok', label: '<green>Okay' })).toBe('Okay')
    expect(entryLabel('buttons', { key: 'ok' })).toBe('ok')
  })
})
