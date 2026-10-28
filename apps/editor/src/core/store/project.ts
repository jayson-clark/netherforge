/**
 * The project slice: the open project and what the editor knows about it
 * (its files and their disk texts, its Minecraft version's game data and
 * glyph advances, what's read-only), opening and closing it, the files the
 * editor writes into it on its own (schemas, agent docs, the default font),
 * notices, and the hot reload that follows a write.
 */
import type { ReloadResult } from '@netherforge/format/types'
import type { AppInfo, CacheStatus, ProjectInfo, Unlisten } from '@/core/backend/types'
import { editorAgentDocFiles } from '@/core/agentDocs'
import { DEFAULT_FONT_FILE, defaultFontToWrite, defaultFontWriteFailed } from '@/core/defaultFont'
import { clientJson, VANILLA_FILE, vanillaLooks, vanillaToWrite } from '@/core/vanilla'
import {
  AGENT_FILES,
  MANIFEST_FILE,
  newAgentFiles,
  newProjectFiles,
  projectRefusal,
  type GameDataBundle,
  type Problem,
} from '@/core/format'
import {
  MCP_CONFIG_FILE,
  OPENCODE_CONFIG_FILE,
  syncMcpConfig,
  syncOpencodeConfig,
} from '@/core/mcp/config'
import { readsText } from '@/core/paths'
import { SCHEMA_FOLDER, schemaFiles } from '@netherforge/project-support'
import { DOCUMENTS_INITIAL } from './documents'
import { isPackageMark, PACKAGES_INITIAL } from './packages'
import { REFACTORS_INITIAL } from './refactors'
import { errorText, isUnder, nextNonce, type SliceArgs } from './slice'
import { TABS_INITIAL } from './tabs'
import { VALIDATION_INITIAL } from './validation'

export interface Notice {
  kind: 'error' | 'info'
  text: string
  nonce: number
}

/** What the workspace needs from the run controls, wired after both exist. */
export interface RunHooks {
  bridgeConnected(): boolean
  /** Reports the outcome of a hot reload in the console. */
  reloaded(paths: string[], result: ReloadResult | null, error?: string): void
  /**
   * Whether [paths] wait instead of reloading now: true while the debugger
   * holds the dev server at a breakpoint (the plugin refuses every reload
   * then), and it sends them itself once the server runs again.
   */
  holdReload?(paths: string[]): boolean
}

/**
 * Why [path] can't be changed (the innermost read-only folder's reason), or
 * null when it can. A mark ending in `:` (`library:`) is a whole package:
 * every package path in it.
 */
export function readOnlyReason(readOnly: Record<string, string>, path: string): string | null {
  let found: string | null = null
  let depth = -1
  for (const [root, reason] of Object.entries(readOnly)) {
    const inside = isPackageMark(root) ? path.startsWith(root) : isUnder(path, root)
    if (inside && root.length > depth) {
      found = reason
      depth = root.length
    }
  }
  return found
}

export interface ProjectState {
  appInfo: AppInfo | null
  project: ProjectInfo | null
  /** Paths of every file in the project. */
  files: string[]
  /** Last-known disk text of every .json file, for validation. */
  diskTexts: Record<string, string>
  minecraft: string | null
  /** The dev server's export for [minecraft] (registries, shapes): what validation checks against. */
  gameData: GameDataBundle | null
  /** The imported client's default-font advances, `{ "<code point>": pixels }`. */
  glyphAdvances: Record<string, number> | null
  cache: CacheStatus | null
  notice: Notice | null
  /**
   * Bumped for a path whenever it's written or changes on disk, so image
   * URLs (`projectFileUrl(path, stamp)`) reload.
   */
  fileStamps: Record<string, number>
  /**
   * Files and folders that can't be changed, and why ("From the package
   * acme_economy"): a dependency's resources, shown but never edited, saved,
   * renamed or deleted. Kept per project; see [readOnlyReason].
   */
  readOnly: Record<string, string>
  /** Writing `fonts/default.json` failed: shown with the problems until a write succeeds. */
  fontProblem: Problem | null
}

