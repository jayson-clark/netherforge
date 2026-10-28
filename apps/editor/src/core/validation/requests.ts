/**
 * What the main thread tells the validation worker: only what changed since
 * the last request, so an edit sends one file, not the project.
 */
import type { GameDataBundle, ImageSizes, PackageInputs, ProjectValidation } from '@/core/format'

export interface ValidationRequest {
  /** A new project: forget every file and everything validated before. */
  reset: boolean
  /** Files new or changed since the last request: their text, or null for what format doesn't read. */
  files: Record<string, string | null>
  /** Files gone since the last request. */
  deleted: string[]
  /** The game data, when it changed since the last request (null: there's none). */
  gameData?: GameDataBundle | null
  /** The packages the dependencies resolve to, when they were read again since the last request. */
  packages?: PackageInputs
  /** The pictures' sizes, when they changed since the last request. */
  sizes?: ImageSizes
}

/** A whole project to check once (another project of the workspace), apart from the open one. */
export interface CheckRequest {
  files: Record<string, string | null>
  gameData: GameDataBundle | null
  packages: PackageInputs
}

/** The worker's side (`validator.ts`), as Comlink exposes it. */
export interface ValidationApi {
  validate(request: ValidationRequest): ProjectValidation
  /** Validates [request]'s project once, with a validator of its own (the open project's is untouched). */
  check(request: CheckRequest): ProjectValidation
}

/**
 * Remembers what the worker has been told, and turns the project as it is
 * now into the request that brings the worker up to date.
 */
export class ChangeTracker {
  private sent = new Map<string, string | null>()
  private gameData: GameDataBundle | null | undefined = undefined
  private packages: PackageInputs | undefined = undefined
  private sizes: string | undefined = undefined
  private fresh = true

  /** The worker starts over with the next request (a new project, or a worker that was replaced). */
  reset(): void {
    this.sent = new Map()
    this.gameData = undefined
    this.packages = undefined
    this.sizes = undefined
    this.fresh = true
  }

  /**
   * What changed since the last request, for [files] (every project file, as
   * format reads it), [gameData] and [packages] (sent whole when they were
   * read again; the worker validates only their files that changed).
   */
  request(
    files: Record<string, string | null>,
    gameData: GameDataBundle | null,
    packages: PackageInputs,
    sizes: ImageSizes = {},
  ): ValidationRequest {
    const request: ValidationRequest = { reset: this.fresh, files: {}, deleted: [] }
    const sent = new Map<string, string | null>()
    for (const [path, text] of Object.entries(files)) {
      sent.set(path, text)
      // Unchanged texts are the same strings (disk texts, a model's cached text), so this is mostly a pointer check.
      if (!this.sent.has(path) || this.sent.get(path) !== text) request.files[path] = text
    }
    for (const path of this.sent.keys()) if (!sent.has(path)) request.deleted.push(path)
    if (gameData !== this.gameData) request.gameData = gameData
    if (packages !== this.packages) request.packages = packages
    const sizesText = JSON.stringify(sizes)
    if (sizesText !== this.sizes) request.sizes = sizes
    this.sizes = sizesText
    this.sent = sent
    this.gameData = gameData
    this.packages = packages
    this.fresh = false
    return request
  }
}
