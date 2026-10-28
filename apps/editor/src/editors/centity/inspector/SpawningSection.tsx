/** The centity's `spawning` block (natural spawning): the switch adds or removes it; every change is a `spawning.ts` function. */
import {
  MoonPhaseValues,
  SpawnWeatherValues,
  SPAWNING_DEFAULTS,
  type CentityFile,
} from '@/core/format'
import { useApp } from '@/state/providers'
import {
  NumberField,
  Section,
  SelectField,
  TextField,
  CheckField,
  Check,
  FieldGroup,
} from '@/ui/fields'
import { Hint } from '@/ui/text'
import {
  formatList,
  setList,
  setNumber,
  setRangeEnd,
  setKeepOnInteract,
  setCondition,
  setSpawning,
  setTimeEnd,
  setTimeMode,
  timeEnd,
  timeMode,
  toggleChoice,
  type SpawnTimeMode,
  type SpawningList,
  type SpawningNumber,
  type SpawningRange,
} from '@/editors/centity/spawning'

const SPAWNING_LISTS: { key: SpawningList; label: string; placeholder: string }[] = [
  { key: 'worlds', label: 'Worlds', placeholder: 'any (world names)' },
  {
    key: 'biomes',
    label: 'Biomes',
    placeholder: 'any (ruby_grove, minecraft:plains, #minecraft:is_forest)',
  },
  {
    key: 'blocks',
    label: 'Stands on',
    placeholder: 'any (minecraft:grass_block, #minecraft:dirt)',
  },
]

const SPAWNING_RANGES: { key: SpawningRange; label: string; floor?: number }[] = [
  { key: 'light', label: 'Light', floor: SPAWNING_DEFAULTS.minLight },
  { key: 'height', label: 'Height (y)' },
  { key: 'group', label: 'Group size', floor: 1 },
]

const SPAWNING_NUMBERS: { key: SpawningNumber; label: string; fallback: number }[] = [
  { key: 'weight', label: 'Weight', fallback: SPAWNING_DEFAULTS.weight },
  { key: 'cap', label: 'Cap per player', fallback: SPAWNING_DEFAULTS.cap },
  {
    key: 'despawnDistance',
    label: 'Despawn distance',
    fallback: SPAWNING_DEFAULTS.despawnDistance,
  },
]

/**
 * `spawning`: where the centity appears by itself near players. Off when the
 * file has no block; on, every field is optional and an empty one is left
 * out of the file.
 */
export function SpawningSection({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const edit = (recipe: (draft: CentityFile) => void) =>
    workspace.getState().edit<CentityFile>(path, recipe)
  const spawning = model.spawning
  return (
    <Section
      title="Natural spawning"
      icon="sprout"
      enabled={spawning !== undefined}
      onToggle={(on) => edit((draft) => setSpawning(draft, on))}
    >
      {spawning && (
        <>
          {SPAWNING_LISTS.map(({ key, label, placeholder }) => (
            <TextField
              key={key}
              label={label}
              dataPath={`centity.spawning.${key}`}
              value={formatList(spawning[key])}
              placeholder={placeholder}
              onChange={(text) => edit((draft) => setList(draft, key, text))}
            />
          ))}
          {SPAWNING_RANGES.map(({ key, label, floor }) => (
            <FieldGroup key={key} header={label}>
              {(['min', 'max'] as const).map((end) => (
                <NumberField
                  key={end}
                  label={`${label} ${end}`}
                  dataPath={`centity.spawning.${key}.${end}`}
                  value={spawning[key]?.[end]}
                  placeholder={end}
                  step={1}
                  min={floor}
                  onChange={(value) => edit((draft) => setRangeEnd(draft, key, end, value))}
                />
              ))}
            </FieldGroup>
          ))}
          {SPAWNING_NUMBERS.map(({ key, label, fallback }) => (
            <NumberField
              key={key}
              label={label}
              dataPath={`centity.spawning.${key}`}
              value={spawning[key]}
              placeholder={String(fallback)}
              step={1}
              min={1}
              onChange={(value) => edit((draft) => setNumber(draft, key, value))}
            />
          ))}
          <SelectField<SpawnTimeMode>
            label="Time of day"
            dataPath="centity.spawning.time"
            value={timeMode(spawning.time)}
            options={[
              { value: 'any', label: 'any time' },
              { value: 'day', label: 'day (23000–12999)' },
              { value: 'night', label: 'night (13000–22999)' },
              { value: 'range', label: 'ticks of the day' },
            ]}
            onChange={(mode) => edit((draft) => setTimeMode(draft, mode))}
          />
          {timeMode(spawning.time) === 'range' && (
            <FieldGroup header="Ticks of the day">
              {(['min', 'max'] as const).map((end) => (
                <NumberField
                  key={end}
                  label={`Time ${end}`}
                  dataPath={`centity.spawning.time.${end}`}
                  value={timeEnd(spawning.time, end)}
                  placeholder={end === 'min' ? '0' : '23999'}
                  step={1}
                  min={0}
                  onChange={(value) => edit((draft) => setTimeEnd(draft, end, value))}
                />
              ))}
            </FieldGroup>
          )}
          <FieldGroup header="Moon phase" data-path="centity.spawning.moonPhase">
            {MoonPhaseValues.map((phase) => (
              <Check
                key={phase}
                label={phase.replace(/_/g, ' ')}
                checked={spawning.moonPhase?.includes(phase) ?? false}
                onChange={(on) => edit((draft) => toggleChoice(draft, 'moonPhase', phase, on))}
              />
            ))}
          </FieldGroup>
          <FieldGroup header="Weather" data-path="centity.spawning.weather">
            {SpawnWeatherValues.map((weather) => (
              <Check
                key={weather}
                label={weather}
                checked={spawning.weather?.includes(weather) ?? false}
                onChange={(on) => edit((draft) => toggleChoice(draft, 'weather', weather, on))}
              />
            ))}
          </FieldGroup>
          <TextField
            label="Only when"
            dataPath="centity.spawning.when"
            value={spawning.when ?? ''}
            placeholder="none (a condition from nf.spawning.condition)"
            onChange={(text) => edit((draft) => setCondition(draft, text))}
          />
          <CheckField
            label="Interaction keeps one"
            dataPath="centity.spawning.keepOnInteract"
            value={spawning.keepOnInteract === true}
            onChange={(on) => edit((draft) => setKeepOnInteract(draft, on))}
          />
          <Hint>
            Placed {SPAWNING_DEFAULTS.minDistance} to {SPAWNING_DEFAULTS.maxDistance} blocks from a
            player. A natural centity isn't saved, and goes when no player is within its despawn
            distance, until a script calls keep() (or, with the option above, a player clicks it).
          </Hint>
        </>
      )}
    </Section>
  )
}
