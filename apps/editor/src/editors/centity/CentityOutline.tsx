/**
 * A centity in the outline dock: its animation clips and files first (there
 * are few of them, and they're easy to lose under a long node list), then
 * its node hierarchy.
 * Picking either brings up the centity's own tab (the outline also shows
 * while one of its scripts is the active tab).
 */
import { usePane, useTreeExpansion } from '@/state/useExpanded'
import { useApp } from '@/state/providers'
import type { CentityFile, DisplayDef } from '@/core/format'
import type { OutlineProps } from '@/editors/contributions'
import { useModelDoc } from '@/editors/shared/modelDoc'
import { KeyedList, nameProblem } from '@/editors/shared/KeyedList'
import { parseBlockState } from '@netherforge/models/model'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { Badge } from '@/ui/Badge'
import { IconButton } from '@/ui/Button'
import type { MenuItem } from '@/ui/ContextMenu'
import { Pane } from '@/ui/Pane'
import { Empty } from '@/ui/text'
import { Tree, type TreeNode } from '@/ui/Tree'
import {
  addAnimation,
  addNode,
  childrenOf,
  deleteAnimation,
  deleteNode,
  descendantsOf,
  duplicateNode,
  renameAnimation,
  renameNode,
  reparent,
} from './ops'
import { usePrimary, useSelection, useView, useViewState } from '@/editors/views'

/** A block or item display's row shows what it displays, as the inventory draws it. */
function displayItem(display: DisplayDef | undefined) {
  // A model display is drawn as its look, which is what a stack of it would show.
  if (display?.type === 'model')
    return <ItemIcon item={{ kind: '', itemModel: display.model }} size={16} />
  const kind =
    display?.type === 'block'
      ? parseBlockState(display.block).id
      : display?.type === 'item'
        ? display.item
        : null
  return kind ? <ItemIcon item={{ kind }} size={16} /> : undefined
}

export function CentityOutline({ path, files }: OutlineProps) {
  const { model } = useModelDoc<CentityFile>(path)
  if (!model) return files
  return (
    <>
      <AnimationsPane path={path} model={model} />
      {files}
      <NodesPane path={path} model={model} />
    </>
  )
}

