/**
 * A resource's files as VS Code shows a folder: nested, folders first,
 * collapsible, with new file and folder in place, inline rename, drag to
 * move, duplicate, delete, and copying a path or a script's require name.
 * Lua and JSON open in tabs; a pack's pictures and sounds open the pack on
 * that entry.
 */
import { useEffect, useState } from 'react'
import { useExpansionSetter, useTreeExpansion, usePane } from '@/state/useExpanded'
import { useApp, useWorkspace } from '@/state/providers'
import { KINDS, newSiblingFile, type ScriptedKindId } from '@/core/format'
import { basename, dirname } from '@/core/paths'
import { filesIn, splitPackagePath } from '@/core/store/packages'
import { kindContribution } from '@/editors/registry'
import { IconButton } from '@/ui/Button'
import type { MenuItem } from '@/ui/ContextMenu'
import { ask } from '@/ui/dialogs'
import type { IconName } from '@/ui/Icon'
import { Pane } from '@/ui/Pane'
import { Tree, type CreateRequest, type TreeNode } from '@/ui/Tree'
import { Empty } from '@/ui/text'
import type { OutlineSubject } from './subject'
import {
  buildFileTree,
  copyPathFor,
  moveTarget,
  nameProblem,
  requireNameOf,
  type FileNode,
} from './fileTree'

const OPENS_AS_TEXT = /\.(lua|json|md|txt|mcmeta)$/

function iconFor(path: string, folder: boolean, open: boolean): IconName {
  if (folder) return open ? 'folderOpen' : 'folder'
  if (path.endsWith('.lua')) return 'code'
  if (path.endsWith('.png')) return 'image'
  if (path.endsWith('.ogg')) return 'sound'
  return 'file'
}

/** What a new file starts as: Lua in a kind that runs it is format's sibling template (a table to require). */
function newFileText(kind: OutlineSubject['kind'], name: string): string {
  if (!name.endsWith('.lua') || !KINDS[kind].scripted) return ''
  return newSiblingFile(kind as ScriptedKindId)
}

