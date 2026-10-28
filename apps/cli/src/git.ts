/**
 * Fetching git packages into the package cache, with the user's own `git`
 * (as npm, pnpm and Go do: their credentials, SSH keys and proxy settings
 * work as they do for everything else). The editor's Rust backend does the
 * same, step for step (`src-tauri/src/fs/git.rs`): keep the two alike,
 * since both must write the same files for a commit or the hash differs.
 *
 * A fetched package is someone else's code, so nothing of the repository
 * is ever run or obeyed: it's fetched into a bare repository of our own
 * (no hooks: an empty template), and its commit's files are written out
 * from the raw objects, never checked out, so no `.gitattributes` filter,
 * no submodule, no LFS smudge and no link of the repository's applies. Only
 * regular files are written; links and submodules are left out, and a path
 * that could reach outside the checkout (`..`, `.git`, `\`, `:`) refuses the
 * whole commit. Only https, ssh and file URLs are fetched from (format
 * checked the URL already; `GIT_ALLOW_PROTOCOL` holds git to it too).
 *
 * The cache (`<data>/packages/git/`):
 * - `db/<first 16 hex of the URL's SHA-256>/`: a bare repository per URL,
 *   holding every commit fetched from it (`refs/netherforge/commits/<commit>`).
 * - `checkouts/<commit>/`: that commit's files (format's `Packages.gitCheckout`),
 *   written under a temp name and renamed into place, so one is whole or absent.
 */
import { spawnSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

/** A git dependency to fetch, as format asks for it (`GitFetchRequest`). */
export interface GitRequest {
  url: string
  /** A branch, tag or full commit; the default branch when absent. */
  rev?: string | null
  /** The commit the lock pins, when it does. */
  commit?: string | null
}

/** Why a fetch failed, in words for the problem format reports. */
export class GitError extends Error {}

const COMMIT = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/

/** The checkout of [commit] in the package cache [cache]. */
export function checkoutDir(cache: string, commit: string): string {
  return path.join(cache, 'git', 'checkouts', commit)
}

/**
 * [request] fetched into [cache] (`<data>/packages`): the commit it names,
 * whose checkout is then in [checkoutDir]. A pinned commit already checked
 * out needs no network at all. Throws a [GitError] saying why it couldn't.
 */
export function fetchGit(cache: string, request: GitRequest): string {
  if (request.commit != null && !COMMIT.test(request.commit))
    throw new GitError(`"${request.commit}" isn't a commit`)
  if (request.commit != null && existsSync(checkoutDir(cache, request.commit)))
    return request.commit
  const repo = database(cache, request.url)
  const commit = fetchCommit(repo, request)
  if (!existsSync(checkoutDir(cache, commit))) checkout(repo, commit, checkoutDir(cache, commit))
  return commit
}

/** What every git command runs with: no prompts (nobody's there to answer), and only the transports format allows. */
function gitEnv(): NodeJS.ProcessEnv {
  return { ...process.env, GIT_TERMINAL_PROMPT: '0', GIT_ALLOW_PROTOCOL: 'https:ssh:file' }
}

/** Runs git with [args]; its stdout, or a [GitError] with what it said. */
function git(args: string[], input?: Buffer): Buffer {
  const result = spawnSync('git', args, {
    env: gitEnv(),
    input,
    maxBuffer: 1 << 30,
    timeout: 10 * 60_000,
    stdio: [input ? 'pipe' : 'ignore', 'pipe', 'pipe'],
  })
  if (result.error) {
    const missing = (result.error as NodeJS.ErrnoException).code === 'ENOENT'
    throw new GitError(
      missing
        ? "git isn't installed, or isn't on the PATH: NetherForge fetches packages with it"
        : result.error.message,
    )
  }
  if (result.status !== 0) {
    const said = result.stderr.toString('utf8').trim().split('\n').filter(Boolean)
    throw new GitError(said.at(-1) ?? `git ${args[0]} failed`)
  }
  return result.stdout
}

/** The bare repository [url] is fetched into, made (empty, without hooks) the first time. */
function database(cache: string, url: string): string {
  const key = createHash('sha256').update(url).digest('hex').slice(0, 16)
  const repo = path.join(cache, 'git', 'db', key)
  if (existsSync(repo)) return repo
  mkdirSync(path.dirname(repo), { recursive: true })
  const staging = `${repo}.tmp-${randomBytes(4).toString('hex')}`
  git(['init', '--bare', '--quiet', '--template=', staging])
  try {
    renameSync(staging, repo)
  } catch {
    // Another fetch made it first.
    rmSync(staging, { recursive: true, force: true })
  }
  return repo
}

/**
 * The commit [request] names, fetched into [repo]: its pinned commit
 * (fetched by id, or with its `rev` when the server won't hand out commits
 * by id), or whatever its `rev` (the default branch: `HEAD`) is now.
 */
function fetchCommit(repo: string, request: GitRequest): string {
  const fetchInto = (what: string): string => {
    const ref = `refs/netherforge/fetch-${randomBytes(6).toString('hex')}`
    git(
      [
        '-C',
        repo,
        'fetch',
        '--quiet',
        '--no-tags',
        '--no-recurse-submodules',
        '--no-write-fetch-head',
        '--',
        request.url,
        `+${what}:${ref}`,
      ],
      undefined,
    )
    try {
      return git(['-C', repo, 'rev-parse', '--verify', '--end-of-options', `${ref}^{commit}`])
        .toString('utf8')
        .trim()
    } finally {
      git(['-C', repo, 'update-ref', '-d', ref])
    }
  }
  const has = (commit: string) => {
    try {
      git(['-C', repo, 'cat-file', '-e', `${commit}^{commit}`])
      return true
    } catch {
      return false
    }
  }
  let commit: string
  if (request.commit != null) {
    commit = request.commit
    if (!has(commit)) {
      try {
        fetchInto(commit)
      } catch {
        fetchInto(request.rev ?? 'HEAD')
      }
      if (!has(commit))
        throw new GitError(`${request.url} has no commit ${commit} (was it force-pushed away?)`)
    }
  } else {
    try {
      commit = fetchInto(request.rev ?? 'HEAD')
    } catch (error) {
      throw new GitError(
        `${(error as Error).message}${request.rev ? ` (is "${request.rev}" a branch, a tag or a full commit there?)` : ''}`,
      )
    }
  }
  // Kept, so the commit's objects stay in the database.
  git(['-C', repo, 'update-ref', `refs/netherforge/commits/${commit}`, commit])
  return commit
}

/** Whether [file], a path in a commit's tree, is safe to write under the checkout on every OS. */
function isSafePath(file: string): boolean {
  return file
    .split('/')
    .every(
      (part) =>
        part !== '' &&
        part !== '.' &&
        part !== '..' &&
        part.toLowerCase() !== '.git' &&
        !/[\\:\0]/.test(part),
    )
}

/** [commit]'s regular files out of [repo] into [target]: written aside and renamed into place. */
function checkout(repo: string, commit: string, target: string): void {
  const listed = git(['-C', repo, 'ls-tree', '-r', '-z', '--full-tree', commit])
  const files: { object: string; path: string }[] = []
  for (const entry of listed.toString('utf8').split('\0')) {
    if (!entry) continue
    const tab = entry.indexOf('\t')
    const [mode, type, object] = entry.slice(0, tab).split(' ')
    const file = entry.slice(tab + 1)
    // Links and submodules are left out: only a commit's plain files are a package.
    if (type !== 'blob' || (mode !== '100644' && mode !== '100755')) continue
    if (!isSafePath(file))
      throw new GitError(`${commit} has a file named "${file}", which can't be written safely`)
    files.push({ object: object!, path: file })
  }
  const blobs = readBlobs(
    repo,
    files.map((it) => it.object),
  )
  const staging = path.join(
    path.dirname(target),
    `.${path.basename(target)}.tmp-${randomBytes(4).toString('hex')}`,
  )
  try {
    for (const [index, { path: file }] of files.entries()) {
      const to = path.join(staging, ...file.split('/'))
      mkdirSync(path.dirname(to), { recursive: true })
      writeFileSync(to, blobs[index]!)
    }
    mkdirSync(staging, { recursive: true })
    try {
      renameSync(staging, target)
    } catch (error) {
      // Another fetch wrote it first: theirs is the same commit's files.
      if (!existsSync(target)) throw error
    }
  } finally {
    rmSync(staging, { recursive: true, force: true })
  }
}

/** The raw bytes of each blob in [objects], in order, read in one `cat-file --batch`. */
function readBlobs(repo: string, objects: string[]): Buffer[] {
  if (objects.length === 0) return []
  const out = git(['-C', repo, 'cat-file', '--batch'], Buffer.from(objects.join('\n') + '\n'))
  const blobs: Buffer[] = []
  let at = 0
  for (const object of objects) {
    const end = out.indexOf(0x0a, at)
    const [name, type, size] = out.subarray(at, end).toString('utf8').split(' ')
    if (name !== object || type !== 'blob') throw new GitError(`couldn't read ${object}`)
    const start = end + 1
    blobs.push(out.subarray(start, start + Number(size)))
    at = start + Number(size) + 1
  }
  return blobs
}
