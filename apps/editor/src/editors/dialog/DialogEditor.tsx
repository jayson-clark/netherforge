/**
 * The dialog editor: a live preview of the screen | the inspector for the
 * dialog (its one script included) and its body, inputs and buttons (add,
 * reorder, edit). Clicking an element in the preview picks it in the inspector;
 * problems about one (`$.buttons[1].key`) pick it and focus the field.
 */
import { setKey } from '@/core/draft'
import type { ReactNode } from 'react'
import {
  newScript,
  newSiblingFile,
  DIALOG_COLUMNS,
  DialogTypeValues,
  type AfterAction,
  type DialogBody,
  type DialogButton,
  type DialogFile,
  type DialogInput,
  type DialogType,
  type ItemDef,
} from '@/core/format'
import { ItemEditor } from '@/minecraft/item/ItemForm'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { dirname } from '@/core/paths'
import { useWorkspace } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import { ask } from '@/ui/dialogs'
import {
  NumberField,
  Row,
  Section,
  SelectField,
  TextField,
  TriStateField,
  Check,
  CheckField,
} from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import entries from '@/editors/shared/entries.module.css'
import { ScriptField } from '@/editors/shared/ScriptField'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import styles from './DialogEditor.module.css'
import { DialogPreview, type DialogPick } from './DialogPreview'
import { useListedDialogs } from './listed'
import {
  addBody,
  addButton,
  addInput,
  canAddButton,
  moveEntry,
  removeEntry,
  setType,
  type ListName,
} from './ops'

/** One empty list, so a selector returns the same value while there's no outline yet. */
const NO_DIALOGS: string[] = []

const TYPE_LABELS: Record<DialogType, string> = {
  notice: 'Notice (one button)',
  confirmation: 'Confirmation (yes, no)',
  multi_action: 'Multi action (columns)',
  dialog_list: 'Dialog list',
}
const AFTER_ACTIONS: AfterAction[] = ['close', 'none', 'wait_for_response']

export function DialogEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<DialogFile>(path)
  const picked = usePrimary('dialog', path)
  const view = useView('dialog', path)
  const glyphs = useGlyphMap()
  const listed = useListedDialogs(model?.dialogs ?? [])
  const pick = (next: DialogPick) => (next ? view.select(next) : view.select())

  useFocusRequests(path, (segments) => {
    const [list, index] = segments
    if ((list === 'body' || list === 'inputs' || list === 'buttons') && typeof index === 'number') {
      pick({ list, index })
    }
    return segments
  })

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  return (
    <EditorScreen>
      <EditorBar title={model.name ?? path.split('/')[1]}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage className={styles.stage}>
        <DialogPreview
          model={model}
          listed={listed}
          glyphs={glyphs}
          picked={picked}
          onPick={pick}
        />
      </Stage>
      <InspectorPanel>
        <DialogSettings path={path} model={model} edit={edit} />
        <ListSection
          title="Body"
          list="body"
          items={model.body ?? []}
          picked={picked}
          onPick={pick}
          edit={edit}
          describe={(part) =>
            part.type === 'message'
              ? `Message: ${part.text.slice(0, 24)}`
              : `Item: ${part.item.kind}`
          }
          adders={[
            {
              label: 'Add message',
              run: () =>
                edit((draft) =>
                  pick({ list: 'body', index: addBody(draft, { type: 'message', text: 'Text' }) }),
                ),
            },
            {
              label: 'Add item',
              run: async () => {
                const id = await askItemId()
                if (id)
                  edit((draft) =>
                    pick({
                      list: 'body',
                      index: addBody(draft, { type: 'item', item: { kind: id } }),
                    }),
                  )
              },
            },
          ]}
          render={(part, index) => <BodyFields part={part} index={index} edit={edit} />}
        />
        <ListSection
          title="Inputs"
          list="inputs"
          items={model.inputs ?? []}
          picked={picked}
          onPick={pick}
          edit={edit}
          describe={(input) => `${input.key} (${input.type.replace('_', ' ')})`}
          adders={(['text', 'boolean', 'single_option', 'number_range'] as const).map((type) => ({
            label: `Add ${type.replace('_', ' ')} input`,
            run: () => edit((draft) => pick({ list: 'inputs', index: addInput(draft, type) })),
          }))}
          render={(input, index) => <InputFields input={input} index={index} edit={edit} />}
        />
        <ListSection
          title="Buttons"
          list="buttons"
          items={model.buttons ?? []}
          picked={picked}
          onPick={pick}
          edit={edit}
          describe={(button) => button.label ?? button.key}
          adders={[
            {
              label: 'Add button',
              disabled: !canAddButton(model),
              title: canAddButton(model)
                ? undefined
                : `A ${model.type ?? 'notice'} dialog has no room for another button`,
              run: () => edit((draft) => pick({ list: 'buttons', index: addButton(draft) })),
            },
          ]}
          render={(button, index) => <ButtonFields button={button} index={index} edit={edit} />}
        />
      </InspectorPanel>
    </EditorScreen>
  )
}

