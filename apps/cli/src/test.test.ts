import {
  chmodSync,
  cpSync,
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
import { findJava, type JavaEnv } from './java.ts'
import { run } from './main.ts'
import { gameDataFile } from './project.ts'
import { fetchJar, findJar, jarName, releasesUrl, test } from './test.ts'

let tmp: string
beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-cli-test-'))
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

/** A fake JDK: `<dir>/bin/java` (a script, on unix) and a `release` file saying its version. */
function jdk(dir: string, major: number): string {
  const bin = path.join(dir, 'bin')
  mkdirSync(bin, { recursive: true })
  const java = path.join(bin, process.platform === 'win32' ? 'java.exe' : 'java')
  writeFileSync(java, '#!/bin/sh\necho "openjdk version \\"1\\"" >&2\n')
  chmodSync(java, 0o755)
  writeFileSync(path.join(dir, 'release'), `JAVA_VERSION="${major}.0.1"\n`)
  return java
}

/** The dev server's export for [minecraft] in the editor's cache under [data] (a stand-in: the runner is stubbed here). */
function cachedGameData(data: string, minecraft: string, schema = GAME_DATA_SCHEMA): string {
  const file = gameDataFile(data, minecraft)
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, JSON.stringify({ minecraft, schema }))
  return file
}

/** A machine with nothing on it but [env]'s variables, its home and data under [tmp]. */
function machine(env: NodeJS.ProcessEnv): JavaEnv {
  return {
    platform: process.platform,
    home: path.join(tmp, 'home'),
    env: { NETHERFORGE_DATA_DIR: path.join(tmp, 'data'), PATH: '', ...env },
    systemRoot: path.join(tmp, 'root'),
  }
}

// Finding Java itself is java.test.ts.

describe('the runner jar', () => {
  it('is named for the version', () => {
    expect(jarName('1.2.3')).toBe('NetherForgeTest-1.2.3.jar')
  })

  it('is found by NETHERFORGE_TEST_JAR, beside the script, or in the editor data folder', () => {
    const beside = path.join(tmp, 'beside')
    mkdirSync(beside)
    const env = { NETHERFORGE_DATA_DIR: path.join(tmp, 'data') }
    expect(findJar('1.0.0', env, beside)).toBeNull()
    const inData = path.join(tmp, 'data', 'test-runner', jarName('1.0.0'))
    mkdirSync(path.dirname(inData), { recursive: true })
    writeFileSync(inData, '')
    expect(findJar('1.0.0', env, beside)).toBe(inData)
    const next = path.join(beside, jarName('1.0.0'))
    writeFileSync(next, '')
    expect(findJar('1.0.0', env, beside)).toBe(next)
    const explicit = path.join(tmp, 'mine.jar')
    writeFileSync(explicit, '')
    expect(findJar('1.0.0', { ...env, NETHERFORGE_TEST_JAR: explicit }, beside)).toBe(explicit)
    // A named jar that isn't there is not quietly replaced by another.
    expect(
      findJar('1.0.0', { ...env, NETHERFORGE_TEST_JAR: path.join(tmp, 'gone.jar') }, beside),
    ).toBeNull()
  })

  /** A release at [base]: these files, by name; anything else answers 404. [asked] hears each URL. */
  const release =
    (files: Record<string, string>, asked: string[] = []): typeof fetch =>
    (input) => {
      const url = String(input)
      asked.push(url)
      const name = url.slice(url.lastIndexOf('/') + 1)
      const text = files[name]
      return Promise.resolve(
        text === undefined ? new Response(null, { status: 404 }) : new Response(text),
      )
    }
  const sha = (text: string) => createHash('sha256').update(text).digest('hex')

  it("is downloaded from its version's release, checked against SHA256SUMS.txt, where it's found next time", async () => {
    const env = {
      NETHERFORGE_DATA_DIR: path.join(tmp, 'data'),
      NETHERFORGE_RELEASES: 'https://r.test/dl/',
    }
    const asked: string[] = []
    const jar = 'the runner'
    const said: string[] = []
    const got = await fetchJar(
      '1.2.3',
      env,
      (line) => said.push(line),
      release(
        {
          'SHA256SUMS.txt': `${sha('x')}  other.jar\n${sha(jar)}  ${jarName('1.2.3')}\n`,
          [jarName('1.2.3')]: jar,
        },
        asked,
      ),
    )
    expect(got).toEqual({ file: path.join(tmp, 'data', 'test-runner', jarName('1.2.3')) })
    expect(asked).toEqual([
      'https://r.test/dl/v1.2.3/SHA256SUMS.txt',
      `https://r.test/dl/v1.2.3/${jarName('1.2.3')}`,
    ])
    expect(readFileSync((got as { file: string }).file, 'utf8')).toBe(jar)
    expect(findJar('1.2.3', env, path.join(tmp, 'nowhere'))).toBe((got as { file: string }).file)
    expect(said[0]).toContain('Downloading')
  })

  it("refuses a jar that isn't the one the release lists, and says how to get it when there's no release", async () => {
    const env = { NETHERFORGE_DATA_DIR: path.join(tmp, 'data') }
    const tampered = await fetchJar(
      '1.2.3',
      env,
      undefined,
      release({
        'SHA256SUMS.txt': `${sha('the runner')}  ${jarName('1.2.3')}\n`,
        [jarName('1.2.3')]: 'something else',
      }),
    )
    expect('err' in tampered && tampered.err).toContain(
      "doesn't match the release's SHA256SUMS.txt",
    )
    expect(findJar('1.2.3', env, path.join(tmp, 'nowhere'))).toBeNull()
    const missing = await fetchJar('9.9.9', env, undefined, release({}))
    expect('err' in missing && missing.err).toContain('answered 404')
    expect('err' in missing && missing.err).toContain('NETHERFORGE_TEST_JAR')
    expect(releasesUrl({})).toBe('https://github.com/jayson-clark/netherforge/releases/download')
  })
})

