/**
 * A `Backend` with no disk, no server and no Tauri: projects are maps of
 * path → text, and the dev server is a small state machine.
 *
 * Unit tests and Playwright run on this, and only they: the app always
 * runs the Rust backend. It behaves like the Rust side where the UI can tell
 * the difference: every write (the editor's own included) is echoed as a
 * debounced `onFilesChanged`, folders are implicit, paths are scoped to the
 * project, and failures reject with the same `BackendError` codes.
 * `contract.json` holds it to that: `contract.test.ts` runs it here and
 * `src-tauri/src/commands/contract.rs` on the real commands.
 *
 * The `test*` methods are the "outside world" for tests: a git checkout or an
 * agent editing a file, the plugin sending a message.
 */
import type { GameDataBundle } from '@netherforge/format/types'
import { sha256Hex } from '@/core/sha256'
import type { DebugProtocol } from '@vscode/debugprotocol'
import { base64ToBytes, bytesToBase64 } from '@/core/backend/base64'
import { FakeDebugAdapter, type FakeStop } from './memoryDebug'
import { FakePlugin, fail, manifestField, type BridgeCall } from './memoryPlugin'
import type {
  AppInfo,
  AppMenu,
  Backend,
  BridgeEventName,
  BridgeEvents,
  BridgeMethod,
  BridgeParamsArg,
  BridgeResult,
  CacheStatus,
  CaptureProgress,
  ConvertProgress,
  EulaStatus,
  FileEntry,
  FsChangedEvent,
  ImportProgress,
  LualsStarted,
  McpMessage,
  McpStatus,
  MinecraftInstall,
  NetworkLayout,
  NetworkMember,
  NetworkMembers,
  NetworkOutputEvent,
  NetworkServerCheck,
  PackageFiles,
  PrepareProgress,
  ProjectInfo,
  RecentProject,
  ServerOutputEvent,
  ServerState,
  Settings,
  TestReport,
  TestStatus,
  Unlisten,
  UpdateInfo,
  UpdateProgress,
  VideoConverted,
  VideoInfo,
  WorkspaceInfo,
} from '@/core/backend/types'
import { jvmArgumentProblem } from './jvmArgs'
import { nfvHeader, readNfvHeader, sameBytes } from './nfv'

type Listener<T> = (value: T) => void

/** A file's contents: text, or bytes for binary files (PNGs). */
export type FileContents = string | Uint8Array

const textDecoder = new TextDecoder('utf-8', { fatal: true })

export { base64ToBytes, bytesToBase64 } from '@/core/backend/base64'

const MIME: Record<string, string> = {
  png: 'image/png',
  json: 'application/json',
  lua: 'text/plain',
  mcmeta: 'application/json',
}

class Emitter<T> {
  private listeners = new Set<Listener<T>>()

  listen(listener: Listener<T>): Promise<Unlisten> {
    this.listeners.add(listener)
    return Promise.resolve(() => {
      this.listeners.delete(listener)
    })
  }

  emit(value: T) {
    for (const listener of [...this.listeners]) listener(value)
  }

  get listened(): boolean {
    return this.listeners.size > 0
  }
}

export type { BridgeCall } from './memoryPlugin'

/** The MCP token `mcpStatus` reports in memory. */
export const MEMORY_MCP_TOKEN = 'memory-mcp-token'

/** A JSON-RPC response, as an agent gets it (`testMcpCall`). */
export interface McpTestResponse {
  result?: unknown
  error?: { code: number; message: string }
}

export interface MemoryBackendOptions {
  /** Projects that exist, by absolute root → files (text, or bytes for binary files). */
  projects?: Record<string, Record<string, FileContents>>
  recent?: RecentProject[]
  appInfo?: Partial<AppInfo>
  installs?: MinecraftInstall[]
  /** Versions whose client assets are already imported. */
  importedClients?: string[]
  /**
   * Client asset files (path in the jar → URL, usually a data: URL) that an
   * "import" makes available, for every version. Without them, asset URLs
   * point nowhere and previews show placeholders.
   */
  clientAssets?: Record<string, string>
  /** Server game data by version. */
  gameData?: Record<string, GameDataBundle>
  /** A newer release the "updater" reports; none by default. */
  update?: UpdateInfo
  /** Default-font glyph advances an "import" provides, by version (see `Backend.glyphAdvances`). */
  glyphAdvances?: Record<string, Record<string, number>>
  eulaAccepted?: boolean
  /** Folders `pickFolder` returns, in order; afterwards it returns null. */
  pickFolderResults?: (string | null)[]
  /** Milliseconds the fake server takes per phase; 0 in unit tests. */
  serverDelayMs?: number
  /** How often the fake profiler sends a batch, while subscribed (the plugin: once a second). */
  profileEveryMs?: number
  /** How long change events are debounced, like the watcher's ~100 ms. */
  watchDebounceMs?: number
  /** The dev server's folder (paths relative to it): what `save_world` points into and a capture copies. */
  serverFiles?: Record<string, FileContents>
  /** Git repositories `packageFetchGit` fetches from, by URL. */
  gitRepos?: Record<string, MemoryGitRepo>
  /**
   * Project roots the user trusted on an earlier run. Left out, every project in `projects` is
   * (as most tests want a project that runs); the contract suite gives none, as the app starts.
   */
  trusted?: string[]
  /** Whether "ffmpeg" is on this computer, for `convertVideo` (the contract suite's has none). */
  ffmpeg?: boolean
  /** Workspace files (`netherforge-workspace.json`), by the folder each is in. */
  workspaces?: Record<string, string>
}

/**
 * A git repository, as `MemoryBackend` serves it: what each rev names
 * (`HEAD` for the default branch; a full commit names itself when it's in
 * [commits]) and each commit's files.
 */
export interface MemoryGitRepo {
  refs: Record<string, string>
  commits: Record<string, Record<string, FileContents>>
}