const askItemId = () =>
  ask
    .prompt({
      title: 'Item',
      label: 'Item id',
      message: 'The item type, like minecraft:diamond.',
      confirmLabel: 'Add',
      validate: (value) =>
        /^([a-z0-9_.-]+:)?[a-z0-9_./-]+$/.test(value.trim())
          ? null
          : 'An item id, like minecraft:diamond',
    })
    .then((id) => id?.trim() ?? null)

function DialogSettings({
  path,
  model,
  edit,
}: {
  path: string
  model: DialogFile
  edit: (recipe: (draft: DialogFile) => void) => void
}) {
  const dialogs = useWorkspace((s) => s.outline?.resources.dialog ?? NO_DIALOGS)
  const self = path.split('/')[1]
  const type = model.type ?? 'notice'
  return (
    <Section title="Dialog" icon="dialog">
      <TextField
        label="Title"
        dataPath="title"
        value={model.title}
        onChange={(title) => edit((draft) => (draft.title = title))}
      />
      <TextField
        label="Name"
        dataPath="name"
        value={model.name}
        placeholder="(for people)"
        onChange={(value) => edit((draft) => setKey(draft, 'name', value))}
      />
      <TextField
        label="External title"
        dataPath="externalTitle"
        value={model.externalTitle}
        placeholder="(the title)"
        onChange={(value) => edit((draft) => setKey(draft, 'externalTitle', value))}
      />
      <SelectField
        label="Type"
        dataPath="type"
        value={type}
        options={DialogTypeValues.map((it) => ({ value: it, label: TYPE_LABELS[it] }))}
        onChange={(next) => edit((draft) => setType(draft, next))}
      />
      {type === 'multi_action' && (
        <NumberField
          label="Columns"
          dataPath="columns"
          value={model.columns}
          placeholder={String(DIALOG_COLUMNS.default)}
          step={1}
          min={1}
          onChange={(value) =>
            edit((draft) =>
              setKey(
                draft,
                'columns',
                value === undefined ? undefined : Math.min(DIALOG_COLUMNS.max, Math.round(value)),
              ),
            )
          }
        />
      )}
      <SelectField
        label="After a press"
        dataPath="afterAction"
        value={model.afterAction ?? ''}
        options={[
          { value: '', label: 'Default (close)' },
          ...AFTER_ACTIONS.filter((it) => it !== 'close').map((it) => ({
            value: it,
            label: it === 'none' ? 'Stay up' : 'Show the waiting screen',
          })),
          { value: 'close', label: 'Close' },
        ]}
        onChange={(value) =>
          edit((draft) =>
            setKey(draft, 'afterAction', (value || undefined) as AfterAction | undefined),
          )
        }
      />
      <TriStateField
        label="Escape closes"
        dataPath="canCloseWithEscape"
        value={model.canCloseWithEscape}
        defaultLabel="on"
        onChange={(value) => edit((draft) => setKey(draft, 'canCloseWithEscape', value))}
      />
      <CheckField
        label="On the pause screen"
        dataPath="pauseMenu"
        value={model.pauseMenu ?? false}
        onChange={(value) => edit((draft) => setKey(draft, 'pauseMenu', value || undefined))}
      />
      <CheckField
        label="On the quick actions key"
        dataPath="quickActions"
        value={model.quickActions ?? false}
        onChange={(value) => edit((draft) => setKey(draft, 'quickActions', value || undefined))}
      />
      {type === 'dialog_list' && (
        <Row label="Dialogs">
          <div className={styles.listPicker} data-path="dialogs">
            {dialogs
              .filter((it) => it !== self)
              .map((id) => (
                <Check
                  key={id}
                  label={id}
                  checked={(model.dialogs ?? []).includes(id)}
                  onChange={(checked) =>
                    edit((draft) => {
                      const next = (draft.dialogs ?? []).filter((it) => it !== id)
                      if (checked) next.push(id)
                      setKey(draft, 'dialogs', next.length ? next : undefined)
                    })
                  }
                />
              ))}
          </div>
        </Row>
      )}
      <ScriptField
        folder={dirname(path)}
        script={model.script}
        dataPath="script"
        suggestedName="script.lua"
        template={newScript('dialog', '')}
        siblingTemplate={newSiblingFile('dialog')}
        onChange={(script) => edit((draft) => setKey(draft, 'script', script))}
      />
    </Section>
  )
}

