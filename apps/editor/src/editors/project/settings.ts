/**
 * Pure edits to `netherforge.json`'s `settings`: the server-owner settings a
 * project declares. Each kind of setting is one entry in [SETTING_TYPES]
 * (keyed by format's `type`, so a kind format adds is a type error here until
 * it has one): what it's called and the setting a new one starts as. Like the
 * rest of the manifest's edits, `settings` leaves the file once it's empty.
 */
import type { ProjectManifest, SettingDef } from '@/core/format'

export type SettingType = SettingDef['type']

/** Each kind of setting: how the editor names it, and a new one with [description] (and [choices], for a choice). */
export const SETTING_TYPES: Record<
  SettingType,
  { label: string; blank: (description: string, choices: string[]) => SettingDef }
> = {
  boolean: {
    label: 'On or off',
    blank: (description) => ({ type: 'boolean', description, default: false }),
  },
  integer: {
    label: 'Whole number',
    blank: (description) => ({ type: 'integer', description, default: 0 }),
  },
  number: {
    label: 'Number',
    blank: (description) => ({ type: 'number', description, default: 0 }),
  },
  string: { label: 'Text', blank: (description) => ({ type: 'string', description, default: '' }) },
  choice: {
    label: 'Choice',
    blank: (description, choices) => ({
      type: 'choice',
      description,
      default: choices[0] ?? '',
      choices,
    }),
  },
}

export const SETTING_TYPE_OPTIONS = (Object.keys(SETTING_TYPES) as SettingType[]).map((value) => ({
  value,
  label: SETTING_TYPES[value].label,
}))

/** Choices typed one per line: trimmed, the empty lines left out. */
export function choicesOf(text: string): string[] {
  return text
    .split('\n')
    .map((it) => it.trim())
    .filter((it) => it !== '')
}

export function addSetting(manifest: ProjectManifest, name: string, setting: SettingDef) {
  manifest.settings = { ...manifest.settings, [name]: setting }
}

export function removeSetting(manifest: ProjectManifest, name: string) {
  if (!manifest.settings) return
  delete manifest.settings[name]
  if (Object.keys(manifest.settings).length === 0) delete manifest.settings
}

/** Makes [name] another kind of setting: its description stays, the rest starts over as that kind's. */
export function changeSettingType(manifest: ProjectManifest, name: string, type: SettingType) {
  const setting = manifest.settings?.[name]
  if (!setting || setting.type === type) return
  const choices = setting.type === 'choice' ? setting.choices : []
  manifest.settings![name] = SETTING_TYPES[type].blank(setting.description, choices)
}

/** Changes [name] in place with [recipe]; a setting that isn't there is left alone. */
export function editSetting(
  manifest: ProjectManifest,
  name: string,
  recipe: (setting: SettingDef) => void,
) {
  const setting = manifest.settings?.[name]
  if (setting) recipe(setting)
}

/**
 * A choice setting's [choices]: the default follows along when the choice it
 * was is gone (to the first one left), so the file stays valid where it can.
 */
export function setChoices(manifest: ProjectManifest, name: string, choices: string[]) {
  editSetting(manifest, name, (setting) => {
    if (setting.type !== 'choice') return
    setting.choices = choices
    if (!choices.includes(setting.default) && choices.length > 0) setting.default = choices[0]!
  })
}

/** A bound (`min`, `max`) of a number setting, or (undefined) none; a whole number's is rounded. */
export function setBound(
  manifest: ProjectManifest,
  name: string,
  bound: 'min' | 'max',
  value: number | undefined,
) {
  editSetting(manifest, name, (setting) => {
    if (setting.type !== 'integer' && setting.type !== 'number') return
    if (value === undefined) delete setting[bound]
    else setting[bound] = setting.type === 'integer' ? Math.round(value) : value
  })
}
