import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type ProjectManifest } from '@/core/format'
import {
  SETTING_TYPES,
  addSetting,
  changeSettingType,
  choicesOf,
  editSetting,
  removeSetting,
  setBound,
  setChoices,
} from './settings'

const manifest = (): ProjectManifest => ({
  formatVersion: 1,
  name: 'Test',
  namespace: 'test',
  version: '1.0.0',
  minecraft: '26.3',
})
const canonical = (model: ProjectManifest) => {
  const result = canonicalizeModel('netherforge', 'netherforge.json', model)
  expect(result.problems).toEqual([])
  return JSON.parse(result.text!) as ProjectManifest
}

describe('server-owner settings in netherforge.json', () => {
  it('adds each kind of setting as format writes it, and drops settings once the last goes', () => {
    const model = manifest()
    addSetting(model, 'rounds', SETTING_TYPES.integer.blank('Rounds a game lasts.', []))
    addSetting(model, 'size', SETTING_TYPES.choice.blank('How big.', ['small', 'large']))
    addSetting(model, 'pvp', SETTING_TYPES.boolean.blank('Hurting each other.', []))
    // What the editor holds is what format writes: saving it changes nothing.
    expect(canonical(model).settings).toEqual(model.settings)
    expect(model.settings!.size).toEqual({
      type: 'choice',
      description: 'How big.',
      default: 'small',
      choices: ['small', 'large'],
    })
    for (const name of ['rounds', 'size', 'pvp']) removeSetting(model, name)
    expect('settings' in model).toBe(false)
  })

  it('changing the type starts the setting over as that type, keeping its description', () => {
    const model = manifest()
    addSetting(model, 'size', SETTING_TYPES.choice.blank('How big.', ['small', 'large']))
    changeSettingType(model, 'size', 'string')
    expect(model.settings!.size).toEqual({ type: 'string', description: 'How big.', default: '' })
    changeSettingType(model, 'size', 'number')
    expect(canonical(model).settings!.size).toEqual({
      type: 'number',
      description: 'How big.',
      default: 0,
    })
  })

  it('rounds a whole number’s bounds, and takes a bound away when it’s cleared', () => {
    const model = manifest()
    addSetting(model, 'rounds', SETTING_TYPES.integer.blank('Rounds.', []))
    setBound(model, 'rounds', 'min', 1.6)
    setBound(model, 'rounds', 'max', 10)
    expect(canonical(model).settings!.rounds).toMatchObject({ min: 2, max: 10 })
    setBound(model, 'rounds', 'min', undefined)
    expect('min' in model.settings!.rounds!).toBe(false)
  })

  it('a choice’s default follows when its choice goes', () => {
    const model = manifest()
    addSetting(model, 'size', SETTING_TYPES.choice.blank('How big.', ['small', 'large']))
    editSetting(model, 'size', (it) => {
      if (it.type === 'choice') it.default = 'large'
    })
    setChoices(model, 'size', choicesOf(' huge \n\nsmall\n'))
    expect(model.settings!.size).toMatchObject({ choices: ['huge', 'small'], default: 'huge' })
  })
})
