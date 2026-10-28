/**
 * `netherforge.json`'s `settings` in the project settings: each server-owner
 * setting's type, description, default and bounds or choices, edited in
 * place, and "Add setting…", a dialog asking what a new one is. The values a
 * server runs them with aren't here: they're the server's owner's (Settings ›
 * Server-owner settings, for the dev server).
 */
import { useState } from 'react'
import { ID_PATTERN, ID_RULE, type ProjectManifest, type SettingDef } from '@/core/format'
import { Button, IconButton } from '@/ui/Button'
import { Modal } from '@/ui/dialogs'
import {
  CheckField,
  FormError,
  FormLabel,
  NumberField,
  Section,
  SelectField,
  TextField,
} from '@/ui/fields'
import { Spacer } from '@/ui/layout'
import { Hint, Mono, Muted } from '@/ui/text'
import styles from './ProjectEditor.module.css'
import {
  SETTING_TYPE_OPTIONS,
  SETTING_TYPES,
  addSetting,
  changeSettingType,
  choicesOf,
  editSetting,
  removeSetting,
  setBound,
  setChoices,
  type SettingType,
} from './settings'

type Edit = (recipe: (draft: ProjectManifest) => void) => void

export function SettingsSection({
  settings,
  edit,
}: {
  settings: ProjectManifest['settings']
  edit: Edit
}) {
  const [adding, setAdding] = useState(false)
  const names = Object.keys(settings ?? {}).sort()
  return (
    <Section
      title="Server-owner settings"
      actions={
        <Button size="small" icon="plus" onClick={() => setAdding(true)}>
          Add setting…
        </Button>
      }
    >
      <Hint>
        What whoever runs this project may change without touching its scripts, like a plugin&apos;s
        config. Scripts read one with <Mono>nf.config(&quot;name&quot;)</Mono>; the server&apos;s
        owner sets it with <Mono>/nf settings</Mono>, and for the dev server in Settings ›
        Server-owner settings.
      </Hint>
      {names.length === 0 && <Muted>None.</Muted>}
      {names.map((name) => (
        <SettingEditor key={name} name={name} setting={settings![name]!} edit={edit} />
      ))}
      {adding && (
        <AddSettingDialog
          taken={names}
          onClose={() => setAdding(false)}
          onAdd={(name, setting) => {
            setAdding(false)
            edit((draft) => addSetting(draft, name, setting))
          }}
        />
      )}
    </Section>
  )
}

function SettingEditor({ name, setting, edit }: { name: string; setting: SettingDef; edit: Edit }) {
  const path = `settings.${name}`
  const change = (recipe: (setting: SettingDef) => void) =>
    edit((draft) => editSetting(draft, name, recipe))
  return (
    <fieldset className={styles.setting} aria-label={`Setting ${name}`} data-path={path}>
      <legend className={styles.settingName}>
        <Mono>{name}</Mono>
        <IconButton
          icon="trash"
          label={`Remove setting ${name}`}
          onClick={() => edit((draft) => removeSetting(draft, name))}
        />
      </legend>
      <SelectField
        label="Type"
        dataPath={`${path}.type`}
        value={setting.type}
        options={SETTING_TYPE_OPTIONS}
        onChange={(type) => edit((draft) => changeSettingType(draft, name, type))}
      />
      <TextField
        label="Description"
        dataPath={`${path}.description`}
        multiline
        value={setting.description}
        onChange={(description) =>
          change((it) => {
            it.description = description
          })
        }
      />
      <DefaultField path={path} setting={setting} change={change} />
      {(setting.type === 'integer' || setting.type === 'number') && (
        <>
          <NumberField
            label="Least"
            dataPath={`${path}.min`}
            value={setting.min}
            placeholder="None"
            step={1}
            onChange={(value) => edit((draft) => setBound(draft, name, 'min', value))}
          />
          <NumberField
            label="Most"
            dataPath={`${path}.max`}
            value={setting.max}
            placeholder="None"
            step={1}
            onChange={(value) => edit((draft) => setBound(draft, name, 'max', value))}
          />
        </>
      )}
      {setting.type === 'choice' && (
        <TextField
          label="Choices"
          dataPath={`${path}.choices`}
          multiline
          placeholder="One per line"
          value={setting.choices.join('\n')}
          onChange={(text) => edit((draft) => setChoices(draft, name, choicesOf(text)))}
        />
      )}
    </fieldset>
  )
}

