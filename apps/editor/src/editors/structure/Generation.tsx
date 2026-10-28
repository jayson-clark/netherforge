/**
 * "Generates in the world" on a structure's screen: `structures/<id>.json`,
 * where the game places the structure by itself (biomes, spacing, height,
 * terrain), and the pools of more pieces its jigsaw blocks pick from. The
 * section is a switch: on writes a new file through format, off deletes it
 * (after asking). Each change is saved at once, since this file has no tab of
 * its own to save from. docs/format/worlds.md has the fields.
 */
import { useEffect } from 'react'
import {
  canonicalizeModel,
  GenerationStepValues,
  PoolProjectionValues,
  StructureHeightmapValues,
  TerrainAdaptationValues,
  type GenerationStep,
  type PoolProjection,
  type StructureGeneration,
  type StructureHeightmap,
  type TerrainAdaptation,
} from '@/core/format'
import { setKey } from '@/core/draft'
import { modelOf } from '@/core/store/documents'
import { useApp, useWorkspace } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { ID_PATTERN } from '@/core/paths'
import { Button, IconButton } from '@/ui/Button'
import {
  FieldGroup,
  NumberField,
  NumberInput,
  Row,
  Section,
  SelectField,
  TextField,
} from '@/ui/fields'
import { Hint } from '@/ui/text'
import {
  addElement,
  addPool,
  biomesText,
  deletePool,
  newGeneration,
  poolNames,
  removeElement,
  renamePool,
  setBiomes,
  setCount,
  setElement,
  setProjection,
  START_POOL,
} from './ops'

/** One empty list, so a selector returns the same value while there's no outline yet. */
const NO_STRUCTURES: string[] = []

const label = (value: string) => value.replaceAll('_', ' ')

export function Generation({ id, path, exists }: { id: string; path: string; exists: boolean }) {
  const { workspace } = useApp()
  const doc = useWorkspace((s) => s.docs[path])
  const allProblems = useWorkspace((s) => s.problems)
  const problems = allProblems.filter((it) => it.file === path)
  const model = modelOf<StructureGeneration>(doc)

  // This file has no tab: load it for the screen.
  useEffect(() => {
    if (exists) void workspace.getState().loadDoc(path)
  }, [exists, path, workspace])

  const edit = (recipe: (draft: StructureGeneration) => void) => {
    workspace.getState().edit<StructureGeneration>(path, recipe)
    void workspace.getState().save(path)
  }

  const toggle = async (on: boolean) => {
    if (on) {
      const written = canonicalizeModel('structure_generation', path, newGeneration())
      if (written.text) await workspace.getState().createFile(path, written.text)
      return
    }
    const ok = await ask.confirm({
      title: `Stop generating ${id}`,
      message: `Delete ${path}? The structure stays, and scripts can still place it; it just won't appear in new chunks by itself. Git can bring the file back if it was committed.`,
      confirmLabel: 'Delete',
      danger: true,
    })
    if (ok === true) await workspace.getState().deletePath(path)
  }

  return (
    <Section
      title="Generates in the world"
      icon="sprout"
      enabled={exists}
      onToggle={(on) => void toggle(on)}
    >
      {!exists && (
        <Hint>
          Turn this on to have the game place {id} by itself in new chunks, in the biomes you pick.
        </Hint>
      )}
      {exists && !model && (
        <Hint tone="error" role="status">
          {path} can't be read as a generation file
          {doc?.parseProblem ? `: ${doc.parseProblem.message}` : ''}.
        </Hint>
      )}
      {model && (
        <>
          <TextField
            label="Biomes"
            dataPath="biomes"
            value={biomesText(model)}
            placeholder="#minecraft:is_overworld"
            onChange={(text) => edit((draft) => setBiomes(draft, text))}
          />
          <Hint>Biome ids, or one #tag, separated by commas.</Hint>
          <NumberField
            label="Spacing (chunks)"
            dataPath="spacing"
            value={model.spacing}
            placeholder="32"
            step={1}
            min={1}
            onChange={(value) => edit((draft) => setCount(draft, 'spacing', value))}
          />
          <NumberField
            label="Separation (chunks)"
            dataPath="separation"
            value={model.separation}
            placeholder="8"
            step={1}
            min={0}
            onChange={(value) => edit((draft) => setCount(draft, 'separation', value))}
          />
          <SelectField<GenerationStep>
            label="Step"
            dataPath="step"
            value={model.step ?? 'surface_structures'}
            options={GenerationStepValues.map((value) => ({ value, label: label(value) }))}
            onChange={(value) =>
              edit((draft) =>
                setKey(draft, 'step', value === 'surface_structures' ? undefined : value),
              )
            }
          />
          <SelectField<TerrainAdaptation>
            label="Terrain adaptation"
            dataPath="terrainAdaptation"
            value={model.terrainAdaptation ?? 'none'}
            options={TerrainAdaptationValues.map((value) => ({ value, label: label(value) }))}
            onChange={(value) =>
              edit((draft) =>
                setKey(draft, 'terrainAdaptation', value === 'none' ? undefined : value),
              )
            }
          />
          <SelectField<StructureHeightmap>
            label="Height from"
            dataPath="heightmap"
            value={model.heightmap ?? 'world_surface_wg'}
            options={StructureHeightmapValues.map((value) => ({
              value,
              label: value === 'none' ? 'an absolute height' : label(value),
            }))}
            onChange={(value) =>
              edit((draft) =>
                setKey(draft, 'heightmap', value === 'world_surface_wg' ? undefined : value),
              )
            }
          />
          <NumberField
            label="Start height"
            dataPath="startHeight"
            value={model.startHeight}
            placeholder="0"
            step={1}
            onChange={(value) => edit((draft) => setCount(draft, 'startHeight', value))}
          />
          <NumberField
            label="Salt"
            dataPath="salt"
            value={model.salt}
            placeholder="from its name"
            step={1}
            min={0}
            onChange={(value) => edit((draft) => setCount(draft, 'salt', value))}
          />
          <Pools id={id} model={model} edit={edit} />
        </>
      )}
      {problems.map((problem, index) => (
        <Hint key={index} tone={problem.severity === 'error' ? 'error' : undefined} role="status">
          {problem.message}
        </Hint>
      ))}
      <Hint>Changing this restarts the dev server: the game reads structures as it starts.</Hint>
    </Section>
  )
}

