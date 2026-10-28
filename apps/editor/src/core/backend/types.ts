/**
 * The contract between the editor's UI and its Rust backend.
 *
 * Every Tauri command and event, and every type they carry, is generated
 * from the Rust side by tauri-specta (`generated/bindings.ts`, from
 * `src-tauri/src/commands/bindings.rs`; `pnpm generate` rewrites it). This
 * file re-exports those types and adds what only the UI has: the `Backend`
 * interface (which also covers Tauri's own plugins: dialogs, the updater,
 * menus), its errors and the menu model. The UI never calls `invoke`
 * directly: it goes through a `Backend`, which is `TauriBackend` in the app
 * and `MemoryBackend` in unit and e2e tests; `contract.json` is what both
 * must do.
 *
 * Paths:
 * - "project paths" are relative to the open project root, `/`-separated on
 *   every OS. The backend refuses any that escape the root.
 * - "absolute paths" are only used to open/create a project and to pick a
 *   client jar, and come from a native dialog or the recent list.
 */
import type { DebugProtocol } from '@vscode/debugprotocol'
import type { GameDataBundle } from '@netherforge/format/types'
import type {
  BridgeEventName,
  BridgeEvents,
  BridgeMethod,
  BridgeParamsArg,
  BridgeResult,
} from '@/core/bridge/client'
import type {
  AppInfo,
  CacheStatus,
  CaptureProgress,
  ConvertProgress,
  ErrorCode,
  EulaStatus,
  FileEntry,
  FsChangedEvent,
  ImportProgress,
  LualsExit,
  LualsMessage,
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
  WorkspaceInfo,
  RecentProject,
  ServerOutputEvent,
  ServerState,
  Settings,
  TestReport,
  VideoConverted,
  VideoInfo,
} from './generated/bindings'

export type { BridgeEventName, BridgeEvents, BridgeMethod, BridgeParamsArg, BridgeResult }
export type {
  AppInfo,
  CacheStatus,
  CaptureProgress,
  ConvertProgress,
  VideoConverted,
  VideoInfo,
  CommandError,
  ErrorCode,
  EulaStatus,
  FileEntry,
  FsChangedEvent,
  ImportProgress,
  LualsExit,
  LualsMessage,
  LualsStarted,
  McpMessage,
  McpStatus,
  MemberKind,
  MinecraftInstall,
  NetworkLayout,
  NetworkMember,
  NetworkMembers,
  NetworkOutputEvent,
  NetworkServer,
  NetworkServerCheck,
  PackageFiles,
  PrepareProgress,
  PrepareStep,
  ProjectInfo,
  RecentProject,
  ServerOutputEvent,
  ServerPhase,
  ServerState,
  Settings,
  TestPlace,
  TestReport,
  TestResult,
  TestStatus,
  WorkspaceInfo,
  WorkspaceMember,
  WorkspaceNetworkInfo,
  WorkspaceServer,
} from './generated/bindings'

/**
 * What every backend rejects with: the Rust side's `{ code, message }`. The
 * message is a sentence for people; code that reacts to a failure checks
 * `code` (`isBackendError(e, 'notFound')`).
 */
export class BackendError extends Error {
  readonly code: ErrorCode

  constructor(code: ErrorCode, message: string) {
    super(message)
    this.name = 'BackendError'
    this.code = code
  }
}

/** Whether [error] is a backend rejection, with [code] if one is given. */
export function isBackendError(error: unknown, code?: ErrorCode): error is BackendError {
  return error instanceof BackendError && (code === undefined || error.code === code)
}

export type WindowAction = 'minimize' | 'toggleMaximize' | 'close'

export type Unlisten = () => void

/**
 * The app's menus (File, Edit, View…), built by the UI from its commands.
 * macOS shows them in its menu bar (`setAppMenu`); Windows and Linux draw
 * the same model in the title bar, so nothing here reaches their backend.
 */
export interface AppMenu {
  label: string
  items: MenuEntry[]
}

