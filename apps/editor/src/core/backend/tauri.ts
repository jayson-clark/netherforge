/**
 * The real `Backend`: each method is a generated command or event
 * (`generated/bindings.ts`, from the Rust side by tauri-specta) or a Tauri
 * plugin's API. A command's rejection, `{ code, message }`, is rethrown as a
 * `BackendError`.
 */
import type { EventCallback } from '@tauri-apps/api/event'
import { getCurrentWebview } from '@tauri-apps/api/webview'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { open } from '@tauri-apps/plugin-dialog'
import { openUrl } from '@tauri-apps/plugin-opener'
import { relaunch } from '@tauri-apps/plugin-process'
import { check, type Update } from '@tauri-apps/plugin-updater'
import type { DebugProtocol } from '@vscode/debugprotocol'
import type { GameDataBundle } from '@netherforge/format/types'
import { BridgeClient } from '@/core/bridge/client'
import { commands, events, type CommandError } from './generated/bindings'
import { TauriMenuBar } from './tauriMenu'
import { BackendError } from './types'
import type {
  AppMenu,
  AppInfo,
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
  PackageFiles,
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
  PrepareProgress,
  ProjectInfo,
  RecentProject,
  ServerOutputEvent,
  ServerState,
  Settings,
  TestReport,
  VideoConverted,
  VideoInfo,
  WorkspaceInfo,
  Unlisten,
  UpdateInfo,
  UpdateProgress,
  WindowAction,
} from './types'

/** Runs a generated command, rethrowing its rejection as a `BackendError`. */
async function call<T>(command: Promise<T>): Promise<T> {
  try {
    return await command
  } catch (error) {
    throw toBackendError(error)
  }
}

/** A command's `{ code, message }` as a `BackendError`; anything else (Tauri's own failures) as `other`. */
export function toBackendError(error: unknown): BackendError {
  if (typeof error === 'object' && error !== null && 'code' in error && 'message' in error) {
    const { code, message } = error as CommandError
    return new BackendError(code, message)
  }
  return new BackendError('other', error instanceof Error ? error.message : String(error))
}

/** Listens to a generated event, handing the listener its payload. */
function on<T>(
  event: { listen: (cb: EventCallback<T>) => Promise<Unlisten> },
  listener: (payload: T) => void,
): Promise<Unlisten> {
  return event.listen((message) => listener(message.payload))
}

/** Commands answer `null` where the UI's contract says `void`. */
async function done(command: Promise<null>): Promise<void> {
  await call(command)
}

/**
 * The URL the backend's `nfasset` protocol serves a cached client asset at.
 * WebView2 (Windows) can't load custom schemes directly, so Tauri maps them
 * to `http://<scheme>.localhost/` there.
 */
export function nfAssetUrl(os: AppInfo['os'], version: string, path: string): string {
  const encoded = path.split('/').map(encodeURIComponent).join('/')
  const base = os === 'windows' ? 'http://nfasset.localhost' : 'nfasset://localhost'
  return `${base}/${encodeURIComponent(version)}/${encoded}`
}

/** The URL the backend's `nfproject` protocol serves a project file at. */
export function nfProjectUrl(os: AppInfo['os'], path: string, stamp?: number): string {
  const encoded = path.split('/').map(encodeURIComponent).join('/')
  const base = os === 'windows' ? 'http://nfproject.localhost' : 'nfproject://localhost'
  return `${base}/${encoded}${stamp ? `?v=${stamp}` : ''}`
}

/**
 * A package's file, served by the same protocol: `/:package/<location>/<path>`,
 * the location (`../library`) one encoded segment, so `..` is never a dot
 * segment the URL would resolve away (see `fs::serve`).
 */
export function nfPackageUrl(
  os: AppInfo['os'],
  location: string,
  path: string,
  stamp?: number,
): string {
  const encoded = path.split('/').map(encodeURIComponent).join('/')
  const base = os === 'windows' ? 'http://nfproject.localhost' : 'nfproject://localhost'
  return `${base}/:package/${encodeURIComponent(location)}/${encoded}${stamp ? `?v=${stamp}` : ''}`
}

