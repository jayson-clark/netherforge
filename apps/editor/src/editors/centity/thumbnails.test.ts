import { beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { EXAMPLE_ROOT } from '@/testing/fixtures'
import { exampleBackend } from '@/testing/workspace'
import {
  finishThumbnail,
  forgetSaved,
  loadThumbnails,
  pictureOf,
  requestThumbnail,
  textHash,
  THUMBNAIL_FOLDER,
  thumbnailKey,
  useThumbnails,
} from './thumbnails'

// A one-byte "PNG": the cache stores whatever the studio drew.
const PICTURE = 'data:image/png;base64,AQ=='
const INDEX = `${THUMBNAIL_FOLDER}/index.json`

let backend: MemoryBackend

beforeEach(async () => {
  backend = exampleBackend()
  await backend.openProject(EXAMPLE_ROOT)
  useThumbnails.setState({ root: null })
  await loadThumbnails(backend, EXAMPLE_ROOT)
})

const slice = (id: string, key: string) => {
  const s = useThumbnails.getState()
  return { drawn: s.done[key], savedKey: s.saved?.[id], latest: s.latest[id] }
}

describe('centity thumbnails', () => {
  it('keys a picture by what it shows and which client drew it', () => {
    expect(textHash('a')).toBe(textHash('a'))
    expect(textHash('a')).not.toBe(textHash('b'))
    expect(thumbnailKey('tower', '{}', '26.3')).not.toBe(thumbnailKey('tower', '{}', null))
  })

  it('queues each picture once, and never again once drawn', async () => {
    const job = { key: 'k', id: 'tower', text: '{}' }
    requestThumbnail(job)
    requestThumbnail(job)
    expect(useThumbnails.getState().queue).toEqual([job])
    await finishThumbnail(backend, job, PICTURE, ['tower'])
    expect(useThumbnails.getState().queue).toEqual([])
    requestThumbnail(job)
    expect(useThumbnails.getState().queue).toEqual([])
  })

  it('saves a drawing in the project, and the next session shows it without drawing', async () => {
    const job = { key: 'tower:1', id: 'tower', text: '{}' }
    await finishThumbnail(backend, job, PICTURE, ['tower', 'lamp'])
    expect(backend.testBytes(`${THUMBNAIL_FOLDER}/tower.png`)).toBe('AQ==')
    expect(JSON.parse(backend.testFiles()[INDEX]!)).toEqual({ tower: 'tower:1' })

    // Another session: the saved picture is the one shown, and nothing is queued.
    useThumbnails.setState({ root: null })
    await loadThumbnails(backend, EXAMPLE_ROOT)
    requestThumbnail(job)
    expect(useThumbnails.getState().queue).toEqual([])
    const shown = pictureOf(backend, 'tower', 'tower:1', slice('tower', 'tower:1'))
    expect(shown.url).toMatch(/^data:image\/png/)

    // A changed centity is drawn again, showing the saved picture meanwhile.
    const changed = { ...job, key: 'tower:2', text: '{"nodes":{}}' }
    requestThumbnail(changed)
    expect(useThumbnails.getState().queue).toEqual([changed])
    const meanwhile = pictureOf(backend, 'tower', 'tower:2', slice('tower', 'tower:2'))
    expect(meanwhile).toEqual({ url: undefined, stale: shown.url })
  })

  it('forgets centities that are gone, files included, and a saved picture that went missing', async () => {
    await finishThumbnail(backend, { key: 'a:1', id: 'old', text: '{}' }, PICTURE, ['old'])
    await finishThumbnail(backend, { key: 'b:1', id: 'tower', text: '{}' }, PICTURE, ['tower'])
    expect(JSON.parse(backend.testFiles()[INDEX]!)).toEqual({ tower: 'b:1' })
    expect(backend.testBytes(`${THUMBNAIL_FOLDER}/old.png`)).toBeNull()

    forgetSaved('tower')
    const job = { key: 'b:1', id: 'tower', text: '{}' }
    useThumbnails.setState({ done: {} })
    requestThumbnail(job)
    expect(useThumbnails.getState().queue).toEqual([job])
  })

  it('draws everything again when the cache is unreadable', async () => {
    backend.testWrite(INDEX, 'not json')
    useThumbnails.setState({ root: null })
    await loadThumbnails(backend, EXAMPLE_ROOT)
    expect(useThumbnails.getState().saved).toEqual({})
  })
})
