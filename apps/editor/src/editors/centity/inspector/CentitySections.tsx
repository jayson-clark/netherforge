/** The centity's own sections, shown with no node selected: its settings, body and health bar. */
import { setKey } from '@/core/draft'
import {
  newScript,
  newSiblingFile,
  BODY_DEFAULTS,
  HEALTH_BAR_DEFAULTS,
  type BodyDef,
  type CentityFile,
  type HealthBarDef,
  type HealthDef,
} from '@/core/format'
import { dirname } from '@/core/paths'
import { useApp } from '@/state/providers'
import {
  NumberField,
  Section,
  TextField,
  CheckField,
  FieldGroup,
  JsonObjectField,
} from '@/ui/fields'
import { Hint } from '@/ui/text'
import { ScriptField } from '@/editors/shared/ScriptField'

export function CentitySettings({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const edit = (recipe: (draft: CentityFile) => void) =>
    workspace.getState().edit<CentityFile>(path, recipe)
  return (
    <Section title="Centity" icon="cube">
      <TextField
        label="Display name"
        dataPath="centity.name"
        value={model.name}
        placeholder="(none)"
        onChange={(name) => edit((draft) => setKey(draft, 'name', name || undefined))}
      />
      <ScriptField
        folder={dirname(path)}
        script={model.script}
        dataPath="centity.script"
        suggestedName="script.lua"
        template={newScript('centity', '')}
        siblingTemplate={newSiblingFile('centity')}
        onChange={(script) => edit((draft) => setKey(draft, 'script', script))}
      />
      <JsonObjectField
        label="Properties"
        dataPath="centity.properties"
        value={model.properties}
        onChange={(value) => edit((draft) => setKey(draft, 'properties', value))}
      />
      <Hint>
        Properties are the project&apos;s own facts about it, any JSON: scripts read them with{' '}
        <code>centity:properties()</code>.
      </Hint>
    </Section>
  )
}

const BODY_NUMBERS: { key: keyof BodyDef; label: string; step: number; min?: number }[] = [
  { key: 'width', label: 'Width', step: 0.1, min: 0 },
  { key: 'height', label: 'Height', step: 0.1, min: 0 },
  { key: 'gravity', label: 'Gravity', step: 1 },
  { key: 'maxFallSpeed', label: 'Max fall speed', step: 1, min: 0 },
  { key: 'drag', label: 'Drag in air', step: 0.01, min: 0 },
  { key: 'liquidDrag', label: 'Drag in liquid', step: 0.01, min: 0 },
  { key: 'liquidGravity', label: 'Gravity in liquid', step: 0.05, min: 0 },
  { key: 'acceleration', label: 'Acceleration', step: 1, min: 0 },
  { key: 'airAcceleration', label: 'Air acceleration', step: 1, min: 0 },
  { key: 'friction', label: 'Friction', step: 1, min: 0 },
  { key: 'stepHeight', label: 'Step height', step: 0.1, min: 0 },
  { key: 'bounce', label: 'Bounce', step: 0.05, min: 0 },
]

/** `body`: the box the server walks, drops and stops against blocks, moving the whole centity. */
export function BodySection({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const body = model.body
  const editBody = (recipe: (body: BodyDef) => void) =>
    workspace.getState().edit<CentityFile>(path, (draft) => {
      if (draft.body) recipe(draft.body)
    })
  return (
    <Section
      title="Body"
      icon="person"
      enabled={body !== undefined}
      onToggle={(on) =>
        workspace
          .getState()
          .edit<CentityFile>(path, (draft) => setKey(draft, 'body', on ? {} : undefined))
      }
    >
      {body && (
        <>
          {BODY_NUMBERS.map(({ key, label, step, min }) => (
            <NumberField
              key={key}
              label={label}
              dataPath={`centity.body.${key}`}
              value={body[key] as number | undefined}
              placeholder={String(BODY_DEFAULTS[key as keyof typeof BODY_DEFAULTS])}
              step={step}
              min={min}
              onChange={(value) => editBody((b) => setKey(b, key, value as never))}
            />
          ))}
          <CheckField
            label="Flies"
            dataPath="centity.body.fly"
            value={body.fly === true}
            onChange={(on) => editBody((b) => setKey(b, 'fly', on || undefined))}
          />
          <CheckField
            label="Collides with blocks"
            dataPath="centity.body.blocks"
            value={body.blocks !== false}
            onChange={(on) => editBody((b) => setKey(b, 'blocks', on ? undefined : false))}
          />
          <Hint>
            An upright box {BODY_DEFAULTS.maxWidth} blocks across and {BODY_DEFAULTS.maxHeight} tall
            at most, standing at the centity&apos;s position: scripts walk, jump and push it. Rates
            are per second.
          </Hint>
        </>
      )}
    </Section>
  )
}

const BAR_TEXTS: { key: 'glyph' | 'filled' | 'empty'; label: string }[] = [
  { key: 'glyph', label: 'Piece' },
  { key: 'filled', label: 'Before full pieces' },
  { key: 'empty', label: 'Before empty pieces' },
]

/** `health`: what `damage` takes and `death` hears at 0, and the bar over it. */
export function HealthSection({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const health = model.health
  const editHealth = (recipe: (health: HealthDef) => void) =>
    workspace.getState().edit<CentityFile>(path, (draft) => {
      if (draft.health) recipe(draft.health)
    })
  const editBar = (recipe: (bar: HealthBarDef) => void) =>
    editHealth((h) => {
      if (h.bar) recipe(h.bar)
    })
  const bar = health?.bar
  return (
    <Section
      title="Health"
      icon="heart"
      enabled={health !== undefined}
      onToggle={(on) =>
        workspace
          .getState()
          .edit<CentityFile>(path, (draft) => setKey(draft, 'health', on ? { max: 20 } : undefined))
      }
    >
      {health && (
        <>
          <NumberField
            label="Max"
            dataPath="centity.health.max"
            value={health.max}
            step={1}
            min={0}
            onChange={(value) => value !== undefined && editHealth((h) => (h.max = value))}
          />
          <CheckField
            label="Removed when it dies"
            dataPath="centity.health.removeOnDeath"
            value={health.removeOnDeath !== false}
            onChange={(on) => editHealth((h) => setKey(h, 'removeOnDeath', on ? undefined : false))}
          />
          <CheckField
            label="Health bar"
            dataPath="centity.health.bar"
            value={bar !== undefined}
            onChange={(on) => editHealth((h) => setKey(h, 'bar', on ? {} : undefined))}
          />
          {bar && (
            <FieldGroup header="Bar">
              <NumberField
                label="Segments"
                dataPath="centity.health.bar.segments"
                value={bar.segments}
                placeholder={String(HEALTH_BAR_DEFAULTS.segments)}
                step={1}
                min={1}
                onChange={(value) =>
                  editBar((b) =>
                    setKey(
                      b,
                      'segments',
                      value === undefined
                        ? undefined
                        : Math.min(HEALTH_BAR_DEFAULTS.maxSegments, Math.round(value)),
                    ),
                  )
                }
              />
              <NumberField
                label="Shows for (ticks)"
                dataPath="centity.health.bar.showTicks"
                value={bar.showTicks}
                placeholder={String(HEALTH_BAR_DEFAULTS.showTicks)}
                step={10}
                min={0}
                onChange={(value) =>
                  editBar((b) =>
                    setKey(b, 'showTicks', value === undefined ? undefined : Math.round(value)),
                  )
                }
              />
              <NumberField
                label="Offset"
                dataPath="centity.health.bar.offset"
                value={bar.offset}
                placeholder={String(HEALTH_BAR_DEFAULTS.offset)}
                step={0.05}
                onChange={(value) => editBar((b) => setKey(b, 'offset', value))}
              />
              {BAR_TEXTS.map(({ key, label }) => (
                <TextField
                  key={key}
                  label={label}
                  dataPath={`centity.health.bar.${key}`}
                  value={bar[key]}
                  placeholder={HEALTH_BAR_DEFAULTS[key]}
                  onChange={(value) => editBar((b) => setKey(b, key, value || undefined))}
                />
              ))}
              <Hint>
                0 ticks always shows it. A text display node named{' '}
                <code>{HEALTH_BAR_DEFAULTS.node}</code> draws it where you place it; without one the
                server adds it over the hitboxes.
              </Hint>
            </FieldGroup>
          )}
        </>
      )}
    </Section>
  )
}