describe('netherforge test', () => {
  const calls: { command: string; args: string[] }[] = []
  beforeEach(() => {
    calls.length = 0
  })
  const spawn = (status: number | null) => (command: string, args: string[]) => {
    calls.push({ command, args })
    return { status, error: undefined }
  }

  it('launches the jar with Java, the project, the package cache and what it was asked', () => {
    const java = jdk(path.join(tmp, 'managed'), 25)
    const jar = path.join(tmp, 'runner.jar')
    writeFileSync(jar, '')
    const env = {
      NETHERFORGE_JAVA: java,
      NETHERFORGE_TEST_JAR: jar,
      NETHERFORGE_DATA_DIR: path.join(tmp, 'data'),
    }
    const data = cachedGameData(path.join(tmp, 'data'), '26.3')
    mkdirSync(path.join(tmp, 'project'))
    writeFileSync(path.join(tmp, 'project', 'netherforge.json'), '{ "minecraft": "26.3" }')
    const outcome = test(
      path.join(tmp, 'project'),
      { json: true, junit: path.join(tmp, 'out.xml'), filter: 'greets' },
      '1.0.0',
      env,
      { spawn: spawn(1), machine: machine(env) },
    )
    expect(outcome).toEqual({ out: [], err: [], code: 1 })
    expect(calls).toEqual([
      {
        command: java,
        args: [
          '-jar',
          jar,
          path.join(tmp, 'project'),
          '--packages',
          path.join(tmp, 'data', 'packages'),
          '--game-data',
          data,
          '--json',
          '--junit',
          path.join(tmp, 'out.xml'),
          '--filter',
          'greets',
        ],
      },
    ])
  })

  it('runs on the --game-data file when one is given, and says how to get data when there is none', () => {
    const java = jdk(path.join(tmp, 'managed'), 25)
    const jar = path.join(tmp, 'runner.jar')
    writeFileSync(jar, '')
    const env = {
      NETHERFORGE_JAVA: java,
      NETHERFORGE_TEST_JAR: jar,
      NETHERFORGE_DATA_DIR: path.join(tmp, 'data'),
    }
    const project = path.join(tmp, 'project')
    mkdirSync(project)
    writeFileSync(path.join(project, 'netherforge.json'), '{ "minecraft": "26.3" }')
    const host = { spawn: spawn(0), machine: machine(env) }

    // No cache for the version: no run, and the error says what to do. Never a fallback.
    const none = test(project, {}, '1.0.0', env, host)
    expect(none.code).toBe(2)
    expect(none.err[0]).toContain('game data of Minecraft 26.3')
    expect(none.err[0]).toContain(path.join('minecraft', '26.3', 'server', 'game-data.json'))
    expect(none.err[0]).toContain('Start the dev server')

    // A cache of another schema is as good as none.
    const stale = cachedGameData(path.join(tmp, 'data'), '26.3', 999)
    expect(test(project, {}, '1.0.0', env, host).err[0]).toContain('another shape')
    rmSync(stale)

    const mine = path.join(tmp, 'mine.json')
    writeFileSync(mine, JSON.stringify({ minecraft: '26.3', schema: GAME_DATA_SCHEMA }))
    expect(test(project, { gameData: mine }, '1.0.0', env, host).code).toBe(0)
    expect(calls.at(-1)?.args).toContain(mine)
    expect(test(project, { gameData: path.join(tmp, 'gone.json') }, '1.0.0', env, host).code).toBe(
      2,
    )

    writeFileSync(path.join(project, 'netherforge.json'), '{}')
    expect(test(project, {}, '1.0.0', env, host).err[0]).toContain('Minecraft release')
  })

  it('says what is missing, and exits 2, when there is no Java or no jar', () => {
    const env = { PATH: '', NETHERFORGE_DATA_DIR: path.join(tmp, 'data') }
    const noJava = test(tmp, {}, '1.0.0', env, { spawn: spawn(0), machine: machine(env) })
    expect(noJava.code).toBe(2)
    expect(noJava.err[0]).toContain('needs Java 21')
    expect(noJava.err[0]).toContain('NETHERFORGE_JAVA')
    const java = jdk(path.join(tmp, 'managed'), 25)
    const withJava = { ...env, NETHERFORGE_JAVA: java }
    const noJar = test(tmp, {}, '1.0.0', withJava, {
      spawn: spawn(0),
      machine: machine(withJava),
      beside: path.join(tmp, 'elsewhere'),
    })
    expect(noJar.code).toBe(2)
    expect(noJar.err[0]).toContain('NetherForgeTest-1.0.0.jar')
    expect(calls).toEqual([])
  })

  it('exits 2 when Java could not be started', () => {
    const java = jdk(path.join(tmp, 'managed'), 25)
    const jar = path.join(tmp, 'runner.jar')
    writeFileSync(jar, '')
    const env = { NETHERFORGE_JAVA: java, NETHERFORGE_TEST_JAR: jar }
    writeFileSync(path.join(tmp, 'netherforge.json'), '{ "minecraft": "26.3" }')
    const given = path.join(tmp, 'given.json')
    writeFileSync(given, JSON.stringify({ minecraft: '26.3', schema: GAME_DATA_SCHEMA }))
    const outcome = test(tmp, { gameData: given }, '1.0.0', env, {
      spawn: () => ({ status: null, error: new Error('spawn failed') }),
      machine: machine(env),
    })
    expect(outcome.code).toBe(2)
    expect(outcome.err[0]).toContain('spawn failed')
  })
})

