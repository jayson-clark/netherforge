import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { createHash } from 'node:crypto'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { GAME_DATA_SCHEMA } from '@netherforge/format/constants'
import { zipFiles } from './bundles.ts'
import {
  EXPORT_PROPERTY,
  exportGameData,
  PAPER_PROPERTIES,
  paperBuild,
  pluginJarName,
  zipEntry,
  type GameDataHost,
  type RunServer,
} from './gameData.ts'
import type { JavaEnv } from './java.ts'
import { gameDataFile } from './project.ts'

let tmp: string
beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-cli-game-data-'))
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

const sha = (bytes: Buffer | string) => createHash('sha256').update(bytes).digest('hex')

/** A machine whose only Java is a fake 25, its data under [tmp]. */
function machine(): JavaEnv {
  const home = path.join(tmp, 'jdk')
  mkdirSync(path.join(home, 'bin'), { recursive: true })
  const java = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  writeFileSync(java, '#!/bin/sh\n')
  chmodSync(java, 0o755)
  writeFileSync(path.join(home, 'release'), 'JAVA_VERSION="25.0.1"\n')
  return {
    platform: process.platform,
    home: path.join(tmp, 'home'),
    env: { NETHERFORGE_DATA_DIR: path.join(tmp, 'data'), JAVA_HOME: home, PATH: '' },
    systemRoot: path.join(tmp, 'root'),
  }
}

/** A plugin jar naming Paper [build] for [minecraft]. */
const pluginJar = (minecraft: string, build: number) =>
  zipFiles([
    ['paper-plugin.yml', Buffer.from('name: NetherForge\n')],
    [PAPER_PROPERTIES, Buffer.from(`minecraft=${minecraft}\nbuild=${build}\n`)],
  ])

describe('reading a jar', () => {
  it('finds an entry and reads the Paper build it names', () => {
    const jar = pluginJar('26.3', 152)
    expect(zipEntry(jar, 'paper-plugin.yml')?.toString()).toBe('name: NetherForge\n')
    expect(zipEntry(jar, 'nothing')).toBeNull()
    expect(zipEntry(Buffer.from('not a zip'), PAPER_PROPERTIES)).toBeNull()
    expect(paperBuild(zipEntry(jar, PAPER_PROPERTIES)!.toString(), '26.3')).toBe(152)
    expect(paperBuild('minecraft=26.2\nbuild=7\n', '26.3')).toContain(
      'no Paper build for Minecraft 26.3',
    )
    expect(paperBuild(null, '26.3')).toContain(PAPER_PROPERTIES)
  })
})

