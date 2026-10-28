/**
 * Settings › Server-owner settings: the values the dev server runs the
 * project's (and its packages') settings with, as a form. What a project
 * declares is in `netherforge.json` (the project settings' Settings section);
 * this sets what the server's owner would, on the dev server, through the
 * bridge (`set_setting`), which writes the server's
 * `plugins/NetherForge/settings/<namespace>.json` and tells the scripts. A
 * value is read as the server reads it (format's `readSetting`) before it's
 * sent, so a typo is said at the field.
 */
import { useId, useState } from 'react'
import { readSetting, type PackageSettings, type SettingState } from '@/core/format'
import { useApp, useRun } from '@/state/providers'
import { Badge } from '@/ui/Badge'
import { Button } from '@/ui/Button'
import { FormError } from '@/ui/fields'
import { Hint, Mono, Muted } from '@/ui/text'
import styles from './SettingsScreen.module.css'

export function OwnerSettings() {
  const { workspace } = useApp()
  const connected = useRun((s) => s.server.bridgeConnected)
  const settings = useRun((s) => s.serverSettings)
  return (
    <div>
      <p>
        What whoever runs this project may change without touching its scripts. The project and each
        package it uses declare their own in <Mono>netherforge.json</Mono>; scripts read them with{' '}
        <Mono>nf.config</Mono>. Here they&apos;re set for the dev server, as a server&apos;s owner
        sets them with <Mono>/nf settings</Mono>: a script that listens for{' '}
        <Mono>setting_changed</Mono> hears the change, and one that read the setting otherwise
        restarts.
      </p>
      <Button
        size="small"
        icon="file"
        onClick={() => void workspace.getState().openFile('netherforge.json')}
      >
        Declare settings…
      </Button>
      {!connected ? (
        <Hint role="status" aria-label="Server-owner settings status" className={styles.after}>
          Start the dev server to see and change the values it runs with.
        </Hint>
      ) : !settings ? (
        <Hint className={styles.after}>Loading…</Hint>
      ) : settings.packages.length === 0 ? (
        <Hint role="status" aria-label="Server-owner settings status" className={styles.after}>
          Neither the project nor a package it uses declares any settings.
        </Hint>
      ) : (
        settings.packages.map((pkg) => <PackageForm key={pkg.namespace} pkg={pkg} />)
      )}
    </div>
  )
}

function PackageForm({ pkg }: { pkg: PackageSettings }) {
  const names = Object.keys(pkg.settings).sort()
  return (
    <section aria-label={`${pkg.name} settings`}>
      <h3>
        {pkg.name} <Muted>({pkg.namespace})</Muted>
      </h3>
      <Hint>
        Kept in the dev server&apos;s <Mono>{pkg.file}</Mono>.
      </Hint>
      <div className={styles.settings}>
        {names.map((name) => (
          <SettingRow
            key={name}
            namespace={pkg.namespace}
            name={name}
            state={pkg.settings[name]!}
          />
        ))}
      </div>
    </section>
  )
}

/** One setting: its value as a control of its kind, whether it's the default, and its description. */
function SettingRow({
  namespace,
  name,
  state,
}: {
  namespace: string
  name: string
  state: SettingState
}) {
  const { run } = useApp()
  const id = useId()
  const [error, setError] = useState<string | null>(null)
  const send = (value: unknown) => {
    setError(null)
    run
      .getState()
      .setSetting(namespace, name, value)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
  }
  /** Words typed into a box, read as the server reads them: the value, or what's wrong at the field. */
  const sendTyped = (text: string) => {
    const read = readSetting(state.definition, text, true)
    if (read.error !== undefined) setError(`${name}: ${read.error}`)
    else if (JSON.stringify(read.value) !== JSON.stringify(state.value)) send(read.value)
    else setError(null)
  }
  const definition = state.definition
  return (
    <div className={styles.setting} data-setting={`${namespace}:${name}`}>
      <label htmlFor={id}>
        <Mono>{name}</Mono>
      </label>
      <div className={styles.settingValue}>
        {definition.type === 'boolean' ? (
          <input
            id={id}
            type="checkbox"
            checked={state.value === true}
            onChange={(event) => send(event.target.checked)}
          />
        ) : definition.type === 'choice' ? (
          <select
            id={id}
            value={String(state.value)}
            onChange={(event) => send(event.target.value)}
          >
            {definition.choices.map((choice) => (
              <option key={choice} value={choice}>
                {choice}
              </option>
            ))}
          </select>
        ) : (
          <TypedInput
            id={id}
            value={String(state.value)}
            numeric={definition.type !== 'string'}
            onCommit={sendTyped}
          />
        )}
        {state.set ? (
          <Button size="small" variant="ghost" onClick={() => send(null)}>
            Reset to {String(definition.default)}
          </Button>
        ) : (
          <Badge title="The owner hasn't set it">default</Badge>
        )}
      </div>
      <Hint className={styles.settingHint}>{definition.description}</Hint>
      {state.problem && (
        <Hint tone="error" className={styles.settingHint}>
          The server&apos;s file has a value it can&apos;t use: {state.problem}.
        </Hint>
      )}
      {error && (
        <FormError role="alert" className={styles.settingHint}>
          {error}
        </FormError>
      )}
    </div>
  )
}

/** A text box committing on Enter or blur, like every field; Escape puts the value back. */
function TypedInput({
  id,
  value,
  numeric,
  onCommit,
}: {
  id: string
  value: string
  numeric: boolean
  onCommit: (text: string) => void
}) {
  const [text, setText] = useState(value)
  const [shown, setShown] = useState(value)
  if (shown !== value) {
    setShown(value)
    setText(value)
  }
  return (
    <input
      id={id}
      inputMode={numeric ? 'decimal' : undefined}
      value={text}
      data-commit={text === value ? 'clean' : 'draft'}
      onChange={(event) => setText(event.target.value)}
      onBlur={() => {
        if (text !== value) onCommit(text)
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter') onCommit(text)
        if (event.key === 'Escape') setText(value)
      }}
    />
  )
}
