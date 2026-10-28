import { useApp } from '@/state/providers'
import { setKey } from '@/core/draft'
import { TERRAIN_DEFAULTS, TERRAIN_LIMITS, newTerrainScript } from '@/core/format'
import { fieldPath } from '@/editors/shared/focus'
import { useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'
import { CheckField, Datalist, FieldGroup, NumberField, Section, TextField } from '@/ui/fields'
import { Hint } from '@/ui/text'
import {
  addEntry,
  deleteEntry,
  newEntry,
  renameEntry,
  setScriptBlocks,
  setScripted,
} from '@/editors/terrain/ops'
import { AddButton, EntryHeader, IdListField, type Parts, askName, whole } from './fields'
import { NoiseFields } from './noise'

/**
 * The file's Lua stages: whether it hands them to `terrain/<id>.lua` (made from format's template when it isn't
 * there), the budget each call gets, the noises the script asks for and the blocks it places besides the file's.
 */
const SCRIPT_LOOT_LIST = 'terrain-script-loot'

export function ScriptSection({
  path,
  model,
  edit,
  gesture,
  lootTables,
}: Parts & { path: string }) {
  const { workspace } = useApp()
  const files = useWorkspace((s) => s.files)
  const script = model.script
  const file = path.replace(/\.json$/, '.lua')
  const names = Object.keys(script?.noises ?? {})
  const turn = async (on: boolean) => {
    edit((d) => setScripted(d, on))
    if (on && !files.includes(file)) await workspace.getState().createFile(file, newTerrainScript())
  }
  const addNoise = async () => {
    const name = await askName('script noise', names, names.length ? '' : 'detail')
    if (name) edit((d) => addEntry(d, 'scriptNoises', name, newEntry('scriptNoises', '')))
  }
  return (
    <Section
      title="Script"
      icon="code"
      actions={script ? <AddButton one="script noise" onAdd={addNoise} /> : undefined}
    >
      <CheckField
        label="Hand stages to a script"
        dataPath="script"
        value={script != null}
        onChange={(on) => void turn(on)}
      />
      {!script ? (
        <Hint>
          A Lua script beside the file (its height, terrain and decorate stages) shapes what the
          file makes, on the server and in this preview alike.
        </Hint>
      ) : (
        <>
          <Button icon="code" onClick={() => void workspace.getState().openFile(file)}>
            Open {file.replace(/^.*\//, '')}
          </Button>
          <NumberField
            label="Budget"
            dataPath="script.budget"
            value={script.budget}
            placeholder={String(TERRAIN_DEFAULTS.scriptBudget)}
            min={TERRAIN_LIMITS.minScriptBudget}
            step={1000}
            {...gesture}
            onChange={(value) => edit((d) => d.script && setKey(d.script, 'budget', whole(value)))}
          />
          <IdListField
            label="Game blocks it places"
            dataPath="script.blocks"
            value={script.blocks}
            placeholder="minecraft:cobblestone"
            onChange={(ids) => edit((d) => setScriptBlocks(d, 'blocks', ids))}
          />
          <IdListField
            label="Project blocks it places"
            dataPath="script.customBlocks"
            value={script.customBlocks}
            placeholder="ruby_ore"
            onChange={(ids) => edit((d) => setScriptBlocks(d, 'customBlocks', ids))}
          />
          <TextField
            label="Loot tables it fills"
            dataPath="script.loot"
            value={(script.loot ?? []).join(', ')}
            placeholder="none (chunk:set_loot names them)"
            list={SCRIPT_LOOT_LIST}
            onChange={(text) => {
              const ids = text
                .split(',')
                .map((it) => it.trim())
                .filter(Boolean)
              edit((d) => setScriptBlocks(d, 'loot', ids))
            }}
          />
          <Datalist id={SCRIPT_LOOT_LIST} values={lootTables} />
          {names.map((name) => {
            const here = ['script', 'noises', name]
            return (
              <FieldGroup
                key={name}
                data-path={fieldPath(here)}
                header={
                  <EntryHeader
                    name={name}
                    one="script noise"
                    taken={names}
                    onRename={(to) => edit((d) => renameEntry(d, 'scriptNoises', name, to))}
                    onDelete={() => edit((d) => deleteEntry(d, 'scriptNoises', name))}
                  />
                }
              >
                <NoiseFields
                  noise={script.noises?.[name] ?? {}}
                  at={here}
                  gesture={gesture}
                  change={(recipe) =>
                    edit((d) => {
                      const target = d.script?.noises?.[name]
                      if (target) recipe(target)
                    })
                  }
                />
              </FieldGroup>
            )
          })}
        </>
      )}
    </Section>
  )
}