export class TauriBackend implements Backend {
  /** Known after `init`, needed synchronously by `assetUrl`. */
  private os: AppInfo['os'] = 'linux'
  /** What the last `checkForUpdate` found, for `installUpdate`. */
  private update: Update | null = null
  private menuListeners = new Set<(id: string) => void>()
  private menuBar = new TauriMenuBar((id) => {
    for (const listener of this.menuListeners) listener(id)
  })
  /** JSON-RPC with the plugin, over the backend's relay. */
  private bridge = new BridgeClient({
    send: (message) => done(commands.bridgeSend(message)),
    listen: (listener) => on(events.bridgeMessage, (event) => listener(event.message)),
    onConnected: (listener) => on(events.serverState, (state) => listener(state.bridgeConnected)),
  })

  /** Fetches the platform once, so `assetUrl` can stay synchronous. */
  async init(): Promise<this> {
    this.os = (await this.appInfo()).os
    return this
  }

  appInfo(): Promise<AppInfo> {
    return call(commands.appInfo())
  }
  getSettings(): Promise<Settings> {
    return call(commands.settingsGet())
  }
  setSettings(settings: Settings): Promise<void> {
    return done(commands.settingsSet(settings))
  }
  openExternal(url: string): Promise<void> {
    return openUrl(url)
  }
  windowAction(action: WindowAction): Promise<void> {
    const window = getCurrentWindow()
    if (action === 'minimize') return window.minimize()
    if (action === 'toggleMaximize') return window.toggleMaximize()
    return window.close()
  }
  setAppMenu(menus: AppMenu[]): Promise<void> {
    return this.menuBar.set(menus)
  }
  onMenuAction(listener: (id: string) => void): Unlisten {
    this.menuListeners.add(listener)
    return () => this.menuListeners.delete(listener)
  }
  setZoom(factor: number): Promise<void> {
    return getCurrentWebview().setZoom(factor)
  }

  async checkForUpdate(): Promise<UpdateInfo | null> {
    const update = await check()
    this.update = update
    if (!update) return null
    return {
      version: update.version,
      currentVersion: update.currentVersion,
      notes: update.body ?? null,
      date: update.date ?? null,
    }
  }
  async installUpdate(onProgress?: (progress: UpdateProgress) => void): Promise<void> {
    const update = this.update ?? (await check())
    if (!update) throw new Error('There is no update to install')
    let downloaded = 0
    let total: number | null = null
    await update.downloadAndInstall((event) => {
      if (event.event === 'Started') total = event.data.contentLength ?? null
      else if (event.event === 'Progress') downloaded += event.data.chunkLength
      onProgress?.({ downloaded, total })
    })
    await relaunch()
  }

  recentProjects(): Promise<RecentProject[]> {
    return call(commands.projectRecent())
  }
  async pickFolder(title: string): Promise<string | null> {
    const picked = await open({ directory: true, multiple: false, title })
    return typeof picked === 'string' ? picked : null
  }
  async pickJar(title: string): Promise<string | null> {
    const picked = await open({
      directory: false,
      multiple: false,
      title,
      filters: [{ name: 'Minecraft client jar', extensions: ['jar'] }],
    })
    return typeof picked === 'string' ? picked : null
  }
  openProject(root: string): Promise<ProjectInfo> {
    return call(commands.projectOpen(root))
  }
  createProject(root: string, files: Record<string, string>): Promise<ProjectInfo> {
    return call(commands.projectCreate(root, files))
  }
  closeProject(): Promise<void> {
    return done(commands.projectClose())
  }
  trustProject(trusted: boolean): Promise<ProjectInfo> {
    return call(commands.projectTrust(trusted))
  }