export type MenuEntry =
  | MenuCommandEntry
  | { kind: 'submenu'; label: string; enabled: boolean; items: MenuEntry[] }
  | { kind: 'separator' }
  /** One the OS implements itself (macOS's Services, Hide, Quit; the clipboard). */
  | { kind: 'native'; role: NativeMenuRole }

export interface MenuCommandEntry {
  kind: 'item'
  /** What `onMenuAction` reports when it's chosen. */
  id: string
  label: string
  /** `Mod+Shift+S`: Mod is Cmd on macOS and Ctrl elsewhere; keys as `KeyboardEvent.key`. */
  shortcut?: string
  enabled: boolean
  /** Set for a toggle (a dock that's shown or hidden). */
  checked?: boolean
}

export type NativeMenuRole =
  | 'about'
  | 'services'
  | 'hide'
  | 'hideOthers'
  | 'showAll'
  | 'quit'
  | 'cut'
  | 'copy'
  | 'paste'
  | 'minimize'
  | 'maximize'
  | 'fullscreen'

/** A newer editor release, found by the updater. */
export interface UpdateInfo {
  version: string
  currentVersion: string
  /** Release notes, Markdown. */
  notes: string | null
  /** ISO 8601. */
  date: string | null
}

export interface UpdateProgress {
  /** Bytes downloaded so far / in total (null when the server doesn't say). */
  downloaded: number
  total: number | null
}

/**
 * Everything the UI can ask of the backend. In `TauriBackend` each method is
 * one generated command or event, or a Tauri plugin's API (noted here).
 */
export interface Backend {
  appInfo(): Promise<AppInfo>
  getSettings(): Promise<Settings>
  setSettings(settings: Settings): Promise<void>
  mcpStatus(): Promise<McpStatus>
  /** A JSON-RPC message from the UI's MCP server: a response reaches the agent that asked; anything else is dropped. */
  mcpSend(message: unknown): Promise<void>
  onMcpMessage(listener: (message: McpMessage) => void): Promise<Unlisten>
  /**
   * Opens an http(s) URL in the user's browser (the EULA, docs). A webview
   * can't navigate away to one itself.
   */
  openExternal(url: string): Promise<void> // (opener plugin: openUrl)
  /** The title bar's own buttons, where the window has no native frame (Windows). */
  windowAction(action: WindowAction): Promise<void> // (core window API)
  /** Replaces the OS menu bar (macOS); its items report through `onMenuAction`. */
  setAppMenu(menus: AppMenu[]): Promise<void> // (core menu API)
  onMenuAction(listener: (id: string) => void): Unlisten // (core menu API: item actions)
  /** Scales the whole UI; 1 is actual size. */
  setZoom(factor: number): Promise<void> // (core webview API: setZoom)
  /**
   * Asks the release endpoint for a newer editor; null when this is the
   * newest. Rejects when the endpoint can't be reached or has no signed
   * release yet; callers checking quietly ignore that.
   */
  checkForUpdate(): Promise<UpdateInfo | null> // (updater plugin: check)
  /**
   * Downloads and installs the update the last check found, then restarts
   * the editor (so on success this never resolves in practice). The caller
   * stops the dev server first.
   */
  installUpdate(onProgress?: (progress: UpdateProgress) => void): Promise<void> // (updater plugin: downloadAndInstall; process plugin: relaunch)

  // Projects
  recentProjects(): Promise<RecentProject[]>
  /** Opens a native folder picker. Null if cancelled. */
  pickFolder(title: string): Promise<string | null> // (dialog plugin)
  /** Opens a native file picker for a .jar. Null if cancelled. */
  pickJar(title: string): Promise<string | null> // (dialog plugin)
  /** Fails if the folder has no netherforge.json. Starts watching it. */
  openProject(root: string): Promise<ProjectInfo>
  /** Writes [files] (project paths → text) into an empty or new folder, then opens it. */
  createProject(root: string, files: Record<string, string>): Promise<ProjectInfo>
  closeProject(): Promise<void>
  /**
   * Trusts the open project (or, `false`, stops trusting it, stopping its dev server), and
   * answers it as opened. Until it's trusted, the backend refuses its dev server, its
   * lua-language-server, fetching its git packages and agents' tools (`untrusted`).
   */
  trustProject(trusted: boolean): Promise<ProjectInfo>

