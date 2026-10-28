/**
 * An advancement (`advancements/<id>.json`): how the game's advancements
 * screen shows it (its frame, icon, title and description), the criteria that
 * complete it as cards, and which of them are needed. The inspector edits the
 * advancement (its parent, experience, display, requirements) and the selected
 * criterion (its trigger and conditions).
 *
 * Criteria are listed in the outline (`AdvancementOutline`). Problems focus the
 * field they're about.
 */
import { useMemo, useState } from 'react'
import { TRIGGER_TYPE_REGISTRY, type AdvancementFile, type Problem } from '@/core/format'
import { setKey } from '@/core/draft'
import { resourceOf } from '@/core/paths'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { useNameableIds, useProjectItemIds } from '@/minecraft/item/projectItems'
import { usePickerIds } from '@/minecraft/client/usePickerIds'
import { MiniText } from '@/minecraft/text/MiniText'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { useWorkspace } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { fieldPath } from '@/editors/shared/focus'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { problemsUnder } from '@/editors/recipe/problems'
import { Button, IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import {
  CheckField,
  Datalist,
  NumberField,
  Section,
  SelectField,
  TextField,
  TriStateField,
} from '@/ui/fields'
import { Icon } from '@/ui/Icon'
import { Empty, Muted } from '@/ui/text'
import {
  addCriterion,
  addGroup,
  conditionsText,
  criterionNames,
  customizeRequirements,
  FRAMES,
  groupsOf,
  parseConditions,
  parseGroup,
  removeCriterion,
  removeGroup,
  resetRequirements,
  setConditions,
  setDisplay,
  setDisplayEnabled,
  setGroup,
  setIconItem,
  setIconKind,
  setTrigger,
} from './ops'
import styles from './AdvancementEditor.module.css'

type Edit = (recipe: (draft: AdvancementFile) => void) => void

const idOf = (path: string) => resourceOf(path)?.id ?? ''

/** `criteria.found.trigger`, `display.title`: where a field is, for `data-path`s and problems. */
const at = (...segments: (string | number)[]) => fieldPath(segments)

export function AdvancementEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<AdvancementFile>(path)
  const allProblems = useWorkspace((s) => s.problems)
  const problems = useMemo(() => allProblems.filter((it) => it.file === path), [allProblems, path])
  const view = useView('advancement', path)
  const primary = usePrimary('advancement', path)
  const glyphs = useGlyphMap()

  useFocusRequests(path, (segments) => {
    const [first, name] = segments
    if (first === 'criteria' && typeof name === 'string') view.select(name)
    return segments
  })

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const id = idOf(path)
  const names = criterionNames(model)
  const selected = primary && names.includes(primary) ? primary : names[0]
  const display = model.display
  const groups = groupsOf(model)

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.page}>
          <section className={styles.shown} aria-label="Preview">
            {display ? (
              <div className={styles.card} data-frame={display.frame ?? 'task'}>
                <span className={styles.frame}>
                  <ItemIcon item={display.icon} size={32} />
                </span>
                <span className={styles.words}>
                  <span className={styles.title}>
                    <MiniText text={display.title} glyphs={glyphs} />
                  </span>
                  {display.description && (
                    <Muted>
                      <MiniText text={display.description} glyphs={glyphs} />
                    </Muted>
                  )}
                </span>
              </div>
            ) : (
              <Empty>
                No display: it is never shown, and only tracks something for scripts to read.
              </Empty>
            )}
            <Muted>
              {model.parent ? `Follows ${model.parent}` : 'The root of a tree'}
              {model.experience ? `, ${model.experience} experience` : ''}
            </Muted>
          </section>
          <section className={styles.criteria} aria-label="Criteria">
            <header className={styles.head}>
              <Icon name="check" /> <strong>Criteria</strong>
              <Muted>{describeGroups(groups)}</Muted>
            </header>
            {names.length === 0 && (
              <Empty>No criteria: the game refuses an advancement of none.</Empty>
            )}
            <ol className={styles.list} aria-label="Criteria">
              {names.map((name) => {
                const criterion = model.criteria![name]!
                const mine = problemsUnder(problems, at('criteria', name))
                return (
                  <li
                    key={name}
                    className={cx(
                      styles.entry,
                      selected === name && styles.selected,
                      mine.some((it) => it.severity === 'error') && styles.entryError,
                    )}
                    data-path={at('criteria', name)}
                  >
                    <button
                      type="button"
                      className={styles.entryMain}
                      aria-pressed={selected === name}
                      aria-label={`Criterion: ${name}`}
                      title={mine.map((it) => it.message).join('\n') || undefined}
                      onClick={() => view.select(name)}
                    >
                      <span className={styles.entryLabel}>{name}</span>
                      <Muted>{criterion.trigger ?? 'Met when a script grants it'}</Muted>
                    </button>
                    <IconButton
                      icon="trash"
                      label={`Remove ${name}`}
                      onClick={() => edit((draft) => removeCriterion(draft, name))}
                    />
                  </li>
                )
              })}
            </ol>
            <div>
              <Button
                size="small"
                icon="plus"
                onClick={() => {
                  let name = ''
                  edit((draft) => {
                    name = addCriterion(draft)
                  })
                  view.select(name)
                }}
              >
                Add criterion
              </Button>
            </div>
          </section>
        </div>
      </Stage>
      <InspectorPanel>
        <AdvancementInspector path={path} model={model} edit={edit} problems={problems} />
        {selected !== undefined && (
          <CriterionInspector name={selected} model={model} edit={edit} problems={problems} />
        )}
      </InspectorPanel>
    </EditorScreen>
  )
}

