/**
 * A project block (`blocks/<id>/block.json`, docs/format/block.md): its cube
 * as its pack draws it, and in the inspector its look, how long it takes to
 * mine and with what, what it drops, whether it falls, its sounds, the centity
 * drawn over it, how often it ticks, and its one script.
 */
import { setKey } from '@/core/draft'
import {
  BlockToolValues,
  type BlockCarrier,
  type BlockRotation,
  newScript,
  newSiblingFile,
  type BlockFile,
  type BlockTool,
} from '@/core/format'
import { dirname } from '@/core/paths'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { ScriptField } from '@/editors/shared/ScriptField'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { resourcePackRefs } from '@/minecraft/item/item'
import {
  useCompiledResourcePacks,
  useHomeNamespace,
  useReferenceNamespace,
  useResourcePackOutlines,
} from '@/state/useResourcePacks'
import { useProjectFileUrl } from '@/state/useResourcePacks'
import { useWorkspace } from '@/state/providers'
import { CheckField, JsonObjectField, NumberField, SelectField, Section } from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import { BLOCK_FACES, lookOf } from '@/minecraft/block/look'
import { setSound, soundRefs } from './ops'
import styles from './BlockEditor.module.css'

/** Kinds of the game's blocks a block can sound like: each has the game's `block.<kind>.place` and `.break`. */
const SOUND_KINDS = [
  'stone',
  'deepslate',
  'tuff',
  'calcite',
  'basalt',
  'netherrack',
  'nether_bricks',
  'bone_block',
  'wood',
  'cherry_wood',
  'bamboo_wood',
  'nether_wood',
  'gravel',
  'sand',
  'soul_sand',
  'mud',
  'grass',
  'moss',
  'snow',
  'metal',
  'copper',
  'glass',
  'amethyst_block',
  'wool',
  'coral_block',
  'honey_block',
  'slime_block',
]

/** What a block sounds like without a `sound`: what its tool's blocks mostly are (BlockFile.soundOrDefault). */
function toolSound(tool: BlockTool | undefined): string {
  if (tool === 'axe') return 'wood'
  if (tool === 'shovel') return 'gravel'
  if (tool === 'hoe') return 'grass'
  return 'stone'
}

/** One empty map, so a selector returns the same value while there's no outline yet. */
const NO_IDS: string[] = []

/** The choices of a reference field: none, each of [refs], and the current one when nothing has it. */
function options(refs: string[], current: string | undefined, none: string) {
  return [
    { value: '', label: none },
    ...refs.map((it) => ({ value: it, label: it })),
    ...(current && !refs.includes(current)
      ? [{ value: current, label: `${current} (missing)` }]
      : []),
  ]
}

