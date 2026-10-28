/**
 * A pack in the outline dock: its entries by kind (skins, glyphs, item
 * models, tooltips) and its sounds. Picking one picks it in the editor (and
 * shows the pack's tab); entries rename inline (F2), duplicate (Cmd/Ctrl+D)
 * and delete; a sound's Delete removes its file, as the editor's button does.
 */
import { ID_PATTERN, resourcePackSoundPath } from '@/core/paths'
import type { ResourcePackFile } from '@/core/format'
import { useApp, useWorkspace } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import { usePane, useTreeExpansion } from '@/state/useExpanded'
import { useModelDoc } from '@/editors/shared/modelDoc'
import { Badge } from '@/ui/Badge'
import { ask } from '@/ui/dialogs'
import { Pane } from '@/ui/Pane'
import { Tree, type TreeNode } from '@/ui/Tree'
import {
  duplicateEntry,
  ENTRY_KINDS,
  removeEntry,
  ENTRY_REFS,
  renameEntry,
  soundEvents,
  soundFileOf,
  soundFilesOf,
  packOf,
  type EntryKind,
} from './ops'
import { filesIn, splitPackagePath } from '@/core/store/packages'
import type { OutlineProps } from '@/editors/contributions'
import { pickKey } from './view'

const KINDS: Record<EntryKind | 'sounds', { title: string; icon: TreeNode['icon'] }> = {
  skins: { title: 'Skins', icon: 'grid' },
  glyphs: { title: 'Glyphs', icon: 'image' },
  items: { title: 'Item models', icon: 'gem' },
  tooltips: { title: 'Tooltips', icon: 'dialog' },
  equipment: { title: 'Equipment', icon: 'box' },
  blocks: { title: 'Blocks', icon: 'cube' },
  sounds: { title: 'Sounds', icon: 'sound' },
}

/** A row's entry: `skins:shop` → kind and key (the view's own key for it); null for a group row. */
function parseEntry(id: string): { kind: EntryKind | 'sounds'; key: string } | null {
  const at = id.indexOf(':')
  if (at < 0) return null
  const kind = id.slice(0, at)
  return kind in KINDS ? { kind: kind as EntryKind | 'sounds', key: id.slice(at + 1) } : null
}

export function ResourcePackOutline({ path, files: filesPane }: OutlineProps) {
  const { workspace } = useApp()
  const { model, edit } = useModelDoc<ResourcePackFile>(path)
  // A package's pack (opened read-only from Dependencies) has its pictures and sounds in the package.
  const files = useWorkspace((s) => filesIn(s, splitPackagePath(path)[0]))
  const selected = usePrimary('resource_pack', path)
  const view = useView('resource_pack', path)
  const pane = usePane('resource-pack-entries')
  const expansion = useTreeExpansion(`resource_pack:${path}`, () => true)
  if (!model) return filesPane
  const ws = () => workspace.getState()
  const pack = packOf(path)
  const soundFiles = soundFilesOf(files, pack)
  const sounds = soundEvents(model, soundFiles)

  const group = (kind: EntryKind | 'sounds', keys: string[]): TreeNode => ({
    id: kind,
    label: KINDS[kind].title,
    icon: 'folder',
    fixed: true,
    detail: <Badge>{keys.length}</Badge>,
    children: keys.map((key) => ({
      id: pickKey({ kind, key }),
      label: key,
      icon: KINDS[kind].icon,
    })),
  })
  const nodes: TreeNode[] = [
    ...ENTRY_KINDS.map((kind) => group(kind, Object.keys(model[kind] ?? {}).sort())),
    group(
      'sounds',
      sounds.map((it) => it.key),
    ),
  ]

  const select = (id: string) => {
    const entry = parseEntry(id)
    if (!entry) return
    view.select(entry)
    void ws().openFile(path)
  }
  const remove = async (id: string) => {
    const entry = parseEntry(id)
    if (!entry) return
    if (entry.kind !== 'sounds') {
      edit((draft) => removeEntry(draft, entry.kind as EntryKind, entry.key))
    } else {
      const file = soundFileOf(entry.key)
      if (!soundFiles.includes(file)) return
      const ok = await ask.confirm({
        title: 'Delete sound',
        message: `Delete resource_packs/${pack}/sounds/${file}? Scripts playing ${pack}/${entry.key} will play nothing.`,
        confirmLabel: 'Delete',
        danger: true,
      })
      if (ok !== true) return
      await ws().deletePath(resourcePackSoundPath(pack, file))
    }
    view.mapSelection((it) => (pickKey(it) === id ? null : it))
  }
  const duplicate = (id: string) => {
    const entry = parseEntry(id)
    if (!entry || entry.kind === 'sounds') return
    let copy: string | null = null
    edit((draft) => {
      copy = duplicateEntry(draft, entry.kind as EntryKind, entry.key)
    })
    if (copy) select(`${entry.kind}:${copy}`)
  }

  return (
    <>
      <Pane title="Entries" {...pane}>
        <Tree
          label="Resource pack entries"
          nodes={nodes}
          selected={selected && pickKey(selected)}
          onSelect={select}
          selectOnFocus
          {...expansion}
          rename={(id) => {
            const entry = parseEntry(id)
            if (!entry || entry.kind === 'sounds') return null
            const kind = entry.kind
            return {
              initial: entry.key,
              validate: (value) =>
                !ID_PATTERN.test(value)
                  ? 'Lowercase letters, digits and _'
                  : model[kind]?.[value]
                    ? 'That key is taken'
                    : null,
              commit: (value) => {
                // Menus, items and text that use it follow it, all one undo step.
                const target = {
                  type: 'resource_pack_entry',
                  pack,
                  entry: ENTRY_REFS[kind],
                  key: entry.key,
                } as const
                void ws().transact(`Rename ${pack}/${entry.key} to ${value}`, async () => {
                  edit((draft) => renameEntry(draft, kind, entry.key, value))
                  await ws().renameReferences(target, value)
                })
                view.mapSelection((it) => (pickKey(it) === id ? { kind, key: value } : it))
              },
            }
          }}
          onDelete={(id) => void remove(id)}
          onDuplicate={duplicate}
          menu={(id, actions) => {
            const entry = parseEntry(id)
            if (!entry) return []
            if (entry.kind === 'sounds') {
              return [
                {
                  label: 'Delete file',
                  danger: true,
                  disabled: !soundFiles.includes(soundFileOf(entry.key)),
                  run: () => void remove(id),
                },
              ]
            }
            return [
              { label: 'Rename', shortcut: 'F2', run: () => actions.rename(id) },
              { label: 'Duplicate', shortcut: 'Mod+D', run: () => duplicate(id) },
              'separator',
              { label: 'Delete', shortcut: 'Delete', danger: true, run: () => void remove(id) },
            ]
          }}
        />
      </Pane>
      {filesPane}
    </>
  )
}
