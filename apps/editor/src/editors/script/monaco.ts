/**
 * Monaco, set up once per page: the VS Code services `monaco-languageclient`
 * needs (`@codingame/monaco-vscode-api` in its "classic" mode: Monarch
 * highlighting and the standalone editor, no workbench), workers bundled by
 * Vite (no CDN), the editor theme, Lua's and SQL's tokenizers and the JSON
 * language service. Lua's language features (diagnostics, completion, hover,
 * signatures, definitions, references, rename) come from lua-language-server
 * through `luaClient.ts`.
 *
 * Models are keyed by the file's real URI (`file:///…/project/centities/…`),
 * which is what LuaLS names files by. A file nobody has open is read through
 * the project's files (`ProjectFiles`), so going to a definition in it works.
 */
import * as monaco from '@codingame/monaco-vscode-editor-api'
import {
  FileSystemProviderCapabilities,
  FileSystemProviderError,
  FileSystemProviderErrorCode,
  FileType,
  registerFileSystemOverlay,
  type IFileSystemProviderWithFileReadWriteCapability,
  type IStat,
} from '@codingame/monaco-vscode-files-service-override'
import EditorWorker from '@codingame/monaco-vscode-editor-api/esm/vs/editor/editor.worker.js?worker'
import JsonWorker from '@codingame/monaco-vscode-standalone-json-language-features/worker?worker'
import { MonacoVscodeApiWrapper } from 'monaco-languageclient/vscodeApiWrapper'
import type { Backend } from '@/core/backend/types'
import type { WorkspaceStore } from '@/core/store/workspace'

export { monaco }

export const THEME = 'netherforge-dark'

export interface MonacoContext {
  workspace: WorkspaceStore
  backend: Backend
}

let context: MonacoContext | null = null

/** The open project's root as a URI path (`/Users/me/shop`), or null. */
function rootPath(): string | null {
  const root = context?.workspace.getState().project?.root
  return root ? monaco.Uri.file(root).path : null
}

/** The project path a URI names, or null for a file outside the open project. */
export function pathOf(resource: monaco.Uri | monaco.editor.ITextModel): string | null {
  const uri = 'uri' in resource ? resource.uri : resource
  const root = rootPath()
  if (uri.scheme !== 'file' || !root || !uri.path.startsWith(`${root}/`)) return null
  return uri.path.slice(root.length + 1)
}

/** The URI a project file's model has: the file's own, as LuaLS names it. */
export function modelUri(path: string): monaco.Uri {
  const root = context?.workspace.getState().project?.root ?? '/'
  return monaco.Uri.joinPath(monaco.Uri.file(root), ...path.split('/'))
}

const notFound = (what: string) =>
  FileSystemProviderError.create(
    `${what} isn't in the project`,
    FileSystemProviderErrorCode.FileNotFound,
  )

/**
 * The open project's files, read-only, for models of files no tab has open
 * (a definition in another file). Open documents read as they are now,
 * unsaved edits included.
 */
class ProjectFiles implements IFileSystemProviderWithFileReadWriteCapability {
  readonly capabilities =
    FileSystemProviderCapabilities.FileReadWrite |
    FileSystemProviderCapabilities.PathCaseSensitive |
    FileSystemProviderCapabilities.Readonly
  readonly onDidChangeCapabilities = new monaco.Emitter<void>().event
  readonly onDidChangeFile = new monaco.Emitter<never[]>().event

  watch() {
    return { dispose() {} }
  }

  async stat(resource: monaco.Uri): Promise<IStat> {
    const path = pathOf(resource)
    const files = context?.workspace.getState().files ?? []
    if (path !== null && files.includes(path))
      return { type: FileType.File, ctime: 0, mtime: 0, size: 0 }
    if (path !== null && files.some((it) => it.startsWith(`${path}/`)))
      return { type: FileType.Directory, ctime: 0, mtime: 0, size: 0 }
    throw notFound(resource.toString())
  }

  async readFile(resource: monaco.Uri): Promise<Uint8Array> {
    const path = pathOf(resource)
    if (!context || path === null) throw notFound(resource.toString())
    const open = context.workspace.getState().docs[path]?.text
    return new TextEncoder().encode(open ?? (await context.backend.readText(path)))
  }

