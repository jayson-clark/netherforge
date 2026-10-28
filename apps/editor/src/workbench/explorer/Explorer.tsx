/**
 * The project explorer, in the bottom dock: the kinds of resource on the
 * left in their groups' sections, the resources of the picked one on the
 * right as tiles (or a list),
 * each a whole resource rather than its files. Click selects, double-click
 * or Enter opens, F2 renames in place, Ctrl/Cmd+D duplicates, Delete (or
 * Cmd+Backspace) deletes, and the context menu has all of them. Typing in
 * the search box looks across every kind. Below the kinds, the packages the
 * project depends on, each shown read-only (`Dependencies.tsx`).
 *
 * A kind's group folders (`items/(weapons)/`) show as folder tiles before its
 * resources: opening one shows what's in it, the breadcrumb (or Backspace)
 * goes back up, and a resource dropped on a group (or picked "Move to
 * group…") moves there, keeping its id.
 */
import { memo, useLayoutEffect, useMemo, useRef, useState } from 'react'
import type { KindId } from '@/core/format'
import { folderOf, groupName, ID_PATTERN } from '@/core/paths'
import { Header, ListBox, ListBoxItem, ListBoxSection } from 'react-aria-components'
import { useApp, useLayout, useWorkspace } from '@/state/providers'
import { askMoveModule } from '@/workbench/workspaceActions'
import { LIMITS } from '@/core/store/layout'
import { describeOrigin } from '@/core/store/packages'
import { Badge, DirtyDot, ErrorCount } from '@/ui/Badge'
import { Button, IconButton } from '@/ui/Button'
import { openMenu, type MenuItem } from '@/ui/ContextMenu'
import { cx } from '@/ui/cx'
import { Bar, Spacer } from '@/ui/layout'
import { Icon } from '@/ui/Icon'
import { RevealGate, useReveal } from '@/ui/Reveal'
import { InlineInput } from '@/ui/Tree'
import { fuzzyMatch } from '@/workbench/quickOpen/fuzzy'
import { groupPath, idProblem, resourceActions } from '@/workbench/resourceActions'
import { dependencyMenu, PackageView, templateMenu } from './Dependencies'
import styles from './Explorer.module.css'
import {
  EXPLORER_FOLDERS,
  EXPLORER_SECTIONS,
  folderByKey,
  groupPreview,
  type ExplorerFolder,
} from './folders'
import { FolderIcon, GroupThumbnail, Thumbnail } from './Thumbnail'

/**
 * A tile: a resource ([id] in its [group] folders), or a group folder ([group], `(weapons)/(swords)`, whose own
 * name is [id], `swords`).
 */
interface Entry {
  type: 'resource' | 'group'
  folder: ExplorerFolder
  id: string
  group: string
  key: string
  /** A group's resources drawn on its tile: up to four, those right in it first. */
  preview?: string[]
}

const keyOf = (folder: ExplorerFolder, id: string) => `${folder.key}:${id}`
const groupKeyOf = (folder: ExplorerFolder, group: string) => `${folder.key}:group:${group}`

/** What a dragged resource tile carries, so a group tile can take it. */
const DRAG_TYPE = 'application/x-netherforge-resource'

/** The group folders right inside [parent] (empty: the kind's own folder), among [all]. */
const childGroups = (all: string[], parent: string) =>
  all.filter((it) =>
    parent
      ? it.startsWith(`${parent}/`) && !it.slice(parent.length + 1).includes('/')
      : !it.includes('/'),
  )

/** The group folder that holds [group] (`(weapons)` for `(weapons)/(swords)`), empty at the top. */
const parentOf = (group: string) =>
  group.includes('/') ? group.slice(0, group.lastIndexOf('/')) : ''

/** A group folder's own name: `swords` for `(weapons)/(swords)`. */
const ownName = (group: string) => groupName(group.slice(group.lastIndexOf('/') + 1))

/** A dependency's id in the kind list, beside the kinds' (`package:library`). */
const PACKAGE_TAB = 'package:'