  // The open project's workspace (`netherforge-workspace.json` above it; `ProjectInfo.workspace`).
  // Each rejects `notFound` when the project isn't in one.
  /** Trusts the workspace, and so every project in it (or, `false`, stops, stopping the dev server); answers the project. */
  workspaceTrust(trusted: boolean): Promise<ProjectInfo>
  /** The workspace file's text, for format to read. */
  workspaceRead(): Promise<string>
  /** Writes the workspace file (format's canonical text) and answers the workspace as it reads now. */
  workspaceWrite(text: string): Promise<WorkspaceInfo>
  /** The workspace as it reads now (its members may have changed); null when the project isn't in one. */
  workspaceInfo(): Promise<WorkspaceInfo | null>
  /** Every file of the workspace's project [member] (its path in the workspace), with its SHA-256, as `packageFiles` lists a package's. */
  workspaceMemberFiles(member: string): Promise<PackageFiles>
  workspaceMemberReadText(member: string, path: string): Promise<string>
  /** Writes a file of the workspace's project [member]: a package edited in place, a dependent's lock. */
  workspaceMemberWriteText(member: string, path: string, text: string): Promise<void>
  /** Makes a new project of the workspace at [path] (relative to its folder) from [files], without opening it; the folder must be missing or empty. */
  workspaceCreateMember(path: string, files: Record<string, string>): Promise<WorkspaceInfo>
  /** Writes the workspace file's JSON Schema where its `$schema` points (`.netherforge/schema/` beside it). */
  workspaceWriteSchema(text: string): Promise<void>
  /** Whether Start runs the workspace's dev network: this computer's choice. */
  workspaceNetworkEnabled(): Promise<boolean>
  workspaceSetNetworkEnabled(enabled: boolean): Promise<void>

  // Files in the open project
  listFiles(): Promise<FileEntry[]> // (excludes .git/ and .netherforge/)
  readText(path: string): Promise<string>
  /** Atomic: temp file + rename. Creates parent folders. */
  writeText(path: string, text: string): Promise<void>
  /** Atomic like `writeText`, for binary files (a PNG imported into a pack). */
  writeBytes(path: string, bytes: Uint8Array): Promise<void>
  deletePath(path: string): Promise<void> // (files or folders)
  renamePath(from: string, to: string): Promise<void>

  // Packages the project depends on: read-only folders, each named by its
  // location: relative to the project root (`../library`; `..` allowed), or
  // `git:<commit>`, a git package's checkout in the package cache. A location
  // that isn't a folder holding a netherforge.json rejects (`notFound`).
  /** Every file of the package (as `listFiles` lists a project's), each with the hex SHA-256 of its bytes. */
  packageFiles(location: string): Promise<PackageFiles>
  /** A file of the package, by its path inside it. */
  packageReadText(location: string, path: string): Promise<string>
  /** Copies a file or folder of the package ([from], inside it) into the project at [to]; rejects (`alreadyExists`) if [to] exists. */
  packageCopy(location: string, from: string, to: string): Promise<void>
  /**
   * Fetches a git dependency into the package cache: [url] at [rev] (its
   * default branch when null), or exactly [commit] when the lock pins one
   * (no network once it's there). Resolves to the commit, whose files are
   * then the package at `git:<commit>`. Rejects `invalid` (a URL, rev or
   * commit that isn't one), `network` (git couldn't fetch it), `notFound`
   * (the repository lacks the pinned commit), `unavailable` (no git).
   */
  packageFetchGit(url: string, rev: string | null, commit: string | null): Promise<string>
  onFilesChanged(listener: (event: FsChangedEvent) => void): Promise<Unlisten>
  /**
   * URL of a file in the open project, for `<img>` and textures: served by
   * the backend's `nfproject://` protocol, path-scoped like every file
   * command. [stamp] busts the webview's cache after the file changes.
   * Synchronous because it's just string building.
   */
  projectFileUrl(path: string, stamp?: number): string // (nfproject:// protocol)
  /** URL of a file of the package at [location] (its path inside it), as [projectFileUrl] is of the project's. */
  packageFileUrl(location: string, path: string, stamp?: number): string // (nfproject:// protocol)