describe('netherforge game-data', () => {
  const minecraft = '26.3'
  const version = '1.2.3'
  const paper = Buffer.from('the paper jar')
  const plugin = pluginJar(minecraft, 152)

  /** A release with the plugin jar, and Fill with build 152. [asked] hears each URL. */
  const internet =
    (asked: string[] = []): typeof fetch =>
    (input) => {
      const url = String(input)
      asked.push(url)
      const name = url.slice(url.lastIndexOf('/') + 1)
      if (url.startsWith('https://r.test/'))
        return Promise.resolve(
          name === 'SHA256SUMS.txt'
            ? new Response(`${sha(plugin)}  ${pluginJarName(version, minecraft)}\n`)
            : name === pluginJarName(version, minecraft)
              ? new Response(plugin)
              : new Response(null, { status: 404 }),
        )
      if (url === `https://fill.papermc.io/v3/projects/paper/versions/${minecraft}/builds/152`)
        return Promise.resolve(
          Response.json({
            id: 152,
            downloads: {
              'server:default': {
                name: 'paper-26.3-152.jar',
                url: 'https://fill-data.test/paper-26.3-152.jar',
                checksums: { sha256: sha(paper) },
              },
            },
          }),
        )
      if (url === 'https://fill-data.test/paper-26.3-152.jar')
        return Promise.resolve(new Response(paper))
      return Promise.resolve(new Response(null, { status: 404 }))
    }

  /** A server that writes [bundle] where the property says, as the plugin does. [ran] hears its arguments. */
  const server =
    (bundle: object | null, ran: { args?: string[]; cwd?: string } = {}): RunServer =>
    (_java, args, cwd) => {
      ran.args = args
      ran.cwd = cwd
      const file = args.find((it) => it.startsWith(`-D${EXPORT_PROPERTY}=`))!.split('=')[1]!
      if (bundle) writeFileSync(file, JSON.stringify(bundle))
      return Promise.resolve({ code: 0, tail: ['[Server] Stopping'], timedOut: false })
    }

  const env = () => ({
    NETHERFORGE_DATA_DIR: path.join(tmp, 'data'),
    NETHERFORGE_RELEASES: 'https://r.test/dl',
  })
  const host = (run: RunServer, asked?: string[]): GameDataHost => ({
    machine: machine(),
    get: internet(asked),
    run,
    beside: path.join(tmp, 'nowhere'),
    port: () => Promise.resolve(40123),
  })

  it("needs the EULA accepted, as the editor does, and a release's version", async () => {
    const refused = await exportGameData(minecraft, {}, version, env(), host(server(null)))
    expect(refused.code).toBe(2)
    expect(refused.err[0]).toContain('--accept-eula')
    expect(
      (await exportGameData('latest', { acceptEula: true }, version, env(), host(server(null))))
        .err[0],
    ).toContain("isn't a Minecraft release")
  })

  it('downloads the plugin and its Paper build, runs it headless, and caches what it exports where check and test look', async () => {
    const asked: string[] = []
    const ran: { args?: string[]; cwd?: string } = {}
    const outcome = await exportGameData(
      minecraft,
      { acceptEula: true },
      version,
      env(),
      host(server({ minecraft, schema: GAME_DATA_SCHEMA }, ran), asked),
    )
    expect(outcome.err).toEqual([])
    expect(outcome.code).toBe(0)
    const cached = gameDataFile(path.join(tmp, 'data'), minecraft)
    expect(JSON.parse(readFileSync(cached, 'utf8'))).toEqual({
      minecraft,
      schema: GAME_DATA_SCHEMA,
    })
    expect(outcome.out[0]).toContain(cached)
    expect(ran.args).toContain('-Dcom.mojang.eula.agree=true')
    expect(ran.args).toContain(path.join(tmp, 'data', 'paper', 'paper-26.3-152.jar'))
    expect(readFileSync(path.join(ran.cwd!, 'server.properties'), 'utf8')).toContain(
      'server-port=40123',
    )
    expect(existsSync(path.join(ran.cwd!, 'plugins', pluginJarName(version, minecraft)))).toBe(true)
    expect(existsSync(path.join(ran.cwd!, 'eula.txt'))).toBe(false)

    // The second time, nothing is downloaded.
    asked.length = 0
    const out = path.join(tmp, 'mine.json')
    const again = await exportGameData(
      minecraft,
      { acceptEula: true, out },
      version,
      env(),
      host(server({ minecraft, schema: GAME_DATA_SCHEMA }), asked),
    )
    expect(again.code).toBe(0)
    expect(asked).toEqual([])
    expect(existsSync(out)).toBe(true)
  })

  it("keeps the cache it had when the server writes nothing, or another version's or shape's", async () => {
    const cached = gameDataFile(path.join(tmp, 'data'), minecraft)
    mkdirSync(path.dirname(cached), { recursive: true })
    writeFileSync(cached, 'the old one')
    const none = await exportGameData(
      minecraft,
      { acceptEula: true },
      version,
      env(),
      host(server(null)),
    )
    expect(none.code).toBe(1)
    expect(none.err[0]).toContain('without writing the game data')
    expect(none.err).toContain('  [Server] Stopping')
    const other = await exportGameData(
      minecraft,
      { acceptEula: true },
      version,
      env(),
      host(server({ minecraft: '26.2', schema: GAME_DATA_SCHEMA })),
    )
    expect(other.err[0]).toContain("Minecraft 26.2's game data")
    const old = await exportGameData(
      minecraft,
      { acceptEula: true },
      version,
      env(),
      host(server({ minecraft, schema: 0 })),
    )
    expect(old.err[0]).toContain('schema 0')
    expect(readFileSync(cached, 'utf8')).toBe('the old one')
  })

  it('says so when this NetherForge has no plugin for the version', async () => {
    const outcome = await exportGameData(
      '1.8.9',
      { acceptEula: true },
      version,
      env(),
      host(server(null)),
    )
    expect(outcome.code).toBe(2)
    expect(outcome.err[0]).toContain('answered 404')
    expect(outcome.err[0]).toContain('NETHERFORGE_PLUGIN_JAR')
  })
})
