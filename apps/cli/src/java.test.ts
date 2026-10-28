import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  currentJavaEnv,
  findJava,
  javaCandidates,
  javaVersion,
  MIN_JAVA,
  parseReleaseFile,
  parseVersionOutput,
  type JavaEnv,
} from './java.ts'

// The search is a pure function of a JavaEnv: these tests fake the machine (its OS, home, variables and
// filesystem root) in a temp folder, and fake JDKs whose `release` file says their version, so no Java runs.

let tmp: string
beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-cli-java-'))
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

/** A fake JDK at [dir] for [platform]: its `java` (a script) under `bin/` and a `release` file saying [major]. */
function jdk(dir: string, major: number, platform: NodeJS.Platform = process.platform): string {
  const bin = path.join(dir, 'bin')
  mkdirSync(bin, { recursive: true })
  const java = path.join(bin, platform === 'win32' ? 'java.exe' : 'java')
  writeFileSync(java, '#!/bin/sh\nexit 1\n')
  chmodSync(java, 0o755)
  writeFileSync(path.join(dir, 'release'), `JAVA_VERSION="${major}.0.1"\n`)
  return java
}

/** A machine running [platform] with nothing on it but [env]'s variables, its home, data and root under the temp folder. */
function machine(
  env: NodeJS.ProcessEnv = {},
  platform: NodeJS.Platform = process.platform,
): JavaEnv {
  return {
    platform,
    home: path.join(tmp, 'home'),
    env: { NETHERFORGE_DATA_DIR: path.join(tmp, 'data'), PATH: '', ...env },
    systemRoot: path.join(tmp, 'root'),
  }
}

describe('Java versions', () => {
  it('reads the major version from java -version, old and new numbering', () => {
    expect(parseVersionOutput('openjdk version "25.0.1" 2025-10-21\nOpenJDK Runtime')).toBe(25)
    expect(parseVersionOutput('Picked up _JAVA_OPTIONS\nopenjdk version "21" 2023-09-19')).toBe(21)
    expect(parseVersionOutput('java version "1.8.0_391"')).toBe(8)
    expect(parseVersionOutput('openjdk version "26-ea" 2026-03-17')).toBe(26)
    expect(parseVersionOutput('openjdk version "1"')).toBeNull()
    expect(parseVersionOutput('command not found')).toBeNull()
  })

  it("reads a JDK's release file", () => {
    expect(parseReleaseFile('IMPLEMENTOR="Eclipse"\nJAVA_VERSION="25.0.1"\n')).toBe(25)
    expect(parseReleaseFile('JAVA_VERSION="1.8.0_391"')).toBe(8)
    expect(parseReleaseFile('JAVA_VERSION=21')).toBe(21)
    expect(parseReleaseFile('JAVA_VERSION=""')).toBeNull()
    expect(parseReleaseFile('nothing')).toBeNull()
  })

  it("takes a java's version from its JDK's release file before running it", () => {
    expect(javaVersion(jdk(path.join(tmp, 'a'), 25))).toBe(25)
    // A binary that can't run and has no release file has no version.
    const bare = path.join(tmp, 'b', 'bin', 'java')
    mkdirSync(path.dirname(bare), { recursive: true })
    writeFileSync(bare, '')
    expect(javaVersion(bare)).toBeNull()
  })
})

describe('the Java search', () => {
  it('looks where the editor does, in order: NETHERFORGE_JAVA, JAVA_HOME, its own JDKs, PATH', () => {
    const own = jdk(path.join(tmp, 'own'), 25)
    const home = jdk(path.join(tmp, 'jdk-home'), 25)
    const managedOld = jdk(path.join(tmp, 'data', 'jdks', 'temurin-21'), 21)
    const managedNew = jdk(path.join(tmp, 'data', 'jdks', 'temurin-25'), 25)
    const onPath = jdk(path.join(tmp, 'pathjdk'), 25)
    const found = javaCandidates(
      machine({
        NETHERFORGE_JAVA: own,
        JAVA_HOME: path.join(tmp, 'jdk-home'),
        PATH: path.dirname(onPath),
      }),
    )
    // The editor's downloads newest first, by name.
    expect(found).toEqual([own, home, managedNew, managedOld, onPath])
  })

  it('lists a Java once, however many ways it is found', () => {
    const home = jdk(path.join(tmp, 'jdk-home'), 25)
    const found = javaCandidates(
      machine({ JAVA_HOME: path.join(tmp, 'jdk-home'), PATH: path.dirname(home) }),
    )
    expect(found).toEqual([home])
  })

  it("looks in a JDK's macOS bundle layout too", () => {
    const bundle = path.join(tmp, 'data', 'jdks', 'temurin-25.jdk')
    const java = jdk(path.join(bundle, 'Contents', 'Home'), 25)
    expect(javaCandidates(machine())).toEqual([java])
  })

  it("then each OS's usual install folders", () => {
    const root = path.join(tmp, 'root')
    const home = path.join(tmp, 'home')
    const sdkman = jdk(path.join(home, '.sdkman', 'candidates', 'java', '25-tem'), 25, 'linux')
    const linux = jdk(path.join(root, 'usr/lib/jvm/java-25-openjdk'), 25, 'linux')
    expect(javaCandidates(machine({}, 'linux'))).toEqual([sdkman, linux])

    const system = jdk(
      path.join(root, 'Library/Java/JavaVirtualMachines/temurin-25.jdk'),
      25,
      'darwin',
    )
    const brew = jdk(
      path.join(root, 'opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk'),
      25,
      'darwin',
    )
    // Not a JDK formula: skipped. Homebrew's are found as the search goes past them, before the other folders.
    jdk(path.join(root, 'opt/homebrew/opt/other/libexec/openjdk.jdk'), 25, 'darwin')
    expect(javaCandidates(machine({}, 'darwin'))).toEqual([brew, sdkman, system])

    const files = path.join(tmp, 'pf')
    const adoptium = jdk(path.join(files, 'Eclipse Adoptium', 'jdk-25'), 25, 'win32')
    const local = path.join(tmp, 'local')
    const user = jdk(path.join(local, 'Programs', 'Eclipse Adoptium', 'jdk-21'), 21, 'win32')
    expect(javaCandidates(machine({ ProgramFiles: files, LOCALAPPDATA: local }, 'win32'))).toEqual([
      adoptium,
      user,
    ])
  })

  it(`finds the first Java of at least ${MIN_JAVA}, and none when every one is older`, () => {
    const old = jdk(path.join(tmp, 'old'), 17)
    const current = jdk(path.join(tmp, 'data', 'jdks', 'temurin-25'), 25)
    expect(findJava(machine({ JAVA_HOME: path.join(tmp, 'old') }))).toBe(current)
    expect(findJava(machine({ NETHERFORGE_JAVA: old }))).toBe(current)
    rmSync(path.join(tmp, 'data'), { recursive: true })
    expect(findJava(machine({ NETHERFORGE_JAVA: old }))).toBeNull()
    expect(findJava(machine())).toBeNull()
  })

  it('describes the machine it runs on', () => {
    const here = currentJavaEnv({ A: '1' })
    expect(here.platform).toBe(process.platform)
    expect(here.env).toEqual({ A: '1' })
    expect(here.home).toBe(os.homedir())
    expect(path.isAbsolute(here.systemRoot)).toBe(true)
  })
})