function ListSection<T>({
  title,
  list,
  items,
  picked,
  onPick,
  edit,
  describe,
  adders,
  render,
}: {
  title: string
  list: ListName
  items: T[]
  picked: DialogPick
  onPick: (pick: DialogPick) => void
  edit: (recipe: (draft: DialogFile) => void) => void
  describe: (item: T) => string
  adders: { label: string; run: () => void | Promise<void>; disabled?: boolean; title?: string }[]
  render: (item: T, index: number) => ReactNode
}) {
  const move = (from: number, to: number) => {
    edit((draft) => moveEntry(draft, list, from, to))
    if (picked?.list === list && picked.index === from) onPick({ list, index: to })
  }
  return (
    <Section title={`${title} (${items.length})`}>
      <ol className={entries.list} aria-label={title}>
        {items.map((item, index) => {
          const open = picked?.list === list && picked.index === index
          return (
            <li key={index} className={cx(entries.entry, open && entries.open)}>
              <div className={entries.header}>
                <button
                  type="button"
                  className={entries.title}
                  aria-expanded={open}
                  onClick={() => onPick(open ? null : { list, index })}
                >
                  {describe(item)}
                </button>
                <IconButton
                  icon="chevronUp"
                  label={`Move ${describe(item)} up`}
                  disabled={index === 0}
                  onClick={() => move(index, index - 1)}
                />
                <IconButton
                  icon="chevronDown"
                  label={`Move ${describe(item)} down`}
                  disabled={index === items.length - 1}
                  onClick={() => move(index, index + 1)}
                />
                <IconButton
                  icon="trash"
                  label={`Remove ${describe(item)}`}
                  onClick={() => {
                    edit((draft) => removeEntry(draft, list, index))
                    onPick(null)
                  }}
                />
              </div>
              {open && <div className={entries.body}>{render(item, index)}</div>}
            </li>
          )
        })}
      </ol>
      <div className={entries.adders}>
        {adders.map((adder) => (
          <Button
            key={adder.label}
            size="small"
            icon="plus"
            disabled={adder.disabled}
            title={adder.title}
            onClick={() => void adder.run()}
          >
            {adder.label}
          </Button>
        ))}
      </div>
    </Section>
  )
}

type Edit = (recipe: (draft: DialogFile) => void) => void

const int = (value: number | undefined) => (value === undefined ? undefined : Math.round(value))

