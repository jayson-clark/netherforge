/**
 * `netherforge game-data <minecraft version>`: the game's data without the editor. Downloads the
 * version's plugin jar (from this NetherForge's release) and the Paper build it names, as the editor
 * does, starts Paper headless with the plugin told to export (`-Dnetherforge.export-game-data`, which
 * stops the server once written), and puts the export in the per-user cache `check` and `test` read
 * (or `--out`). For CI and other machines with no editor.
 */
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { createServer } from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { inflateRawSync } from 'node:zlib'
import { GAME_DATA_SCHEMA } from '@netherforge/format/constants'
import type { Outcome } from './commands.ts'
import { SERVER_JAVA, currentJavaEnv, findJava, type JavaEnv } from './java.ts'
import { editorDataDir, gameDataFile } from './project.ts'
import { fetchReleaseFile } from './test.ts'

/** What `netherforge game-data` takes besides the version. */
export interface GameDataOptions {
  /** Where to write it, instead of the editor's cache for the version. */
  out?: string
  /** The person running it accepted Minecraft's EULA (`--accept-eula`): Paper won't start otherwise. */
  acceptEula?: boolean
  /** How long the server may take, start to stop. */
  timeoutSeconds?: number
}

/** Where the plugin names the Paper build it was tested against (`minecraft=26.3`, `build=152`). */
export const PAPER_PROPERTIES = 'META-INF/netherforge/paper.properties'

/** The system property that makes the plugin export, then stop the server (`GameDataExport.PROPERTY`). */
export const EXPORT_PROPERTY = 'netherforge.export-game-data'

const FILL = 'https://fill.papermc.io/v3/projects/paper'
const EULA = 'https://aka.ms/MinecraftEULA'

/** The plugin's jar for [minecraft]: `NetherForge-<version>-paper-<minecraft>.jar`. */
export const pluginJarName = (version: string, minecraft: string) =>
  `NetherForge-${version}-paper-${minecraft}.jar`

/** How a server runs: a fake in tests. Resolves with the exit code (null if killed) and its output's last lines. */
export type RunServer = (
  java: string,
  args: string[],
  cwd: string,
  timeoutMs: number,
) => Promise<{ code: number | null; tail: string[]; timedOut: boolean }>

/** The machine `game-data` runs on: everything a test fakes. */
export interface GameDataHost {
  say?: (line: string) => void
  get?: typeof fetch
  run?: RunServer
  machine?: JavaEnv
  /** Where this script is: a plugin jar beside it is used first. */
  beside?: string
  /** A free port for the server. */
  port?: () => Promise<number>
}

/** The bytes of [name] in the zip (or jar) [zip], or null when it has none. Stored and deflated entries; no ZIP64. */
export function zipEntry(zip: Buffer, name: string): Buffer | null {
  const end = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]))
  if (end < 0 || end + 22 > zip.length) return null
  const count = zip.readUInt16LE(end + 10)
  let at = zip.readUInt32LE(end + 16)
  for (let i = 0; i < count; i++) {
    if (at + 46 > zip.length || zip.readUInt32LE(at) !== 0x02014b50) return null
    const method = zip.readUInt16LE(at + 10)
    const size = zip.readUInt32LE(at + 20)
    const nameLength = zip.readUInt16LE(at + 28)
    const extra = zip.readUInt16LE(at + 30)
    const comment = zip.readUInt16LE(at + 32)
    const local = zip.readUInt32LE(at + 42)
    const entry = zip.subarray(at + 46, at + 46 + nameLength).toString('utf8')
    if (entry === name) {
      if (local + 30 > zip.length || zip.readUInt32LE(local) !== 0x04034b50) return null
      const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28)
      const data = zip.subarray(start, start + size)
      if (method === 0) return Buffer.from(data)
      if (method === 8) return inflateRawSync(data)
      return null
    }
    at += 46 + nameLength + extra + comment
  }
  return null
}

/** The Paper build a `paper.properties` names for [minecraft], or why there's none. */
export function paperBuild(text: string | null, minecraft: string): number | string {
  if (text == null) return `it doesn't say which Paper build it's for (no ${PAPER_PROPERTIES})`
  const value = (key: string) =>
    text
      .split('\n')
      .map((line) => line.trim())
      .find((line) => line.startsWith(`${key}=`))
      ?.slice(key.length + 1)
      .trim()
  if (value('minecraft') !== minecraft) return `it names no Paper build for Minecraft ${minecraft}`
  const build = Number.parseInt(value('build') ?? '', 10)
  return Number.isInteger(build) && build > 0 ? build : 'it names no Paper build number'
}