/** "All of: a, b" or "Any of: a, b; and any of: c" in words. */
function describeGroups(groups: string[][]): string {
  if (groups.length === 0) return ''
  if (groups.every((it) => it.length === 1)) return groups.length === 1 ? '' : 'Needs every one'
  return `Needs ${groups.map((it) => `one of ${it.join(', ')}`).join('; and ')}`
}

function AdvancementInspector({
  path,
  model,
  edit,
  problems,
}: {
  path: string
  model: AdvancementFile
  edit: Edit
  problems: Problem[]
}) {
  const advancements = useNameableIds('advancement')
  const projectItems = useProjectItemIds()
  const pickerIds = usePickerIds()
  const self = idOf(path)
  const parents = advancements.filter((it) => it !== self)
  const display = model.display
  const icon = display?.icon
  const project = icon?.item !== undefined
  return (
    <>
      <Section title="Advancement" icon="trophy">
        <SelectField
          label="Parent"
          dataPath={at('parent')}
          value={model.parent ?? ''}
          options={[
            { value: '', label: 'None (the root of a tree)' },
            ...(model.parent && !parents.includes(model.parent)
              ? [{ value: model.parent, label: model.parent }]
              : []),
            ...parents.map((it) => ({ value: it, label: it })),
          ]}
          onChange={(value) => edit((draft) => setKey(draft, 'parent', value || undefined))}
        />
        <NumberField
          label="Experience"
          dataPath={at('experience')}
          value={model.experience}
          placeholder="0"
          step={1}
          min={0}
          onChange={(value) =>
            edit((draft) => setKey(draft, 'experience', value ? Math.round(value) : undefined))
          }
        />
        <FieldProblems problems={fieldProblems(problems, 'parent', 'experience')} />
      </Section>
      <Section
        title="Display"
        icon="eye"
        enabled={display !== undefined}
        onToggle={(on) => edit((draft) => setDisplayEnabled(draft, on))}
      >
        {display && icon && (
          <>
            <TextField
              label="Title"
              dataPath={at('display', 'title')}
              value={display.title}
              onChange={(value) =>
                edit((draft) => {
                  if (draft.display) draft.display.title = value
                })
              }
            />
            <TextField
              label="Description"
              dataPath={at('display', 'description')}
              value={display.description}
              multiline
              onChange={(value) => edit((draft) => setDisplay(draft, 'description', value))}
            />
            <SelectField
              label="Frame"
              dataPath={at('display', 'frame')}
              value={display.frame ?? 'task'}
              options={FRAMES}
              onChange={(value) =>
                edit((draft) => setDisplay(draft, 'frame', value === 'task' ? undefined : value))
              }
            />
            <SelectField
              label="Icon from"
              value={project ? 'project' : 'game'}
              options={[
                { value: 'game', label: 'A game item' },
                { value: 'project', label: 'A project item' },
              ]}
              onChange={(value) =>
                edit((draft) => {
                  if (value === 'project') setIconItem(draft, projectItems[0] ?? '')
                  else setIconKind(draft, 'minecraft:stone')
                })
              }
            />
            {project ? (
              <SelectField
                label="Icon item"
                dataPath={at('display', 'icon', 'item')}
                value={icon.item ?? ''}
                options={[
                  ...(icon.item && !projectItems.includes(icon.item)
                    ? [{ value: icon.item, label: icon.item }]
                    : []),
                  ...projectItems.map((it) => ({ value: it, label: it })),
                ]}
                onChange={(value) => edit((draft) => setIconItem(draft, value))}
              />
            ) : (
              <>
                <TextField
                  label="Icon item"
                  dataPath={at('display', 'icon', 'kind')}
                  value={icon.kind}
                  placeholder="minecraft:stone"
                  list="advancement-game-items"
                  onChange={(value) => edit((draft) => setIconKind(draft, value.trim()))}
                />
                <Datalist id="advancement-game-items" values={pickerIds.items} />
              </>
            )}
            <TextField
              label="Icon item model"
              dataPath={at('display', 'icon', 'itemModel')}
              value={icon.itemModel}
              placeholder="ui/ruby"
              onChange={(value) =>
                edit((draft) => {
                  if (draft.display) setKey(draft.display.icon, 'itemModel', value.trim())
                })
              }
            />
            <TriStateField
              label="Icon glint"
              dataPath={at('display', 'icon', 'glint')}
              value={icon.glint}
              defaultLabel="the item's own"
              onChange={(value) =>
                edit((draft) => {
                  if (draft.display) setKey(draft.display.icon, 'glint', value)
                })
              }
            />
            <TextField
              label="Background"
              dataPath={at('display', 'background')}
              value={display.background}
              placeholder="minecraft:block/stone_bricks"
              onChange={(value) => edit((draft) => setDisplay(draft, 'background', value.trim()))}
            />
            <CheckField
              label="Toast"
              dataPath={at('display', 'toast')}
              value={display.toast ?? true}
              onChange={(value) =>
                edit((draft) => setDisplay(draft, 'toast', value ? undefined : false))
              }
            />
            <CheckField
              label="Announce in chat"
              dataPath={at('display', 'announce')}
              value={display.announce ?? true}
              onChange={(value) =>
                edit((draft) => setDisplay(draft, 'announce', value ? undefined : false))
              }
            />
            <CheckField
              label="Hidden until completed"
              dataPath={at('display', 'hidden')}
              value={display.hidden ?? false}
              onChange={(value) =>
                edit((draft) => setDisplay(draft, 'hidden', value ? true : undefined))
              }
            />
            <FieldProblems problems={problemsUnder(problems, at('display'))} />
          </>
        )}
      </Section>
      <RequirementsSection model={model} edit={edit} problems={problems} />
    </>
  )
}

