/**
 * The open project's lua-language-server, as Monaco's Lua language features:
 * diagnostics, completion, hover, signature help, go to definition,
 * references and rename, all from LuaLS through `monaco-languageclient`.
 *
 * One server per open project, started when Monaco is first set up for it
 * (the first script opened) and stopped when the project closes or another
 * opens. Only for a project the user trusts (the backend refuses the rest:
 * LuaLS would run a plugin a project's `.luarc.json` names): it starts once
 * they trust it, and stops if they stop. Where the backend has no LuaLS (the memory backend in tests, a dev
 * build that never fetched it) the client simply isn't there: scripts are
 * highlighted and nothing more, and nothing else in the editor changes.
 *
 * LuaLS reads the project's `.luarc.json` (the API's stubs and the project's
 * names in `.netherforge/`); the client adds what only the editor knows: its
 * plugin, which resolves `require` as the server does, trusted without a prompt.
 */
import * as vscode from 'vscode'
import { MonacoLanguageClient } from 'monaco-languageclient'
import { CloseAction, ErrorAction } from 'vscode-languageclient/browser'
import type { Backend, ProjectInfo } from '@/core/backend/types'
import { lualsSettings } from '@/core/luals/settings'
import { connectLuals, type LualsConnection } from '@/core/luals/transport'
import type { MonacoContext } from './monaco'

/** The client for one project, from the moment it's wanted until it's stopped. */
interface Session {
  project: ProjectInfo
  connection: LualsConnection | null
  client: MonacoLanguageClient | null
  stopped: boolean
}

let current: Session | null = null
/** Starts and stops in order, so a quick switch never runs two servers. */
let queue: Promise<void> = Promise.resolve()
let following: (() => void) | null = null

async function stop(session: Session, backend: Backend) {
  session.stopped = true
  try {
    await session.client?.dispose()
  } catch {
    // It may already be gone.
  }
  session.connection?.dispose()
  if (session.connection) await backend.lualsStop().catch(() => {})
}

async function start(session: Session, backend: Backend) {
  let connection: LualsConnection
  try {
    connection = await connectLuals(backend)
  } catch (error) {
    // No LuaLS here: Lua is just highlighted.
    console.info(`Lua language features are off: ${error instanceof Error ? error.message : error}`)
    return
  }
  session.connection = connection
  const settings = lualsSettings(connection.started.plugin)
  const client = new MonacoLanguageClient({
    name: 'lua-language-server',
    clientOptions: {
      documentSelector: [{ scheme: 'file', language: 'lua' }],
      workspaceFolder: {
        uri: vscode.Uri.file(session.project.root),
        name: session.project.name,
        index: 0,
      },
      // Our own plugin: no "trust this plugin?" question.
      initializationOptions: { trustByClient: true },
      middleware: {
        workspace: {
          // LuaLS asks for its settings; the project's .luarc.json still wins where it says.
          configuration: (params) =>
            params.items.map((item) => (item.section === 'Lua' ? settings : null)),
        },
      },
      errorHandler: {
        error: () => ({ action: ErrorAction.Continue }),
        closed: () => ({ action: CloseAction.DoNotRestart }),
      },
    },
    messageTransports: { reader: connection.reader, writer: connection.writer },
  })
  session.client = client
  try {
    await client.start()
  } catch (error) {
    console.warn('lua-language-server failed to start', error)
  }
}

/** Keeps one LuaLS running for [context]'s open project (see the file's doc). */
export function followLuaClient(context: MonacoContext) {
  following?.()
  const sync = () => {
    const opened = context.workspace.getState().project
    const project = opened?.trusted ? opened : null
    if (current?.project.root === project?.root) return
    const previous = current
    const next: Session | null = project
      ? { project, connection: null, client: null, stopped: false }
      : null
    current = next
    queue = queue.then(async () => {
      if (previous) await stop(previous, context.backend)
      if (next && !next.stopped) await start(next, context.backend)
    })
  }
  following = context.workspace.subscribe((state, previous) => {
    if (
      state.project?.root !== previous.project?.root ||
      state.project?.trusted !== previous.project?.trusted
    )
      sync()
  })
  sync()
}
