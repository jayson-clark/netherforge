/**
 * A tree like VS Code's explorer, for every tree in the editor (a resource's
 * files, a centity's nodes, a particle effect's emitters): rows that open and
 * close, indent guides, inline rename and create, drag to move, a context
 * menu, and the keyboard.
 *
 * It's react-aria's `Tree` (a `treegrid` of `row`s): focus, the arrow keys
 * (Left and Right close and open), Home/End, typeahead, Space, selection and
 * drag and drop (with the keyboard too, through each row's drag button) are
 * react-aria's. On top of that: Enter and double-click open (`onOpen`), F2
 * renames, Delete (or Cmd+Backspace) deletes, Ctrl/Cmd+D duplicates, the
 * context-menu key opens the menu.
 *
 * It owns only what's transient (the row being renamed, the drag); what's
 * selected, what's expanded and every change belong to the caller. With
 * [TreeProps.multiple], several rows can be selected the way react-aria
 * does it: Shift-click and Shift+arrows extend, Cmd/Ctrl-click toggles.
 */
import { useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import {
  Button,
  Collection,
  Tree as AriaTree,
  TreeItem,
  TreeItemContent,
  useDragAndDrop,
  type DropTarget,
  type Key,
} from 'react-aria-components'
import { DirtyDot, ErrorCount } from './Badge'
import { openMenu, type MenuItem } from './ContextMenu'
import { cx } from './cx'
import { Icon, type IconName } from './Icon'
import styles from './Tree.module.css'

export interface TreeNode {
  id: string
  label: string
  icon?: IconName
  iconClass?: string
  /** Drawn in the icon's place when set: a centity node's block or item. */
  picture?: ReactNode
  /** Undefined for a leaf; an array, maybe empty, for a row things can go inside. */
  children?: TreeNode[]
  /** After the label: a badge, a muted note. */
  detail?: ReactNode
  title?: string
  dirty?: boolean
  errors?: number
  /** A row the caller doesn't let be renamed, moved or deleted (the effect's own row). */
  fixed?: boolean
  /** The JSON path of what the row is (`emitters.ring`), as its `data-path`. */
  dataPath?: string
}

export interface InlineEdit {
  /** An error message for [value], or null when it can be committed. */
  validate: (value: string) => string | null
  commit: (value: string) => void
}

export interface CreateRequest extends InlineEdit {
  /** The row it goes inside, or null for the top. */
  parent: string | null
  icon: IconName
  initial: string
  label: string
  cancel: () => void
}

export interface TreeActions {
  rename: (id: string) => void
}

/** A tree whose rows can be selected together. */
export interface MultipleSelection {
  /** Every selected row, oldest first: the last is the primary. */
  selection: readonly string[]
  /** The rows now selected, oldest first, the one just clicked or reached last. */
  onChange: (ids: string[]) => void
}

export interface TreeProps {
  label: string
  nodes: TreeNode[]
  /** The selected row (the primary one with [multiple]). */
  selected: string | null
  /** A row picked alone (a click, the arrows, its context menu). */
  onSelect: (id: string) => void
  multiple?: MultipleSelection
  /** Enter and double-click; without it, double-click renames. */
  onOpen?: (id: string) => void
  /** Moving with the arrow keys selects too (a node tree), rather than only moving focus (files). */
  selectOnFocus?: boolean
  isExpanded: (node: TreeNode) => boolean
  onExpand: (id: string, open: boolean) => void
  /** Inline rename (F2, the menu, double-click without `onOpen`): the edit for that row. */
  rename?: (id: string) => (InlineEdit & { initial: string; select?: [number, number] }) | null
  onDelete?: (id: string) => void
  onDuplicate?: (id: string) => void
  /** Moves [id] inside [target] (null: the top). Drops are offered only where [canMove] says. */
  onMove?: (id: string, target: string | null) => void
  canMove?: (id: string, target: string | null) => boolean
  menu?: (id: string, actions: TreeActions) => MenuItem[]
  /** Buttons shown on the hovered or selected row. */
  rowActions?: (node: TreeNode, actions: TreeActions) => ReactNode
  create?: CreateRequest | null
  empty?: ReactNode
}

/** What `onSelectionChange` is given: a set of keys that knows the one last acted on. */
type Selected = Set<Key> & { currentKey?: Key | null }

const INDENT = 12
/** The inline create row's key: no node's id is empty. */
const CREATING = ''
const NODE_TYPE = 'application/x-netherforge-tree-node'

/** [nodes] with the create row placed first inside its parent (or at the top). */
function withCreateRow(nodes: TreeNode[], create: CreateRequest | null | undefined): TreeNode[] {
  if (!create) return nodes
  const row: TreeNode = { id: CREATING, label: create.label, icon: create.icon }
  if (create.parent === null) return [row, ...nodes]
  const place = (list: TreeNode[]): TreeNode[] =>
    list.map((node) =>
      node.id === create.parent
        ? { ...node, children: [row, ...(node.children ?? [])] }
        : node.children
          ? { ...node, children: place(node.children) }
          : node,
    )
  return place(nodes)
}

/** Every row's parent, and the rows that are open. */
function indexOf(nodes: TreeNode[], isExpanded: (node: TreeNode) => boolean) {
  const parents = new Map<string, string | null>()
  const nodesById = new Map<string, TreeNode>()
  const expanded = new Set<string>()
  const walk = (list: TreeNode[], parent: string | null) => {
    for (const node of list) {
      parents.set(node.id, parent)
      nodesById.set(node.id, node)
      if (node.children === undefined) continue
      if (isExpanded(node)) expanded.add(node.id)
      walk(node.children, node.id)
    }
  }
  walk(nodes, null)
  return { parents, nodesById, expanded }
}

export function Tree(props: TreeProps) {
  const {
    label,
    selected,
    onSelect,
    onOpen,
    selectOnFocus,
    isExpanded,
    onExpand,
    rename,
    onDelete,
    onDuplicate,
    onMove,
    canMove,
    menu,
    rowActions,
    create,
    empty,
    multiple,
  } = props
  const ref = useRef<HTMLDivElement>(null)
  const [renaming, setRenaming] = useState<string | null>(null)
  const dragging = useRef<string | null>(null)

  const nodes = withCreateRow(props.nodes, create)
  const { parents, nodesById, expanded } = indexOf(nodes, isExpanded)
  const actions: TreeActions = {
    rename: (id) => {
      if (rename?.(id)) setRenaming(id)
    },
  }
  // Back to the tree after an inline edit, once the rows show it: the focused row, else the
  // selected one, else the first.
  const focusTree = () =>
    setTimeout(() => {
      const root = ref.current
      const row =
        root?.querySelector<HTMLElement>('[role="row"][data-focused]') ??
        root?.querySelector<HTMLElement>('[role="row"][aria-selected="true"]') ??
        root?.querySelector<HTMLElement>('[role="row"]')
      ;(row ?? root)?.focus()
    })

  // Where dropping on [target] puts a row: inside a row that takes rows, else beside it; the top.
  const destination = (target: DropTarget): string | null | undefined => {
    if (target.type === 'root') return null
    if (target.dropPosition !== 'on') return undefined
    const key = String(target.key)
    return nodesById.get(key)?.children !== undefined ? key : (parents.get(key) ?? null)
  }
  const droppable = (target: DropTarget) => {
    const id = dragging.current
    const to = destination(target)
    return (
      id !== null &&
      !nodesById.get(id)?.fixed &&
      to !== undefined &&
      to !== id &&
      (canMove?.(id, to) ?? true)
    )
  }
  const { dragAndDropHooks } = useDragAndDrop<TreeNode>({
    getItems: (keys) =>
      [...keys].map((key) => ({
        [NODE_TYPE]: String(key),
        'text/plain': nodesById.get(String(key))?.label ?? String(key),
      })),
    getAllowedDropOperations: () => ['move'],
    onDragStart: (event) => {
      dragging.current = String([...event.keys][0])
    },
    onDragEnd: () => {
      dragging.current = null
    },
    getDropOperation: (target, types) =>
      types.has(NODE_TYPE) && droppable(target) ? 'move' : 'cancel',
    onItemDrop: (event) => {
      if (dragging.current !== null && droppable(event.target)) {
        onMove?.(dragging.current, destination(event.target) ?? null)
      }
    },
    onRootDrop: () => {
      if (dragging.current !== null && droppable({ type: 'root' })) onMove?.(dragging.current, null)
    },
  })

  // The rows are drawn from these (react-aria caches a row until its node or these change).
  const dependencies = [props, renaming]

  const rowOf = (target: EventTarget): string | null => {
    const row = (target as Element).closest?.('[data-node]')
    return row ? row.getAttribute('data-node') : null
  }

  // Enter opens, before react-aria would take it as selecting the row.
  const onKeyDownCapture = (event: KeyboardEvent) => {
    if (event.key !== 'Enter' || !onOpen || renaming) return
    if ((event.target as Element).getAttribute('role') !== 'row') return
    const id = rowOf(event.target)
    if (id === null || id === CREATING) return
    event.preventDefault()
    event.stopPropagation()
    onOpen(id)
  }

  const onKeyDown = (event: KeyboardEvent) => {
    // Keys on a row itself: not in a field or a button inside one.
    if (renaming || (event.target as Element).getAttribute('role') !== 'row') return
    const id = rowOf(event.target)
    const node = id === null ? undefined : nodesById.get(id)
    if (id === null || id === CREATING || !node) return
    const mod = event.metaKey || event.ctrlKey
    switch (event.key) {
      case 'F2':
        if (node.fixed) return
        event.preventDefault()
        actions.rename(id)
        return
      case 'Delete':
      case 'Backspace':
        if (node.fixed || !onDelete) return
        // Backspace alone is too easy to hit: Cmd+Backspace on a Mac, as in Finder.
        if (event.key === 'Backspace' && !mod) return
        event.preventDefault()
        onDelete(id)
        return
      case 'd':
        if (!mod || node.fixed || !onDuplicate) return
        event.preventDefault()
        onDuplicate(id)
        return
      case 'ContextMenu':
        if (!menu) return
        event.preventDefault()
        openMenu({ clientX: 0, clientY: 0, preventDefault: () => {} }, menu(id, actions))
        return
    }
  }

  const renderNode = (node: TreeNode): ReactNode => {
    if (node.id === CREATING && create) {
      return (
        <TreeItem id={CREATING} textValue={create.label} className={styles.row}>
          <TreeItemContent>
            {({ level }) => (
              <RowFrame level={level}>
                <span className={styles.twisty} />
                {onMove && <Button slot="drag" className={styles.dragHandle} isDisabled />}
                <Icon name={create.icon} />
                <InlineInput
                  label={create.label}
                  initial={create.initial}
                  validate={create.validate}
                  onCommit={(value) => {
                    create.commit(value)
                    focusTree()
                  }}
                  onCancel={() => {
                    create.cancel()
                    focusTree()
                  }}
                />
              </RowFrame>
            )}
          </TreeItemContent>
        </TreeItem>
      )
    }
    const edit = renaming === node.id ? rename?.(node.id) : null
    const parent = node.children !== undefined && node.children.length > 0
    return (
      <TreeItem
        id={node.id}
        textValue={node.label}
        aria-label={node.label}
        data-node={node.id}
        data-path={node.dataPath}
        className={styles.row}
        render={(rowProps) => <div {...rowProps} title={node.title} />}
        // Clicking the selected row again picks it again (a folder opens or closes); a
        // Shift or Cmd/Ctrl-click is react-aria's, adding to or taking from the selection.
        onPress={(event) => {
          const modified = event.shiftKey || event.metaKey || event.ctrlKey
          if (event.pointerType !== 'keyboard' && !modified && node.id === selected)
            onSelect(node.id)
        }}
        onDoubleClick={(event) => {
          if ((event.target as HTMLElement).closest('[data-twisty], [data-row-actions], input'))
            return
          if (onOpen) onOpen(node.id)
          else if (parent && !rename) onExpand(node.id, !expanded.has(node.id))
          else if (!node.fixed) actions.rename(node.id)
        }}
        onContextMenu={(event) => {
          if (!menu) return
          event.stopPropagation()
          // A row already among several selected keeps them: its menu acts on them all.
          if (!multiple?.selection.includes(node.id)) onSelect(node.id)
          openMenu(event, menu(node.id, actions))
        }}
      >
        <TreeItemContent>
          {({ level, isExpanded: open }: { level: number; isExpanded: boolean }) => (
            <RowFrame level={level}>
              {parent ? (
                <Button slot="chevron" className={styles.twisty} data-twisty>
                  <Icon name={open ? 'chevronDown' : 'chevronRight'} size={12} />
                </Button>
              ) : (
                <span className={styles.twisty} />
              )}
              {/* Keyboard dragging (Enter on it, arrows, Enter): shown only while focused. Every
                  row has one (react-aria warns about a draggable row without it, and a fixed
                  row is still a draggable item to it): disabled where the row can't move. */}
              {onMove && (
                <Button
                  slot="drag"
                  className={styles.dragHandle}
                  isDisabled={!!node.fixed || !!edit}
                />
              )}
              {node.picture ?? (node.icon && <Icon name={node.icon} className={node.iconClass} />)}
              {edit ? (
                <InlineInput
                  label={`Rename ${node.label}`}
                  initial={edit.initial}
                  select={edit.select}
                  validate={edit.validate}
                  onCommit={(value) => {
                    setRenaming(null)
                    if (value !== edit.initial) edit.commit(value)
                    focusTree()
                  }}
                  onCancel={() => {
                    setRenaming(null)
                    focusTree()
                  }}
                />
              ) : (
                <span className={styles.label}>{node.label}</span>
              )}
              {!edit && node.detail}
              {!edit && node.dirty && <DirtyDot />}
              {!edit && !!node.errors && <ErrorCount value={node.errors} />}
              {!edit && rowActions && (
                <span
                  className={styles.actions}
                  data-row-actions
                  // The row's buttons act; they don't select the row.
                  onPointerDown={(event) => event.stopPropagation()}
                  onClick={(event) => event.stopPropagation()}
                >
                  {rowActions(node, actions)}
                </span>
              )}
            </RowFrame>
          )}
        </TreeItemContent>
        {node.children && (
          <Collection items={node.children} dependencies={dependencies}>
            {renderNode}
          </Collection>
        )}
      </TreeItem>
    )
  }

  return (
    // The keys react-aria leaves alone (F2, Delete…) and the menu for the tree's own background.
    <div
      className={styles.frame}
      onKeyDownCapture={onKeyDownCapture}
      onKeyDown={onKeyDown}
      onContextMenu={(event) => {
        if (menu && rowOf(event.target) === null) openMenu(event, menu('', actions))
      }}
    >
      <AriaTree
        ref={ref}
        aria-label={label}
        className={styles.tree}
        items={nodes}
        selectionMode={multiple ? 'multiple' : 'single'}
        selectionBehavior={selectOnFocus || multiple ? 'replace' : 'toggle'}
        disallowEmptySelection
        selectedKeys={multiple ? multiple.selection : selected === null ? [] : [selected]}
        onSelectionChange={(keys) => {
          const ids = (keys === 'all' ? [...nodesById.keys()] : [...keys].map(String)).filter(
            (id) => id !== CREATING,
          )
          if (multiple) {
            // react-aria's Selection says which row the change was made at.
            const current = keys === 'all' ? null : ((keys as Selected).currentKey ?? null)
            multiple.onChange(selectionOrder(multiple.selection, ids, current))
          } else if (ids[0] !== undefined) onSelect(ids[0])
        }}
        disabledKeys={create ? [CREATING] : undefined}
        disabledBehavior="selection"
        expandedKeys={expanded}
        onExpandedChange={(keys) => {
          for (const key of keys) if (!expanded.has(String(key))) onExpand(String(key), true)
          for (const key of expanded) if (!keys.has(key)) onExpand(key, false)
        }}
        // Tab reaches a row's buttons; arrows stay on the rows.
        keyboardNavigationBehavior="tab"
        dragAndDropHooks={onMove ? dragAndDropHooks : undefined}
        renderEmptyState={() => empty}
        dependencies={dependencies}
      >
        {renderNode}
      </AriaTree>
    </div>
  )
}

/**
 * [ids] in selection order: those already selected where they were, the new
 * ones after them, and [current] (the row just clicked or reached) last.
 */
export function selectionOrder(
  before: readonly string[],
  ids: string[],
  current: string | number | null,
): string[] {
  const now = new Set(ids)
  const kept = before.filter((id) => now.has(id))
  const added = ids.filter((id) => !before.includes(id))
  const ordered = [...kept, ...added]
  const last = current === null ? null : String(current)
  return last !== null && now.has(last) ? [...ordered.filter((id) => id !== last), last] : ordered
}

function RowFrame({ level, children }: { level: number; children: ReactNode }) {
  const depth = level - 1
  return (
    <div className={styles.content} style={{ paddingLeft: 4 + depth * INDENT }}>
      {depth > 0 && (
        <span
          className={styles.guides}
          aria-hidden="true"
          style={{ width: depth * INDENT, left: 4 + INDENT / 2 }}
        />
      )}
      {children}
    </div>
  )
}

/**
 * The text field a row turns into while it's renamed or created: Enter or
 * leaving it commits a valid name, Escape cancels, and a name that can't be
 * used says why under it.
 */
export function InlineInput({
  label,
  initial,
  select,
  validate,
  onCommit,
  onCancel,
  className,
}: {
  className?: string
  label: string
  initial: string
  /** What's selected at first (a file's name without its extension); all of it by default. */
  select?: [number, number]
  validate: (value: string) => string | null
  onCommit: (value: string) => void
  onCancel: () => void
}) {
  const [value, setValue] = useState(initial)
  const done = useRef(false)
  const error = value === initial ? null : validate(value)
  const finish = (commit: boolean) => {
    if (done.current) return
    done.current = true
    if (commit && value !== initial && !validate(value)) onCommit(value)
    else onCancel()
  }
  return (
    <span className={cx(styles.inlineInput, className)}>
      <input
        aria-label={label}
        aria-invalid={!!error}
        value={value}
        spellCheck={false}
        autoFocus
        ref={(el) => {
          if (!el || el.dataset.ready) return
          el.dataset.ready = '1'
          const [start, end] = select ?? [0, initial.length]
          // After autofocus has run.
          requestAnimationFrame(() => el.setSelectionRange(start, end))
        }}
        onChange={(event) => setValue(event.target.value)}
        onClick={(event) => event.stopPropagation()}
        onDoubleClick={(event) => event.stopPropagation()}
        // A press in the field is the field's, not the row's; and its focus isn't the
        // tree's (which would move focus on to a row).
        onPointerDown={(event) => event.stopPropagation()}
        onFocus={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          event.stopPropagation()
          if (event.key === 'Enter') {
            event.preventDefault()
            if (!error) finish(true)
          } else if (event.key === 'Escape') {
            event.preventDefault()
            finish(false)
          }
        }}
        onBlur={() => finish(true)}
      />
      {error && (
        <span className={styles.inlineError} role="alert">
          {error}
        </span>
      )}
    </span>
  )
}