export interface ProjectActions {
  init(): Promise<void>
  openProject(root: string): Promise<void>
  createProject(root: string, name: string, minecraft: string): Promise<void>
  closeProject(): Promise<void>
  /**
   * Trusts the open project (or, `false`, stops trusting it). The backend decides and keeps
   * it; until it's trusted, the project's dev server, its Lua language features, fetching its
   * git packages and agents' tools are refused there, and neither .mcp.json nor opencode.json is written.
   */
  trustProject(trusted: boolean): Promise<void>
  /**
   * Trusts the open project's workspace, and so every project in it, this one included (or stops
   * trusting it): as [trustProject], for a project that's in one.
   */
  trustWorkspace(trusted: boolean): Promise<void>
  refreshFiles(): Promise<void>
  refreshGameData(): Promise<void>
  /**
   * Writes `fonts/default.json` from the import when it's missing or
   * differs (see `core/defaultFont.ts`). Never over an open document with
   * unsaved edits; the watcher's echo of our write then matches and stops.
   */
  syncDefaultFont(): Promise<void>
  /**
   * Writes `client/vanilla.json` from the import when a tripwire block or a
   * pack's `hud.hide` needs it and it's missing or differs (see `core/vanilla.ts`),
   * never over unsaved edits.
   */
  syncVanilla(): Promise<void>
  /**
   * Adds AGENTS.md and CLAUDE.md to the open project, each only if it's
   * missing, and the editor's MCP server (its port and token) to `.mcp.json` and `opencode.json`
   * (created, merged into or brought up to date); returns the paths written.
   */
  addAgentFiles(): Promise<string[]>
  /**
   * Marks [path] (a file, or a folder and everything in it) read-only for
   * [reason], or writable again with null. Packages a project depends on
   * are marked as they load.
   */
  setReadOnly(path: string, reason: string | null): void
  /**
   * Whether [paths] include one that's read-only: if so, says why and the
   * caller changes nothing. Every action that writes a file asks first.
   */
  refuseReadOnly(...paths: string[]): boolean
  notify(kind: Notice['kind'], text: string): void
  setRunHooks(hooks: RunHooks): void
  /** Tells the dev server to reload [paths], when it's connected; the result goes to the console. */
  hotReload(paths: string[]): void
}

export const PROJECT_INITIAL: Omit<ProjectState, 'appInfo' | 'notice'> = {
  project: null,
  files: [],
  diskTexts: {},
  minecraft: null,
  gameData: null,
  glyphAdvances: null,
  cache: null,
  fileStamps: {},
  readOnly: {},
  fontProblem: null,
}

