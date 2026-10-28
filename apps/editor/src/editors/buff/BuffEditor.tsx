/**
 * A project buff (`buffs/<id>.json`, docs/format/buff.md): what scripts put on a player or a centity for a while
 * with `add_buff`. The stage shows it as a HUD might (its colour and name); the inspector edits how it's shown,
 * the stats it adds, what it cures and what keeps it off.
 */
import type { BuffFile, BuffStacking } from '@/core/format'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { CheckField, ColorField, NumberField, Section, SelectField, TextField } from '@/ui/fields'
import { Hint } from '@/ui/text'
import styles from './BuffEditor.module.css'

const STACKING: { value: BuffStacking; label: string }[] = [
  { value: 'longest', label: 'Keep the longer time' },
  { value: 'add', label: 'Add the times' },
  { value: 'replace', label: 'Start again' },
]

/** `"a, b"` as a list, empty entries left out. */
const list = (text: string) =>
  text
    .split(',')
    .map((it) => it.trim())
    .filter((it) => it.length > 0)

export function BuffEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<BuffFile>(path)
  const id = path.split('/')[1]?.replace(/\.json$/, '') ?? ''
  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />
  const stats = Object.entries(model.stats ?? {})
  const setStats = (next: [string, number][]) =>
    edit((d) => {
      if (next.length === 0) delete d.stats
      else d.stats = Object.fromEntries(next)
    })
  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div
          className={styles.badge}
          role="img"
          aria-label="How the buff is shown"
          style={{ borderColor: model.color ?? (model.debuff ? '#b01c24' : '#8a8a98') }}
        >
          <span className={styles.name}>{model.name ?? id}</span>
          <span className={styles.kind}>{model.debuff ? 'Debuff' : 'Buff'}</span>
          <ul className={styles.stats} aria-label="What it adds">
            {stats.map(([stat, value]) => (
              <li key={stat}>
                {value > 0 ? '+' : ''}
                {value} {stat}
              </li>
            ))}
          </ul>
        </div>
      </Stage>
      <InspectorPanel>
        <Section title="Shown as" icon="eye">
          <TextField
            label="Name"
            dataPath="name"
            value={model.name}
            placeholder={id}
            onChange={(value) =>
              edit((d) => {
                if (value.trim()) d.name = value
                else delete d.name
              })
            }
          />
          <TextField
            label="Icon"
            dataPath="icon"
            value={model.icon}
            placeholder="ui/buffs/ironskin"
            onChange={(value) =>
              edit((d) => {
                if (value.trim()) d.icon = value.trim()
                else delete d.icon
              })
            }
          />
          <ColorField
            label="Colour"
            dataPath="color"
            value={model.color}
            onChange={(value) =>
              edit((d) => {
                if (value) d.color = value
                else delete d.color
              })
            }
          />
          <CheckField
            label="A debuff"
            dataPath="debuff"
            value={model.debuff === true}
            onChange={(debuff) =>
              edit((d) => {
                if (debuff) d.debuff = true
                else delete d.debuff
              })
            }
          />
        </Section>
        <Section title="Stats" icon="chart">
          {stats.map(([stat, value], index) => (
            <NumberField
              key={stat}
              label={stat}
              dataPath={`stats.${stat}`}
              value={value}
              onChange={(next) =>
                setStats(
                  next === undefined
                    ? stats.filter((_, i) => i !== index)
                    : stats.map((it, i) => (i === index ? [stat, next] : it)),
                )
              }
            />
          ))}
          <TextField
            label="Add a stat"
            value=""
            placeholder="defense"
            onChange={(name) => {
              const stat = name.trim()
              if (stat && !stats.some(([it]) => it === stat)) setStats([...stats, [stat, 1]])
            }}
          />
          <Hint>
            What it adds to each stat while it lasts; scripts read the totals with
            player:stat("defense"). Clear a number to take the stat off.
          </Hint>
        </Section>
        <Section title="Rules" icon="sliders">
          <TextField
            label="Cures"
            dataPath="cures"
            value={(model.cures ?? []).join(', ')}
            placeholder="frail, on_fire"
            onChange={(value) =>
              edit((d) => {
                const next = list(value)
                if (next.length) d.cures = next
                else delete d.cures
              })
            }
          />
          <TextField
            label="Blocked by"
            dataPath="blockedBy"
            value={(model.blockedBy ?? []).join(', ')}
            placeholder="wet"
            onChange={(value) =>
              edit((d) => {
                const next = list(value)
                if (next.length) d.blockedBy = next
                else delete d.blockedBy
              })
            }
          />
          <SelectField
            label="Added again"
            dataPath="stacking"
            value={model.stacking ?? 'longest'}
            options={STACKING}
            onChange={(value) =>
              edit((d) => {
                if (value === 'longest') delete d.stacking
                else d.stacking = value
              })
            }
          />
          <NumberField
            label="Most ticks"
            dataPath="maxTicks"
            value={model.maxTicks}
            step={1}
            min={1}
            onChange={(value) =>
              edit((d) => {
                if (value === undefined) delete d.maxTicks
                else d.maxTicks = Math.round(value)
              })
            }
          />
          <Hint>
            Scripts give it with player:add_buff("{id}", ticks). It isn't saved: after a restart
            nobody has it.
          </Hint>
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}