export function Explorer() {
  const app = useApp()
  const { backend, workspace, layout } = app
  const inWorkspace = useWorkspace((s) => s.project?.workspace != null)
  const files = useWorkspace((s) => s.files)
  const problems = useWorkspace((s) => s.problems)
  const docs = useWorkspace((s) => s.docs)
  const folderKey = useLayout((s) => s.explorerFolder)
  const tileSize = useLayout((s) => s.tileSize)
  const view = useLayout((s) => s.explorerView)
  const [query, setQuery] = useState('')
  const packages = useWorkspace((s) => s.outline?.packages)
  /** The dependency shown instead of a kind's resources, by namespace. */
  const [pkg, setPkg] = useState<string | null>(null)
  const shownPackage = pkg !== null ? packages?.[pkg] : undefined
  const [selected, setSelected] = useState<string | null>(null)
  const [renaming, setRenaming] = useState<string | null>(null)
  /** The group folder open in each kind (`(weapons)`), the kind's own folder when it has none. */
  const [openGroups, setOpenGroups] = useState<Partial<Record<KindId, string>>>({})
  const items = useRef<HTMLDivElement>(null)
  const folder = folderByKey(folderKey)
  const actions = resourceActions(workspace)
  const ws = () => workspace.getState()
  const allGroups = folder.groups(files)
  // A group the files no longer have (its last resource moved out) shows the kind's own folder.
  const wanted = openGroups[folder.key] ?? ''
  const group = wanted && allGroups.includes(wanted) ? wanted : ''
  const openGroup = (next: string) => {
    setOpenGroups((it) => ({ ...it, [folder.key]: next }))
    setSelected(null)
  }

  const entries: Entry[] = useMemo(() => {
    if (!query.trim()) {
      const groups = childGroups(allGroups, group).map((it): Entry => ({
        type: 'group',
        folder,
        id: ownName(it),
        group: it,
        key: groupKeyOf(folder, it),
        preview: groupPreview(folder, files, it),
      }))
      const resources = folder
        .ids(files)
        .filter((id) => folder.groupOf(files, id) === group)
        .map((id): Entry => ({ type: 'resource', folder, id, group, key: keyOf(folder, id) }))
      return [...groups, ...resources]
    }
    return EXPLORER_FOLDERS.flatMap((it) =>
      it.ids(files).flatMap((id) => {
        const match = fuzzyMatch(query, id)
        return match
          ? [
              {
                type: 'resource' as const,
                folder: it,
                id,
                group: it.groupOf(files, id),
                key: keyOf(it, id),
                score: match.score,
              },
            ]
          : []
      }),
    ).sort((a, b) => b.score - a.score)
  }, [query, folder, files, allGroups, group])

  // Counted once per change by every folder a path is in, not once per tile: a kind can hold thousands.
  const errors = useMemo(
    () => countByFolder(problems.filter((it) => it.severity === 'error').map((it) => it.file)),
    [problems],
  )
  const dirty = useMemo(
    () =>
      countByFolder(
        Object.values(docs)
          .filter((doc) => doc.dirty)
          .map((doc) => doc.path),
      ),
    [docs],
  )
  const locationOf = (entry: Entry) =>
    entry.type === 'group'
      ? folderOf(entry.folder.key, entry.group)
      : entry.folder.location(entry.id, entry.group)
  const errorsIn = (entry: Entry) => errors.get(locationOf(entry)) ?? 0
  const dirtyIn = (entry: Entry) => dirty.has(locationOf(entry))

  const open = (entry: Entry) => {
    if (entry.type === 'group') return openGroup(entry.group)
    void ws().openFile(entry.folder.openPath(entry.id, entry.group))
  }
  const select = (key: string | null) => setSelected(key)
  const focusItems = () => items.current?.focus()

  const duplicate = async (entry: Entry) => {
    const copy = await actions.duplicate(entry.folder, entry.id)
    if (copy) {
      setSelected(keyOf(entry.folder, copy))
      setRenaming(keyOf(entry.folder, copy))
    }
  }
  const remove = async (entry: Entry) => {
    if (await actions.remove(entry.folder, entry.id)) setSelected(null)
    focusItems()
  }
  const create = async (target: ExplorerFolder) => {
    const id = await actions.create(target, group)
    if (id) setSelected(keyOf(target, id))
  }
  const createGroup = async () => {
    const made = await actions.createGroup(folder, group)
    if (made) setSelected(groupKeyOf(folder, made))
  }
  const removeGroup = async (entry: Entry) => {
    if (await actions.removeGroup(entry.folder, entry.group)) setSelected(null)
    focusItems()
  }
  /** Moves the resource [id] of the shown kind into [to] (group folders; empty for the kind's own folder). */
  const moveTo = async (id: string, to: string) => {
    await actions.move(folder, id, to)
    focusItems()
  }

  const menuFor = (entry: Entry): MenuItem[] =>
    entry.type === 'group'
      ? [
          { label: 'Open', shortcut: 'Enter', run: () => open(entry) },
          { label: 'Rename group', shortcut: 'F2', run: () => setRenaming(entry.key) },
          'separator',
          {
            label: 'Delete group',
            shortcut: 'Mod+Backspace',
            danger: true,
            run: () => void removeGroup(entry),
          },
        ]
      : [
          { label: 'Open', shortcut: 'Enter', run: () => open(entry) },
          { label: 'Rename', shortcut: 'F2', run: () => setRenaming(entry.key) },
          { label: 'Duplicate', shortcut: 'Mod+D', run: () => void duplicate(entry) },
          ...(entry.folder.groupable
            ? [
                {
                  label: 'Move to group…',
                  run: () => void actions.moveAsking(entry.folder, entry.id),
                },
              ]
            : []),
          ...(entry.folder.contribution.movesToPackage && inWorkspace
            ? [
                {
                  label: 'Move to Package…',
                  run: () =>
                    void askMoveModule(
                      app,
                      entry.id,
                      entry.folder.location(entry.id, entry.folder.groupOf(files, entry.id)),
                    ),
                },
              ]
            : []),
          { label: 'Copy id', run: () => void navigator.clipboard?.writeText(entry.id) },
          'separator',
          {
            label: 'Delete',
            shortcut: 'Mod+Backspace',
            danger: true,
            run: () => void remove(entry),
          },
        ]

  const columns = () => {
    if (view === 'list' || !items.current) return 1
    const width = items.current.clientWidth - 20
    return Math.max(1, Math.floor((width + 6) / (tileSize + 6)))
  }

  const searching = query.trim().length > 0
  // A kind's or group's tiles show once their thumbnails are ready, not as icons turning into pictures.
  const reveal = useReveal(items, searching ? 'search' : `${folder.key}:${group}`)
  const cursor = entries.findIndex((it) => it.key === selected)
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (renaming || event.target !== event.currentTarget) return
    const entry = entries[cursor]
    const mod = event.metaKey || event.ctrlKey
    // Backspace alone (Cmd+Backspace deletes) goes up out of a group folder.
    if (event.key === 'Backspace' && !mod && group && !searching) {
      event.preventDefault()
      const from = group
      openGroup(parentOf(group))
      setSelected(groupKeyOf(folder, from))
      return
    }
    const move = (to: number) => {
      event.preventDefault()
      const next = entries[Math.max(0, Math.min(entries.length - 1, to))]
      if (next) {
        select(next.key)
        document.getElementById(`explorer-${next.key}`)?.scrollIntoView?.({ block: 'nearest' })
      }
    }
    if (event.key === 'ArrowRight') move(cursor + 1)
    else if (event.key === 'ArrowLeft') move(cursor - 1)
    else if (event.key === 'ArrowDown') move(cursor < 0 ? 0 : cursor + columns())
    else if (event.key === 'ArrowUp') move(cursor - columns())
    else if (event.key === 'Home') move(0)
    else if (event.key === 'End') move(entries.length - 1)
    else if (!entry) return
    else if (event.key === 'Enter') {
      event.preventDefault()
      open(entry)
    } else if (event.key === 'F2') {
      event.preventDefault()
      setRenaming(entry.key)
    } else if (entry.type === 'group') {
      if (event.key === 'Delete' || (event.key === 'Backspace' && mod)) {
        event.preventDefault()
        void removeGroup(entry)
      }
    } else if (event.key === 'Delete' || (event.key === 'Backspace' && mod)) {
      event.preventDefault()
      void remove(entry)
    } else if (event.key === 'd' && mod) {
      event.preventDefault()
      void duplicate(entry)
    }
  }

  // The tiles' handlers stay the same functions, so a tile renders again only when what it shows changes.
  const latest = useRef({ select, focusItems, open, menuFor, moveTo })
  useLayoutEffect(() => {
    latest.current = { select, focusItems, open, menuFor, moveTo }
  })
  const handlers: TileHandlers = useMemo(
    () => ({
      select: (entry) => {
        latest.current.select(entry.key)
        latest.current.focusItems()
      },
      open: (entry) => latest.current.open(entry),
      menu: (event, entry) => {
        latest.current.select(entry.key)
        openMenu(event, latest.current.menuFor(entry))
      },
      drop: (id, to) => void latest.current.moveTo(id, to),
    }),
    [],
  )

  const shownKey = shownPackage ? `${PACKAGE_TAB}${pkg}` : folder.key
  const pick = (id: string) => {
    // Picking a kind, even the one already picked, leaves a search.
    setQuery('')
    if (id.startsWith(PACKAGE_TAB)) return setPkg(id.slice(PACKAGE_TAB.length))
    setPkg(null)
    layout.getState().set({ explorerFolder: id })
  }

  return (
    <div className={styles.explorer}>
      <div className={styles.folders}>
        <ListBox
          aria-label="Resource kinds"
          className={styles.folderList}
          selectionMode="single"
          // While searching, the results are every kind's: no kind shows as picked.
          selectedKeys={searching ? [] : [shownKey]}
          disallowEmptySelection={!searching}
          onSelectionChange={(keys) => {
            const [id] = keys === 'all' ? [] : [...keys]
            if (id !== undefined) pick(String(id))
          }}
        >
          {EXPLORER_SECTIONS.map((section) => (
            <ListBoxSection key={section.id} id={section.id} className={styles.section}>
              <Header className={styles.sectionTitle}>{section.title}</Header>
              {section.folders.map((it) => (
                <ListBoxItem
                  key={it.key}
                  id={it.key}
                  textValue={it.title}
                  aria-label={it.title}
                  className={styles.folder}
                >
                  <FolderIcon folder={it} />
                  <span className={styles.folderTitle}>{it.title}</span>
                  <span className={styles.folderCount}>{it.ids(files).length}</span>
                </ListBoxItem>
              ))}
            </ListBoxSection>
          ))}
          {packages && Object.keys(packages).length > 0 && (
            <ListBoxSection id="dependencies" className={styles.section}>
              <Header className={styles.sectionTitle}>Dependencies</Header>
              {Object.entries(packages).map(([namespace, it]) => (
                <ListBoxItem
                  key={`${PACKAGE_TAB}${namespace}`}
                  id={`${PACKAGE_TAB}${namespace}`}
                  textValue={namespace}
                  aria-label={`Package ${namespace}`}
                  className={styles.folder}
                >
                  <span
                    className={styles.folderTitle}
                    title={`${it.name ?? namespace}, from ${describeOrigin(it.origin)} (read-only)`}
                  >
                    {namespace}
                  </span>
                  <span className={styles.folderCount}>{it.version}</span>
                </ListBoxItem>
              ))}
            </ListBoxSection>
          )}
        </ListBox>
        <div className={styles.folderActions}>
          <Button
            variant="link"
            icon="plus"
            onClick={(event) => openMenu(event, dependencyMenu(backend, workspace, setPkg))}
          >
            Add dependency…
          </Button>
          <Button
            variant="link"
            icon="plus"
            onClick={(event) => openMenu(event, templateMenu(workspace))}
          >
            Add template…
          </Button>
          <Button variant="link" icon="gear" onClick={() => void ws().openFile('netherforge.json')}>
            Project settings
          </Button>
        </div>
      </div>
      <div className={styles.rule} />
      {shownPackage && !searching ? (
        <div className={styles.content}>
          <PackageView
            workspace={workspace}
            namespace={pkg!}
            pkg={shownPackage}
            onCopied={(target, id) => {
              setPkg(null)
              layout.getState().set({ explorerFolder: target.key })
              setSelected(keyOf(target, id))
            }}
          />
        </div>
      ) : (
        <div className={styles.content}>
          <Bar>
            {searching ? (
              <span className={styles.title}>Search results</span>
            ) : (
              <Breadcrumb folder={folder} group={group} onOpen={openGroup} onDrop={handlers.drop} />
            )}
            <Spacer />
            <input
              type="search"
              className={styles.search}
              aria-label="Search resources"
              placeholder="Search all resources"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape') setQuery('')
                if (event.key === 'ArrowDown') {
                  event.preventDefault()
                  if (entries[0]) select(entries[0].key)
                  focusItems()
                }
              }}
            />
            {view === 'grid' && (
              <input
                type="range"
                className={styles.size}
                aria-label="Tile size"
                min={LIMITS.tileSize[0]}
                max={LIMITS.tileSize[1]}
                value={tileSize}
                onChange={(event) =>
                  layout.getState().set({ tileSize: Number(event.target.value) })
                }
              />
            )}
            <IconButton
              icon={view === 'grid' ? 'list' : 'grid'}
              label={view === 'grid' ? 'Show as list' : 'Show as tiles'}
              onClick={() =>
                layout.getState().set({ explorerView: view === 'grid' ? 'list' : 'grid' })
              }
            />
            {folder.groupable && !searching && (
              <Button
                size="small"
                icon="newFolder"
                aria-label={group ? `New group in ${groupPath(group)}` : 'New group'}
                onClick={() => void createGroup()}
              >
                New group
              </Button>
            )}
            <Button
              size="small"
              icon="plus"
              aria-label={group ? `New ${folder.one} in ${groupPath(group)}` : `New ${folder.one}`}
              onClick={() => void create(folder)}
            >
              New {folder.one}
            </Button>
          </Bar>
          {entries.length === 0 ? (
            <div className={styles.empty}>
              {searching ? (
                <p>Nothing matches “{query}”.</p>
              ) : group ? (
                <>
                  <p>This group is empty.</p>
                  <Button variant="primary" icon="plus" onClick={() => void create(folder)}>
                    Create a {folder.one}
                  </Button>
                </>
              ) : (
                <>
                  <p>No {folder.title.toLowerCase()} yet.</p>
                  <Button variant="primary" icon="plus" onClick={() => void create(folder)}>
                    Create a {folder.one}
                  </Button>
                </>
              )}
            </div>
          ) : (
            <div
              ref={items}
              className={cx(styles.items, view === 'grid' ? styles.grid : styles.list)}
              style={{
                ...(view === 'grid' && {
                  gridTemplateColumns: `repeat(auto-fill, minmax(${tileSize}px, 1fr))`,
                }),
                ...(!reveal.shown && { opacity: 0 }),
              }}
              data-loading={reveal.shown ? undefined : ''}
              role="listbox"
              aria-label={searching ? 'Search results' : folder.title}
              tabIndex={0}
              aria-activedescendant={cursor >= 0 ? `explorer-${entries[cursor]!.key}` : undefined}
              onKeyDown={onKeyDown}
              onClick={(event) => event.target === event.currentTarget && select(null)}
            >
              <RevealGate value={reveal.gate}>
                {entries.map((entry) => (
                  <Tile
                    key={entry.key}
                    entry={entry}
                    view={view}
                    size={tileSize}
                    subtitle={
                      searching
                        ? entry.group
                          ? `${entry.folder.one} · ${groupPath(entry.group)}`
                          : entry.folder.one
                        : undefined
                    }
                    location={locationOf(entry)}
                    selected={entry.key === selected}
                    errors={errorsIn(entry)}
                    dirty={dirtyIn(entry)}
                    rename={
                      renaming !== entry.key
                        ? null
                        : entry.type === 'group'
                          ? {
                              validate: (value) => groupNameProblem(value, allGroups, entry.group),
                              commit: (value) => {
                                setRenaming(null)
                                void actions
                                  .renameGroup(entry.folder, entry.group, value)
                                  .then((next) => {
                                    if (next) setSelected(groupKeyOf(entry.folder, next))
                                    focusItems()
                                  })
                              },
                              cancel: () => {
                                setRenaming(null)
                                focusItems()
                              },
                            }
                          : {
                              validate: (value) =>
                                idProblem(value, entry.folder.ids(files), entry.id),
                              commit: (value) => {
                                setRenaming(null)
                                setSelected(keyOf(entry.folder, value))
                                void actions.rename(entry.folder, entry.id, value).then(focusItems)
                              },
                              cancel: () => {
                                setRenaming(null)
                                focusItems()
                              },
                            }
                    }
                    handlers={handlers}
                  />
                ))}
              </RevealGate>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/** Every folder [paths] are in, and each path itself, with how many of them are in it. */
function countByFolder(paths: string[]): Map<string, number> {
  const out = new Map<string, number>()
  for (const path of paths) {
    for (let end = path.length; end > 0; end = path.lastIndexOf('/', end - 1)) {
      const folder = path.slice(0, end)
      out.set(folder, (out.get(folder) ?? 0) + 1)
    }
  }
  return out
}

interface TileHandlers {
  select: (entry: Entry) => void
  open: (entry: Entry) => void
  menu: (event: React.MouseEvent, entry: Entry) => void
  /** A resource tile of the shown kind (its [id]) was dropped on group folder [to] (empty: the kind's own folder). */
  drop: (id: string, to: string) => void
}

/** Why [value] can't rename group folder [group] among [all] (its siblings' names are taken), or null. */
function groupNameProblem(value: string, all: string[], group: string): string | null {
  if (!ID_PATTERN.test(value)) return idProblem(value, [])
  const parent = parentOf(group)
  const taken = childGroups(all, parent)
    .filter((it) => it !== group)
    .map(ownName)
  return taken.includes(value) ? 'There is a group by that name here' : null
}

/** What a drag carries when it's a resource of [kind]: its id; null for anything else. */
function draggedId(event: React.DragEvent, kind: KindId): string | null {
  const data = event.dataTransfer.getData(DRAG_TYPE)
  if (!data) return null
  try {
    const it = JSON.parse(data) as { kind?: string; id?: string }
    return it.kind === kind && typeof it.id === 'string' ? it.id : null
  } catch {
    return null
  }
}

/** Whether a drag over [event] is a resource tile (its data is only readable on drop). */
const carriesResource = (event: React.DragEvent) => event.dataTransfer.types.includes(DRAG_TYPE)

/**
 * The shown kind's title, then the group folders down to the open one (`Items › weapons › swords`): each goes back
 * there when clicked, and takes a dropped resource of the kind.
 */
function Breadcrumb({
  folder,
  group,
  onOpen,
  onDrop,
}: {
  folder: ExplorerFolder
  group: string
  onOpen: (group: string) => void
  onDrop: (id: string, to: string) => void
}) {
  const parts = group ? group.split('/') : []
  const [over, setOver] = useState<number | null>(null)
  const crumbs = [
    { label: folder.title, group: '' },
    ...parts.map((part, i) => ({ label: groupName(part), group: parts.slice(0, i + 1).join('/') })),
  ]
  if (crumbs.length === 1) return <span className={styles.title}>{folder.title}</span>
  return (
    <nav className={styles.breadcrumb} aria-label="Group folders">
      {crumbs.map((crumb, i) => {
        const last = i === crumbs.length - 1
        return (
          <span key={crumb.group} className={styles.crumb}>
            {i > 0 && <Icon name="chevronRight" />}
            {last ? (
              <span className={styles.title} aria-current="location">
                {crumb.label}
              </span>
            ) : (
              <button
                type="button"
                className={cx(styles.crumbButton, over === i && styles.dropTarget)}
                onClick={() => onOpen(crumb.group)}
                onDragOver={(event) => {
                  if (!carriesResource(event)) return
                  event.preventDefault()
                  setOver(i)
                }}
                onDragLeave={() => setOver(null)}
                onDrop={(event) => {
                  setOver(null)
                  const id = draggedId(event, folder.key)
                  if (id === null) return
                  event.preventDefault()
                  onDrop(id, crumb.group)
                }}
              >
                {crumb.label}
              </button>
            )}
          </span>
        )
      })}
    </nav>
  )
}

const Tile = memo(function Tile({
  entry,
  view,
  size,
  subtitle,
  location,
  selected,
  errors,
  dirty,
  rename,
  handlers,
}: {
  entry: Entry
  view: 'grid' | 'list'
  size: number
  subtitle?: string
  /** Its folder or file (a group's folder), shown on hover. */
  location: string
  selected: boolean
  errors: number
  dirty: boolean
  rename: {
    validate: (value: string) => string | null
    commit: (value: string) => void
    cancel: () => void
  } | null
  handlers: TileHandlers
}) {
  const isGroup = entry.type === 'group'
  const [over, setOver] = useState(false)
  const label = rename ? (
    <InlineInput
      className={view === 'grid' ? styles.renameTile : undefined}
      label={isGroup ? `Rename group ${entry.id}` : `Rename ${entry.id}`}
      initial={entry.id}
      validate={rename.validate}
      onCommit={rename.commit}
      onCancel={rename.cancel}
    />
  ) : (
    <span className={styles.label} title={location}>
      {entry.id}
    </span>
  )
  // A resource tile drags; a group tile takes a resource of its kind dropped on it.
  const drag = isGroup
    ? {
        onDragOver: (event: React.DragEvent) => {
          if (!carriesResource(event)) return
          event.preventDefault()
          setOver(true)
        },
        onDragLeave: () => setOver(false),
        onDrop: (event: React.DragEvent) => {
          setOver(false)
          const id = draggedId(event, entry.folder.key)
          if (id === null) return
          event.preventDefault()
          handlers.drop(id, entry.group)
        },
      }
    : {
        draggable: !rename,
        onDragStart: (event: React.DragEvent) => {
          event.dataTransfer.setData(
            DRAG_TYPE,
            JSON.stringify({ kind: entry.folder.key, id: entry.id }),
          )
          event.dataTransfer.effectAllowed = 'move'
        },
      }
  return (
    <div
      id={`explorer-${entry.key}`}
      role="option"
      aria-selected={selected}
      aria-label={isGroup ? `Group ${entry.id}` : entry.id}
      className={cx(view === 'grid' ? styles.tile : styles.row, over && styles.dropTarget)}
      onClick={() => handlers.select(entry)}
      onDoubleClick={() => handlers.open(entry)}
      onContextMenu={(event) => handlers.menu(event, entry)}
      {...drag}
    >
      <span
        className={styles.picture}
        style={view === 'grid' ? { width: size - 12, height: size - 12 } : undefined}
      >
        {isGroup && view === 'grid' && entry.preview?.length ? (
          <GroupThumbnail folder={entry.folder} ids={entry.preview} size={size - 12} />
        ) : isGroup ? (
          <Icon name="folder" size={view === 'grid' ? Math.round((size - 12) * 0.55) : 16} />
        ) : (
          <Thumbnail folder={entry.folder} id={entry.id} size={view === 'grid' ? size - 12 : 22} />
        )}
      </span>
      {label}
      {subtitle && view === 'grid' && <span className={styles.subtitle}>{subtitle}</span>}
      {subtitle && view === 'list' && <Badge>{subtitle}</Badge>}
      <span className={view === 'grid' ? styles.markers : undefined}>
        {dirty && <DirtyDot />}
        <ErrorCount value={errors} />
      </span>
    </div>
  )
})