function BodyFields({ part, index, edit }: { part: DialogBody; index: number; edit: Edit }) {
  const glyphs = useGlyphMap()
  const at = `body[${index}]`
  const editPart = <P extends DialogBody>(recipe: (draft: P) => void) =>
    edit((draft) => {
      const target = draft.body?.[index]
      if (target) recipe(target as P)
    })
  // Optional: a name scripts can refer to the element by.
  const key = (
    <TextField
      label="Key"
      dataPath={`${at}.key`}
      value={part.key}
      placeholder="none"
      onChange={(value) => editPart((d) => setKey(d, 'key', value.trim() || undefined))}
    />
  )
  if (part.type === 'message') {
    return (
      <>
        {key}
        <TextField
          label="Text"
          multiline
          dataPath={`${at}.text`}
          value={part.text}
          onChange={(text) => editPart<typeof part>((d) => (d.text = text))}
        />
        <NumberField
          label="Width"
          dataPath={`${at}.width`}
          value={part.width}
          placeholder="200"
          step={10}
          min={1}
          onChange={(value) => editPart<typeof part>((d) => setKey(d, 'width', int(value)))}
        />
      </>
    )
  }
  const editItem = (recipe: (draft: ItemDef) => void) =>
    editPart<typeof part>((d) => recipe(d.item))
  return (
    <>
      {key}
      <ItemEditor item={part.item} onEdit={editItem} dataPath={`${at}.item`} glyphs={glyphs} />
      <TextField
        label="Description"
        dataPath={`${at}.description`}
        value={part.description}
        onChange={(value) => editPart<typeof part>((d) => setKey(d, 'description', value))}
      />
      <TriStateField
        label="Show tooltip"
        dataPath={`${at}.showTooltip`}
        value={part.showTooltip}
        defaultLabel="on"
        onChange={(value) => editPart<typeof part>((d) => setKey(d, 'showTooltip', value))}
      />
      <NumberField
        label="Width"
        dataPath={`${at}.width`}
        value={part.width}
        placeholder="16"
        step={1}
        min={1}
        onChange={(value) => editPart<typeof part>((d) => setKey(d, 'width', int(value)))}
      />
      <NumberField
        label="Height"
        dataPath={`${at}.height`}
        value={part.height}
        placeholder="16"
        step={1}
        min={1}
        onChange={(value) => editPart<typeof part>((d) => setKey(d, 'height', int(value)))}
      />
    </>
  )
}

