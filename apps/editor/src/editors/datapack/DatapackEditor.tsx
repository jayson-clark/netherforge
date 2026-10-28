/**
 * A datapack passed through (`datapacks/<id>/`): the game's own worldgen JSON,
 * for experts. The inspector edits `pack.mcmeta` (its description, the formats
 * it's for and its overlays); the screen lists its files by folder, each opening
 * in a JSON tab of its own, and makes new ones. What's wrong with any of them is
 * in Problems.
 */
import type { PackMeta } from '@/core/format'
import { locationOf, resourceOf } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { EditorBar, EditorScreen, Page } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { FieldGroup, Row, Section, TextField } from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { ask } from '@/ui/dialogs'
import { Hint, Muted } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { addOverlay, fileGroups, formatText, parseFormat, rangeText, removeOverlay } from './ops'

export function DatapackEditor({ path }: { path: string }) {
  const { workspace } = useApp()
  const { model, edit } = useModelDoc<PackMeta>(path)
  const found = resourceOf(path)
  const id = found?.id ?? path
  const folder = `${locationOf('datapack', id, found?.group)}/`
  const all = useWorkspace((s) => s.files)
  useFocusRequests(path, (segments) => segments)
  const files = all.filter((file) => file.startsWith(folder))
  if (!model) return <RawDocView path={path} />
  const groups = fileGroups(
    model,
    files.map((file) => file.slice(folder.length)),
  )

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <Button
          size="small"
          icon="plus"
          onClick={async () => {
            const file = await ask.prompt({
              title: 'New datapack file',
              label: `Path in ${folder}`,
              initial: 'data/',
              confirmLabel: 'Create',
              validate: (value) =>
                !/^[a-z0-9_]+\/data\/|^data\//.test(value) || !value.endsWith('.json')
                  ? 'A .json file under data/ (or an overlay folder’s data/)'
                  : files.includes(`${folder}${value}`)
                    ? 'That file exists'
                    : null,
            })
            if (!file) return
            await workspace.getState().createFile(`${folder}${file}`, '{}\n')
            void workspace.getState().openFile(`${folder}${file}`)
          }}
        >
          New file…
        </Button>
        <EditAsJson path={path} />
      </EditorBar>
      <Page>
        <Hint>
          The game&apos;s own worldgen JSON, passed through into the server&apos;s start-up
          datapack. A server whose data pack format isn&apos;t{' '}
          {rangeText(model.pack.min_format, model.pack.max_format)} leaves it out. Saving a file
          restarts the dev server.
        </Hint>
        {groups.map((group) => (
          <Section
            key={group.folder || 'other'}
            title={
              group.folder === ''
                ? 'Not part of the pack'
                : group.folder === 'data'
                  ? `data/ (formats ${group.formats})`
                  : `Overlay ${group.folder}/ (formats ${group.formats})`
            }
          >
            {group.files.length === 0 ? (
              <Muted>No files.</Muted>
            ) : (
              <ul aria-label={`Files in ${group.folder || 'no folder of the pack'}`}>
                {group.files.map((file) => (
                  <li key={file}>
                    <Button
                      size="small"
                      variant="ghost"
                      onClick={() => void workspace.getState().openFile(`${folder}${file}`)}
                    >
                      {file}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </Section>
        ))}
      </Page>
      <InspectorPanel>
        <PackFields model={model} edit={edit} />
      </InspectorPanel>
    </EditorScreen>
  )
}

type Edit = (recipe: (draft: PackMeta) => void) => void

/** A format field: `94` or `94.1`, put back as it was when it's neither. */
function FormatField({
  label,
  dataPath,
  value,
  onChange,
}: {
  label: string
  dataPath: string
  value: unknown
  onChange: (value: number | [number, number]) => void
}) {
  return (
    <TextField
      label={label}
      dataPath={dataPath}
      value={formatText(value) ?? ''}
      placeholder="94, or 94.1"
      onChange={(text) => {
        const parsed = parseFormat(text)
        if (parsed !== null) onChange(parsed)
      }}
    />
  )
}

/** `pack.mcmeta`: the pack's description and formats, and its overlays in the order the game applies them. */
function PackFields({ model, edit }: { model: PackMeta; edit: Edit }) {
  const description = model.pack.description
  const overlays = model.overlays?.entries ?? []
  return (
    <>
      <Section title="Pack" icon="box">
        {description === undefined || typeof description === 'string' ? (
          <TextField
            label="Description"
            dataPath="pack.description"
            value={description}
            placeholder="(none)"
            onChange={(text) =>
              edit((draft) => {
                if (text) draft.pack.description = text
                else delete draft.pack.description
              })
            }
          />
        ) : (
          <Hint>The description is a text component: edit it as JSON.</Hint>
        )}
        <FormatField
          label="Oldest format"
          dataPath="pack.min_format"
          value={model.pack.min_format}
          onChange={(value) => edit((draft) => void (draft.pack.min_format = value))}
        />
        <FormatField
          label="Newest format"
          dataPath="pack.max_format"
          value={model.pack.max_format}
          onChange={(value) => edit((draft) => void (draft.pack.max_format = value))}
        />
      </Section>
      <Section
        title="Overlays"
        actions={
          <IconButton
            icon="plus"
            label="Add overlay"
            onClick={async () => {
              const directory = await ask.prompt({
                title: 'New overlay',
                label: 'Folder',
                initial: '',
                confirmLabel: 'Add',
                validate: (value) =>
                  !/^[a-z0-9_]+$/.test(value)
                    ? 'Lowercase letters, digits and _'
                    : value === 'data' || overlays.some((it) => it.directory === value)
                      ? 'That folder is taken'
                      : null,
              })
              if (directory) edit((draft) => addOverlay(draft, directory))
            }}
          />
        }
      >
        {overlays.length === 0 && (
          <Hint>
            An overlay is a folder of files for some formats only, applied over data/ in order: each
            Minecraft version&apos;s own shape of a file.
          </Hint>
        )}
        {overlays.map((overlay, index) => {
          const at = `overlays.entries[${index}]`
          return (
            <FieldGroup
              key={index}
              data-path={at}
              header={
                <>
                  {overlay.directory}/
                  <IconButton
                    icon="trash"
                    label={`Remove overlay ${overlay.directory}`}
                    onClick={() => edit((draft) => removeOverlay(draft, index))}
                  />
                </>
              }
            >
              <TextField
                label="Folder"
                dataPath={`${at}.directory`}
                value={overlay.directory}
                onChange={(text) =>
                  text.trim() &&
                  edit((draft) => void (draft.overlays!.entries![index]!.directory = text.trim()))
                }
              />
              <FormatField
                label="Oldest format"
                dataPath={`${at}.min_format`}
                value={overlay.min_format}
                onChange={(value) =>
                  edit((draft) => void (draft.overlays!.entries![index]!.min_format = value))
                }
              />
              <FormatField
                label="Newest format"
                dataPath={`${at}.max_format`}
                value={overlay.max_format}
                onChange={(value) =>
                  edit((draft) => void (draft.overlays!.entries![index]!.max_format = value))
                }
              />
            </FieldGroup>
          )
        })}
        <Row label="">
          <Muted>Renaming a folder doesn&apos;t move its files.</Muted>
        </Row>
      </Section>
    </>
  )
}