/** What the Rust side's `git::is_url` accepts: https, ssh, file, or user@host:path. */
const GIT_URL = /^(?:(?:https|ssh|file):\/\/\S+|[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:[^/\s]\S*)$/

const GIT_REV = /^[A-Za-z0-9_][A-Za-z0-9_./+-]*$/

const COMMIT = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/

/** As the Rust side's `layout::is_server_name`. */
const SERVER_NAME = /^[a-z0-9_-]{1,32}$/

/** As the Rust side's `layout::MAX_SERVERS`. */
const MAX_NETWORK_SERVERS = 8

const DEFAULT_LAYOUT: NetworkLayout = { enabled: false, name: 'main', servers: [] }

const DEFAULT_SETTINGS: Settings = {
  serverJvmArgs: [],
  serverMemoryMb: 2048,
  serverPort: 25565,
  mcpEnabled: true,
  mcpPort: 47615,
  ffmpegPath: '',
}

/** A file as a `data:` URL, for `<img>` and textures. */
function dataUrl(path: string, contents: FileContents): string {
  const bytes = typeof contents === 'string' ? new TextEncoder().encode(contents) : contents
  const mime = MIME[path.slice(path.lastIndexOf('.') + 1).toLowerCase()]
  return `data:${mime ?? 'application/octet-stream'};base64,${bytesToBase64(bytes)}`
}

export class MemoryBackend implements Backend {
  private projects: Map<string, Map<string, FileContents>>
  private recent: RecentProject[]
  private openRoot: string | null = null
  /** Project roots the user trusts (the Rust side's `trusted.json`). */
  private trustedRoots: Set<string>
  private settings: Settings = { ...DEFAULT_SETTINGS }
  private info: AppInfo
  private installs: MinecraftInstall[]
  private clients: Set<string>
  private assets: Record<string, string>
  private game: Map<string, GameDataBundle>
  private advances: Record<string, Record<string, number>>
  private eula: boolean
  private pickResults: (string | null)[]
  private serverDelay: number
  private debounce: number
  private state: ServerState = {
    phase: 'stopped',
    minecraft: null,
    port: null,
    bridgeConnected: false,
    message: null,
  }
  /** The dev server's folder. */
  private serverFiles: Map<string, FileContents>
  private captureEvents = new Emitter<CaptureProgress>()
  private videoEvents = new Emitter<ConvertProgress>()
  private ffmpeg: boolean
  private pendingChanges = new Set<string>()
  private gitRepos: Record<string, MemoryGitRepo>
  /** The package cache's checkouts, by commit. */
  private checkouts = new Map<string, Map<string, FileContents>>()
  /** Every `packageFetchGit` that reached a repository, for assertions: what was asked. */
  readonly gitFetchLog: { url: string; rev: string | null; commit: string | null }[] = []
  private flushTimer: ReturnType<typeof setTimeout> | null = null

  /** Every bridge request the UI sent, for assertions. */
  readonly bridgeLog: BridgeCall[] = []
  /** Every console command the UI sent. */
  get commandLog(): readonly string[] {
    return this.plugin.commandLog
  }
  /** What the test runner answers, when a test sets it (`testTestReport`): there is no Lua here to run. */
  private testReport: TestReport | null = null
  /** The test runner's answer for each workspace member, by its path there. */
  private memberTestReports = new Map<string, TestReport>()
  /** The plugin's debug adapter, on the bridge's `dap` channel. */
  private debugAdapter = new FakeDebugAdapter((message) => this.testBridgeEvent('dap', message))
  /** The plugin on the fake server: what it answers on the bridge. */
  private plugin: FakePlugin

  /** How many times the UI asked to open the Minecraft launcher. */
  launcherOpened = 0
  /** Versions the UI installed (it would have restarted after each). */
  readonly updatesInstalled: string[] = []
  private update: UpdateInfo | null
  /** The last `setAppMenu`, for tests (the in-app menu bar never sets it). */
  appMenu: AppMenu[] | null = null
  private menuListeners = new Set<(id: string) => void>()

  /** Each project's dev network layout, by root (the Rust side's `networks.json`). */
  private layouts = new Map<string, NetworkLayout>()
  /** The proxy and other servers of the network last started. */
  private members: NetworkMember[] = []
  /** Workspace files' texts, by the folder each is in. */
  private workspaceTexts: Map<string, string>
  /** Trusted workspaces' folders: each trusts everything in it. */
  private trustedWorkspaces = new Set<string>()
  /** Whether each workspace's dev network is on, on this "computer". */
  private workspaceNetworks = new Set<string>()
  private networkMembersEvents = new Emitter<NetworkMembers>()
  private networkMemberEvents = new Emitter<NetworkMember>()
  private networkOutputEvents = new Emitter<NetworkOutputEvent>()
  private fsChanged = new Emitter<FsChangedEvent>()
  private serverStateEvents = new Emitter<ServerState>()
  private serverOutput = new Emitter<ServerOutputEvent>()
  private prepare = new Emitter<PrepareProgress>()
  private bridge = new Emitter<{ event: BridgeEventName; params: unknown }>()
  private importEvents = new Emitter<ImportProgress>()
  private cacheEvents = new Emitter<CacheStatus>()
  private mcpEvents = new Emitter<McpMessage>()
  private mcpWaiting = new Map<number, (response: McpTestResponse) => void>()
  private mcpNext = 1

  constructor(options: MemoryBackendOptions = {}) {
    this.projects = new Map(
      Object.entries(options.projects ?? {}).map(([root, files]) => [
        root,
        new Map(Object.entries(files)),
      ]),
    )
    this.recent = options.recent ?? []
    this.info = {
      version: '0.0.0-memory',
      minecraftVersions: ['26.3'],
      os: 'linux',
      ...options.appInfo,
    }
    this.installs = options.installs ?? []
    this.clients = new Set(options.importedClients ?? [])
    this.assets = options.clientAssets ?? {}
    this.game = new Map(Object.entries(options.gameData ?? {}))
    this.advances = options.glyphAdvances ?? {}
    this.update = options.update ?? null
    this.eula = options.eulaAccepted ?? false
    this.pickResults = [...(options.pickFolderResults ?? [])]
    this.serverDelay = options.serverDelayMs ?? 0
    this.ffmpeg = options.ffmpeg ?? false
    this.plugin = new FakePlugin(
      {
        connected: () => this.state.bridgeConnected,
        paused: () => this.debugAdapter.paused,
        manifest: () => this.manifest(),
        gameData: () => this.game.get(this.state.minecraft ?? '') ?? null,
        files: () => this.files(),
        emit: (event, params) => this.testBridgeEvent(event, params),
      },
      options.profileEveryMs ?? 1000,
    )
    this.debounce = options.watchDebounceMs ?? 0
    this.gitRepos = options.gitRepos ?? {}
    this.trustedRoots = new Set(options.trusted ?? Object.keys(options.projects ?? {}))
    this.workspaceTexts = new Map(Object.entries(options.workspaces ?? {}))
    this.serverFiles = new Map(Object.entries(options.serverFiles ?? {}))
  }

  // ---- app -----------------------------------------------------------------

  async appInfo(): Promise<AppInfo> {
    return { ...this.info }
  }

  async getSettings(): Promise<Settings> {
    return { ...this.settings, serverJvmArgs: [...this.settings.serverJvmArgs] }
  }

  async setSettings(settings: Settings): Promise<void> {
    validateSettings(settings)
    this.settings = { ...settings, serverJvmArgs: [...settings.serverJvmArgs] }
  }

  async openExternal(): Promise<void> {}
  async windowAction(): Promise<void> {}
  async setAppMenu(menus: AppMenu[]): Promise<void> {
    this.appMenu = structuredClone(menus)
  }
  onMenuAction(listener: (id: string) => void): Unlisten {
    this.menuListeners.add(listener)
    return () => this.menuListeners.delete(listener)
  }
  /** A browser has no webview zoom to set: CSS zoom stands in, so Playwright sees the UI scale. */
  async setZoom(factor: number): Promise<void> {
    if (typeof document !== 'undefined') document.documentElement.style.zoom = String(factor)
  }

  async checkForUpdate(): Promise<UpdateInfo | null> {
    return this.update ? { ...this.update } : null
  }

  async installUpdate(onProgress?: (progress: UpdateProgress) => void): Promise<void> {
    if (!this.update) throw new Error('There is no update to install')
    onProgress?.({ downloaded: 1, total: 1 })
    this.updatesInstalled.push(this.update.version)
  }

  // ---- projects ------------------------------------------------------------

  async recentProjects(): Promise<RecentProject[]> {
    return [...this.recent]
  }

  async pickFolder(): Promise<string | null> {
    return this.pickResults.shift() ?? null
  }

  async pickJar(): Promise<string | null> {
    return null
  }

  async openProject(root: string): Promise<ProjectInfo> {
    root = normalizeRoot(root)
    // A workspace's own folder opens its entry project, as the Rust side's `workspace_entry`.
    if (!this.projects.get(root)?.has('netherforge.json') && this.workspaceTexts.has(root)) {
      const info = this.readWorkspace(root)
      const project = info.network?.servers.find((it) => it.name === info.network?.arrival)?.project
      const entry =
        info.members.find((it) => it.path === project) ??
        info.members.find((it) => !it.package) ??
        info.members[0]
      if (!entry)
        fail(
          'invalid',
          `The workspace ${info.name} has no projects yet: name some in its netherforge-workspace.json`,
        )
      root = entry.root
    }
    const files = this.projects.get(root)
    if (!files || !files.has('netherforge.json')) {
      fail('invalid', `${root} isn't a NetherForge project (it has no netherforge.json)`)
    }
    const workspace = this.findWorkspace(root)
    const before = this.openRoot ? this.findWorkspace(this.openRoot) : null
    // Another project of a workspace whose network runs: it keeps running, attached to this one's server.
    const keepNetwork =
      !!workspace && before?.root === workspace.root && this.members.some((m) => isActive(m.state))
    this.openRoot = root
    this.state = { ...this.state, minecraft: this.projectMinecraft() }
    if (keepNetwork) this.attach(this.members.find((m) => m.project === root)?.name ?? null)
    const manifest = files.get('netherforge.json')
    const info = {
      root,
      name: projectName(root, typeof manifest === 'string' ? manifest : undefined),
      trusted: this.isTrusted(root),
      workspace,
    }
    this.recent = [
      { root, name: info.name, openedAt: new Date().toISOString() },
      ...this.recent.filter((it) => it.root !== root),
    ]
    return info
  }

  async createProject(root: string, files: Record<string, FileContents>): Promise<ProjectInfo> {
    if (!('netherforge.json' in files)) fail('invalid', 'A new project needs a netherforge.json')
    for (const path of Object.keys(files)) checkPath(path)
    const existing = this.projects.get(root)
    if (existing && existing.size > 0)
      fail('alreadyExists', `${root} isn't empty; pick an empty or new folder`)
    this.projects.set(root, new Map(Object.entries(files)))
    // Made here by the user: theirs to run.
    this.trustedRoots.add(root)
    return this.openProject(root)
  }

  async trustProject(trusted: boolean): Promise<ProjectInfo> {
    const root = this.openRoot
    if (!root) fail('noProject', 'No project is open')
    if (trusted) this.trustedRoots.add(root)
    else {
      this.trustedRoots.delete(root)
      await this.stopServer()
    }
    const manifest = this.projects.get(root)?.get('netherforge.json')
    return {
      root,
      name: projectName(root, typeof manifest === 'string' ? manifest : undefined),
      trusted,
      workspace: this.findWorkspace(root),
    }
  }

  /** Whether [root] is trusted: itself, or inside a trusted workspace's folder. */
  private isTrusted(root: string): boolean {
    if (this.trustedRoots.has(root)) return true
    return [...this.trustedWorkspaces].some((it) => root.startsWith(`${it}/`))
  }

  // ---- workspaces ------------------------------------------------------------

  /** The workspace [root] (a project) is one of the projects of: the nearest file above it that lists it. */
  private findWorkspace(root: string): WorkspaceInfo | null {
    let dir = root.slice(0, root.lastIndexOf('/'))
    for (let i = 0; i < 6 && dir; i += 1) {
      if (this.workspaceTexts.has(dir)) {
        const info = this.readWorkspace(dir)
        return info.members.some((m) => m.root === root) ? info : null
      }
      dir = dir.slice(0, dir.lastIndexOf('/'))
    }
    return null
  }

  /** The workspace in [dir], read leniently as the Rust side reads it (format says what's wrong with it). */
  private readWorkspace(dir: string): WorkspaceInfo {
    const file = readLeniently<{
      name?: string
      projects?: string[]
      network?: { arrival?: string; servers?: Record<string, string> }
    }>(this.workspaceTexts.get(dir))
    const under = [...this.projects.entries()]
      .filter(([root, files]) => root.startsWith(`${dir}/`) && files.has('netherforge.json'))
      .map(([root]) => root.slice(dir.length + 1))
      .sort()
    const members: WorkspaceInfo['members'] = []
    for (const raw of file.projects ?? []) {
      const pattern = raw.trim().replace(/^\.\//, '').replace(/\/+$/, '')
      if (
        !pattern ||
        pattern.split('/').some((it) => it === '..' || it === '.') ||
        pattern.startsWith('/')
      )
        continue
      const named = pattern.endsWith('*')
        ? under.filter((it) => {
            const parent = pattern.slice(0, -1).replace(/\/$/, '')
            const rest = parent
              ? it.startsWith(`${parent}/`)
                ? it.slice(parent.length + 1)
                : null
              : it
            return rest !== null && !rest.includes('/')
          })
        : under.includes(pattern)
          ? [pattern]
          : []
      for (const path of named) {
        if (members.some((m) => m.path === path)) continue
        const root = `${dir}/${path}`
        const manifest = this.projects.get(root)?.get('netherforge.json')
        const text = typeof manifest === 'string' ? manifest : undefined
        const exports = typeof readLeniently<{ exports?: unknown }>(text).exports === 'object'
        members.push({ path, root, name: projectName(root, text), package: exports })
      }
    }
    return {
      root: dir,
      name: file.name?.trim() || dir.slice(dir.lastIndexOf('/') + 1),
      trusted: this.trustedWorkspaces.has(dir),
      members,
      network: file.network
        ? {
            arrival: file.network.arrival ?? '',
            servers: Object.entries(file.network.servers ?? {})
              .sort(([a], [b]) => (a < b ? -1 : 1))
              .map(([name, project]) => ({ name, project })),
          }
        : null,
    }
  }

  /** The open project's workspace folder, refusing as the Rust side does when there's none. */
  private workspaceRoot(): string {
    if (!this.openRoot) fail('noProject', 'No project is open')
    const workspace = this.findWorkspace(this.openRoot)
    if (!workspace) fail('notFound', "The open project isn't in a workspace")
    return workspace.root
  }

  async workspaceTrust(trusted: boolean): Promise<ProjectInfo> {
    const root = this.workspaceRoot()
    if (trusted) this.trustedWorkspaces.add(root)
    else this.trustedWorkspaces.delete(root)
    if (!this.isTrusted(this.openRoot!)) await this.stopServer()
    const manifest = this.projects.get(this.openRoot!)?.get('netherforge.json')
    return {
      root: this.openRoot!,
      name: projectName(this.openRoot!, typeof manifest === 'string' ? manifest : undefined),
      trusted: this.isTrusted(this.openRoot!),
      workspace: this.findWorkspace(this.openRoot!),
    }
  }

  async workspaceRead(): Promise<string> {
    return this.workspaceTexts.get(this.workspaceRoot())!
  }

  async workspaceWrite(text: string): Promise<WorkspaceInfo> {
    const root = this.workspaceRoot()
    this.workspaceTexts.set(root, text)
    return this.readWorkspace(root)
  }

  async workspaceInfo(): Promise<WorkspaceInfo | null> {
    if (!this.openRoot) return null
    return this.findWorkspace(this.openRoot)
  }

  /** The workspace's project [member] (its path there): its root and files. */
  private member(member: string): { root: string; files: Map<string, FileContents> } {
    const root = this.workspaceRoot()
    const found = this.readWorkspace(root).members.find((it) => it.path === member)
    if (!found) fail('notFound', `"${member}" isn't one of the workspace's projects`)
    return { root: found.root, files: this.projects.get(found.root)! }
  }

  async workspaceMemberFiles(member: string): Promise<PackageFiles> {
    const { root, files } = this.member(member)
    const listed = [...files.entries()]
      .filter(([path]) => !path.startsWith('.netherforge/') && !path.split('/').includes('.git'))
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    return {
      root,
      files: await Promise.all(
        listed.map(async ([path, contents]) => ({ path, sha256: await sha256Hex(contents) })),
      ),
    }
  }

  async workspaceMemberReadText(member: string, path: string): Promise<string> {
    checkPath(path)
    const contents = this.member(member).files.get(path)
    if (contents === undefined) fail('notFound', `${path} doesn't exist`)
    if (typeof contents !== 'string') fail('invalid', `${path} isn't text`)
    return contents
  }

  async workspaceMemberWriteText(member: string, path: string, text: string): Promise<void> {
    checkWritable(path)
    const found = this.member(member)
    found.files.set(path, text)
    // The open project's own folder: a change on disk like any other, which the watcher reports.
    if (found.root === this.openRoot) this.changed([path])
  }

  async workspaceCreateMember(path: string, files: Record<string, string>): Promise<WorkspaceInfo> {
    const root = this.workspaceRoot()
    checkWritable(path)
    if (!('netherforge.json' in files)) fail('invalid', 'A new project needs a netherforge.json')
    for (const it of Object.keys(files)) checkPath(it)
    const target = `${root}/${path}`
    if ((this.projects.get(target)?.size ?? 0) > 0)
      fail('alreadyExists', `${target} isn't empty; pick an empty or new folder`)
    this.projects.set(target, new Map(Object.entries(files)))
    return this.readWorkspace(root)
  }

  /** The workspace schemas written, by workspace folder, for tests. */
  readonly workspaceSchemas = new Map<string, string>()

  async workspaceWriteSchema(text: string): Promise<void> {
    this.workspaceSchemas.set(this.workspaceRoot(), text)
  }

  async workspaceNetworkEnabled(): Promise<boolean> {
    return this.workspaceNetworks.has(this.workspaceRoot())
  }

  async workspaceSetNetworkEnabled(enabled: boolean): Promise<void> {
    const root = this.workspaceRoot()
    if (enabled) this.workspaceNetworks.add(root)
    else this.workspaceNetworks.delete(root)
  }

  /** The open project's root, refusing as the Rust side's `trusted_root` does when it isn't trusted. */
  private trustedRoot(): string {
    const root = this.openRoot
    if (!root) fail('noProject', 'No project is open')
    if (!this.isTrusted(root)) {
      fail(
        'untrusted',
        `"${root}" isn't trusted yet: its dev server, Lua language features, git packages and agents' tools stay off until you trust it`,
      )
    }
    return root
  }

  async closeProject(): Promise<void> {
    await this.stopServer()
    this.clearNetwork()
    this.openRoot = null
    this.state = { ...this.state, minecraft: null }
  }

  // ---- files ---------------------------------------------------------------

  private files(): Map<string, FileContents> {
    if (!this.openRoot) fail('noProject', 'No project is open')
    return this.projects.get(this.openRoot)!
  }

  async listFiles(): Promise<FileEntry[]> {
    return (
      [...this.files().entries()]
        .filter(([path]) => !path.startsWith('.netherforge/') && !path.startsWith('.git/'))
        .map(([path, contents]) => ({ path, size: contents.length, modified: 0 }))
        // Byte order, as Rust sorts: `B.txt` before `_x.txt` before `a.txt`.
        .sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))
    )
  }

  async readText(path: string): Promise<string> {
    const files = this.files()
    checkPath(path)
    const contents = files.get(path)
    if (contents === undefined) fail('notFound', `Couldn't read ${path}: no such file`)
    if (typeof contents === 'string') return contents
    try {
      return textDecoder.decode(contents)
    } catch {
      fail('invalid', `${path} isn't text`)
    }
  }

  async writeText(path: string, text: string): Promise<void> {
    checkWritable(path)
    this.files().set(path, text)
    this.changed([path])
  }

  async writeBytes(path: string, bytes: Uint8Array): Promise<void> {
    checkWritable(path)
    this.files().set(path, bytes.slice())
    this.changed([path])
  }

  projectFileUrl(path: string): string {
    const contents =
      this.openRoot && isServed(path, true)
        ? this.projects.get(this.openRoot)?.get(path)
        : undefined
    if (contents === undefined) return `memory-project://${path}`
    return dataUrl(path, contents)
  }

  packageFileUrl(location: string, path: string): string {
    let contents: FileContents | undefined
    try {
      contents = isServed(path, false) ? this.packageAt(location).files.get(path) : undefined
    } catch {
      contents = undefined
    }
    if (contents === undefined) return `memory-package://${location}/${path}`
    return dataUrl(path, contents)
  }

  async deletePath(path: string): Promise<void> {
    checkWritable(path)
    const files = this.files()
    const removed = [...files.keys()].filter((it) => it === path || it.startsWith(`${path}/`))
    if (removed.length === 0) fail('notFound', `Couldn't find ${path}`)
    for (const it of removed) files.delete(it)
    this.changed(removed)
  }

  async renamePath(from: string, to: string): Promise<void> {
    checkWritable(from)
    checkWritable(to)
    const files = this.files()
    const moved = [...files.keys()].filter((it) => it === from || it.startsWith(`${from}/`))
    if (moved.length === 0) fail('notFound', `Couldn't find ${from}`)
    if ([...files.keys()].some((it) => it === to || it.startsWith(`${to}/`))) {
      fail('alreadyExists', `${to} already exists`)
    }
    const changed: string[] = []
    for (const old of moved) {
      const next = to + old.slice(from.length)
      files.set(next, files.get(old)!)
      files.delete(old)
      changed.push(old, next)
    }
    this.changed(changed)
  }

  // ---- packages --------------------------------------------------------------

  /**
   * As the Rust side's `package::open`: a relative location (`..` allowed)
   * naming a project beside the open one, or `git:<commit>`, a checkout in
   * the package cache.
   */
  private packageAt(location: string): { root: string; files: Map<string, FileContents> } {
    this.files()
    if (location.startsWith('git:')) {
      const commit = location.slice('git:'.length)
      if (!COMMIT.test(commit)) {
        fail('invalidPath', `"${location}" isn't a git package's location (git:<commit>)`)
      }
      const files = this.checkouts.get(commit)
      if (!files?.has('netherforge.json')) {
        fail('notFound', `${location} hasn't been fetched into the package cache`)
      }
      return { root: `/packages/git/checkouts/${commit}`, files }
    }
    if (
      !location ||
      location.startsWith('/') ||
      /[\\:\0]/.test(location) ||
      location.split('/').some((it) => it === '')
    ) {
      fail(
        'invalidPath',
        `"${location}" isn't a package folder relative to the project (like ../economy)`,
      )
    }
    const parts = this.openRoot!.split('/').filter(Boolean)
    for (const part of location.split('/')) {
      if (part === '..') parts.pop()
      else if (part !== '.') parts.push(part)
    }
    const root = `/${parts.join('/')}`
    const files = this.projects.get(root) ?? this.folderOfOpenProject(root)
    if (!files) fail('notFound', `There's no folder at ${location}`)
    if (!files.has('netherforge.json')) {
      fail('notFound', `${location} isn't a project (it has no netherforge.json)`)
    }
    return { root, files }
  }

  /**
   * A folder inside the open project (a package kept in it, like a template at
   * `templates/<namespace>`), its files by path inside it; undefined when
   * there's none there.
   */
  private folderOfOpenProject(root: string): Map<string, FileContents> | undefined {
    const prefix = `${this.openRoot}/`
    if (!root.startsWith(prefix)) return undefined
    const folder = `${root.slice(prefix.length)}/`
    const inside = [...(this.projects.get(this.openRoot!)?.entries() ?? [])]
      .filter(([path]) => path.startsWith(folder))
      .map(([path, contents]) => [path.slice(folder.length), contents] as const)
    return inside.length > 0 ? new Map(inside) : undefined
  }

  async packageFiles(location: string): Promise<PackageFiles> {
    const { root, files } = this.packageAt(location)
    const listed = [...files.entries()]
      .filter(([path]) => !path.startsWith('.netherforge/') && !path.split('/').includes('.git'))
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    return {
      root,
      files: await Promise.all(
        listed.map(async ([path, contents]) => ({ path, sha256: await sha256Hex(contents) })),
      ),
    }
  }

  async packageReadText(location: string, path: string): Promise<string> {
    checkPath(path)
    const contents = this.packageAt(location).files.get(path)
    if (contents === undefined) fail('notFound', `Couldn't read ${path}: no such file`)
    if (typeof contents === 'string') return contents
    try {
      return textDecoder.decode(contents)
    } catch {
      fail('invalid', `${path} isn't text`)
    }
  }

  async packageCopy(location: string, from: string, to: string): Promise<void> {
    checkPath(from)
    checkWritable(to)
    const source = this.packageAt(location).files
    const copied = [...source.keys()].filter((it) => it === from || it.startsWith(`${from}/`))
    if (copied.length === 0) fail('notFound', `Couldn't find ${from}`)
    const files = this.files()
    if ([...files.keys()].some((it) => it === to || it.startsWith(`${to}/`))) {
      fail('alreadyExists', `${to} already exists`)
    }
    const changed: string[] = []
    for (const path of copied) {
      const contents = source.get(path)!
      const next = to + path.slice(from.length)
      files.set(next, typeof contents === 'string' ? contents : contents.slice())
      changed.push(next)
    }
    this.changed(changed)
  }

  async packageFetchGit(url: string, rev: string | null, commit: string | null): Promise<string> {
    if (url.startsWith('-') || !GIT_URL.test(url)) {
      fail('invalid', `"${url}" isn't a repository NetherForge fetches from`)
    }
    if (rev !== null && (!GIT_REV.test(rev) || /\.\.|\/\/|\/$|\.$|\.lock$|(^|\/)\./.test(rev))) {
      fail('invalid', `"${rev}" isn't the name of a branch, a tag or a full commit`)
    }
    if (commit !== null) {
      if (!COMMIT.test(commit)) fail('invalid', `"${commit}" isn't a commit`)
      if (this.checkouts.has(commit)) return commit
    }
    // Reaching the repository the project names needs the project trusted.
    this.trustedRoot()
    this.gitFetchLog.push({ url, rev, commit })
    const repo = this.gitRepos[url]
    if (!repo) fail('network', `fatal: '${url}' does not appear to be a git repository`)
    const wanted =
      commit ?? repo.refs[rev ?? 'HEAD'] ?? (rev !== null && repo.commits[rev] ? rev : undefined)
    if (wanted === undefined) {
      fail(
        'network',
        `fatal: couldn't find remote ref ${rev ?? 'HEAD'} (is "${rev}" a branch, a tag or a full commit there?)`,
      )
    }
    const files = repo.commits[wanted]
    if (!files) fail('notFound', `${url} has no commit ${wanted} (was it force-pushed away?)`)
    this.checkouts.set(wanted, new Map(Object.entries(files)))
    return wanted
  }

  onFilesChanged(listener: (event: FsChangedEvent) => void): Promise<Unlisten> {
    return this.fsChanged.listen(listener)
  }

  private changed(paths: string[]) {
    for (const path of paths) {
      if (!path.startsWith('.netherforge/') && !path.startsWith('.git/'))
        this.pendingChanges.add(path)
    }
    if (this.flushTimer !== null || this.pendingChanges.size === 0) return
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null
      const paths = [...this.pendingChanges].sort()
      this.pendingChanges.clear()
      this.fsChanged.emit({ paths, rescan: false })
    }, this.debounce)
  }

  // ---- server --------------------------------------------------------------

  async serverState(): Promise<ServerState> {
    // While a workspace's network runs, the editor's own state is the attached server's (mirrored into it).
    return { ...this.state }
  }

  async eulaStatus(): Promise<EulaStatus> {
    return { accepted: this.eula, url: 'https://aka.ms/MinecraftEULA' }
  }

  async acceptEula(): Promise<void> {
    this.eula = true
  }

  async startServer(): Promise<void> {
    const root = this.trustedRoot()
    const workspace = this.findWorkspace(root)
    if (workspace?.network && this.workspaceNetworks.has(workspace.root)) {
      return this.startWorkspace(workspace)
    }
    const layout = this.layouts.get(root) ?? DEFAULT_LAYOUT
    // A network is planned (and refused) whole before anything starts, as the Rust side's `network::plan`.
    const peers = layout.enabled ? this.planNetwork(root, layout) : null
    if (!this.eula) fail('eulaRequired', 'Accept the Minecraft EULA before starting the dev server')
    if (this.state.phase !== 'stopped' && this.state.phase !== 'crashed')
      fail('busy', 'The dev server is already running')
    if (this.members.some((m) => isActive(m.state)))
      fail('busy', 'The dev network is already running')
    if (peers) this.startNetwork(peers)
    else this.clearNetwork()
    const minecraft = this.projectMinecraft()
    this.setState({ phase: 'preparing', minecraft, message: 'Downloading Paper' })
    const steps: [PrepareProgress['step'], string][] = [
      ['java', 'Java runtime'],
      ['paper', `Paper ${minecraft ?? ''}`.trim()],
      ['plugin', 'NetherForge plugin'],
    ]
    void (async () => {
      for (const [step, label] of steps) {
        for (let done = 0; done <= 4; done += 1) {
          this.prepare.emit({ step, label, done, total: 4 })
          await this.wait()
        }
      }
      this.setState({ phase: 'starting', message: null })
      this.output('stdout', `[Server] Starting minecraft server version ${minecraft ?? '?'}`)
      await this.wait()
      this.setState({ phase: 'running', port: this.settings.serverPort })
      this.output('stdout', '[Server] Done! For help, type "help"')
      await this.wait()
      this.setState({ bridgeConnected: true })
      this.testBridgeEvent('console', {
        items: [{ type: 'log', level: 'info', message: 'NetherForge loaded the project' }],
      })
    })()
  }

  async stopServer(): Promise<void> {
    // The rest of the network stops alongside, as the Rust side stops them all at once.
    const network = this.stopNetwork()
    // A workspace's network: the editor's state is the attached server's, which stops with it.
    if (this.state.phase === 'stopped' || this.members.some((m) => m.attached)) return network
    this.plugin.profiling(false)
    this.setState({ phase: 'stopping', bridgeConnected: false })
    await this.wait()
    this.output('stdout', '[Server] Stopping server')
    this.plugin.instances = []
    this.setState({ phase: 'stopped', port: null, message: null })
    await network
  }

  async runTests(filter?: string, member?: string): Promise<TestReport> {
    let report: TestReport | null
    if (member === undefined) {
      this.trustedRoot()
      report = this.testReport
    } else {
      const { root } = this.member(member)
      if (!this.isTrusted(root)) fail('untrusted', `"${root}" isn't trusted yet`)
      report = this.memberTestReports.get(member) ?? null
    }
    if (!report) fail('unavailable', 'This editor has no test runner')
    if (!filter) return report
    const wanted = report.results.filter((it) =>
      `${it.file}: ${it.name}`.toLowerCase().includes(filter.toLowerCase()),
    )
    const count = (status: TestStatus) => wanted.filter((it) => it.status === status).length
    return {
      ...report,
      results: wanted,
      passed: count('passed'),
      failed: count('failed'),
      errored: count('errored'),
    }
  }

  async serverCommand(line: string): Promise<void> {
    if (this.state.phase !== 'starting' && this.state.phase !== 'running')
      fail('notConnected', "The dev server isn't running")
    this.plugin.commandLog.push(line)
    const attached = this.members.find((m) => m.attached)
    if (attached) this.memberOutput(attached.name, `> ${line}`)
    else this.output('stdout', `> ${line}`)
  }

  bridgeRequest<M extends BridgeMethod>(
    method: M,
    ...params: BridgeParamsArg<M>
  ): Promise<BridgeResult<M>> {
    // The switch below can't narrow a generic, so the answer is typed here.
    const request = { method, params: params[0] } as BridgeCall
    this.bridgeLog.push(
      request.params === undefined ? ({ method: request.method } as BridgeCall) : request,
    )
    return this.plugin.answer(request) as Promise<BridgeResult<M>>
  }

  async dapSend(message: DebugProtocol.ProtocolMessage): Promise<void> {
    if (!this.state.bridgeConnected)
      fail('notConnected', "The NetherForge plugin isn't connected to the editor yet")
    this.debugAdapter.receive(message)
  }

  /** Every DAP request the UI sent the plugin's debug adapter, for assertions. */
  get dapLog(): DebugProtocol.Request[] {
    return this.debugAdapter.log
  }

  /** The breakpoint lines the debug adapter has, by file. */
  testBreakpoints(): Record<string, number[]> {
    return Object.fromEntries(this.debugAdapter.breakpoints)
  }

  /** A breakpoint (or a step, a pause, an error) holds the fake server (see `FakeDebugAdapter`). */
  testDebugStop(options: FakeStop = {}) {
    this.debugAdapter.stop(options)
  }

  async captureMap(
    source: { level: string; dimension: string },
    id: string,
    replace: boolean,
  ): Promise<void> {
    const files = this.files()
    // As the Rust side: a path first, then one segment that isn't hidden.
    checkPath(id)
    // Group folders (`(arenas)/duel`), then one segment that isn't hidden.
    const segments = id.split('/')
    const name = segments.pop()!
    if (name.startsWith('.') || !segments.every((it) => /^\([a-z0-9][a-z0-9_]{0,63}\)$/.test(it)))
      fail('invalid', `"${id}" can't name a map`)
    const folder = `maps/${id}`
    const existing = [...files.keys()].filter((it) => it.startsWith(`${folder}/`))
    if (existing.length > 0 && !replace) fail('alreadyExists', `${folder} already exists`)
    const level = this.serverFiles.get(source.level)
    if (level === undefined || !source.level.endsWith('/level.dat')) {
      fail('notFound', `${source.level} isn't a world's level.dat in the server's folder`)
    }
    const prefix = `${source.dimension}/`
    const copied: [string, FileContents][] = [[`${folder}/level.dat`, level]]
    for (const [path, contents] of this.serverFiles) {
      if (!path.startsWith(prefix)) continue
      const relative = path.slice(prefix.length)
      if (!isMapFile(relative)) continue
      copied.push([`${folder}/dimensions/minecraft/overworld/${relative}`, contents])
    }
    if (copied.length === 1)
      fail('notFound', `${source.dimension} isn't a world folder in the server's folder`)
    const size = (contents: FileContents) =>
      typeof contents === 'string' ? contents.length : contents.length
    const total = copied.reduce((sum, [, contents]) => sum + size(contents), 0)
    for (const path of existing) files.delete(path)
    let done = 0
    for (const [path, contents] of copied) {
      files.set(path, contents)
      done += size(contents)
      this.captureEvents.emit({ done, total })
    }
    this.changed([...existing, ...copied.map(([path]) => path)])
  }

  onCaptureProgress(listener: (event: CaptureProgress) => void): Promise<Unlisten> {
    return this.captureEvents.listen(listener)
  }

  /** A video's folder and what its `video.json` says, as the Rust side checks them. */
  private videoSettings(folder: string): {
    source: string
    width: number
    height: number
    fps: number
    audio: boolean
  } {
    checkPath(folder)
    const segments = folder.split('/')
    if (segments[0] !== 'videos' || segments.length < 2)
      fail('invalid', `${folder} isn't a video's folder`)
    const text = this.files().get(`${folder}/video.json`)
    if (text === undefined) fail('notFound', `${folder} has no video.json`)
    try {
      const json = JSON.parse(typeof text === 'string' ? text : textDecoder.decode(text))
      return {
        source: json.source,
        width: json.width,
        height: json.height,
        fps: json.fps ?? 20,
        audio: json.audio ?? true,
      }
    } catch {
      fail('invalid', `${folder}/video.json can't be read`)
    }
  }

  /**
   * As the Rust side converts, without ffmpeg: one second of frames, a sound when the file keeps one, the files
   * written (the frames' file a real header and nothing after) and the progress reported.
   */
  async convertVideo(folder: string): Promise<VideoConverted> {
    this.trustedRoot()
    const minecraft = this.state.minecraft
    if (!minecraft)
      fail(
        'invalid',
        "netherforge.json doesn't name a Minecraft release, so there are no map colours to convert to",
      )
    if (!this.ffmpeg) {
      fail(
        'unavailable',
        "Converting a video needs ffmpeg, and there's none on PATH: install it, or set where it is in Settings",
      )
    }
    const colors = this.game.get(minecraft)?.mapColors
    if (!colors?.length) {
      fail(
        'unavailable',
        `Converting a video needs Minecraft ${minecraft}'s map colours, which aren't exported yet`,
      )
    }
    const settings = this.videoSettings(folder)
    const files = this.files()
    const source = files.get(`${folder}/${settings.source}`)
    if (source === undefined)
      fail('notFound', `${folder} has no ${settings.source}: put the video there first`)
    const frames = settings.fps
    this.videoEvents.emit({ folder, stage: 'audio', done: 0, total: 0 })
    const removed = [...files.keys()].filter(
      (it) => it === `${folder}/audio.ogg` || it.startsWith(`${folder}/audio/`),
    )
    for (const path of removed) files.delete(path)
    this.videoEvents.emit({ folder, stage: 'frames', done: frames, total: frames })
    const header = nfvHeader({
      columns: settings.width,
      rows: settings.height,
      fps: settings.fps,
      frames,
      audio: settings.audio,
      source: await sha256(source),
    })
    files.set(`${folder}/video.nfv`, header)
    const written = [`${folder}/video.nfv`]
    if (settings.audio) {
      files.set(`${folder}/audio.ogg`, new Uint8Array([9]))
      files.set(`${folder}/audio/0.ogg`, new Uint8Array([0]))
      written.push(`${folder}/audio.ogg`, `${folder}/audio/0.ogg`)
    }
    this.videoEvents.emit({ folder, stage: 'writing', done: frames, total: frames })
    this.changed([...removed, ...written])
    return { frames, seconds: 1, audio: settings.audio, bytes: header.length }
  }

  onVideoProgress(listener: (event: ConvertProgress) => void): Promise<Unlisten> {
    return this.videoEvents.listen(listener)
  }

  async videoInfo(folder: string): Promise<VideoInfo> {
    const settings = this.videoSettings(folder)
    const files = this.files()
    const source = files.get(`${folder}/${settings.source}`)
    const frames = files.get(`${folder}/video.nfv`)
    const header =
      frames === undefined
        ? null
        : readNfvHeader(typeof frames === 'string' ? new TextEncoder().encode(frames) : frames)
    if (!header) {
      return {
        converted: false,
        columns: 0,
        rows: 0,
        fps: 0,
        frames: 0,
        audio: false,
        bytes: 0,
        sourcePresent: source !== undefined,
        sourceChanged: null,
      }
    }
    return {
      converted: true,
      columns: header.columns,
      rows: header.rows,
      fps: header.fps,
      frames: header.frames,
      audio: header.audio,
      bytes: frames!.length,
      sourcePresent: source !== undefined,
      sourceChanged: source === undefined ? null : !sameBytes(await sha256(source), header.source),
    }
  }

  onServerState(listener: (state: ServerState) => void): Promise<Unlisten> {
    return this.serverStateEvents.listen(listener)
  }

  onServerOutput(listener: (event: ServerOutputEvent) => void): Promise<Unlisten> {
    return this.serverOutput.listen(listener)
  }

  onPrepareProgress(listener: (event: PrepareProgress) => void): Promise<Unlisten> {
    return this.prepare.listen(listener)
  }

  onBridgeEvent<E extends BridgeEventName>(
    event: E,
    listener: (params: BridgeEvents[E]) => void,
  ): Promise<Unlisten> {
    return this.bridge.listen((it) => {
      if (it.event === event) listener(it.params as BridgeEvents[E])
    })
  }

  // ---- Minecraft -------------------------------------------------------------

  async findInstalls(): Promise<MinecraftInstall[]> {
    return this.installs.map((it) => ({ ...it, versions: [...it.versions] }))
  }

  async cacheStatus(version: string): Promise<CacheStatus> {
    checkVersion(version)
    return { version, client: this.clients.has(version), server: this.game.has(version) }
  }

  async importClient(version: string): Promise<void> {
    checkVersion(version)
    const total = 5
    for (let done = 0; done <= total; done += 1) {
      this.importEvents.emit({ version, done, total })
      await this.wait()
    }
    this.clients.add(version)
    this.cacheEvents.emit(await this.cacheStatus(version))
  }

  onImportProgress(listener: (event: ImportProgress) => void): Promise<Unlisten> {
    return this.importEvents.listen(listener)
  }

  onCacheChanged(listener: (status: CacheStatus) => void): Promise<Unlisten> {
    return this.cacheEvents.listen(listener)
  }

  /** Nothing listens in memory; the status is what the real backend would report. */
  async mcpStatus(): Promise<McpStatus> {
    const { mcpEnabled: enabled, mcpPort: port } = this.settings
    return {
      enabled,
      port,
      url: enabled ? `http://127.0.0.1:${port}/mcp` : null,
      error: null,
      token: MEMORY_MCP_TOKEN,
    }
  }

  /** As the Rust pipe: a response completes the request it answers; anything else is dropped. */
  async mcpSend(message: unknown): Promise<void> {
    const response = message as { id?: unknown; method?: unknown } & McpTestResponse
    if (response.method !== undefined || typeof response.id !== 'number') return
    const waiter = this.mcpWaiting.get(response.id)
    this.mcpWaiting.delete(response.id)
    waiter?.(response)
  }

  onMcpMessage(listener: (message: McpMessage) => void): Promise<Unlisten> {
    return this.mcpEvents.listen(listener)
  }

  /** There's no language server in memory: Lua gets highlighting only, as in an editor without LuaLS. */
  async lualsStart(): Promise<LualsStarted> {
    this.trustedRoot()
    fail('unavailable', 'lua-language-server only runs in the desktop editor')
  }

  async lualsSend(): Promise<void> {
    fail('unavailable', 'lua-language-server has stopped')
  }

  async lualsStop(): Promise<void> {}

  async onLualsMessage(): Promise<Unlisten> {
    return () => {}
  }

  async onLualsExit(): Promise<Unlisten> {
    return () => {}
  }

  /** Whether the UI's MCP server has connected (listens for `mcp://message`). */
  get testMcpConnected(): boolean {
    return this.mcpEvents.listened
  }

  /** Plays the Rust pipe: hands one JSON-RPC message to the UI's MCP server. */
  testMcpMessage(message: unknown, protocolVersion: string | null = null): void {
    // The Rust pipe keeps agents out while the open project isn't trusted: a request gets
    // the refusal, nothing reaches the UI's tools.
    if (this.openRoot && !this.isTrusted(this.openRoot)) {
      const request = message as { id?: number; method?: unknown }
      if (typeof request.method === 'string' && typeof request.id === 'number') {
        this.mcpWaiting.get(request.id)?.({
          error: { code: -32001, message: `"${this.openRoot}" isn't trusted yet` },
        })
        this.mcpWaiting.delete(request.id)
      }
      return
    }
    this.mcpEvents.emit({ message, protocolVersion })
  }

  /**
   * Plays a coding agent: sends a JSON-RPC request to the UI's MCP server and
   * resolves with its response.
   */
  testMcpCall(
    method: string,
    params: unknown = {},
    protocolVersion: string | null = null,
  ): Promise<McpTestResponse> {
    const id = this.mcpNext++
    const answer = new Promise<McpTestResponse>((resolve) => this.mcpWaiting.set(id, resolve))
    this.testMcpMessage({ jsonrpc: '2.0', id, method, params }, protocolVersion)
    return answer
  }

  async gameData(version: string): Promise<GameDataBundle | null> {
    checkVersion(version)
    return this.game.get(version) ?? null
  }

  async glyphAdvances(version: string): Promise<Record<string, number> | null> {
    checkVersion(version)
    if (!this.clients.has(version)) return null
    return { ...(this.advances[version] ?? {}) }
  }

  async openLauncher(): Promise<void> {
    this.launcherOpened += 1
  }

  assetUrl(version: string, path: string): string {
    const served = this.clients.has(version) ? this.assets[path] : undefined
    // Anything else points at the real protocol's URL, which nothing serves
    // here: the viewport degrades to placeholders, as it does before a real
    // import. (The app's CSP allows that scheme, and only that one.)
    return served ?? `nfasset://localhost/${encodeURIComponent(version)}/${path}`
  }

  // ---- the outside world, for tests ------------------------------------------

  /** The open project's text files as they are "on disk" (binary files are left out). */
  /** Chooses a menu item, as clicking it in the macOS menu bar would. */
  testMenuAction(id: string) {
    for (const listener of this.menuListeners) listener(id)
  }

  testFiles(): Record<string, string> {
    return Object.fromEntries(
      [...this.files()].filter((entry): entry is [string, string] => typeof entry[1] === 'string'),
    )
  }

  /** A file's bytes, base64 (so Playwright can carry them out of the page), or null. */
  testBytes(path: string): string | null {
    const contents = this.files().get(path)
    if (contents === undefined) return null
    return bytesToBase64(
      typeof contents === 'string' ? new TextEncoder().encode(contents) : contents,
    )
  }

  /** Another program writes a binary file, given as base64. */
  testWriteBytes(path: string, base64: string) {
    this.files().set(path, base64ToBytes(base64))
    this.changed([path])
  }

  /** Another program writes a file (git, an agent, another editor). */
  testWrite(path: string, text: string) {
    this.files().set(path, text)
    this.changed([path])
  }

  /** Another program writes a file and the watcher misses it, saying only that it lost events. */
  testWriteMissed(path: string, text: string) {
    this.files().set(path, text)
    setTimeout(() => this.fsChanged.emit({ paths: [], rescan: true }), this.debounce)
  }

  /** Another program deletes a file or folder. */
  testDelete(path: string) {
    const files = this.files()
    const removed = [...files.keys()].filter((it) => it === path || it.startsWith(`${path}/`))
    for (const it of removed) files.delete(it)
    this.changed(removed)
  }

  /** Another program renames a file or folder (`git mv`, a file manager). */
  testRename(from: string, to: string) {
    const files = this.files()
    const changed: string[] = []
    for (const old of [...files.keys()].filter((it) => it === from || it.startsWith(`${from}/`))) {
      const next = to + old.slice(from.length)
      files.set(next, files.get(old)!)
      files.delete(old)
      changed.push(old, next)
    }
    this.changed(changed)
  }

  /** The plugin sends a notification. */
  testBridgeEvent<E extends BridgeEventName>(event: E, params: BridgeEvents[E]) {
    this.bridge.emit({ event, params })
  }

  /** From now on the test runner answers with [report] (filtered by the test's file and name, as the real one does). */
  testTestReport(report: TestReport | null, member?: string) {
    if (member === undefined) this.testReport = report
    else if (report) this.memberTestReports.set(member, report)
    else this.memberTestReports.delete(member)
  }

  /** From now on the plugin's answer to a reload says the server must restart. */
  testReloadRestarts(on = true) {
    this.plugin.reloadRestarts = on
  }

  /** The server prints a line. */
  testServerOutput(line: string, stream: 'stdout' | 'stderr' = 'stdout') {
    this.output(stream, line)
  }

  /** A player joins the fake server, standing at a block position (or moves there). */
  testPlayer(player: string, world: string, x: number, y: number, z: number) {
    this.plugin.players = [
      ...this.plugin.players.filter((it) => it.player !== player),
      { player, world, x, y, z },
    ]
  }

  /** The fake server loads another world. */
  testWorld(name: string, environment = 'normal') {
    if (!this.plugin.worlds.some((it) => it.name === name)) {
      this.plugin.worlds = [...this.plugin.worlds, { name, environment, main: false }]
    }
  }

  /** Files appear in the dev server's folder, as base64 (a world the server saved). */
  testServerFiles(files: Record<string, string>) {
    for (const [path, base64] of Object.entries(files))
      this.serverFiles.set(path, base64ToBytes(base64))
  }

  /** Jumps the fake server straight to running with the bridge up. */
  testConnect() {
    this.eula = true
    this.setState({
      phase: 'running',
      minecraft: this.projectMinecraft(),
      port: this.settings.serverPort,
      bridgeConnected: true,
      message: null,
    })
  }

  // ---- dev network -----------------------------------------------------------

  async networkLayout(): Promise<NetworkLayout> {
    const root = this.openRoot
    if (!root) fail('noProject', 'No project is open')
    return structuredClone(this.layouts.get(root) ?? DEFAULT_LAYOUT)
  }

  async networkSetLayout(layout: NetworkLayout): Promise<void> {
    const root = this.openRoot
    if (!root) fail('noProject', 'No project is open')
    const names = [layout.name]
    for (const server of layout.servers) {
      names.push(server.name)
      if (!server.project.trim())
        fail('invalid', `Server "${server.name}" runs no project: pick its folder`)
    }
    names.forEach((name, i) => {
      if (!SERVER_NAME.test(name))
        fail(
          'invalid',
          `"${name}" can't name a server: use lowercase letters, digits, _ and - (at most 32)`,
        )
      if (names.slice(0, i).includes(name)) fail('invalid', `Two servers are named "${name}"`)
      if (name === 'proxy')
        fail('invalid', '"proxy" is the network\'s proxy: name the server something else')
    })
    if (layout.servers.length > MAX_NETWORK_SERVERS)
      fail('invalid', `A dev network runs at most ${MAX_NETWORK_SERVERS} other servers`)
    this.layouts.set(root, structuredClone(layout))
  }

  async networkCheck(): Promise<NetworkServerCheck[]> {
    const root = this.openRoot
    if (!root) fail('noProject', 'No project is open')
    const layout = this.layouts.get(root) ?? DEFAULT_LAYOUT
    return layout.servers.map((server) => {
      const check: NetworkServerCheck = {
        name: server.name,
        root: null,
        projectName: null,
        minecraft: null,
        trusted: false,
        problem: null,
      }
      let found: string
      try {
        found = this.resolveNetworkProject(root, server.project)
      } catch (error) {
        check.problem = (error as Error).message
        return check
      }
      const manifest = this.projects.get(found)?.get('netherforge.json')
      const text = typeof manifest === 'string' ? manifest : null
      check.root = found
      check.trusted = this.isTrusted(found)
      check.projectName = projectName(found, text ?? undefined)
      check.minecraft = manifestField(text, 'minecraft')
      if (!check.minecraft) check.problem = 'Its netherforge.json names no Minecraft version'
      return check
    })
  }

  async networkTrust(name: string): Promise<void> {
    const root = this.trustedRoot()
    const server = (this.layouts.get(root) ?? DEFAULT_LAYOUT).servers.find((it) => it.name === name)
    if (!server) fail('notFound', `The dev network has no server "${name}"`)
    this.trustedRoots.add(this.resolveNetworkProject(root, server.project))
  }

  async networkMembers(): Promise<NetworkMember[]> {
    return structuredClone(this.members)
  }

  async networkCommand(name: string, line: string): Promise<void> {
    const member = this.members.find((it) => it.name === name)
    if (!member) fail('notFound', `The dev network has no server "${name}"`)
    if (member.state.phase !== 'starting' && member.state.phase !== 'running')
      fail('notConnected', "The dev server isn't running")
    this.networkOutputEvents.emit({ name, stream: 'stdout', line: `> ${line.trim()}` })
  }

  onNetworkMembers(listener: (event: NetworkMembers) => void): Promise<Unlisten> {
    return this.networkMembersEvents.listen(listener)
  }

  onNetworkMember(listener: (member: NetworkMember) => void): Promise<Unlisten> {
    return this.networkMemberEvents.listen(listener)
  }

  onNetworkOutput(listener: (event: NetworkOutputEvent) => void): Promise<Unlisten> {
    return this.networkOutputEvents.listen(listener)
  }

  /** Where [project] (a layout's server's) is, from [root]: as the Rust side's `layout::resolve_project`. */
  private resolveNetworkProject(root: string, project: string): string {
    const parts = project.trim().startsWith('/') ? [] : root.split('/').filter(Boolean)
    for (const part of project.trim().split('/')) {
      if (part === '..') parts.pop()
      else if (part && part !== '.') parts.push(part)
    }
    const found = `/${parts.join('/')}`
    const files = this.projects.get(found)
    if (!files) fail('notFound', `There's no folder ${found}`)
    if (!files.has('netherforge.json'))
      fail('notFound', `${found} isn't a NetherForge project: it has no netherforge.json`)
    return found
  }

  /** Each other server's name and project, or the refusal Start gives: every folder first, then trust. */
  private planNetwork(root: string, layout: NetworkLayout): { name: string; project: string }[] {
    const peers = layout.servers.map((server) => {
      try {
        return { name: server.name, project: this.resolveNetworkProject(root, server.project) }
      } catch (error) {
        fail('notFound', `Server "${server.name}": ${(error as Error).message}`)
      }
    })
    for (const peer of peers) {
      if (!this.isTrusted(peer.project))
        fail(
          'untrusted',
          `Server "${peer.name}" runs ${peer.project}, which isn't trusted yet: trust it in the Network panel first`,
        )
    }
    return peers
  }

  /** The workspace's dev network: a proxy and every server its file names, attached to the open project's. */
  private startWorkspace(workspace: WorkspaceInfo) {
    const network = workspace.network!
    if (network.servers.length === 0) fail('invalid', "The workspace's network has no servers yet")
    if (!this.eula) fail('eulaRequired', 'Accept the Minecraft EULA before starting the dev server')
    if (this.state.phase !== 'stopped' && this.state.phase !== 'crashed')
      fail('busy', 'The dev server is already running')
    if (this.members.some((m) => isActive(m.state)))
      fail('busy', 'The dev server is already running')
    const servers = [...network.servers].sort(
      (a, b) => Number(b.name === network.arrival) - Number(a.name === network.arrival),
    )
    const peers = servers.map((server) => {
      const member = workspace.members.find((m) => m.path === server.project.replace(/\/+$/, ''))
      if (!member)
        fail(
          'notFound',
          `Server "${server.name}" runs ${server.project}, which isn't one of the workspace's projects`,
        )
      return { name: server.name, project: member.root }
    })
    const attach = peers.find((peer) => peer.project === this.openRoot)?.name ?? null
    this.startNetwork(peers, attach)
    if (!attach) this.attach(null)
  }

  /**
   * Attaches the editor to the member [name] (or none), as the Rust side does: its state is the
   * editor's own (`server://state`), and `network://members` moves the flag.
   */
  private attach(name: string | null) {
    for (const member of this.members) member.attached = member.name === name
    const attached = this.members.find((m) => m.attached)
    this.setState(
      attached
        ? { ...attached.state }
        : {
            phase: 'stopped',
            port: null,
            bridgeConnected: false,
            message: 'No server of the dev network runs this project',
          },
    )
    this.networkMembersEvents.emit({ members: structuredClone(this.members) })
  }

  /** The proxy and [peers] go through their phases as the main server does. */
  private startNetwork(peers: { name: string; project: string }[], attach: string | null = null) {
    const stopped: ServerState = {
      phase: 'stopped',
      minecraft: null,
      port: null,
      bridgeConnected: false,
      message: null,
    }
    this.members = [
      { name: 'proxy', kind: 'proxy', project: null, state: { ...stopped }, attached: false },
      ...peers.map((peer) => ({
        name: peer.name,
        kind: 'server' as const,
        project: peer.project,
        state: { ...stopped },
        attached: peer.name === attach,
      })),
    ]
    this.networkMembersEvents.emit({ members: structuredClone(this.members) })
    for (const [i, member] of this.members.entries()) {
      const manifest = member.project
        ? this.projects.get(member.project)?.get('netherforge.json')
        : undefined
      const minecraft = manifestField(typeof manifest === 'string' ? manifest : null, 'minecraft')
      void (async () => {
        if (member.kind === 'server' && !minecraft) {
          this.setMember(member.name, {
            message:
              'netherforge.json has no usable Minecraft version: "minecraft" must name a release, like "26.3"',
          })
          return
        }
        this.setMember(member.name, { phase: 'preparing', minecraft, message: 'Preparing' })
        await this.wait()
        this.setMember(member.name, {
          phase: 'starting',
          port: member.kind === 'proxy' ? this.settings.serverPort : 40000 + i,
          message: null,
        })
        await this.wait()
        this.memberOutput(member.name, '[00:00:00 INFO]: Done (0.1s)!')
        this.setMember(member.name, { phase: 'running' })
        if (member.kind === 'server') {
          await this.wait()
          this.setMember(member.name, { bridgeConnected: true })
        }
      })()
    }
  }

  private async stopNetwork() {
    const running = this.members.filter(
      (it) => it.state.phase !== 'stopped' && it.state.phase !== 'crashed',
    )
    for (const member of running)
      this.setMember(member.name, { phase: 'stopping', bridgeConnected: false })
    if (running.length === 0) return
    await this.wait()
    for (const member of running)
      this.setMember(member.name, { phase: 'stopped', port: null, message: null })
  }

  private clearNetwork() {
    if (this.members.length === 0) return
    this.members = this.members.map((m) => ({ ...m, attached: false }))
    this.members = []
    this.networkMembersEvents.emit({ members: [] })
  }

  private setMember(name: string, patch: Partial<ServerState>) {
    const member = this.members.find((it) => it.name === name)
    if (!member) return
    member.state = { ...member.state, ...patch }
    this.networkMemberEvents.emit(structuredClone(member))
    // The attached member's state is the editor's own.
    if (member.attached) this.setState(patch)
  }

  private memberOutput(name: string, line: string) {
    this.networkOutputEvents.emit({ name, stream: 'stdout', line })
    // The attached member's console is the editor's own.
    if (this.members.find((m) => m.name === name)?.attached) this.output('stdout', line)
  }

  /** A line in the console of the network's member [name], as its process would print it. */
  testNetworkOutput(name: string, line: string) {
    this.memberOutput(name, line)
  }

  // ---- internals -------------------------------------------------------------

  private setState(patch: Partial<ServerState>) {
    // The debugger's session is the bridge connection's.
    if (patch.bridgeConnected === false) this.debugAdapter.reset()
    this.state = { ...this.state, ...patch }
    this.serverStateEvents.emit({ ...this.state })
  }

  private output(stream: 'stdout' | 'stderr', line: string) {
    this.serverOutput.emit({ stream, line })
  }

  private wait(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, this.serverDelay))
  }

  private projectMinecraft(): string | null {
    return manifestField(this.manifest(), 'minecraft')
  }

  /** The open project's `netherforge.json`, as text. */
  private manifest(): string | null {
    const manifest = this.openRoot
      ? this.projects.get(this.openRoot)?.get('netherforge.json')
      : null
    return typeof manifest === 'string' ? manifest : null
  }
}