export function projectSlice({ backend, get, set }: SliceArgs): ProjectState & ProjectActions {
  let unlistenFs: Unlisten | null = null
  let unlistenCache: Unlisten | null = null
  let hooks: RunHooks = { bridgeConnected: () => false, reloaded: () => {} }
  let writingFont = false
  let writingBlockStates = false

  const writeSchemas = async () => {
    await Promise.all(
      Object.entries(schemaFiles).map(([name, text]) =>
        backend.writeText(`${SCHEMA_FOLDER}/${name}`, text).catch(() => {}),
      ),
    )
  }

  /**
   * The docs for coding agents, in `.netherforge/docs/`. Not awaited by open: it
   * loads the bundle lazily and a project works without it.
   */
  const writeAgentDocs = async () => {
    const version = get().appInfo?.version ?? 'dev'
    const readme = await backend.readText(`${AGENT_FILES.docs}/README.md`).catch(() => null)
    const files = await editorAgentDocFiles(version, readme)
    const { [`${AGENT_FILES.docs}/README.md`]: last, ...rest } = files
    await Promise.all(Object.entries(rest).map(([path, text]) => backend.writeText(path, text)))
    if (last != null) await backend.writeText(`${AGENT_FILES.docs}/README.md`, last)
  }

  /** What follows trusting the project or its workspace: [project] as the backend answers it now. */
  const afterTrust = async (project: ProjectInfo) => {
    set({ project })
    if (!project.trusted) return
    void syncMcpConfig(backend, get().files, false).catch(() => {})
    void syncOpencodeConfig(backend, get().files).catch(() => {})
    // Its git packages can be fetched now.
    await get().refreshPackages()
    await get().validateNow()
  }

  return {
    appInfo: null,
    notice: null,
    ...PROJECT_INITIAL,

    async init() {
      set({ appInfo: await backend.appInfo() })
    },

    async openProject(root) {
      // One validation once it has everything, not one per file it writes as it opens.
      get().holdValidation(true)
      try {
        const project = await backend.openProject(root)
        unlistenFs?.()
        unlistenFs = await backend.onFilesChanged((event) => {
          void get().handleFilesChanged(event.paths, event.rescan)
        })
        unlistenCache?.()
        // An import or a dev server's export just changed what we know about the game.
        unlistenCache = await backend.onCacheChanged((status) => {
          if (status.version === get().minecraft) void get().refreshGameData()
        })
        set({
          ...DOCUMENTS_INITIAL,
          ...TABS_INITIAL,
          ...VALIDATION_INITIAL,
          ...PACKAGES_INITIAL,
          ...REFACTORS_INITIAL,
          project,
          diskTexts: {},
          readOnly: {},
        })
        await get().refreshFiles()
        const diskTexts: Record<string, string> = {}
        await Promise.all(
          get()
            .files.filter(readsText)
            .map(async (path) => {
              diskTexts[path] = await backend.readText(path)
            }),
        )
        // A project in another format version is refused, never migrated (format's message names it).
        const refusal = projectRefusal(diskTexts[MANIFEST_FILE] ?? '')
        if (refusal) {
          await get().closeProject()
          throw new Error(refusal)
        }
        let minecraft: string | null = null
        try {
          minecraft =
            (JSON.parse(diskTexts[MANIFEST_FILE] ?? '{}') as { minecraft?: string }).minecraft ??
            null
        } catch {
          minecraft = null
        }
        set({ diskTexts, minecraft })
        await writeSchemas()
        void writeAgentDocs().catch(() => {})
        // A .mcp.json (and opencode.json) from another computer, or from before a port change,
        // gets this editor's: only in a project the user trusts, since they carry tokens.
        if (project.trusted) {
          void syncMcpConfig(backend, get().files, true).catch(() => {})
          void syncOpencodeConfig(backend, get().files, true).catch(() => {})
        }
        await get().refreshGameData()
        await get().refreshPackages()
        get().holdValidation(false)
        await get().validateNow()
      } catch (error) {
        get().notify('error', `Couldn't open ${root}: ${errorText(error)}`)
        throw error
      } finally {
        get().holdValidation(false)
      }
    },

    async trustProject(trusted) {
      await afterTrust(await backend.trustProject(trusted))
    },

    async trustWorkspace(trusted) {
      await afterTrust(await backend.workspaceTrust(trusted))
    },

    async createProject(root, name, minecraft) {
      await backend.createProject(root, newProjectFiles(name, minecraft))
      await get().openProject(root)
      // AGENTS.md and CLAUDE.md came with the template; these add .mcp.json and opencode.json for this editor's port and tokens.
      await get()
        .addAgentFiles()
        .catch(() => {})
    },

    async closeProject() {
      unlistenFs?.()
      unlistenFs = null
      unlistenCache?.()
      unlistenCache = null
      get().cancelValidation()
      await backend.closeProject()
      set({
        ...PROJECT_INITIAL,
        ...DOCUMENTS_INITIAL,
        ...TABS_INITIAL,
        ...VALIDATION_INITIAL,
        ...PACKAGES_INITIAL,
        ...REFACTORS_INITIAL,
      })
    },

    async addAgentFiles() {
      const { project, files } = get()
      if (!project) return []
      const missing = Object.entries(newAgentFiles(project.name)).filter(
        ([path]) => !files.includes(path),
      )
      for (const [path, text] of missing) await backend.writeText(path, text)
      const written = missing.map(([path]) => path)
      if (await syncMcpConfig(backend, files, true)) written.push(MCP_CONFIG_FILE)
      if (await syncOpencodeConfig(backend, files, true)) written.push(OPENCODE_CONFIG_FILE)
      await get().refreshFiles()
      return written
    },

    async refreshFiles() {
      const entries = await backend.listFiles()
      set({ files: entries.map((it) => it.path).sort() })
    },

    async refreshGameData() {
      const version = get().minecraft
      if (!version) return
      const [gameData, cache, glyphAdvances] = await Promise.all([
        backend.gameData(version).catch(() => null),
        backend.cacheStatus(version).catch(() => null),
        backend.glyphAdvances(version).catch(() => null),
      ])
      set({ gameData, cache, glyphAdvances })
      get().validateSoon()
      await get().syncDefaultFont()
    },

    async syncDefaultFont() {
      const { project, minecraft, glyphAdvances, diskTexts, docs } = get()
      if (!project || writingFont || docs[DEFAULT_FONT_FILE]?.dirty) return
      const text = defaultFontToWrite(minecraft, glyphAdvances, diskTexts[DEFAULT_FONT_FILE])
      if (text === null) return
      writingFont = true
      try {
        await backend.writeText(DEFAULT_FONT_FILE, text)
        set({
          fontProblem: null,
          diskTexts: { ...get().diskTexts, [DEFAULT_FONT_FILE]: text },
          fileStamps: { ...get().fileStamps, [DEFAULT_FONT_FILE]: nextNonce() },
        })
        await get().refreshFiles()
      } catch (error) {
        set({ fontProblem: defaultFontWriteFailed(errorText(error)) })
      } finally {
        writingFont = false
      }
      get().validateSoon()
    },

    async syncVanilla() {
      const { project, minecraft, diskTexts, docs } = get()
      if (!project || !minecraft || writingBlockStates || docs[VANILLA_FILE]?.dirty) return
      writingBlockStates = true
      try {
        const looks = await vanillaLooks(
          clientJson(backend, minecraft),
          get().outline?.replacedTextures ?? [],
          get().outline?.replacedModels ?? [],
        )
        const text = vanillaToWrite(minecraft, looks, diskTexts[VANILLA_FILE])
        if (text === null) return
        await backend.writeText(VANILLA_FILE, text)
        set({
          diskTexts: { ...get().diskTexts, [VANILLA_FILE]: text },
          fileStamps: { ...get().fileStamps, [VANILLA_FILE]: nextNonce() },
        })
        await get().refreshFiles()
        get().validateSoon()
      } catch (error) {
        get().notify('error', `Couldn't write ${VANILLA_FILE}: ${errorText(error)}`)
      } finally {
        writingBlockStates = false
      }
    },

    setReadOnly(path, reason) {
      const readOnly = { ...get().readOnly }
      if (reason === null) delete readOnly[path]
      else readOnly[path] = reason
      set({ readOnly })
    },

    refuseReadOnly(...paths) {
      for (const path of paths) {
        const reason = readOnlyReason(get().readOnly, path)
        if (reason === null) continue
        get().notify('error', `${path} is read-only: ${reason}.`)
        return true
      }
      return false
    },

    notify(kind, text) {
      set({ notice: { kind, text, nonce: nextNonce() } })
    },

    setRunHooks(next) {
      hooks = next
    },

    hotReload(paths) {
      if (paths.length === 0 || !hooks.bridgeConnected()) return
      if (hooks.holdReload?.(paths)) return
      backend.bridgeRequest('reload', { paths }).then(
        (result) => hooks.reloaded(paths, result),
        (error: unknown) => hooks.reloaded(paths, null, errorText(error)),
      )
    },
  }
}