  // Lua language server
  /**
   * Starts lua-language-server (LSP over stdio) in the open project,
   * replacing one that runs. Rejects when this editor has none (the memory
   * backend, a dev build without `tools/luals.mjs`): scripts then get no
   * language features beyond highlighting.
   */
  lualsStart(): Promise<LualsStarted>
  /** Sends one JSON-RPC message to the server of [generation]; rejects once it's gone. */
  lualsSend(generation: number, message: string): Promise<void>
  lualsStop(): Promise<void>
  onLualsMessage(listener: (event: LualsMessage) => void): Promise<Unlisten>
  onLualsExit(listener: (event: LualsExit) => void): Promise<Unlisten>

  // Dev server
  serverState(): Promise<ServerState>
  eulaStatus(): Promise<EulaStatus>
  acceptEula(): Promise<void>
  /** Downloads Java and Paper if needed, sets up the project's server folder (in the data dir, never the project), starts it. */
  startServer(): Promise<void>
  stopServer(): Promise<void>
  /**
   * Runs the project's script tests (`*_test.lua`) on fake servers, the ones whose "file: name"
   * contains [filter] when given: needs a trusted project, Java and the test runner. Nothing
   * touches the dev server or the project's files. With [member], another project of the open
   * project's workspace (its path there) instead, which has to be trusted too.
   */
  runTests(filter?: string, member?: string): Promise<TestReport>
  /** Runs a console command. */
  serverCommand(line: string): Promise<void>
  /**
   * Sends a dev bridge request (format's generated `BridgeRequests`: a
   * method, its params, its result) and resolves with its result, or rejects
   * with `notConnected`, `timeout`, `unknownMethod` (the plugin lacks it: an
   * extension it doesn't have) or `plugin` (it failed; the message says why).
   */
  bridgeRequest<M extends BridgeMethod>(
    method: M,
    ...params: BridgeParamsArg<M>
  ): Promise<BridgeResult<M>>
  /**
   * Sends one Debug Adapter Protocol message to the plugin, the debug
   * adapter, on the bridge's `dap` channel (a notification: DAP correlates
   * its own responses, which arrive as `onBridgeEvent('dap')`). Rejects with
   * `notConnected` while the bridge is down.
   */
  dapSend(message: DebugProtocol.ProtocolMessage): Promise<void>
  /**
   * Copies a world the plugin just saved (`save_world` answered where its
   * files are, relative to the dev server's folder) into the project as
   * `maps/<id>/` ([id] may start with group folders: `(arenas)/duel` is
   * `maps/(arenas)/duel/`): its `level.dat` at the top and its folder as the
   * map's overworld, without the files a server keeps for itself
   * (locks, identity, players). Rejects when the map exists unless
   * [replace]. Reports `onCaptureProgress` as it copies.
   */
  captureMap(
    source: { level: string; dimension: string },
    id: string,
    replace: boolean,
  ): Promise<void>
  onCaptureProgress(listener: (event: CaptureProgress) => void): Promise<Unlisten>
  /**
   * Converts the video in [folder] (`videos/trailer`) for screens: its frames in the game's map colours
   * (`video.nfv`) and its sound (`audio.ogg`, `audio/<n>.ogg`), with ffmpeg (the `ffmpegPath` setting, or
   * the first on `PATH`), reporting `onVideoProgress`. Rejects `untrusted` (ffmpeg reads the project's file),
   * `unavailable` without ffmpeg or the project's version's game data, `notFound` without the source.
   */
  convertVideo(folder: string): Promise<VideoConverted>
  onVideoProgress(listener: (event: ConvertProgress) => void): Promise<Unlisten>
  /** What the converted frames of the video in [folder] say, and whether its source changed since. */
  videoInfo(folder: string): Promise<VideoInfo>
  onServerState(listener: (state: ServerState) => void): Promise<Unlisten>
  onServerOutput(listener: (event: ServerOutputEvent) => void): Promise<Unlisten>
  onPrepareProgress(listener: (event: PrepareProgress) => void): Promise<Unlisten>