  listFiles(): Promise<FileEntry[]> {
    return call(commands.fsList())
  }
  readText(path: string): Promise<string> {
    return call(commands.fsReadText(path))
  }
  writeText(path: string, text: string): Promise<void> {
    return done(commands.fsWriteText(path, text))
  }
  writeBytes(path: string, bytes: Uint8Array): Promise<void> {
    // A JSON array of numbers: textures are small, and it needs no extra crate.
    return done(commands.fsWriteBytes(path, Array.from(bytes)))
  }
  projectFileUrl(path: string, stamp?: number): string {
    return nfProjectUrl(this.os, path, stamp)
  }
  packageFileUrl(location: string, path: string, stamp?: number): string {
    return nfPackageUrl(this.os, location, path, stamp)
  }
  deletePath(path: string): Promise<void> {
    return done(commands.fsDelete(path))
  }
  renamePath(from: string, to: string): Promise<void> {
    return done(commands.fsRename(from, to))
  }
  packageFiles(location: string): Promise<PackageFiles> {
    return call(commands.packageFiles(location))
  }
  packageReadText(location: string, path: string): Promise<string> {
    return call(commands.packageReadText(location, path))
  }
  packageCopy(location: string, from: string, to: string): Promise<void> {
    return done(commands.packageCopy(location, from, to))
  }
  packageFetchGit(url: string, rev: string | null, commit: string | null): Promise<string> {
    return call(commands.packageFetchGit(url, rev, commit))
  }
  onFilesChanged(listener: (event: FsChangedEvent) => void): Promise<Unlisten> {
    return on(events.fsChanged, listener)
  }

  serverState(): Promise<ServerState> {
    return call(commands.serverState())
  }
  eulaStatus(): Promise<EulaStatus> {
    return call(commands.serverEulaStatus())
  }
  acceptEula(): Promise<void> {
    return done(commands.serverEulaAccept())
  }
  startServer(): Promise<void> {
    return done(commands.serverStart())
  }
  stopServer(): Promise<void> {
    return done(commands.serverStop())
  }
  serverCommand(line: string): Promise<void> {
    return done(commands.serverCommand(line))
  }
  runTests(filter?: string, member?: string): Promise<TestReport> {
    return call(commands.testsRun(filter ?? null, member ?? null))
  }
  bridgeRequest<M extends BridgeMethod>(
    method: M,
    ...params: BridgeParamsArg<M>
  ): Promise<BridgeResult<M>> {
    return this.bridge.request(method, ...params)
  }
  dapSend(message: DebugProtocol.ProtocolMessage): Promise<void> {
    return this.bridge.notify('dap', message)
  }
  captureMap(
    source: { level: string; dimension: string },
    id: string,
    replace: boolean,
  ): Promise<void> {
    return done(commands.mapCapture(source.level, source.dimension, id, replace))
  }
  onCaptureProgress(listener: (event: CaptureProgress) => void): Promise<Unlisten> {
    return on(events.mapCaptureProgress, listener)
  }
  convertVideo(folder: string): Promise<VideoConverted> {
    return call(commands.videoConvert(folder))
  }
  onVideoProgress(listener: (event: ConvertProgress) => void): Promise<Unlisten> {
    return on(events.videoConvertProgress, listener)
  }
  videoInfo(folder: string): Promise<VideoInfo> {
    return call(commands.videoInfo(folder))
  }
  onServerState(listener: (state: ServerState) => void): Promise<Unlisten> {
    return on(events.serverState, listener)
  }
  onServerOutput(listener: (event: ServerOutputEvent) => void): Promise<Unlisten> {
    return on(events.serverOutput, listener)
  }

  workspaceTrust(trusted: boolean): Promise<ProjectInfo> {
    return call(commands.workspaceTrust(trusted))
  }
  workspaceRead(): Promise<string> {
    return call(commands.workspaceRead())
  }
  workspaceWrite(text: string): Promise<WorkspaceInfo> {
    return call(commands.workspaceWrite(text))
  }
  workspaceInfo(): Promise<WorkspaceInfo | null> {
    return call(commands.workspaceInfo())
  }
  workspaceMemberFiles(member: string): Promise<PackageFiles> {
    return call(commands.workspaceMemberFiles(member))
  }
  workspaceMemberReadText(member: string, path: string): Promise<string> {
    return call(commands.workspaceMemberReadText(member, path))
  }
  workspaceMemberWriteText(member: string, path: string, text: string): Promise<void> {
    return done(commands.workspaceMemberWriteText(member, path, text))
  }
  workspaceCreateMember(path: string, files: Record<string, string>): Promise<WorkspaceInfo> {
    return call(commands.workspaceCreateMember(path, files))
  }
  workspaceWriteSchema(text: string): Promise<void> {
    return done(commands.workspaceWriteSchema(text))
  }
  workspaceNetworkEnabled(): Promise<boolean> {
    return call(commands.workspaceNetworkEnabled())
  }
  workspaceSetNetworkEnabled(enabled: boolean): Promise<void> {
    return done(commands.workspaceSetNetworkEnabled(enabled))
  }