  async readdir(): Promise<[string, FileType][]> {
    return []
  }

  async writeFile(): Promise<void> {
    throw FileSystemProviderError.create('Read-only', FileSystemProviderErrorCode.NoPermissions)
  }

  async mkdir(): Promise<void> {
    throw FileSystemProviderError.create('Read-only', FileSystemProviderErrorCode.NoPermissions)
  }

  async delete(): Promise<void> {
    throw FileSystemProviderError.create('Read-only', FileSystemProviderErrorCode.NoPermissions)
  }

  async rename(): Promise<void> {
    throw FileSystemProviderError.create('Read-only', FileSystemProviderErrorCode.NoPermissions)
  }
}

let started: Promise<void> | null = null
/**
 * Whether the services are up. Any use of Monaco's API before then (even
 * listing models) would start them with the defaults, and the real start
 * would then fail.
 */
let servicesUp = false

function start(): Promise<void> {
  const wrapper = new MonacoVscodeApiWrapper({
    $type: 'classic',
    viewsConfig: {
      $type: 'EditorService',
      // Another file's definition opens it in its own tab, through the workspace.
      openEditorFunc: async (reference, options) => {
        const path = pathOf(reference.object.textEditorModel.uri)
        // Files outside the project (the API's stubs) are only peeked at.
        if (!context || path === null || path.startsWith('.netherforge/')) return undefined
        // A text editor's options carry where to go (`ITextEditorOptions.selection`).
        const selection = (
          options as { selection?: { startLineNumber: number; startColumn: number } } | undefined
        )?.selection
        void context.workspace
          .getState()
          .openFile(
            path,
            selection
              ? { line: selection.startLineNumber, column: selection.startColumn }
              : { line: 1, column: 1 },
          )
        return undefined
      },
    },
    monacoWorkerFactory: () => {
      self.MonacoEnvironment = {
        ...self.MonacoEnvironment,
        getWorker(_id: string, label: string) {
          return label === 'json' ? new JsonWorker() : new EditorWorker()
        },
      }
    },
  })
  return wrapper.start({ caller: 'netherforge' }).then(async () => {
    // Registering a language touches the editor's services, so only once they're up.
    await import('@codingame/monaco-vscode-standalone-languages/languages/definitions/lua/register')
    // Migrations: Monaco's own SQL tokenizer (highlighting only, no language service).
    await import('@codingame/monaco-vscode-standalone-languages/languages/definitions/sql/register')
    await import('@codingame/monaco-vscode-standalone-json-language-features')
    servicesUp = true
    registerFileSystemOverlay(1, new ProjectFiles())
    monaco.editor.defineTheme(THEME, {
      base: 'vs-dark',
      inherit: true,
      rules: [],
      colors: {
        'editor.background': '#15171c',
        'editor.lineHighlightBackground': '#1d2028',
        'editorGutter.background': '#15171c',
      },
    })
  })
}

/**
 * Resolves once Monaco can create editors. [app] is the project the
 * editors show (the most recent caller's): lua-language-server follows it.
 */
export function setupMonaco(app?: MonacoContext): Promise<void> {
  if (app && app !== context) {
    context = app
    // The language client (and the VS Code extension API under it) loads once the services are up.
    started = (started ?? start()).then(async () =>
      (await import('./luaClient')).followLuaClient(app),
    )
  }
  started ??= start()
  return started
}

/** One Monaco model per project file, kept across tab switches so undo survives. */
export function modelFor(path: string, text: string, language: string): monaco.editor.ITextModel {
  const uri = modelUri(path)
  const existing = monaco.editor.getModel(uri)
  if (existing) return existing
  return monaco.editor.createModel(text, language, uri)
}

/** Drops models of files no longer open (closed tabs, renamed or deleted files). */
export function disposeModelsExcept(paths: Set<string>) {
  if (!servicesUp) return
  for (const model of monaco.editor.getModels()) {
    // Models of files outside the project (a peeked stub) belong to the services that opened them.
    const path = pathOf(model)
    if (path !== null && !paths.has(path)) model.dispose()
  }
}