/** [text] as JSON, or an empty object when it isn't (or there's none): read as the Rust side reads leniently. */
function readLeniently<T extends object>(text: string | undefined): T {
  try {
    const value: unknown = JSON.parse(text ?? '{}')
    return (value && typeof value === 'object' ? value : {}) as T
  } catch {
    return {} as T
  }
}

/** [root] with `.` and `..` resolved, as the Rust side's canonical paths are. */
function normalizeRoot(root: string): string {
  const parts: string[] = []
  for (const part of root.split('/')) {
    if (part === '..') parts.pop()
    else if (part && part !== '.') parts.push(part)
  }
  return `/${parts.join('/')}`
}

/** Whether a server is up, or on its way up or down. */
function isActive(state: ServerState): boolean {
  return state.phase !== 'stopped' && state.phase !== 'crashed'
}

function projectName(root: string, manifest: string | undefined): string {
  try {
    const name = (JSON.parse(manifest ?? '') as { name?: unknown }).name
    if (typeof name === 'string' && name) return name
  } catch {
    // Fall back to the folder name, as the Rust side does.
  }
  return root.split('/').filter(Boolean).pop() ?? root
}

/** As the Rust side's `scope::is_reserved_name`: a device name Windows reserves, in any case, with or without an extension. */
function isReservedName(segment: string): boolean {
  const stem = segment.split('.')[0]!.replace(/ +$/, '').toLowerCase()
  return /^(con|prn|aux|nul|(com|lpt)[1-9\u00b9\u00b2\u00b3])$/.test(stem)
}