/** The setting's default, as a control of its kind (a whole number's rounded, so the file stays one). */
function DefaultField({
  path,
  setting,
  change,
}: {
  path: string
  setting: SettingDef
  change: (recipe: (setting: SettingDef) => void) => void
}) {
  const dataPath = `${path}.default`
  switch (setting.type) {
    case 'boolean':
      return (
        <CheckField
          label="Default"
          dataPath={dataPath}
          value={setting.default}
          onChange={(value) =>
            change((it) => {
              if (it.type === 'boolean') it.default = value
            })
          }
        />
      )
    case 'integer':
    case 'number':
      return (
        <NumberField
          label="Default"
          dataPath={dataPath}
          value={setting.default}
          step={1}
          onChange={(value) =>
            change((it) => {
              if (value === undefined) return
              if (it.type === 'integer') it.default = Math.round(value)
              else if (it.type === 'number') it.default = value
            })
          }
        />
      )
    case 'string':
      return (
        <TextField
          label="Default"
          dataPath={dataPath}
          value={setting.default}
          onChange={(value) =>
            change((it) => {
              if (it.type === 'string') it.default = value
            })
          }
        />
      )
    case 'choice':
      return (
        <SelectField
          label="Default"
          dataPath={dataPath}
          value={setting.default}
          options={
            setting.choices.includes(setting.default)
              ? setting.choices
              : [setting.default, ...setting.choices]
          }
          onChange={(value) =>
            change((it) => {
              if (it.type === 'choice') it.default = value
            })
          }
        />
      )
  }
}

/** "Add setting…": its name (an id no other setting has), its type, what it's for and, for a choice, its choices. */
function AddSettingDialog({
  taken,
  onAdd,
  onClose,
}: {
  taken: string[]
  onAdd: (name: string, setting: SettingDef) => void
  onClose: () => void
}) {
  const [name, setName] = useState('')
  const [type, setType] = useState<SettingType>('integer')
  const [description, setDescription] = useState('')
  const [choices, setChoicesText] = useState('')
  const trimmed = name.trim()
  const nameError =
    trimmed === ''
      ? null
      : !ID_PATTERN.test(trimmed)
        ? `A setting's name is ${ID_RULE}`
        : taken.includes(trimmed)
          ? 'The project has a setting of that name already'
          : null
  const listed = choicesOf(choices)
  const choicesError = type === 'choice' && listed.length === 0 ? 'A choice needs choices' : null
  const ready = trimmed !== '' && nameError === null && choicesError === null
  const add = () => {
    if (ready) onAdd(trimmed, SETTING_TYPES[type].blank(description.trim(), listed))
  }
  return (
    <Modal
      title="Add setting"
      onClose={onClose}
      footer={
        <>
          <Spacer />
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={!ready} onClick={add}>
            Add
          </Button>
        </>
      }
    >
      <form
        className={styles.addSetting}
        onSubmit={(event) => {
          event.preventDefault()
          add()
        }}
      >
        <FormLabel htmlFor="setting-name">Name</FormLabel>
        <input
          id="setting-name"
          autoFocus
          placeholder="max_players"
          value={name}
          aria-invalid={nameError !== null}
          onChange={(event) => setName(event.target.value)}
        />
        {nameError && <FormError role="alert">{nameError}</FormError>}
        <FormLabel htmlFor="setting-type">Type</FormLabel>
        <select
          id="setting-type"
          value={type}
          onChange={(event) => setType(event.target.value as SettingType)}
        >
          {SETTING_TYPE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <FormLabel htmlFor="setting-description">Description</FormLabel>
        <textarea
          id="setting-description"
          rows={2}
          placeholder="What it's for, for whoever runs the project"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
        {type === 'choice' && (
          <>
            <FormLabel htmlFor="setting-choices">Choices, one per line</FormLabel>
            <textarea
              id="setting-choices"
              rows={3}
              value={choices}
              onChange={(event) => setChoicesText(event.target.value)}
            />
            {choicesError && <Hint tone="error">{choicesError}</Hint>}
          </>
        )}
        <Hint>
          It starts at {type === 'choice' ? 'its first choice' : 'a default of its type'}; change
          the default and its bounds once it&apos;s added.
        </Hint>
      </form>
    </Modal>
  )
}