  // Dev network: the project's server behind a proxy (Velocity with NetherForge's hub),
  // beside other servers, each running a project folder of its own. `startServer` and
  // `stopServer` start and stop all of it when the layout is `enabled`.
  /** The open project's network layout (the editor's, kept per project; the default when none). */
  networkLayout(): Promise<NetworkLayout>
  /** Saves the open project's layout for the next start; rejects `invalid` for a bad or repeated name. */
  networkSetLayout(layout: NetworkLayout): Promise<void>
  /** Each of the layout's other servers as a start would find it: its folder, trust, version, problem. */
  networkCheck(): Promise<NetworkServerCheck[]>
  /** Trusts the folder the layout's server [name] runs (from a trusted project only). */
  networkTrust(name: string): Promise<void>
  /** The proxy and other servers of the network last started. */
  networkMembers(): Promise<NetworkMember[]>
  /** Runs a console command on the member [name]: `proxy`, or one of the other servers. */
  networkCommand(name: string, line: string): Promise<void>
  /** Every member at once, when a network starts or goes away. */
  onNetworkMembers(listener: (event: NetworkMembers) => void): Promise<Unlisten>
  /** One member changed (its phase, its plugin connecting). */
  onNetworkMember(listener: (member: NetworkMember) => void): Promise<Unlisten>
  /** A member's console line. */
  onNetworkOutput(listener: (event: NetworkOutputEvent) => void): Promise<Unlisten>
  /**
   * The plugin's notifications of one kind (format's generated
   * `BridgeEvents`): `problems`, `status`, and the `console` stream, whose
   * params are a batch of entries.
   */
  onBridgeEvent<E extends BridgeEventName>(
    event: E,
    listener: (params: BridgeEvents[E]) => void,
  ): Promise<Unlisten>

  // Minecraft installs and cache
  findInstalls(): Promise<MinecraftInstall[]>
  cacheStatus(version: string): Promise<CacheStatus>
  /** Extracts the client assets for [version] from [jar] into the cache. */
  importClient(version: string, jar: string): Promise<void>
  onImportProgress(listener: (event: ImportProgress) => void): Promise<Unlisten>
  /** After a client import or a game-data export finishes, with that version's new status. */
  onCacheChanged(listener: (status: CacheStatus) => void): Promise<Unlisten>
  /** The cached server half, or null if no dev server has exported it yet. */
  gameData(version: string): Promise<GameDataBundle | null>
  /**
   * The default font's glyph advances (`{ "<code point>": pixels }`), computed
   * from the imported client's font files; null when the client isn't
   * imported. The UI merges them into the `GameDataBundle` it hands to
   * format (`glyphAdvances`) for text measuring.
   */
  glyphAdvances(version: string): Promise<Record<string, number> | null>
  /** Starts the player's Minecraft launcher, or rejects with a sentence if none is installed. */
  openLauncher(): Promise<void>
  /**
   * URL of a cached client asset, e.g. `assets/minecraft/textures/block/stone.png`.
   * Served by the backend's `nfasset://` protocol; synchronous because it's
   * just string building.
   */
  assetUrl(version: string, path: string): string
}