/**
 * As the Rust side's `scope::is_named`: [segment] is [name] (lowercase) on a
 * case-insensitive file system, after dropping the trailing dots and spaces
 * Windows ignores. Unicode lowercase, the same as Rust's `to_lowercase`.
 */
function isNamed(segment: string, name: string): boolean {
  return segment.replace(/[. ]+$/, '').toLowerCase() === name
}

async function sha256(contents: FileContents): Promise<Uint8Array> {
  // A copy, so it's a plain `ArrayBuffer`'s whatever the file's bytes are a view of.
  const bytes =
    typeof contents === 'string' ? new TextEncoder().encode(contents) : new Uint8Array(contents)
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))
}

function checkPath(path: string) {
  const parts = path.split('/')
  if (
    !path ||
    path.startsWith('/') ||
    /[\\:\0]/.test(path) ||
    parts.some((it) => it === '..' || it === '.' || it === '') ||
    parts.some((it) => /[. ]$/.test(it) || isReservedName(it))
  ) {
    fail('invalidPath', `"${path}" isn't a project path`)
  }
}

/**
 * As the Rust side's `fs::serve`: what the `nfproject` protocol serves of a
 * project (or, not [project], of a package): a project path that isn't
 * hidden, or the project's own cached thumbnails.
 */
function isServed(path: string, project: boolean): boolean {
  try {
    checkPath(path)
  } catch {
    return false
  }
  const [top, folder] = path.split('/')
  if (project && isNamed(top!, '.netherforge') && folder === 'thumbnails') return true
  return !isNamed(top!, '.netherforge') && !path.split('/').some((it) => isNamed(it, '.git'))
}

