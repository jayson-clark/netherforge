// @vitest-environment node
/**
 * The real lua-language-server against a copy of `examples/showcase`, set up
 * the way the editor sets it up: the project's `.luarc.json`, the API's
 * stubs and the project's names (`stubs.ts`) where the editor writes them,
 * the client settings (`settings.ts`) with NetherForge's plugin
 * (`src-tauri/src/luals/plugin.lua`), spoken to over LSP on stdio.
 *
 * Runs the LuaLS the editor ships (`node tools/luals.mjs`, which `pnpm test`
 * runs first) or the one in `LUA_LANGUAGE_SERVER`; skipped without either.
 * The Rust side's framing and process handling have their own tests.
 */
import { spawn, type ChildProcess } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import {
  createMessageConnection,
  StreamMessageReader,
  StreamMessageWriter,
  type MessageConnection,
} from 'vscode-jsonrpc/node'
import { AGENT_FILES, loadProject } from '@/core/format'
import type { DeclaringManifest } from '@netherforge/api/requirements'
import { lualsSettings } from './settings'
import {
  lualsLibraryFiles,
  projectNames,
  tailoredApiStubs,
  type ApiGate,
  type ApiStubs,
} from '@netherforge/project-support'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../../..')
const executable = process.platform === 'win32' ? 'lua-language-server.exe' : 'lua-language-server'
const binary = [
  process.env.LUA_LANGUAGE_SERVER,
  path.join(repo, 'apps/editor/src-tauri/lua-language-server/bin', executable),
].find((it): it is string => !!it && existsSync(it))
if (!binary) console.info('Skipping the LuaLS integration test: run node tools/luals.mjs first.')

interface Diagnostic {
  range: { start: { line: number } }
  code?: string
  message: string
}
interface Location {
  uri: string
  range: { start: { line: number; character: number } }
}