export function FilesPane({
  root,
  kind,
  main,
}: {
  /** The resource's folder. */
  root: string
  kind: OutlineSubject['kind']
  /** Its main file, which opens the resource and can't be renamed or moved here. */
  main: string | null
}) {
  const { workspace } = useApp()
  // A package's resource (opened read-only from Dependencies) lists the package's files, by package path.
  const files = useWorkspace((s) => filesIn(s, splitPackagePath(root)[0]))
  const docs = useWorkspace((s) => s.docs)
  const problems = useWorkspace((s) => s.problems)
  const activePath = useWorkspace((s) => s.tabs.find((it) => it.id === s.activeTab)?.path ?? null)
  const pane = usePane('files')
  const treeKey = `files:${root}`
  const expansion = useTreeExpansion(treeKey, () => false)
  const setExpanded = useExpansionSetter(treeKey)
  // The row the user picked, while the same tab is active; switching tabs shows the new tab's file.
  const [pick, setPick] = useState<{ path: string | null; active: string | null }>({
    path: null,
    active: null,
  })
  const [extraFolders, setExtraFolders] = useState<string[]>([])
  const [create, setCreate] = useState<{ parent: string; kind: 'file' | 'folder' } | null>(null)
  const ws = () => workspace.getState()

  const inside = (path: string | null) => !!path && path.startsWith(`${root}/`)
  const picked = pick.active === activePath ? pick.path : null
  const selected = picked ?? (inside(activePath) ? activePath : null)
  const setPicked = (path: string | null) => setPick({ path, active: activePath })
  const isFolder = (path: string) =>
    path === root || extraFolders.includes(path) || files.some((it) => it.startsWith(`${path}/`))

  // Show the active file: open the folders it's in.
  useEffect(() => {
    if (!activePath?.startsWith(`${root}/`)) return
    for (let folder = dirname(activePath); folder.length > root.length; folder = dirname(folder)) {
      setExpanded(folder, true)
    }
  }, [activePath, root, setExpanded])

  const toNode = (node: FileNode): TreeNode => {
    const folder = node.children !== undefined
    const open = folder && expansion.isExpanded(node)
    const errors = folder
      ? 0
      : problems.filter((it) => it.severity === 'error' && it.file === node.id).length
    return {
      id: node.id,
      label: node.label,
      icon: iconFor(node.id, folder, open),
      children: node.children?.map(toNode),
      dirty: docs[node.id]?.dirty,
      errors,
      fixed: node.id === main,
      title: node.id,
    }
  }
  const nodes = buildFileTree(files, root, extraFolders).map(toNode)

  const openPath = (path: string) => {
    if (OPENS_AS_TEXT.test(path)) return void ws().openFile(path)
    // Anything else is the kind's to show in its editor (a pack's picture or sound), if it does.
    const selection = main
      ? kindContribution(kind).viewState?.opensInside?.(path.slice(root.length + 1))
      : null
    if (main && selection) {
      ws().select(main, [selection])
      return void ws().openFile(main)
    }
    ws().notify('info', `${basename(path)} isn't something the editor opens.`)
  }

  /** The folder new things go in: the picked folder, or the picked file's. */
  const targetFolder = (from: string | null) =>
    !from || !inside(from) ? root : isFolder(from) ? from : dirname(from)

  const startCreate = (kindOf: 'file' | 'folder', from: string | null) => {
    const parent = targetFolder(from)
    if (parent !== root) setExpanded(parent, true)
    if (!pane.open) pane.onToggle(true)
    setCreate({ parent, kind: kindOf })
  }

  const createRequest: CreateRequest | null = create && {
    parent: create.parent === root ? null : create.parent,
    icon: create.kind === 'file' ? 'file' : 'folder',
    label: create.kind === 'file' ? 'New file name' : 'New folder name',
    initial: '',
    validate: (value) => nameProblem(value, create.parent, root, files, create.kind),
    commit: (value) => {
      setCreate(null)
      const path = `${create.parent}/${value}`
      if (create.kind === 'folder') {
        setExtraFolders((it) => [...it, path])
        setExpanded(path, true)
        setPicked(path)
        return
      }
      void ws()
        .createFile(path, newFileText(kind, value))
        .then(() => {
          setPicked(path)
          if (OPENS_AS_TEXT.test(path)) void ws().openFile(path)
        })
    },
    cancel: () => setCreate(null),
  }

  const renameTo = async (path: string, next: string) => {
    if (next === path) return
    if (extraFolders.includes(path) && !files.some((it) => it.startsWith(`${path}/`))) {
      setExtraFolders((it) => it.map((folder) => (folder === path ? next : folder)))
    } else {
      await ws().renamePath(path, next)
    }
    setPicked(next)
  }

  const duplicate = async (path: string) => {
    const copy = copyPathFor(path, files)
    if (await ws().copyPath(path, copy)) setPicked(copy)
  }

  const remove = async (path: string) => {
    const folder = isFolder(path)
    const ok = await ask.confirm({
      title: `Delete ${basename(path)}`,
      message: folder
        ? `Delete the folder ${path} and everything in it? Git can bring it back if it was committed.`
        : `Delete ${path}? Git can bring it back if it was committed.`,
      confirmLabel: 'Delete',
      danger: true,
    })
    if (ok !== true) return
    setExtraFolders((it) => it.filter((f) => f !== path && !f.startsWith(`${path}/`)))
    if (files.some((it) => it === path || it.startsWith(`${path}/`))) await ws().deletePath(path)
    setPicked(null)
  }

  const copyText = (text: string) => void navigator.clipboard?.writeText(text)

  const menu = (id: string, actions: { rename: (id: string) => void }): MenuItem[] => {
    const creating: MenuItem[] = [
      { label: 'New File…', run: () => startCreate('file', id || null) },
      { label: 'New Folder…', run: () => startCreate('folder', id || null) },
    ]
    if (!id) return creating
    const folder = isFolder(id)
    const require = requireNameOf(id)
    return [
      ...(folder
        ? []
        : [{ label: 'Open', shortcut: 'Enter', run: () => openPath(id) }, 'separator' as const]),
      ...creating,
      'separator',
      { label: 'Rename', shortcut: 'F2', disabled: id === main, run: () => actions.rename(id) },
      {
        label: 'Duplicate',
        shortcut: 'Mod+D',
        disabled: id === main,
        run: () => void duplicate(id),
      },
      { label: 'Copy Path', run: () => copyText(id) },
      ...(require
        ? [{ label: 'Copy require(…)', run: () => copyText(`require("${require}")`) }]
        : []),
      'separator',
      {
        label: 'Delete',
        shortcut: 'Mod+Backspace',
        danger: true,
        disabled: id === main,
        run: () => void remove(id),
      },
    ]
  }

  return (
    <Pane
      title="Files"
      {...pane}
      actions={
        <>
          <IconButton
            icon="newFile"
            label="New file"
            onClick={() => startCreate('file', selected)}
          />
          <IconButton
            icon="newFolder"
            label="New folder"
            onClick={() => startCreate('folder', selected)}
          />
          <IconButton
            icon="collapseAll"
            label="Collapse folders"
            onClick={() => {
              const walk = (list: FileNode[]) => {
                for (const node of list) {
                  if (!node.children) continue
                  setExpanded(node.id, false)
                  walk(node.children)
                }
              }
              walk(buildFileTree(files, root, extraFolders))
            }}
          />
        </>
      }
    >
      <Tree
        label={`Files in ${root}`}
        nodes={nodes}
        selected={selected}
        isExpanded={expansion.isExpanded}
        onExpand={expansion.onExpand}
        onSelect={(id) => {
          setPicked(id)
          if (isFolder(id)) expansion.onExpand(id, !expansion.isExpanded({ id }))
          else openPath(id)
        }}
        onOpen={(id) => (isFolder(id) ? expansion.onExpand(id, true) : openPath(id))}
        rename={(id) => {
          if (id === main) return null
          const parent = dirname(id)
          const name = basename(id)
          const dot = name.lastIndexOf('.')
          return {
            initial: name,
            select: [0, dot > 0 && !isFolder(id) ? dot : name.length],
            validate: (value) =>
              nameProblem(value, parent, root, files, isFolder(id) ? 'folder' : 'file', id),
            commit: (value) => void renameTo(id, `${parent}/${value}`),
          }
        }}
        onDelete={(id) => id !== main && void remove(id)}
        onDuplicate={(id) => id !== main && void duplicate(id)}
        canMove={(id, target) => id !== main && moveTarget(id, target ?? root) !== null}
        onMove={(id, target) => {
          const to = moveTarget(id, target ?? root)
          if (to) void renameTo(id, to)
        }}
        menu={menu}
        create={createRequest}
        empty={<Empty>No files.</Empty>}
      />
    </Pane>
  )
}

