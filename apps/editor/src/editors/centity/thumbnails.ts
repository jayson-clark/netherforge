/**
 * Pictures of centities for the explorer and the outline, by id and the text
 * they were drawn from: a changed centity is drawn again, an unchanged one
 * never, not even after a restart. Drawing happens in `ThumbnailStudio`, one
 * at a time, from the queue here; each picture is then kept in the project's
 * `.netherforge/thumbnails/` (`<id>.png`, and `index.json` saying which key
 * each was drawn for), which the next session shows without drawing.
 */
import { create } from 'zustand'
import type { Backend } from '@/core/backend/types'

export interface ThumbnailJob {
  key: string
  id: string
  text: string
}

interface Thumbnails {
  /** Pictures drawn this session by key, as data URLs; null when the centity couldn't be drawn. */
  done: Record<string, string | null>
  /** Each centity's newest picture, shown while a changed one is drawn again. */
  latest: Record<string, string>
  queue: ThumbnailJob[]
  /** The project whose cache is loaded. */
  root: string | null
  /** Its cache: id → the key its saved picture was drawn for. Null while it's read. */
  saved: Record<string, string> | null
}

export const THUMBNAIL_FOLDER = '.netherforge/thumbnails'
const INDEX = `${THUMBNAIL_FOLDER}/index.json`
const fileOf = (id: string) => `${THUMBNAIL_FOLDER}/${id}.png`

const EMPTY: Omit<Thumbnails, 'root'> = { done: {}, latest: {}, queue: [], saved: null }

export const useThumbnails = create<Thumbnails>(() => ({ ...EMPTY, root: null }))

/** A short, stable hash of [text] (FNV-1a), to key a picture by what it shows. */
export function textHash(text: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(36)
}

/** What a picture depends on: the centity's text and which client's models drew it. */
export const thumbnailKey = (id: string, text: string, assets: string | null) =>
  `${id}:${textHash(text)}:${assets ?? '-'}`

/** Reads [root]'s saved pictures, once per project; another project starts afresh. */
export async function loadThumbnails(backend: Backend, root: string): Promise<void> {
  if (useThumbnails.getState().root === root) return
  useThumbnails.setState({ ...EMPTY, root })
  const saved: Record<string, string> = {}
  try {
    const parsed: unknown = JSON.parse(await backend.readText(INDEX))
    if (typeof parsed === 'object' && parsed !== null) {
      for (const [id, key] of Object.entries(parsed)) if (typeof key === 'string') saved[id] = key
    }
  } catch {
    // No cache yet, or an unreadable one: everything is drawn and saved again.
  }
  if (useThumbnails.getState().root === root) useThumbnails.setState({ saved })
}

/** What the store holds for one centity, picked as primitives (a selector must not build objects). */
export interface ThumbnailSlice {
  /** Its picture drawn this session for the key: a URL, null if it couldn't be, undefined if not drawn. */
  drawn: string | null | undefined
  /** The key its saved picture was drawn for. */
  savedKey: string | undefined
  /** Its newest picture drawn this session, whatever the key. */
  latest: string | undefined
}

/**
 * The picture to show for [id] at [key]: drawn this session, else the saved
 * file when it was drawn for this key. `stale` is an older picture of it, to
 * show while the new one is drawn.
 */
export function pictureOf(
  backend: Backend,
  id: string,
  key: string,
  { drawn, savedKey, latest }: ThumbnailSlice,
): { url: string | null | undefined; stale: string | undefined } {
  const savedUrl = savedKey ? backend.projectFileUrl(fileOf(id), stampOf(savedKey)) : undefined
  if (drawn !== undefined) return { url: drawn, stale: undefined }
  if (savedKey === key) return { url: savedUrl, stale: undefined }
  return { url: undefined, stale: latest ?? savedUrl }
}

/** Queues [job] unless it's drawn, queued, or saved for its key (or the cache isn't read yet). */
export function requestThumbnail(job: ThumbnailJob) {
  const { done, queue, saved } = useThumbnails.getState()
  if (saved === null || saved[job.id] === job.key) return
  if (job.key in done || queue.some((it) => it.key === job.key)) return
  useThumbnails.setState({ queue: [...queue, job] })
}

/** A saved picture that wouldn't load (deleted by hand): forget it, so it's drawn again. */
export function forgetSaved(id: string) {
  const { saved } = useThumbnails.getState()
  if (!saved?.[id]) return
  const next = { ...saved }
  delete next[id]
  useThumbnails.setState({ saved: next })
}

// Cache writes go one after another, so the index never loses a picture to a race.
let writing: Promise<unknown> = Promise.resolve()

/**
 * Records a drawn picture and saves it. [ids] are the project's centities
 * now: the cache forgets any others (deleted or renamed), files included.
 */
export function finishThumbnail(
  backend: Backend,
  job: ThumbnailJob,
  url: string | null,
  ids: string[],
): Promise<unknown> {
  const { done, latest, queue, root } = useThumbnails.getState()
  useThumbnails.setState({
    done: { ...done, [job.key]: url },
    latest: url ? { ...latest, [job.id]: url } : latest,
    queue: queue.filter((it) => it.key !== job.key),
  })
  if (!url || !root) return writing
  writing = writing
    .then(() => saveThumbnail(backend, root, job, url, ids))
    .catch(() => {
      // Only a cache: the picture still shows, and is drawn again next session.
    })
  return writing
}

async function saveThumbnail(
  backend: Backend,
  root: string,
  job: ThumbnailJob,
  url: string,
  ids: string[],
) {
  await backend.writeBytes(fileOf(job.id), bytesOfDataUrl(url))
  const state = useThumbnails.getState()
  if (state.root !== root) return
  const live = new Set([...ids, job.id])
  const saved: Record<string, string> = { [job.id]: job.key }
  const gone: string[] = []
  for (const [id, key] of Object.entries(state.saved ?? {})) {
    if (id === job.id) continue
    if (live.has(id)) saved[id] = key
    else gone.push(id)
  }
  useThumbnails.setState({ saved })
  await backend.writeText(INDEX, `${JSON.stringify(saved, null, 2)}\n`)
  for (const id of gone) await backend.deletePath(fileOf(id)).catch(() => {})
}

/** The webview caches by URL: a new key gets a new one. */
const stampOf = (key: string) => parseInt(textHash(key), 36)

function bytesOfDataUrl(url: string): Uint8Array {
  const binary = atob(url.slice(url.indexOf(',') + 1))
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return bytes
}