describe.skipIf(!binary)('lua-language-server, as the editor runs it', () => {
  const work = mkdtempSync(path.join(tmpdir(), 'netherforge-luals-it-'))
  const project = path.join(work, 'basic')
  const uri = (file: string) => pathToFileURL(path.join(project, file)).href
  // One file whichever way its URI is spelled: LuaLS answers Windows paths as `file:///c%3A/…`
  // (as vscode's Uri does), Node writes `file:///C:/…`, escaping differently.
  const same = (it: string) =>
    decodeURIComponent(it).replace(
      /^file:\/\/\/([A-Za-z]):/,
      (_, drive: string) => `file:///${drive.toLowerCase()}:`,
    )
  const diagnostics = new Map<string, Diagnostic[]>()
  let child: ChildProcess
  let connection: MessageConnection

  const open = (file: string, text: string) =>
    connection.sendNotification('textDocument/didOpen', {
      textDocument: { uri: uri(file), languageId: 'lua', version: 1, text },
    })

  async function diagnosticsOf(file: string): Promise<Diagnostic[]> {
    for (let i = 0; i < 300 && !diagnostics.has(same(uri(file))); i++)
      await new Promise((resolve) => setTimeout(resolve, 100))
    return diagnostics.get(same(uri(file))) ?? []
  }

  beforeAll(async () => {
    // The project, plus what the editor writes into .netherforge/ for it.
    cpSync(path.join(repo, 'examples/showcase'), project, {
      recursive: true,
      filter: (source: string) => !source.includes(`${path.sep}.netherforge`),
    })
    // A module in a group folder, and a resource's script in another that requires it and the file beside it.
    const groupFiles: Record<string, string> = {
      'modules/(social)/(chat)/chatter/init.lua':
        'local chatter = {}\n\nfunction chatter.say(text)\n  return text\nend\n\nreturn chatter\n',
      'modules/(social)/(chat)/chatter/words.lua': 'return { hello = "hello" }\n',
      'centities/(test)/probe/centity.json':
        '{\n  "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" } } },\n  "script": { "file": "script.lua" }\n}\n',
      'centities/(test)/probe/script.lua':
        'local chatter = require("chatter")\nlocal words = require("chatter.words")\nlocal near = require("near")\nchatter.say(words.hello .. near.name)\n',
      'centities/(test)/probe/near.lua': 'return { name = "near" }\n',
    }
    for (const [file, text] of Object.entries(groupFiles)) {
      mkdirSync(path.dirname(path.join(project, file)), { recursive: true })
      writeFileSync(path.join(project, file), text)
    }
    // The API's stubs, as generated, with what the example can't use marked.
    const generated = path.join(repo, 'packages/api/generated/luals')
    const api: ApiStubs = {
      files: { 'nf.lua': readFileSync(path.join(generated, 'nf.lua'), 'utf8') },
      gates: JSON.parse(readFileSync(path.join(generated, 'gates.json'), 'utf8')) as ApiGate[],
    }
    for (const surface of readdirSync(path.join(generated, 'surfaces')))
      api.files[`surfaces/${surface}`] = readFileSync(
        path.join(generated, 'surfaces', surface),
        'utf8',
      )
    // The project's names as the editor works them out: format's outline, then each main file.
    const files: Record<string, string | null> = {}
    for (const entry of readdirSync(project, { recursive: true, withFileTypes: true })) {
      if (!entry.isFile()) continue
      const file = path
        .relative(project, path.join(entry.parentPath, entry.name))
        .split(path.sep)
        .join('/')
      files[file] = file.endsWith('.json') ? readFileSync(path.join(project, file), 'utf8') : null
    }
    const modelAt = <T>(file: string): T | null => {
      const text = files[file]
      return text ? (JSON.parse(text) as T) : null
    }
    // The library the example depends on, read as the editor reads a package: JSON and modules' Lua as text.
    const library = path.join(repo, 'examples/library')
    const libraryFiles: Record<string, string | null> = {}
    for (const entry of readdirSync(library, { recursive: true, withFileTypes: true })) {
      if (!entry.isFile() || entry.parentPath.includes(`${path.sep}.netherforge`)) continue
      const file = path
        .relative(library, path.join(entry.parentPath, entry.name))
        .split(path.sep)
        .join('/')
      const text = file.endsWith('.json') || file.endsWith('.lua')
      libraryFiles[file] = text ? readFileSync(path.join(library, file), 'utf8') : null
    }
    const outline = loadProject(files, null, {
      folders: { '../library': { files: libraryFiles, hash: null } },
      git: {},
    })
    const library_ = outline.packages!.library!
    const packages = [{ namespace: 'library', outline: library_, files: libraryFiles }]
    const names = projectNames(outline, modelAt, packages)
    const manifest = modelAt<DeclaringManifest & { minecraft?: string }>('netherforge.json')
    const tailored = tailoredApiStubs(api, { minecraft: manifest?.minecraft ?? null, manifest })
    for (const [file, text] of Object.entries(lualsLibraryFiles(names, packages, tailored))) {
      const target = path.join(project, AGENT_FILES.luals, file)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, text)
    }
    const plugin = path.join(repo, 'apps/editor/src-tauri/src/luals/plugin.lua')
    const settings = lualsSettings(plugin)

    child = spawn(
      binary!,
      [`--metapath=${path.join(work, 'meta')}`, `--logpath=${path.join(work, 'log')}`],
      { cwd: project },
    )
    connection = createMessageConnection(
      new StreamMessageReader(child.stdout!),
      new StreamMessageWriter(child.stdin!),
    )
    connection.onNotification('textDocument/publishDiagnostics', (params) => {
      diagnostics.set(same(params.uri), params.diagnostics)
    })
    connection.onRequest('workspace/configuration', (params: { items: { section?: string }[] }) =>
      params.items.map((item) => (item.section === 'Lua' ? settings : null)),
    )
    connection.onRequest('client/registerCapability', () => null)
    connection.onRequest('window/workDoneProgress/create', () => null)
    connection.onNotification(() => {})
    connection.listen()
    await connection.sendRequest('initialize', {
      processId: process.pid,
      rootUri: pathToFileURL(project).href,
      workspaceFolders: [{ uri: pathToFileURL(project).href, name: 'basic' }],
      initializationOptions: { trustByClient: true },
      capabilities: {
        textDocument: {
          completion: { completionItem: { snippetSupport: true } },
          publishDiagnostics: {},
          definition: {},
        },
        workspace: { configuration: true, workspaceFolders: true },
      },
    })
    await connection.sendNotification('initialized', {})
    // LuaLS diagnoses the workspace once it has loaded it (and the stubs): then it's ready.
    for (let i = 0; i < 600 && diagnostics.size === 0; i++)
      await new Promise((resolve) => setTimeout(resolve, 100))
  }, 90_000)

  afterAll(async () => {
    try {
      await connection?.sendRequest('shutdown')
    } catch {
      // Already gone.
    }
    connection?.dispose()
    // Windows keeps a folder busy while a process in it lives, so the server must be gone first.
    if (child && child.exitCode === null && child.signalCode === null) {
      const exited = new Promise((resolve) => child.once('exit', resolve))
      child.kill()
      await exited
    }
    rmSync(work, { recursive: true, force: true, maxRetries: 10 })
  })

  it('flags a typo with a diagnostic', async () => {
    const file = 'modules/greeter/typo.lua'
    await open(file, 'nf.on("player_join", function(event)\n  prnt(event.player:name())\nend)\n')
    const found = await diagnosticsOf(file)
    expect(found.map((it) => `${it.range.start.line + 1} ${it.code}`)).toContain(
      '2 undefined-global',
    )
  }, 60_000)

  it('flags a handler that waits, and not a task that does', async () => {
    const file = 'modules/greeter/waits.lua'
    await open(
      file,
      [
        'nf.on("player_join", function() nf.wait(20) end)',
        'nf.on("player_join", function() nf.task(function() nf.wait(20) end) end)',
        'nf.on("player_join", function() nf.worlds.copy("arena", "a", function() end) end)',
        'nf.on("player_join", function() local w = nf.worlds.copy("arena", "b") end)',
        '',
      ].join('\n'),
    )
    const found = await diagnosticsOf(file)
    expect(
      found.filter((it) => it.code === 'not-yieldable').map((it) => it.range.start.line + 1),
    ).toEqual([1, 4])
  }, 60_000)

  it("marks what the project hasn't declared it may use", async () => {
    // The example doesn't allow moderation.
    const file = 'modules/greeter/bans.lua'
    await open(file, 'local alex = nf.players.get("Alex")\nif alex then alex:ban() end\n')
    const found = await diagnosticsOf(file)
    const deprecated = found.find((it) => it.code === 'deprecated')
    expect(deprecated?.range.start.line).toBe(1)
    expect(deprecated?.message).toContain('"moderation": true')
  }, 60_000)

  it("completes the project's node names", async () => {
    const file = 'centities/tower/probe.lua'
    await open(file, 'local this = this --[[@as Centity]]\nthis:node("")\n')
    const result = (await connection.sendRequest('textDocument/completion', {
      textDocument: { uri: uri(file) },
      position: { line: 1, character: 11 },
    })) as { items: { label: string }[] } | { label: string }[]
    const labels = ('items' in result ? result.items : result).map((it) => it.label)
    expect(labels).toContain('"cap"')
  }, 60_000)

  it("knows a module a package exports, by the name it's required by", async () => {
    // The example's greeter requires the library's greetings and calls greetings.hello.
    const file = 'modules/greeter/init.lua'
    const text = readFileSync(path.join(project, file), 'utf8')
    await open(file, text)
    const lines = text.split('\n')
    const line = lines.findIndex((it) => it.includes('greetings.hello('))
    const found = (await connection.sendRequest('textDocument/definition', {
      textDocument: { uri: uri(file) },
      position: { line, character: lines[line]!.indexOf('hello') },
    })) as Location[]
    const stub = `${AGENT_FILES.luals}/packages/library/greetings/init.lua`
    expect(found.map((it) => same(it.uri))).toEqual([same(uri(stub))])
    const target = readFileSync(path.join(project, stub), 'utf8')
    expect(target.split('\n')[found[0]!.range.start.line]).toContain(
      'function greetings.hello(name)',
    )
  }, 60_000)

  it('follows requires through group folders: a module, a file in it, and one beside the script', async () => {
    const file = 'centities/(test)/probe/script.lua'
    const text = readFileSync(path.join(project, file), 'utf8')
    await open(file, text)
    const lines = text.split('\n')
    const at = async (line: number, word: string) => {
      const found = (await connection.sendRequest('textDocument/definition', {
        textDocument: { uri: uri(file) },
        position: { line, character: lines[line]!.indexOf(word) },
      })) as Location[]
      return found.map((it) => same(it.uri))
    }
    expect(await at(3, 'say')).toEqual([same(uri('modules/(social)/(chat)/chatter/init.lua'))])
    expect(await at(3, 'hello')).toEqual([same(uri('modules/(social)/(chat)/chatter/words.lua'))])
    expect(await at(3, 'name')).toEqual([same(uri('centities/(test)/probe/near.lua'))])
    expect((await diagnosticsOf(file)).filter((it) => it.message.includes('require'))).toEqual([])
  }, 60_000)

  it('goes to a definition across a require beside the script', async () => {
    const file = 'centities/tower/script.lua'
    const text = readFileSync(path.join(project, file), 'utf8')
    await open(file, text)
    const lines = text.split('\n')
    const line = lines.findIndex((it) => it.includes('turns.spin()'))
    const character = lines[line]!.indexOf('spin')
    const found = (await connection.sendRequest('textDocument/definition', {
      textDocument: { uri: uri(file) },
      position: { line, character },
    })) as Location[]
    expect(found.map((it) => same(it.uri))).toEqual([same(uri('centities/tower/turns.lua'))])
    const target = readFileSync(path.join(project, 'centities/tower/turns.lua'), 'utf8')
    expect(target.split('\n')[found[0]!.range.start.line]).toContain('function turns.spin()')
  }, 60_000)
})