/**
 * The real thing: the jar Gradle built (`:plugin:test-runner:jar`, named for the repo's VERSION), launched with a
 * real Java, on a small project. Locally a machine without either skips it and says why; in CI (`CI` set) that's a
 * failure, so a broken build or a missing JDK can't make these tests pass by not running.
 */
const version = readFileSync(path.resolve(import.meta.dirname, '../../../VERSION'), 'utf8').trim()
const builtJar = path.resolve(
  import.meta.dirname,
  `../../plugin/test-runner/build/libs/${jarName(version)}`,
)
const java = findJava()
const missing = [
  existsSync(builtJar)
    ? null
    : `the runner jar ${builtJar} (node tools/gradle.mjs :plugin:test-runner:jar)`,
  java ? null : 'a Java of 21 or later',
].filter((it): it is string => it != null)
if (missing.length > 0 && !process.env.CI) {
  console.warn(`Skipping "netherforge test, for real": no ${missing.join(' and no ')}.`)
}
describe.runIf(missing.length > 0 && !!process.env.CI)('netherforge test, for real, in CI', () => {
  it('has the jar and a Java to run it', () => {
    throw new Error(`CI must run the real test runner, but there's no ${missing.join(' and no ')}`)
  })
})
// The reason is in the name too, so a skipped run says why wherever its report is read.
const skipped = missing.length > 0 ? ` (skipped: no ${missing.join(' and no ')})` : ''
describe.skipIf(missing.length > 0)(`netherforge test, for real${skipped}`, () => {
  // The small game data format's own tests use: what the runner reads is the same file the editor caches.
  const game = path.resolve(
    import.meta.dirname,
    '../../../packages/format/testdata/game-data/bundle.json',
  )

  it('runs a project’s tests on the game data: 0 when they pass, 1 when one fails, 2 without data', async () => {
    const project = path.join(tmp, 'project')
    mkdirSync(path.join(project, 'tests'), { recursive: true })
    writeFileSync(
      path.join(project, 'netherforge.json'),
      '{ "formatVersion": 1, "name": "P", "namespace": "p", "version": "1.0.0", "minecraft": "26.3" }\n',
    )
    writeFileSync(
      path.join(project, 'tests/ok_test.lua'),
      "nf.test.case('works', function() nf.test.advance(2) end)\n",
    )
    const env = {
      NETHERFORGE_JAVA: java!,
      NETHERFORGE_TEST_JAR: builtJar,
      NETHERFORGE_DATA_DIR: path.join(tmp, 'data'),
    }
    // Nothing cached for 26.3: refused before anything runs.
    expect((await run(['test', project], env)).code).toBe(2)

    const cached = gameDataFile(path.join(tmp, 'data'), '26.3')
    mkdirSync(path.dirname(cached), { recursive: true })
    cpSync(game, cached)
    const passing = await run(['test', project, '--junit', path.join(tmp, 'junit.xml')], env)
    expect([passing.code, passing.err]).toEqual([0, []])
    expect(existsSync(path.join(tmp, 'junit.xml'))).toBe(true)

    writeFileSync(
      path.join(project, 'tests/failing_test.lua'),
      "nf.test.case('fails', function() assert(false) end)\n",
    )
    expect((await run(['test', project], env)).code).toBe(1)
    // One file only, by path.
    expect((await run(['test', path.join(project, 'tests/ok_test.lua')], env)).code).toBe(0)
  }, 120_000)
})