  networkLayout(): Promise<NetworkLayout> {
    return call(commands.networkLayout())
  }
  networkSetLayout(layout: NetworkLayout): Promise<void> {
    return done(commands.networkSetLayout(layout))
  }
  networkCheck(): Promise<NetworkServerCheck[]> {
    return call(commands.networkCheck())
  }
  networkTrust(name: string): Promise<void> {
    return done(commands.networkTrust(name))
  }
  networkMembers(): Promise<NetworkMember[]> {
    return call(commands.networkMembers())
  }
  networkCommand(name: string, line: string): Promise<void> {
    return done(commands.networkCommand(name, line))
  }
  onNetworkMembers(listener: (event: NetworkMembers) => void): Promise<Unlisten> {
    return on(events.networkMembers, listener)
  }
  onNetworkMember(listener: (member: NetworkMember) => void): Promise<Unlisten> {
    return on(events.networkMember, listener)
  }
  onNetworkOutput(listener: (event: NetworkOutputEvent) => void): Promise<Unlisten> {
    return on(events.networkOutput, listener)
  }
  onPrepareProgress(listener: (event: PrepareProgress) => void): Promise<Unlisten> {
    return on(events.serverProgress, listener)
  }
  onBridgeEvent<E extends BridgeEventName>(
    event: E,
    listener: (params: BridgeEvents[E]) => void,
  ): Promise<Unlisten> {
    return this.bridge.onBridgeEvent(event, listener)
  }

  findInstalls(): Promise<MinecraftInstall[]> {
    return call(commands.mcInstalls())
  }
  cacheStatus(version: string): Promise<CacheStatus> {
    return call(commands.mcCacheStatus(version))
  }
  importClient(version: string, jar: string): Promise<void> {
    return done(commands.mcImportClient(version, jar))
  }
  onImportProgress(listener: (event: ImportProgress) => void): Promise<Unlisten> {
    return on(events.mcImportProgress, listener)
  }
  onCacheChanged(listener: (status: CacheStatus) => void): Promise<Unlisten> {
    return on(events.mcCacheChanged, listener)
  }
  mcpStatus(): Promise<McpStatus> {
    return call(commands.mcpStatus())
  }
  mcpSend(message: unknown): Promise<void> {
    return done(commands.mcpSend(message))
  }
  onMcpMessage(listener: (message: McpMessage) => void): Promise<Unlisten> {
    return on(events.mcpMessage, listener)
  }
  lualsStart(): Promise<LualsStarted> {
    return call(commands.lualsStart())
  }
  lualsSend(generation: number, message: string): Promise<void> {
    return done(commands.lualsSend(generation, message))
  }
  lualsStop(): Promise<void> {
    return done(commands.lualsStop())
  }
  onLualsMessage(listener: (event: LualsMessage) => void): Promise<Unlisten> {
    return on(events.lualsMessage, listener)
  }
  onLualsExit(listener: (event: LualsExit) => void): Promise<Unlisten> {
    return on(events.lualsExit, listener)
  }
  gameData(version: string): Promise<GameDataBundle | null> {
    return call(commands.mcGameData(version)) as Promise<GameDataBundle | null>
  }
  glyphAdvances(version: string): Promise<Record<string, number> | null> {
    return call(commands.mcGlyphAdvances(version))
  }
  openLauncher(): Promise<void> {
    return done(commands.mcOpenLauncher())
  }
  assetUrl(version: string, path: string): string {
    return nfAssetUrl(this.os, version, path)
  }
}