/**
 * A single-file resource's files (a terrain's `.json` and the `.lua` beside
 * it): both open in tabs. They're the resource itself, so renaming, moving
 * and deleting them is the explorer's, which keeps the two together.
 */
export function CompanionFilesPane({ file, companion }: { file: string; companion: string }) {
  const { workspace } = useApp()
  const files = useWorkspace((s) => filesIn(s, splitPackagePath(file)[0]))
  const docs = useWorkspace((s) => s.docs)
  const problems = useWorkspace((s) => s.problems)
  const activePath = useWorkspace((s) => s.tabs.find((it) => it.id === s.activeTab)?.path ?? null)
  const pane = usePane('files')
  const shown = [file, companion].filter((it) => files.includes(it))
  const nodes: TreeNode[] = shown.map((path) => ({
    id: path,
    label: basename(path),
    icon: iconFor(path, false, false),
    dirty: docs[path]?.dirty,
    errors: problems.filter((it) => it.severity === 'error' && it.file === path).length,
    fixed: true,
    title: path,
  }))
  const open = (path: string) => void workspace.getState().openFile(path)
  return (
    <Pane title="Files" {...pane}>
      <Tree
        label={`Files of ${basename(file)}`}
        nodes={nodes}
        selected={activePath && shown.includes(activePath) ? activePath : null}
        isExpanded={() => false}
        onExpand={() => {}}
        onSelect={open}
        onOpen={open}
        menu={(id) =>
          id
            ? [
                { label: 'Open', shortcut: 'Enter', run: () => open(id) },
                { label: 'Copy Path', run: () => void navigator.clipboard?.writeText(id) },
              ]
            : []
        }
        empty={<Empty>No files.</Empty>}
      />
    </Pane>
  )
}