function InputFields({ input, index, edit }: { input: DialogInput; index: number; edit: Edit }) {
  const at = `inputs[${index}]`
  const editInput = <P extends DialogInput>(recipe: (draft: P) => void) =>
    edit((draft) => {
      const target = draft.inputs?.[index]
      if (target) recipe(target as P)
    })
  const common = (
    <>
      <TextField
        label="Key"
        dataPath={`${at}.key`}
        value={input.key}
        onChange={(key) => editInput((d) => (d.key = key.trim()))}
      />
      <TextField
        label="Label"
        dataPath={`${at}.label`}
        value={input.label}
        placeholder={input.key}
        onChange={(value) => editInput((d) => setKey(d, 'label', value))}
      />
    </>
  )
  const width = (
    <NumberField
      label="Width"
      dataPath={`${at}.width`}
      value={'width' in input ? input.width : undefined}
      placeholder="200"
      step={10}
      min={1}
      onChange={(value) =>
        editInput<Extract<DialogInput, { width?: number }>>((d) => setKey(d, 'width', int(value)))
      }
    />
  )
  switch (input.type) {
    case 'text':
      return (
        <>
          {common}
          {width}
          <TextField
            label="Initial"
            dataPath={`${at}.initial`}
            value={input.initial}
            onChange={(value) => editInput<typeof input>((d) => setKey(d, 'initial', value))}
          />
          <NumberField
            label="Max length"
            dataPath={`${at}.maxLength`}
            value={input.maxLength}
            step={1}
            min={1}
            onChange={(value) => editInput<typeof input>((d) => setKey(d, 'maxLength', int(value)))}
          />
          <NumberField
            label="Lines"
            dataPath={`${at}.lines`}
            value={input.lines}
            placeholder="1"
            step={1}
            min={1}
            onChange={(value) => editInput<typeof input>((d) => setKey(d, 'lines', int(value)))}
          />
          <TriStateField
            label="Label visible"
            dataPath={`${at}.labelVisible`}
            value={input.labelVisible}
            defaultLabel="on"
            onChange={(value) => editInput<typeof input>((d) => setKey(d, 'labelVisible', value))}
          />
        </>
      )
    case 'boolean':
      return (
        <>
          {common}
          <CheckField
            label="Starts ticked"
            dataPath={`${at}.initial`}
            value={input.initial ?? false}
            onChange={(value) =>
              editInput<typeof input>((d) => setKey(d, 'initial', value || undefined))
            }
          />
          <TextField
            label="Reads when ticked"
            dataPath={`${at}.onTrue`}
            value={input.onTrue}
            placeholder="true"
            onChange={(value) => editInput<typeof input>((d) => setKey(d, 'onTrue', value))}
          />
          <TextField
            label="Reads when not"
            dataPath={`${at}.onFalse`}
            value={input.onFalse}
            placeholder="false"
            onChange={(value) => editInput<typeof input>((d) => setKey(d, 'onFalse', value))}
          />
        </>
      )
    case 'single_option':
      return (
        <>
          {common}
          {width}
          <TextField
            label="Options"
            multiline
            dataPath={`${at}.options`}
            value={input.options
              .map((it) => (it.label ? `${it.id}=${it.label}` : it.id))
              .join('\n')}
            placeholder="id=Label, one per line"
            onChange={(text) =>
              editInput<typeof input>((d) => {
                const initial = d.options.find((it) => it.initial)?.id
                d.options = text
                  .split('\n')
                  .map((line) => line.trim())
                  .filter(Boolean)
                  .map((line) => {
                    const [id, ...label] = line.split('=')
                    const option: (typeof d.options)[number] = { id: id!.trim() }
                    if (label.length) option.label = label.join('=').trim()
                    if (option.id === initial) option.initial = true
                    return option
                  })
              })
            }
          />
          <SelectField
            label="Starts on"
            value={input.options.find((it) => it.initial)?.id ?? ''}
            options={[
              { value: '', label: '(the first)' },
              ...input.options.map((it) => ({ value: it.id, label: it.label ?? it.id })),
            ]}
            onChange={(id) =>
              editInput<typeof input>((d) => {
                for (const option of d.options) {
                  if (option.id === id) option.initial = true
                  else delete option.initial
                }
              })
            }
          />
        </>
      )
    case 'number_range':
      return (
        <>
          {common}
          {width}
          {(['start', 'end'] as const).map((key) => (
            <NumberField
              key={key}
              label={key === 'start' ? 'Start' : 'End'}
              dataPath={`${at}.${key}`}
              value={input[key]}
              step={1}
              onChange={(value) =>
                value !== undefined && editInput<typeof input>((d) => (d[key] = value))
              }
            />
          ))}
          <NumberField
            label="Step"
            dataPath={`${at}.step`}
            value={input.step}
            onChange={(value) => editInput<typeof input>((d) => setKey(d, 'step', value))}
          />
          <NumberField
            label="Initial"
            dataPath={`${at}.initial`}
            value={input.initial}
            placeholder="the middle"
            onChange={(value) => editInput<typeof input>((d) => setKey(d, 'initial', value))}
          />
        </>
      )
  }
}

function ButtonFields({
  button,
  index,
  edit,
}: {
  button: DialogButton
  index: number
  edit: Edit
}) {
  const at = `buttons[${index}]`
  const editButton = (recipe: (draft: DialogButton) => void) =>
    edit((draft) => {
      const target = draft.buttons?.[index]
      if (target) recipe(target)
    })
  return (
    <>
      <TextField
        label="Key"
        dataPath={`${at}.key`}
        value={button.key}
        onChange={(key) => editButton((d) => (d.key = key.trim()))}
      />
      <TextField
        label="Label"
        dataPath={`${at}.label`}
        value={button.label}
        placeholder={button.key}
        onChange={(value) => editButton((d) => setKey(d, 'label', value))}
      />
      <TextField
        label="Tooltip"
        dataPath={`${at}.tooltip`}
        value={button.tooltip}
        onChange={(value) => editButton((d) => setKey(d, 'tooltip', value))}
      />
      <NumberField
        label="Width"
        dataPath={`${at}.width`}
        value={button.width}
        placeholder="150"
        step={10}
        min={1}
        onChange={(value) => editButton((d) => setKey(d, 'width', int(value)))}
      />
    </>
  )
}
