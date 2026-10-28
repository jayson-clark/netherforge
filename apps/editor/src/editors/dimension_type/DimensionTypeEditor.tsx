/**
 * A project dimension type (`dimension_types/<id>.json`, docs/format/dimension-type.md): a
 * small form. The stage says the world's build limits; the inspector edits them,
 * its light and sky, and the game's rules. Every field left empty is the
 * overworld's, which its placeholder says.
 */
import {
  DIMENSION_TYPE_DEFAULTS,
  DimensionTypeSkyValues,
  type DimensionTypeFile,
  type DimensionTypeSky,
} from '@/core/format'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import {
  ColorField,
  NumberField,
  Section,
  SelectField,
  TextField,
  TriStateField,
} from '@/ui/fields'
import { Hint } from '@/ui/text'
import { blockRange, setColor, setMonsterLight, setValue, type DimensionValueKey } from './ops'
import styles from './DimensionTypeEditor.module.css'

type Edit = (recipe: (draft: DimensionTypeFile) => void) => void

const whole = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))

const SWITCHES: { key: DimensionValueKey; label: string; overworld: string }[] = [
  { key: 'skyLight', label: 'Sky light', overworld: 'on' },
  { key: 'ceiling', label: 'Ceiling', overworld: 'off' },
  { key: 'fixedTime', label: 'Fixed time', overworld: 'off' },
]

const RULES: { key: DimensionValueKey; label: string; overworld: string }[] = [
  { key: 'bedWorks', label: 'Beds work', overworld: 'yes' },
  { key: 'respawnAnchorWorks', label: 'Respawn anchors work', overworld: 'no' },
  { key: 'piglinSafe', label: 'Piglins safe', overworld: 'no' },
  { key: 'raids', label: 'Raids', overworld: 'yes' },
  { key: 'ultrawarm', label: 'Ultrawarm', overworld: 'no' },
]

export function DimensionTypeEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<DimensionTypeFile>(path)
  const id = path.split('/')[1]?.replace(/\.json$/, '') ?? ''
  // Every path in the file is an inspector field's.
  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />
  const { lowest, highest } = blockRange(model)
  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.limits} role="group" aria-label="Build limits">
          <div className={styles.range} data-testid="dimension-range">
            y {lowest} to {highest}
          </div>
          <Hint>
            {highest - lowest + 1} blocks tall. Worlds made with it keep it; a change needs a
            restart.
          </Hint>
        </div>
      </Stage>
      <InspectorPanel>
        <LimitsSection model={model} edit={edit} />
        <LightSection model={model} edit={edit} />
        <RulesSection model={model} edit={edit} />
      </InspectorPanel>
    </EditorScreen>
  )
}

function LimitsSection({ model, edit }: { model: DimensionTypeFile; edit: Edit }) {
  return (
    <Section title="Build limits" icon="ruler">
      <NumberField
        label="Bottom (min Y)"
        dataPath="minY"
        value={model.minY}
        placeholder={String(DIMENSION_TYPE_DEFAULTS.minY)}
        step={DIMENSION_TYPE_DEFAULTS.section}
        onChange={(value) => edit((d) => setValue(d, 'minY', whole(value)))}
      />
      <NumberField
        label="Height"
        dataPath="height"
        value={model.height}
        placeholder={String(DIMENSION_TYPE_DEFAULTS.height)}
        step={DIMENSION_TYPE_DEFAULTS.section}
        min={DIMENSION_TYPE_DEFAULTS.section}
        onChange={(value) => edit((d) => setValue(d, 'height', whole(value)))}
      />
      <NumberField
        label="Logical height"
        dataPath="logicalHeight"
        value={model.logicalHeight}
        placeholder="the height"
        step={1}
        min={0}
        onChange={(value) => edit((d) => setValue(d, 'logicalHeight', whole(value)))}
      />
      <Hint>
        Multiples of {DIMENSION_TYPE_DEFAULTS.section}, from {DIMENSION_TYPE_DEFAULTS.lowestY} up to
        a top of {DIMENSION_TYPE_DEFAULTS.topY}. The logical height is how high portals and
        teleports reach.
      </Hint>
    </Section>
  )
}

