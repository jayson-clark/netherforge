import { spawnSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { editorDataDir } from './project.ts'

/**
 * The oldest Java the test runner's jar runs on: the bytecode the build targets (the plugin's
 * runtime, which every supported Minecraft's Java runs). The editor's own Java is newer (25), and
 * is what is found first.
 */
export const MIN_JAVA = 21

/** Where the Java search looks: a pure function of this, so a test can fake the machine. */
export interface JavaEnv {
  platform: NodeJS.Platform
  home: string
  env: NodeJS.ProcessEnv
  /** `/` on a real machine; a temp folder in tests. */
  systemRoot: string
}

export function currentJavaEnv(env: NodeJS.ProcessEnv = process.env): JavaEnv {
  return {
    platform: process.platform,
    home: os.homedir(),
    env,
    systemRoot: path.parse(process.cwd()).root,
  }
}

const executable = (platform: NodeJS.Platform) => (platform === 'win32' ? 'java.exe' : 'java')

const isFile = (file: string) => {
  try {
    return statSync(file).isFile()
  } catch {
    return false
  }
}

/** The `java` binary inside a JDK folder (a home, or a macOS `.jdk` bundle). */
function javaIn(dir: string, platform: NodeJS.Platform): string | null {
  const exe = executable(platform)
  return (
    [path.join(dir, 'bin', exe), path.join(dir, 'Contents', 'Home', 'bin', exe)].find(isFile) ??
    null
  )
}

/** Subfolders of [dir], newest-looking names first. */
function children(dir: string): string[] {
  try {
    return readdirSync(dir, { withFileTypes: true })
      .filter((it) => it.isDirectory())
      .map((it) => path.join(dir, it.name))
      .sort()
      .reverse()
  } catch {
    return []
  }
}

/**
 * Candidate `java` binaries in the order the editor searches (`server/java.rs`): `NETHERFORGE_JAVA`
 * (this command's own override, a `java` binary), `JAVA_HOME`, the JDKs the editor downloaded
 * (`<data>/jdks/`), `java` on `PATH`, then the usual install folders for the OS. Deduplicated.
 */
export function javaCandidates(machine: JavaEnv): string[] {
  const { platform, home, env } = machine
  const list: string[] = []
  const explicit = env.NETHERFORGE_JAVA
  if (explicit) list.push(explicit)
  const homes = [
    env.JAVA_HOME,
    ...children(path.join(editorDataDir(env, platform, home), 'jdks')),
  ].filter((it): it is string => !!it)
  for (const jdk of homes) {
    const java = javaIn(jdk, platform)
    if (java) list.push(java)
  }
  for (const dir of (env.PATH ?? env.Path ?? '').split(path.delimiter).filter(Boolean)) {
    const java = path.join(dir, executable(platform))
    if (isFile(java)) list.push(java)
  }
  const roots = [path.join(home, '.sdkman', 'candidates', 'java'), path.join(home, '.jdks')]
  if (platform === 'darwin') {
    roots.push(
      path.join(machine.systemRoot, 'Library/Java/JavaVirtualMachines'),
      path.join(home, 'Library/Java/JavaVirtualMachines'),
    )
    for (const brew of ['opt/homebrew/opt', 'usr/local/opt'])
      for (const formula of children(path.join(machine.systemRoot, brew)))
        if (path.basename(formula).startsWith('openjdk')) {
          const java = javaIn(path.join(formula, 'libexec/openjdk.jdk'), platform)
          if (java) list.push(java)
        }
  } else if (platform === 'linux') {
    for (const dir of ['usr/lib/jvm', 'usr/java', 'opt/java', 'opt/jdk'])
      roots.push(path.join(machine.systemRoot, dir))
  } else if (platform === 'win32') {
    const programFiles = env.ProgramFiles ?? path.join(machine.systemRoot, 'Program Files')
    for (const vendor of [
      'Eclipse Adoptium',
      'Java',
      'Microsoft',
      'Zulu',
      'Amazon Corretto',
      'BellSoft',
      'Semeru',
    ])
      roots.push(path.join(programFiles, vendor))
    if (env.LOCALAPPDATA) roots.push(path.join(env.LOCALAPPDATA, 'Programs', 'Eclipse Adoptium'))
  }
  for (const root of roots)
    for (const jdk of children(root)) {
      const java = javaIn(jdk, platform)
      if (java) list.push(java)
    }
  return [...new Set(list)]
}

/** The major version in `java -version` output (`openjdk version "25.0.1"`, `"1.8.0_391"` is 8). */
export function parseVersionOutput(output: string): number | null {
  for (const line of output.split('\n')) {
    if (!line.includes(' version "')) continue
    return majorOf(line.split('"')[1] ?? '')
  }
  return null
}

/** The major version in a JDK's `release` file (`JAVA_VERSION="25.0.1"`). */
export function parseReleaseFile(text: string): number | null {
  for (const line of text.split('\n')) {
    const value = line.startsWith('JAVA_VERSION=') ? line.slice('JAVA_VERSION='.length) : null
    if (value != null) return majorOf(value.trim().replace(/^"|"$/g, ''))
  }
  return null
}

function majorOf(version: string): number | null {
  const parts = version.split(/[^0-9]/)
  const first = Number.parseInt(parts[0] ?? '', 10)
  if (Number.isNaN(first)) return null
  if (first !== 1) return first
  const second = Number.parseInt(parts[1] ?? '', 10)
  return Number.isNaN(second) ? null : second
}

/** The major version of [java], from its JDK's `release` file or by running it. */
export function javaVersion(java: string): number | null {
  const home = path.dirname(path.dirname(java))
  const release = path.join(home, 'release')
  if (existsSync(release)) {
    const major = parseReleaseFile(readFileSync(release, 'utf8'))
    if (major != null) return major
  }
  const ran = spawnSync(java, ['-version'], { encoding: 'utf8', timeout: 15_000 })
  if (ran.error) return null
  return parseVersionOutput(`${ran.stderr ?? ''}\n${ran.stdout ?? ''}`)
}

/** The first Java of at least [MIN_JAVA] that the search finds, or null. */
export function findJava(machine: JavaEnv = currentJavaEnv()): string | null {
  for (const java of javaCandidates(machine)) {
    const major = javaVersion(java)
    if (major != null && major >= MIN_JAVA) return java
  }
  return null
}