/** As the Rust side's `map::is_excluded`, inverted: what a map keeps of a world's folder. */
function isMapFile(relative: string): boolean {
  const parts = relative.split('/')
  const name = parts[parts.length - 1]!
  if (['session.lock', 'uid.dat', 'level.dat_old', 'paper-world.yml'].includes(name)) return false
  if (
    parts.slice(0, -1).some((it) => ['players', 'playerdata', 'stats', 'advancements'].includes(it))
  )
    return false
  return !relative.startsWith('data/paper/')
}

/** As the Rust side's `fs::is_writable`: never `.git`, nothing in `.netherforge/` but what the UI regenerates. */
function checkWritable(path: string) {
  const [top, folder, inner] = path.split('/')
  const writable =
    !path.split('/').some((it) => isNamed(it, '.git')) &&
    (!isNamed(top!, '.netherforge') ||
      (inner !== undefined && ['schema', 'docs', 'bin', 'thumbnails', 'luals'].includes(folder!)))
  if (!writable) fail('readOnly', `${path} can't be changed from the editor`)
  checkPath(path)
}

/** As the Rust side's `version::is_release`: `26.3`, `1.21.11`. */
function checkVersion(version: string) {
  if (!/^\d+\.\d+(\.\d+)?$/.test(version))
    fail('invalid', `"${version}" isn't a Minecraft release version`)
}

/** As the Rust side's `Settings::validate`. */
function validateSettings(settings: Settings) {
  if (settings.serverMemoryMb < 512)
    fail('invalid', 'The dev server needs at least 512 MB of memory')
  if (settings.serverPort === 0) fail('invalid', 'Pick a server port between 1 and 65535')
  if (settings.mcpPort === 0) fail('invalid', 'Pick an MCP port between 1 and 65535')
  if (settings.mcpEnabled && settings.mcpPort === settings.serverPort)
    fail('invalid', "The MCP port can't be the dev server's port")
  for (const arg of settings.serverJvmArgs) {
    const why = jvmArgumentProblem(arg)
    if (why) fail('jvmArgument', `JVM argument ${JSON.stringify(arg)} isn't allowed: ${why}`)
  }
}