function LightSection({ model, edit }: { model: DimensionTypeFile; edit: Edit }) {
  const colors = model.colors ?? {}
  return (
    <Section title="Light and sky" icon="sun">
      {SWITCHES.map(({ key, label, overworld }) => (
        <TriStateField
          key={key}
          label={label}
          dataPath={key}
          value={model[key] as boolean | undefined}
          defaultLabel={overworld}
          onChange={(value) => edit((d) => setValue(d, key, value))}
        />
      ))}
      <NumberField
        label="Ambient light"
        dataPath="ambientLight"
        value={model.ambientLight}
        placeholder="0"
        step={0.05}
        min={0}
        onChange={(value) => edit((d) => setValue(d, 'ambientLight', value))}
      />
      <SelectField
        label="Sky"
        dataPath="sky"
        value={model.sky ?? 'overworld'}
        options={DimensionTypeSkyValues}
        onChange={(value) =>
          edit((d) =>
            setValue(d, 'sky', value === 'overworld' ? undefined : (value as DimensionTypeSky)),
          )
        }
      />
      <ColorField
        label="Sky colour"
        dataPath="colors.sky"
        value={colors.sky}
        placeholder={DIMENSION_TYPE_DEFAULTS.sky}
        onChange={(value) => edit((d) => setColor(d, 'sky', value))}
      />
      <ColorField
        label="Fog colour"
        dataPath="colors.fog"
        value={colors.fog}
        placeholder={DIMENSION_TYPE_DEFAULTS.fog}
        onChange={(value) => edit((d) => setColor(d, 'fog', value))}
      />
      <TextField
        label="Cloud colour"
        dataPath="colors.clouds"
        value={colors.clouds}
        placeholder={DIMENSION_TYPE_DEFAULTS.clouds}
        onChange={(value) => edit((d) => setColor(d, 'clouds', value))}
      />
      <NumberField
        label="Cloud height"
        dataPath="cloudHeight"
        value={model.cloudHeight}
        placeholder={String(DIMENSION_TYPE_DEFAULTS.cloudHeight)}
        step={1}
        onChange={(value) => edit((d) => setValue(d, 'cloudHeight', value))}
      />
    </Section>
  )
}

function RulesSection({ model, edit }: { model: DimensionTypeFile; edit: Edit }) {
  const light = model.monsterSpawnLight ?? {}
  return (
    <Section title="Rules" icon="sliders">
      {RULES.map(({ key, label, overworld }) => (
        <TriStateField
          key={key}
          label={label}
          dataPath={key}
          value={model[key] as boolean | undefined}
          defaultLabel={overworld}
          onChange={(value) => edit((d) => setValue(d, key, value))}
        />
      ))}
      <NumberField
        label="Monster light from"
        dataPath="monsterSpawnLight.min"
        value={light.min}
        placeholder={String(DIMENSION_TYPE_DEFAULTS.monsterLightMin)}
        step={1}
        min={0}
        onChange={(value) => edit((d) => setMonsterLight(d, 'min', value))}
      />
      <NumberField
        label="Monster light to"
        dataPath="monsterSpawnLight.max"
        value={light.max}
        placeholder={String(DIMENSION_TYPE_DEFAULTS.monsterLightMax)}
        step={1}
        min={0}
        onChange={(value) => edit((d) => setMonsterLight(d, 'max', value))}
      />
      <NumberField
        label="Monster block light"
        dataPath="monsterSpawnBlockLight"
        value={model.monsterSpawnBlockLight}
        placeholder="0"
        step={1}
        min={0}
        onChange={(value) => edit((d) => setValue(d, 'monsterSpawnBlockLight', whole(value)))}
      />
      <TextField
        label="Infiniburn tag"
        dataPath="infiniburn"
        value={model.infiniburn}
        placeholder={DIMENSION_TYPE_DEFAULTS.infiniburn}
        onChange={(value) => edit((d) => setValue(d, 'infiniburn', value.trim() || undefined))}
      />
      <NumberField
        label="Coordinate scale"
        dataPath="coordinateScale"
        value={model.coordinateScale}
        placeholder="1"
        step={1}
        onChange={(value) => edit((d) => setValue(d, 'coordinateScale', value))}
      />
      <Hint>
        Monsters spawn at light levels {DIMENSION_TYPE_DEFAULTS.monsterLightMin} to{' '}
        {DIMENSION_TYPE_DEFAULTS.maxLight}. The coordinate scale is how far a step goes in the
        overworld through a nether portal.
      </Hint>
    </Section>
  )
}