function NodesPane({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const pane = usePane('centity-nodes')
  const expansion = useTreeExpansion(`nodes:${path}`, () => true)
  const selected = usePrimary('centity', path)
  const selection = useSelection('centity', path)
  const view = useView('centity', path)
  const ws = () => workspace.getState()
  const nodes = model.nodes ?? {}

  const show = (name: string | null) => {
    if (name === null) view.select()
    else view.select(name)
    void ws().openFile(path)
  }
  const add = (parent: string | null) => {
    let created = ''
    ws().edit<CentityFile>(path, (draft) => {
      created = addNode(draft, parent)
    })
    if (parent) expansion.onExpand(parent, true)
    show(created)
  }
  const duplicate = (name: string) => {
    let copy: string | null = null
    ws().edit<CentityFile>(path, (draft) => {
      copy = duplicateNode(draft, name)
    })
    if (copy) show(copy)
  }
  // Deleting a selected node deletes every selected one (and their children).
  const remove = (name: string) => {
    const names = selection.includes(name) ? selection : [name]
    const doomed = new Set(names.flatMap((it) => [it, ...descendantsOf(model, it)]))
    ws().edit<CentityFile>(path, (draft) => {
      for (const it of names) deleteNode(draft, it)
    })
    view.mapSelection((it) => (doomed.has(it) ? null : it))
  }

  // Nodes whose parent is missing (or in a cycle) still show at the top, so they can be fixed.
  const build = (name: string, trail: Set<string>): TreeNode => {
    const node = nodes[name]
    const kind = node?.display?.type ?? 'empty'
    const next = new Set(trail).add(name)
    return {
      id: name,
      label: name,
      icon: kind === 'empty' ? 'key' : kind === 'text' ? 'code' : 'cube',
      picture: displayItem(node?.display),
      title: kind === 'empty' ? 'Empty node' : `${kind} display`,
      children: trail.has(name)
        ? []
        : childrenOf(model, name)
            .filter((child) => !trail.has(child))
            .map((child) => build(child, next)),
    }
  }
  const names = Object.keys(nodes)
  const roots = names.filter((name) => {
    const parent = nodes[name]?.parent
    return !parent || !(parent in nodes)
  })
  const reachable = new Set<string>()
  const walk = (name: string) => {
    if (reachable.has(name)) return
    reachable.add(name)
    childrenOf(model, name).forEach(walk)
  }
  roots.forEach(walk)
  const tops = [...roots, ...names.filter((name) => !reachable.has(name))].sort()

  const menu = (id: string, actions: { rename: (id: string) => void }): MenuItem[] =>
    id === ''
      ? [{ label: 'Add node', run: () => add(null) }]
      : [
          { label: 'Add child', run: () => add(id) },
          'separator',
          { label: 'Rename', shortcut: 'F2', run: () => actions.rename(id) },
          { label: 'Duplicate', shortcut: 'Mod+D', run: () => duplicate(id) },
          'separator',
          { label: 'Delete with children', danger: true, run: () => remove(id) },
        ]

  return (
    <Pane
      title="Nodes"
      {...pane}
      actions={<IconButton icon="plus" label="Add root node" onClick={() => add(null)} />}
    >
      <Tree
        label="Nodes"
        nodes={tops.map((name) => build(name, new Set()))}
        selected={selected}
        selectOnFocus
        onSelect={show}
        multiple={{
          selection,
          onChange: (names) => {
            view.select(...names)
            void ws().openFile(path)
          },
        }}
        {...expansion}
        rename={(id) => ({
          initial: id,
          validate: (value) => nameProblem(value, value !== id && value in nodes, 'node'),
          commit: (next) => {
            ws().edit<CentityFile>(path, (draft) => renameNode(draft, id, next))
            view.mapSelection((it) => (it === id ? next : it))
          },
        })}
        onDelete={remove}
        onDuplicate={duplicate}
        canMove={(id, target) =>
          target === null || (target !== id && !descendantsOf(model, id).has(target))
        }
        onMove={(id, target) => {
          if ((nodes[id]?.parent ?? null) === target) return
          ws().edit<CentityFile>(path, (draft) => {
            reparent(draft, id, target)
          })
          if (target) expansion.onExpand(target, true)
        }}
        menu={menu}
        rowActions={(node) => (
          <IconButton icon="plus" label={`Add child to ${node.id}`} onClick={() => add(node.id)} />
        )}
        empty={<Empty>No nodes yet.</Empty>}
      />
    </Pane>
  )
}

function AnimationsPane({ path, model }: { path: string; model: CentityFile }) {
  const { workspace } = useApp()
  const pane = usePane('centity-animations')
  const view = useViewState('centity', path)
  const changeView = useView('centity', path)
  const ws = () => workspace.getState()
  const clips = Object.keys(model.animations ?? {})
  const current = view.clip && clips.includes(view.clip) ? view.clip : null

  const show = (clip: string | null) => {
    changeView.update({ clip, time: 0, playing: false, selectedKey: null })
    void ws().openFile(path)
  }
  const add = () => {
    let name = ''
    ws().edit<CentityFile>(path, (draft) => {
      name = addAnimation(draft)
    })
    show(name)
  }

  return (
    <KeyedList
      title="Animations"
      one="animation"
      pane={pane}
      list="animations"
      entries={clips.map((clip) => ({
        key: clip,
        icon: 'play',
        detail: model.animations?.[clip]?.autoplay ? (
          <Badge title="Plays when spawned">auto</Badge>
        ) : undefined,
      }))}
      selected={current}
      onSelect={show}
      onAdd={add}
      onRename={(from, to) => {
        ws().edit<CentityFile>(path, (draft) => renameAnimation(draft, from, to))
        if (current === from) changeView.update({ clip: to })
      }}
      onDelete={(clip) => {
        ws().edit<CentityFile>(path, (draft) => deleteAnimation(draft, clip))
        if (current === clip) show(null)
      }}
      empty={<Empty>No animations. The preview shows the base pose.</Empty>}
    />
  )
}