/**
 * The plugin jar for [minecraft]: `NETHERFORGE_PLUGIN_JAR`, beside this script, the editor's data
 * folder (`<data>/plugins/`), or downloaded from this NetherForge's release into it.
 */
async function pluginJar(
  version: string,
  minecraft: string,
  env: NodeJS.ProcessEnv,
  host: GameDataHost,
): Promise<{ file: string } | { err: string }> {
  const explicit = env.NETHERFORGE_PLUGIN_JAR
  if (explicit)
    return existsSync(explicit)
      ? { file: explicit }
      : { err: `NETHERFORGE_PLUGIN_JAR is ${explicit}, which doesn't exist.` }
  const name = pluginJarName(version, minecraft)
  const folder = path.join(editorDataDir(env), 'plugins')
  const beside = host.beside ?? path.dirname(fileURLToPath(import.meta.url))
  const found = [path.join(beside, name), path.join(folder, name)].find(existsSync)
  if (found) return { file: found }
  return fetchReleaseFile(
    version,
    name,
    folder,
    `Exporting the game data of Minecraft ${minecraft}`,
    `NetherForge ${version} may not support Minecraft ${minecraft}; otherwise download ${name} from its release and set NETHERFORGE_PLUGIN_JAR to it.`,
    env,
    host.say,
    host.get,
  )
}

/** Paper's [build] for [minecraft], from PaperMC's Fill API, cached where the editor caches it (`<data>/paper/`). */
async function paperJar(
  minecraft: string,
  build: number,
  env: NodeJS.ProcessEnv,
  version: string,
  host: GameDataHost,
): Promise<{ file: string } | { err: string }> {
  const folder = path.join(editorDataDir(env), 'paper')
  const file = path.join(folder, `paper-${minecraft}-${build}.jar`)
  if (existsSync(file)) return { file }
  const get = host.get ?? fetch
  const headers = {
    'User-Agent': `netherforge-cli/${version} (https://github.com/jayson-clark/netherforge)`,
  }
  const url = `${FILL}/versions/${minecraft}/builds/${build}`
  host.say?.(`Downloading Paper ${minecraft} build ${build} …`)
  try {
    const answer = await get(url, { headers })
    if (!answer.ok) throw new Error(`${url} answered ${answer.status}`)
    const json = (await answer.json()) as {
      downloads?: Record<string, { name?: string; url?: string; checksums?: { sha256?: string } }>
    }
    const download = json.downloads?.['server:default']
    if (!download?.url || !download.checksums?.sha256)
      throw new Error(`build ${build} has no server download`)
    const response = await get(download.url, { headers })
    if (!response.ok) throw new Error(`${download.url} answered ${response.status}`)
    const bytes = Buffer.from(await response.arrayBuffer())
    const actual = createHash('sha256').update(bytes).digest('hex')
    if (actual !== download.checksums.sha256)
      throw new Error(
        `it doesn't match its sha256 (expected ${download.checksums.sha256}, got ${actual})`,
      )
    mkdirSync(folder, { recursive: true })
    writeFileSync(`${file}.part`, bytes)
    renameSync(`${file}.part`, file)
    return { file }
  } catch (error) {
    return {
      err: `Paper ${minecraft} build ${build} couldn't be downloaded: ${(error as Error).message}.`,
    }
  }
}

/** A port nothing listens on now, on loopback. */
function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      server.close(() =>
        typeof address === 'object' && address
          ? resolve(address.port)
          : reject(new Error('no port')),
      )
    })
  })
}

/** Runs the server, keeping its output's last lines for when it fails. */
const runServer: RunServer = (java, args, cwd, timeoutMs) =>
  new Promise((resolve) => {
    const tail: string[] = []
    let partial = ''
    const keep = (chunk: Buffer) => {
      const lines = (partial + chunk.toString('utf8')).split('\n')
      partial = lines.pop() ?? ''
      tail.push(...lines)
      if (tail.length > 40) tail.splice(0, tail.length - 40)
    }
    const child = spawn(java, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] })
    child.stdout.on('data', keep)
    child.stderr.on('data', keep)
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGKILL')
    }, timeoutMs)
    const done = (code: number | null) => {
      clearTimeout(timer)
      if (partial) tail.push(partial)
      resolve({ code, tail, timedOut })
    }
    child.on('error', (error) => {
      tail.push(error.message)
      done(null)
    })
    child.on('exit', (code) => done(code))
  })

/** `server.properties` for a server only started to export: offline, on loopback, nobody let in. */
function serverProperties(port: number): string {
  return [
    '# Written by netherforge game-data: a server started only to export the game data.',
    'online-mode=false',
    'server-ip=127.0.0.1',
    `server-port=${port}`,
    'max-players=1',
    'white-list=true',
    'enforce-whitelist=true',
    'enable-status=false',
    'spawn-monsters=false',
    'generate-structures=false',
    'level-type=minecraft\\:flat',
    '',
  ].join('\n')
}