function RequirementsSection({
  model,
  edit,
  problems,
}: {
  model: AdvancementFile
  edit: Edit
  problems: Problem[]
}) {
  const custom = model.requirements !== undefined
  const groups = groupsOf(model)
  return (
    <Section
      title="Requirements"
      actions={
        custom && (
          <Button size="small" onClick={() => edit(resetRequirements)}>
            Need every criterion
          </Button>
        )
      }
    >
      {!custom ? (
        <>
          <Muted>Every criterion is needed.</Muted>
          <Button size="small" onClick={() => edit(customizeRequirements)}>
            Customize requirements
          </Button>
        </>
      ) : (
        <>
          <Muted>Each group is complete when one of its criteria is met.</Muted>
          {groups.map((group, index) => (
            <div key={index} className={styles.group}>
              <TextField
                label={`Group ${index + 1}`}
                dataPath={at('requirements', index)}
                value={group.join(', ')}
                placeholder="criterion, criterion"
                onChange={(value) => edit((draft) => setGroup(draft, index, parseGroup(value)))}
              />
              <IconButton
                icon="trash"
                label={`Remove group ${index + 1}`}
                onClick={() => edit((draft) => removeGroup(draft, index))}
              />
            </div>
          ))}
          <Button size="small" icon="plus" onClick={() => edit(addGroup)}>
            Add group
          </Button>
        </>
      )}
      <FieldProblems problems={problemsUnder(problems, at('requirements'))} />
    </Section>
  )
}

/** The selected criterion: what meets it. */
function CriterionInspector({
  name,
  model,
  edit,
  problems,
}: {
  name: string
  model: AdvancementFile
  edit: Edit
  problems: Problem[]
}) {
  const gameData = useWorkspace((s) => s.gameData)
  const criterion = model.criteria?.[name]
  if (!criterion) return null
  const here = at('criteria', name)
  return (
    <Section title={`Criterion ${name}`}>
      <TextField
        label="Trigger"
        dataPath={`${here}.trigger`}
        value={criterion.trigger}
        placeholder="None: a script grants it"
        list="advancement-triggers"
        onChange={(value) => edit((draft) => setTrigger(draft, name, value.trim() || undefined))}
      />
      <Datalist
        id="advancement-triggers"
        values={gameData?.registries?.[TRIGGER_TYPE_REGISTRY] ?? []}
      />
      {criterion.trigger && (
        <ConditionsField
          here={here}
          conditions={criterion.conditions}
          onChange={(value) => edit((draft) => setConditions(draft, name, value))}
        />
      )}
      <FieldProblems problems={problemsUnder(problems, here)} />
    </Section>
  )
}

/** The conditions as JSON, the game's own format: committed when they parse as an object. */
function ConditionsField({
  here,
  conditions,
  onChange,
}: {
  here: string
  conditions: Record<string, unknown> | undefined
  onChange: (value: Record<string, unknown> | undefined) => void
}) {
  const [bad, setBad] = useState(false)
  return (
    <>
      <TextField
        label="Conditions (JSON)"
        dataPath={`${here}.conditions`}
        value={conditionsText(conditions)}
        multiline
        placeholder="{}"
        onChange={(text) => {
          const parsed = parseConditions(text)
          setBad(parsed === null)
          if (parsed !== null) onChange(parsed)
        }}
      />
      {bad && (
        <div className={styles.fieldProblems} role="alert">
          <div className={styles.error}>Conditions must be a JSON object.</div>
        </div>
      )}
    </>
  )
}

/** [problems] about the advancement's own [keys]. */
const fieldProblems = (problems: Problem[], ...keys: string[]) =>
  keys.flatMap((key) => problemsUnder(problems, at(key)))

function FieldProblems({ problems }: { problems: Problem[] }) {
  if (problems.length === 0) return null
  return (
    <div className={styles.fieldProblems} role="alert">
      {problems.map((problem, index) => (
        <div key={index} className={styles[problem.severity]}>
          {problem.message}
        </div>
      ))}
    </div>
  )
}