/** The jigsaw pools: more pieces, by name, each picked by weight where a jigsaw block names it. */
function Pools({
  id,
  model,
  edit,
}: {
  id: string
  model: StructureGeneration
  edit: (recipe: (draft: StructureGeneration) => void) => void
}) {
  const structures = useWorkspace((s) => s.outline?.resources.structure ?? NO_STRUCTURES)
  const names = poolNames(model)
  const askName = (title: string, initial: string, except?: string) =>
    ask.prompt({
      title,
      label: 'Pool name',
      initial,
      confirmLabel: 'OK',
      validate: (value) =>
        !ID_PATTERN.test(value)
          ? 'Lowercase letters, digits and _'
          : value === START_POOL
            ? `"${START_POOL}" is the structure's own template`
            : value !== except && names.includes(value)
              ? 'That pool exists'
              : null,
    })
  const structureOptions = (current: string) => [
    ...structures.map((it) => ({ value: it, label: it })),
    ...(current && !structures.includes(current)
      ? [{ value: current, label: `${current} (missing)` }]
      : []),
  ]
  return (
    <FieldGroup header="Jigsaw pools" data-path="pools">
      {names.length > 0 && (
        <>
          <NumberField
            label="Depth"
            dataPath="depth"
            value={model.depth}
            placeholder="7"
            step={1}
            min={1}
            onChange={(value) => edit((draft) => setCount(draft, 'depth', value))}
          />
          <NumberField
            label="Reach (blocks)"
            dataPath="maxDistance"
            value={model.maxDistance}
            placeholder="80"
            step={1}
            min={1}
            onChange={(value) => edit((draft) => setCount(draft, 'maxDistance', value))}
          />
        </>
      )}
      {names.map((name) => {
        const pool = model.pools![name]!
        const at = `pools.${name}`
        return (
          <FieldGroup
            key={name}
            data-path={at}
            aria-label={`Pool ${name}`}
            role="group"
            header={
              <>
                {id}/{name}
                <IconButton
                  icon="edit"
                  label={`Rename pool ${name}`}
                  onClick={async () => {
                    const to = await askName(`Rename pool ${name}`, name, name)
                    if (to && to !== name) edit((draft) => renamePool(draft, name, to))
                  }}
                />
                <IconButton
                  icon="trash"
                  label={`Delete pool ${name}`}
                  onClick={() => edit((draft) => deletePool(draft, name))}
                />
              </>
            }
          >
            <SelectField<PoolProjection>
              label="Projection"
              dataPath={`${at}.projection`}
              value={pool.projection ?? 'rigid'}
              options={PoolProjectionValues.map((value) => ({ value, label: label(value) }))}
              onChange={(value) => edit((draft) => setProjection(draft, name, value))}
            />
            {pool.elements.map((element, index) => (
              <Row key={index} label={`Piece ${index + 1}`}>
                <select
                  aria-label={`Pool ${name} piece ${index + 1}`}
                  data-path={`${at}.elements[${index}].structure`}
                  value={element.structure}
                  onChange={(event) =>
                    edit((draft) =>
                      setElement(draft, name, index, { structure: event.target.value }),
                    )
                  }
                >
                  {structureOptions(element.structure).map((it) => (
                    <option key={it.value} value={it.value}>
                      {it.label}
                    </option>
                  ))}
                </select>
                <NumberInput
                  label={`Pool ${name} piece ${index + 1} weight`}
                  dataPath={`${at}.elements[${index}].weight`}
                  value={element.weight}
                  placeholder="1"
                  step={1}
                  min={1}
                  onChange={(value) =>
                    edit((draft) => setElement(draft, name, index, { weight: value ?? null }))
                  }
                />
                <IconButton
                  icon="trash"
                  label={`Remove pool ${name} piece ${index + 1}`}
                  onClick={() => edit((draft) => removeElement(draft, name, index))}
                />
              </Row>
            ))}
            <Button
              size="small"
              icon="plus"
              disabled={structures.length === 0}
              onClick={() => edit((draft) => addElement(draft, name, structures[0]!))}
            >
              Add piece
            </Button>
          </FieldGroup>
        )
      })}
      <Button
        size="small"
        icon="plus"
        onClick={async () => {
          const name = await askName('New pool', names.length ? '' : 'pieces')
          if (name) edit((draft) => addPool(draft, name))
        }}
      >
        Add pool
      </Button>
      <Hint>
        A jigsaw block whose target pool is {'<namespace>'}:{id}/{'<pool>'} picks a piece of that
        pool by weight. Without pools the structure is one piece.
      </Hint>
    </FieldGroup>
  )
}
