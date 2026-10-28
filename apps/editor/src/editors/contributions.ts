/**
 * What can be contributed to the workbench, and the shapes it takes: a
 * resource kind (its tab, outline, thumbnail, view state and commands), a
 * tab type that isn't a resource's (a script, the settings), a dock panel.
 * The data is in `editors/registry.tsx` (the kinds and file tabs) and
 * `workbench/contributions.tsx` (what only the workbench has); nothing else
 * keeps a list of any of them.
 */
import type { ComponentType, LazyExoticComponent, ReactNode } from 'react'
import type { AppStores } from '@/state/providers'
import type { RecentProject } from '@/core/backend/types'
import type { IconName } from '@/ui/Icon'

type Lazy<P> = LazyExoticComponent<ComponentType<P>>

/** What a kind's outline gets: its main file, and the resource's Files pane to place among its own. */
export interface OutlineProps {
  path: string
  /** Rendered whether or not the model parses, so a broken file's folder can still be reached. */
  files: ReactNode
}

/** What a kind's thumbnail gets. It's drawn centred in its tile, which clips it. */
export interface ThumbnailProps {
  id: string
  /** The picture's edge in pixels (a little smaller than the tile, as an icon in it is). */
  size: number
  /** The kind's icon: what to show until (or unless) there's a picture. */
  fallback: ReactNode
}

/** A colour a kind's icon is drawn in, from the explorer's palette. */
export type KindTone = 'blue' | 'purple' | 'amber' | 'green' | 'orange' | 'cyan'

/** Which of the explorer's sections a kind is listed in (`KIND_GROUPS` in the registry names and orders them). */
export type KindGroup = 'gameplay' | 'interface' | 'art' | 'world' | 'code'

/**
 * A kind's per-document view: the state its editor keeps besides the file
 * ([V]: what's previewed, a playhead, a gizmo mode) and what its selection
 * holds ([S]: node names, slot indexes, picks). Neither is saved or undone.
 * The workspace keeps both per document (`core/store/views.ts`: moved on
 * rename, dropped on close); `editors/views.ts` reads and changes them typed
 * by this registration.
 */
export interface ViewStateSpec<V extends object = object, S = never> {
  /** What a document's view starts as. */
  initial: V
  /** An item's identity: two items with the same key are the same item (toggling, a tree's row id). */
  key(item: S): string
  /**
   * What opening a file inside one of the kind's resources selects, for a
   * file that isn't text (those open in their own tab): [rest] is its path
   * inside the resource's folder, null when it's nothing the editor shows.
   */
  opensInside?(rest: string): S | null
}

/** What a command's `when`, label and run see: the app as it is when the menus are built or a key is pressed. */
export interface CommandContext {
  app: AppStores
  /** The menus are macOS's menu bar (its app and Window menus, the system's clipboard items). */
  mac: boolean
  /** Mod is Cmd: the keyboard's convention, which a browser on a Mac has too. */
  macKeys: boolean
  /** For File → Open Recent. */
  recent: RecentProject[]
}

/** Where a kind's command shows besides the palette. */
export type CommandPlace = 'run'

interface KindCommandBase {
  /** Its id is `<kind>.<name>`. */
  name: string
  hint: string
  icon: IconName
  /** Whether it can run, beyond what its scope needs (the dev server's bridge up). */
  when?: (c: CommandContext) => boolean
  /** The menu it shows in. */
  menu?: CommandPlace
}

/**
 * A command a kind contributes. A `resource` command acts on one of the
 * kind's resources: the active tab's in the menus, the shortcut and the
 * toolbar (so it runs only while one is active), and in the palette, every
 * one of them (`<kind>.<name>:<id>`). A `kind` command needs none.
 */
export type KindCommand =
  | (KindCommandBase & {
      scope: 'resource'
      /** Its menu label: [id] is the active resource, or null with none (then it's disabled). */
      label: (id: string | null) => string
      /** A toolbar button's text, beside the dev server's controls. */
      toolbar?: string
      run: (c: CommandContext, id: string) => unknown
    })
  | (KindCommandBase & {
      scope: 'kind'
      label: string
      run: (c: CommandContext) => unknown
    })

/**
 * Everything the workbench shows of one resource kind. Which kinds exist,
 * where they live and what format makes of them is format's `KINDS`; this
 * is only how they look and what the editor does with them.
 */
export interface KindContribution {
  /** Its kind, plural: the explorer's folder. */
  title: string
  /** One of them, for "New …". */
  one: string
  /** The explorer's section it's listed in. */
  group: KindGroup
  icon: IconName
  tone?: KindTone
  /** Under the id prompt when creating one. */
  message: string
  /**
   * In a workspace, one can move into a package of it (the explorer's Move to Package…): its
   * folder goes there whole, and what names it here names it in the package. Modules only.
   */
  movesToPackage?: boolean
  /**
   * What its own tab shows: the editor for its main file, or a binary kind's
   * screen (given its folder or file). Loaded with the first tab that needs it
   * (three.js and Monaco are most of the bundle). A kind without one opens as
   * a file: a module opens its entry in a script tab.
   */
  view?: Lazy<{ path: string }>
  /**
   * What the outline dock shows for one: `Pane`s of its own trees (a
   * centity's nodes and clips, a menu's slots) with its Files pane placed
   * where it reads best. [path] is its main file, loaded before this renders,
   * whether its own tab or one of its scripts is the active one.
   */
  outline?: Lazy<OutlineProps>
  /** Its picture in the explorer, the palette, the outline's header and its tab: the kind's icon without one. */
  thumbnail?: ComponentType<ThumbnailProps> | Lazy<ThumbnailProps>
  /**
   * Something the workbench keeps mounted while [busy] (a hidden canvas
   * drawing thumbnails), so pictures draw wherever they show. [subscribe]
   * and [busy] are an external store, as `useSyncExternalStore` takes it.
   */
  studio?: {
    subscribe: (listener: () => void) => () => void
    busy: () => boolean
    view: Lazy<object>
  }
  /** Its documents' view state and selection (see `editors/views.ts`). */
  viewState?: ViewStateSpec<object, unknown>
  commands?: KindCommand[]
}

/** What a tab of one type shows, and how the strip draws it. */
export interface TabView {
  icon: IconName
  /** The strip's label: a resource by its id, a file by its name. */
  label: (path: string) => string
  view: Lazy<{ path: string }>
  /** Whether a document backs it (banners, save, undo, read-only); false for a binary kind's screen or the settings. */
  document: boolean
  /** Whether it's a resource's own tab, which shows the resource's thumbnail. */
  resource: boolean
  /**
   * The outline dock while it's active, for a tab that isn't inside a
   * resource (the settings' pages): under its icon and label, with
   * [subtitle] where a resource's kind would be.
   */
  outline?: { subtitle: string; view: ComponentType }
}

/**
 * A panel in the docks. Its [id] is remembered in the layout (where it is,
 * `core/store/docks.ts`, which also says where it starts), so it never changes.
 */
export interface DockPanel {
  id: string
  /** Its tab's name. */
  title: string
  view: ComponentType
  /** Beside its title (counts). */
  badge?: ComponentType
  /** The View menu's item that shows it, and the palette's hint and icon. */
  menu: { label: string; hint: string; icon: IconName }
}