export async function exportGameData(
  minecraft: string,
  options: GameDataOptions,
  version: string,
  env: NodeJS.ProcessEnv = process.env,
  host: GameDataHost = {},
): Promise<Outcome> {
  const fail = (line: string): Outcome => ({ out: [], err: [line], code: 2 })
  if (!/^\d+(\.\d+)+$/.test(minecraft))
    return fail(
      `"${minecraft}" isn't a Minecraft release (26.3, 1.21.11). Usage: netherforge game-data <minecraft version>`,
    )
  if (!options.acceptEula)
    return fail(
      `Exporting the game data runs a Minecraft server, which needs you to accept Minecraft's EULA (${EULA}). ` +
        'Run it again with --accept-eula if you do.',
    )
  const java = findJava(host.machine ?? currentJavaEnv(env), SERVER_JAVA)
  if (!java)
    return fail(
      `Exporting the game data needs Java ${SERVER_JAVA} or newer, and none was found. Install one, or set NETHERFORGE_JAVA to a java binary.`,
    )
  const plugin = await pluginJar(version, minecraft, env, host)
  if ('err' in plugin) return fail(plugin.err)
  const build = paperBuild(
    zipEntry(readFileSync(plugin.file), PAPER_PROPERTIES)?.toString('utf8') ?? null,
    minecraft,
  )
  if (typeof build === 'string')
    return fail(`${path.basename(plugin.file)} can't be used: ${build}.`)
  const paper = await paperJar(minecraft, build, env, version, host)
  if ('err' in paper) return fail(paper.err)

  // A folder of its own per version, kept: Paper's libraries and the patched server stay for the next run.
  const server = path.join(editorDataDir(env), 'game-data-server', minecraft)
  const plugins = path.join(server, 'plugins')
  mkdirSync(plugins, { recursive: true })
  for (const name of readdirSync(plugins))
    if (name.startsWith('NetherForge') && name.endsWith('.jar')) rmSync(path.join(plugins, name))
  copyFileSync(plugin.file, path.join(plugins, path.basename(plugin.file)))
  writeFileSync(
    path.join(server, 'server.properties'),
    serverProperties(await (host.port ?? freePort)()),
  )
  const exported = path.join(server, 'game-data.json')
  rmSync(exported, { force: true })

  host.say?.(
    `Starting Paper ${minecraft} build ${build} to export its game data (the first start takes a while) …`,
  )
  const timeout = (options.timeoutSeconds ?? 600) * 1000
  const ran = await (host.run ?? runServer)(
    java,
    [
      '-Xmx2G',
      // Asked for above: Paper reads this as accepting the EULA, so no eula.txt is written on anyone's behalf.
      '-Dcom.mojang.eula.agree=true',
      `-D${EXPORT_PROPERTY}=${exported}`,
      '-jar',
      paper.file,
      '--nogui',
    ],
    server,
    timeout,
  )
  const why = (line: string): Outcome => ({
    out: [],
    err: [line, '', ...ran.tail.map((it) => `  ${it}`)],
    code: 1,
  })
  if (ran.timedOut)
    return why(`The server didn't finish within ${timeout / 1000} seconds. Its last lines:`)
  let text: string
  try {
    text = readFileSync(exported, 'utf8')
  } catch {
    return why(
      `The server stopped (exit ${ran.code}) without writing the game data. Its last lines:`,
    )
  }
  let bundle: { schema?: unknown; minecraft?: unknown }
  try {
    bundle = JSON.parse(text) as typeof bundle
  } catch {
    return why(`The server wrote ${exported}, which isn't JSON.`)
  }
  if (bundle.schema !== GAME_DATA_SCHEMA)
    return why(
      `The plugin wrote game data of schema ${String(bundle.schema)}, but this NetherForge reads schema ${GAME_DATA_SCHEMA}.`,
    )
  if (bundle.minecraft !== minecraft)
    return why(
      `The server exported Minecraft ${String(bundle.minecraft)}'s game data, not ${minecraft}'s.`,
    )
  const out = options.out ? path.resolve(options.out) : gameDataFile(editorDataDir(env), minecraft)
  mkdirSync(path.dirname(out), { recursive: true })
  // Whole or not at all, and across file systems: a copy beside it, then a rename.
  copyFileSync(exported, `${out}.part`)
  renameSync(`${out}.part`, out)
  rmSync(exported, { force: true })
  return { out: [`Wrote the game data of Minecraft ${minecraft} to ${out}.`], err: [], code: 0 }
}
