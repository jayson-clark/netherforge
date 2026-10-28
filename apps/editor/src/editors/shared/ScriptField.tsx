/**
 * A resource's one `script: { file }` (a centity's, a menu's, a dialog's, an
 * item's): pick one of the resource's .lua files, create one from a template,
 * open it, or unlink it; and add a file beside it for the script to
 * `require`. Scripts are sibling files, never JSON.
 */
import { filesIn, splitPackagePath } from '@/core/store/packages'
import { SCRIPT_DEFAULT_BUDGET, type ScriptDef } from '@/core/format'
import { LUA_FILE_PATTERN } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { NumberField, Row, SelectField } from '@/ui/fields'
import { Button } from '@/ui/Button'
import styles from './ScriptField.module.css'

export function ScriptField({
  folder,
  script,
  onChange,
  suggestedName,
  template,
  siblingTemplate,
  dataPath,
  label = 'Script',
}: {
  /** The resource folder, `menus/shop`. */
  folder: string
  script: ScriptDef | undefined
  onChange: (script: ScriptDef | undefined) => void
  /** File name offered when creating one. */
  suggestedName: string
  /** The new file's text. */
  template: string
  /** A new file beside the script's text (format's `newSiblingFile`). */
  siblingTemplate: string
  /** JSON path of the `script` key, for focus requests. */
  dataPath?: string
  label?: string
}) {
  const { workspace } = useApp()
  // A package's resource (read-only) has its scripts in the package.
  const files = useWorkspace((s) => filesIn(s, splitPackagePath(folder)[0]))
  const luaFiles = files
    .filter((it) => it.startsWith(`${folder}/`) && it.endsWith('.lua'))
    .map((it) => it.slice(folder.length + 1))

  const create = async () => {
    const free = (name: string) => {
      if (!luaFiles.includes(name)) return name
      for (let n = 2; ; n += 1) {
        const next = name.replace(/\.lua$/, `_${n}.lua`)
        if (!luaFiles.includes(next)) return next
      }
    }
    const file = await ask.prompt({
      title: 'New script',
      label: 'File name',
      initial: free(suggestedName),
      confirmLabel: 'Create',
      validate: (value) =>
        !LUA_FILE_PATTERN.test(value)
          ? 'A .lua file named with letters, digits and _'
          : luaFiles.includes(value)
            ? 'That file exists; link it instead'
            : null,
    })
    if (!file) return
    await workspace.getState().createFile(`${folder}/${file}`, template)
    onChange({ ...script, file })
    await workspace.getState().openFile(`${folder}/${file}`)
  }

  /** A file beside the script, in its folder, which it reaches with `require`. */
  const createSibling = async (file: string) => {
    const at = file.includes('/') ? file.slice(0, file.lastIndexOf('/') + 1) : ''
    const name = await ask.prompt({
      title: 'New file beside the script',
      label: 'File',
      message: `The script loads it with require: helpers.lua is require("helpers"), lib/util.lua is require("lib.util"). It runs as part of the script, with the same this.`,
      initial: 'helpers.lua',
      confirmLabel: 'Create',
      validate: (value) =>
        !LUA_FILE_PATTERN.test(value)
          ? 'A .lua path named with letters, digits and _ (folders allowed: lib/util.lua)'
          : luaFiles.includes(at + value)
            ? 'That file exists'
            : null,
    })
    if (!name) return
    await workspace.getState().createFile(`${folder}/${at}${name}`, siblingTemplate)
    await workspace.getState().openFile(`${folder}/${at}${name}`)
  }

  if (!script) {
    return (
      <Row label={label}>
        <div className={styles.actions} data-path={dataPath}>
          <Button size="small" icon="plus" onClick={() => void create()}>
            New script
          </Button>
          {luaFiles.length > 0 && (
            <select
              aria-label={`Link existing ${label.toLowerCase()}`}
              value=""
              onChange={(event) => event.target.value && onChange({ file: event.target.value })}
            >
              <option value="">Link existing…</option>
              {luaFiles.map((it) => (
                <option key={it} value={it}>
                  {it}
                </option>
              ))}
            </select>
          )}
        </div>
      </Row>
    )
  }
  return (
    <>
      <SelectField
        label={label}
        dataPath={dataPath ? `${dataPath}.file` : undefined}
        value={script.file}
        options={[
          ...luaFiles.map((it) => ({ value: it, label: it })),
          ...(luaFiles.includes(script.file)
            ? []
            : [{ value: script.file, label: `${script.file} (missing)` }]),
        ]}
        onChange={(file) => onChange({ ...script, file })}
      />
      <Row label="">
        <div className={styles.actions}>
          <Button
            size="small"
            icon="code"
            aria-label={`Open ${script.file}`}
            disabled={!luaFiles.includes(script.file)}
            onClick={() => void workspace.getState().openFile(`${folder}/${script.file}`)}
          >
            Open script
          </Button>
          <Button
            size="small"
            icon="plus"
            disabled={!luaFiles.includes(script.file)}
            onClick={() => void createSibling(script.file)}
          >
            New file beside it
          </Button>
          <Button size="small" onClick={() => onChange(undefined)}>
            Unlink
          </Button>
        </div>
      </Row>
      <NumberField
        label="Budget"
        dataPath={dataPath ? `${dataPath}.budget` : undefined}
        value={script.budget}
        placeholder={String(SCRIPT_DEFAULT_BUDGET)}
        step={10000}
        onChange={(value) => {
          const next = { ...script }
          if (value === undefined) delete next.budget
          else next.budget = Math.round(value)
          onChange(next)
        }}
      />
    </>
  )
}