export function BlockEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<BlockFile>(path)
  const packs = useResourcePackOutlines()
  const lootTables = useWorkspace((s) => s.outline?.resources.loot_table ?? NO_IDS)
  const centities = useWorkspace((s) => s.outline?.resources.centity ?? NO_IDS)
  const compiled = useCompiledResourcePacks()
  const namespace = useReferenceNamespace()
  const home = useHomeNamespace()
  const url = useProjectFileUrl()
  const id = path.split('/')[1] ?? ''

  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const look = lookOf(compiled, model.model, namespace, home)
  const set = <K extends keyof BlockFile>(key: K, value: BlockFile[K] | undefined) =>
    edit((draft) => setKey(draft, key, value))
  const whole = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))
  const sounds = soundRefs(packs)

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.showcase} aria-label="Block preview">
          {look?.faces ? (
            <div className={styles.cube}>
              {BLOCK_FACES.map((face) => (
                <img
                  key={face}
                  className={`${styles.face} ${styles[face]}`}
                  src={url(look.faces![face])}
                  alt=""
                  data-face={face}
                />
              ))}
            </div>
          ) : (
            <div className={styles.plain} aria-label="Drawn as a note block">
              <Muted>
                {model.model
                  ? look
                    ? 'The look has no texture for some face.'
                    : `There's no look "${model.model}" in the project's packs.`
                  : 'No look yet: it is drawn as the game block it is held as.'}
              </Muted>
            </div>
          )}
          {model.centity && (
            <Muted>
              Drawn by the centity {model.centity}: the cube above is only its particles.
            </Muted>
          )}
        </div>
      </Stage>
      <InspectorPanel>
        <Section title="Block" icon="block">
          <SelectField
            label="Look"
            dataPath="model"
            value={model.model ?? ''}
            options={options(
              resourcePackRefs(packs, 'blocks'),
              model.model,
              '(the plain game block)',
            )}
            onChange={(value) => set('model', value || undefined)}
          />
          <SelectField
            label="Held as"
            dataPath="carrier"
            value={model.carrier ?? ''}
            options={[
              { value: '', label: 'note block (default): a solid, opaque cube' },
              { value: 'tripwire', label: 'tripwire: no collision, for plants and decorations' },
              { value: 'leaves', label: 'leaves: a solid cube you see through' },
            ]}
            onChange={(value) => set('carrier', (value || undefined) as BlockCarrier | undefined)}
          />
          <SelectField
            label="Turns"
            dataPath="rotation"
            value={model.rotation ?? ''}
            options={[
              { value: '', label: 'no (default): always as drawn' },
              {
                value: 'horizontal',
                label: 'horizontal: north, east, south or west, toward who places it',
              },
              { value: 'all', label: 'all: up or down too' },
            ]}
            onChange={(value) => set('rotation', (value || undefined) as BlockRotation | undefined)}
          />
          <NumberField
            label="Hardness"
            dataPath="hardness"
            value={model.hardness}
            placeholder="1.5"
            step={0.5}
            min={-1}
            onChange={(value) => set('hardness', value)}
          />
          <SelectField
            label="Tool"
            dataPath="tool"
            value={model.tool ?? ''}
            options={[
              { value: '', label: '(any)' },
              ...BlockToolValues.map((it) => ({ value: it, label: it })),
            ]}
            onChange={(value) => set('tool', (value || undefined) as BlockTool | undefined)}
          />
          <CheckField
            label="Needs the tool to drop"
            dataPath="requiresTool"
            value={model.requiresTool ?? false}
            onChange={(value) => set('requiresTool', value || undefined)}
          />
          <SelectField
            label="Drops"
            dataPath="drops"
            value={model.drops ?? ''}
            options={options(lootTables, model.drops, '(nothing)')}
            onChange={(value) => set('drops', value || undefined)}
          />
          <CheckField
            label="Falls like sand"
            dataPath="falls"
            value={model.falls ?? false}
            onChange={(value) => set('falls', value || undefined)}
          />
          <NumberField
            label="Ticks between ticks"
            dataPath="tick"
            value={model.tick}
            placeholder="never"
            step={1}
            min={1}
            onChange={(value) => set('tick', whole(value))}
          />
          <SelectField
            label="Centity"
            dataPath="centity"
            value={model.centity ?? ''}
            options={options(centities, model.centity, '(a cube)')}
            onChange={(value) => set('centity', value || undefined)}
          />
          {model.centity && (
            <Hint>
              The block stays a solid cube for collision and mining; the centity is spawned at its
              bottom centre when it's placed, and removed with it.
            </Hint>
          )}
          <JsonObjectField
            label="Properties"
            dataPath="properties"
            value={model.properties}
            onChange={(value) => set('properties', value)}
          />
        </Section>
        <Section title="Sounds" icon="sound">
          <SelectField
            label="Sounds like"
            dataPath="sound"
            value={model.sound ?? ''}
            options={options(SOUND_KINDS, model.sound, `${toolSound(model.tool)} (from its tool)`)}
            onChange={(value) => set('sound', value || undefined)}
          />
          <SelectField
            label="Placed"
            dataPath="sounds.place"
            value={model.sounds?.place ?? ''}
            options={options(sounds, model.sounds?.place, '(as it sounds like)')}
            onChange={(value) => edit((draft) => setSound(draft, 'place', value || undefined))}
          />
          <SelectField
            label="Broken"
            dataPath="sounds.break"
            value={model.sounds?.break ?? ''}
            options={options(sounds, model.sounds?.break, '(as it sounds like)')}
            onChange={(value) => edit((draft) => setSound(draft, 'break', value || undefined))}
          />
        </Section>
        <Section title="Script" icon="code">
          <ScriptField
            folder={dirname(path)}
            script={model.script}
            dataPath="script"
            suggestedName="script.lua"
            template={newScript('block', '')}
            siblingTemplate={newSiblingFile('block')}
            onChange={(script) =>
              edit((draft) => {
                if (script === undefined) delete draft.script
                else draft.script = script
              })
            }
          />
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}
