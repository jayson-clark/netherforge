import { describe, expect, it, vi } from 'vitest'
import type { TerrainPreviewResult } from '@/core/format'
import type { PreviewFile, SliceRequest } from './core'
import { Lane, LatestPreview, type PreviewClient, type PreviewState } from './client'

/** A drawing whose answers the test gives, one request at a time. */
function fakeDraws() {
  const asked: string[] = []
  const pending: { resolve: (a: string) => void; reject: (e: Error) => void }[] = []
  const draw = (request: string) =>
    new Promise<string>((resolve, reject) => {
      asked.push(request)
      pending.push({ resolve, reject })
    })
  return { asked, pending, draw }
}

describe('the slice asks for the newest picture only', () => {
  it('draws one at a time, dropping requests a newer one overtook', async () => {
    const draws = fakeDraws()
    const states: PreviewState<string, string>[] = []
    const queue = new LatestPreview<string, string>(draws.draw, (state) => states.push(state))
    queue.request('1')
    queue.request('2')
    queue.request('3')
    expect(draws.asked).toEqual(['1'])
    draws.pending[0]!.resolve('one')
    // 2 was overtaken by 3 while 1 was drawn.
    await vi.waitFor(() => expect(draws.asked).toEqual(['1', '3']))
    expect(states.at(-1)?.drawing).toBe(true)
    draws.pending[1]!.resolve('three')
    await vi.waitFor(() => expect(states.at(-1)?.drawn).toBe('3'))
    expect(states.at(-1)?.answer).toBe('three')
    expect(states.at(-1)?.drawing).toBe(false)
  })

  it('says why a drawing failed, and draws the next', async () => {
    const draws = fakeDraws()
    const states: PreviewState<string, string>[] = []
    const queue = new LatestPreview<string, string>(draws.draw, (state) => states.push(state))
    queue.request('1')
    draws.pending[0]!.reject(new Error('it stopped'))
    await vi.waitFor(() => expect(states.at(-1)?.error).toBe('it stopped'))
    queue.request('2')
    draws.pending[1]!.resolve('two')
    await vi.waitFor(() => expect(states.at(-1)?.drawn).toBe('2'))
    expect(states.at(-1)?.error).toBeNull()
  })
})

describe('a lane', () => {
  const FILE = { id: 'a' } as PreviewFile
  const SLICE = { type: 'slice' } as unknown as TerrainPreviewResult

  /** Workers that record whether each request carried the file, and fail when told to. */
  function fakeWorkers() {
    const sent: (string | undefined)[] = []
    let made = 0
    let disposed = 0
    let fail = false
    const connect = (): PreviewClient => {
      made += 1
      return {
        tile: () => Promise.reject(new Error('unused')),
        slice: (request: SliceRequest) => {
          sent.push(request.file?.id)
          return fail ? Promise.reject(new Error('it stopped')) : Promise.resolve(SLICE)
        },
        dispose: () => {
          disposed += 1
        },
      }
    }
    return {
      sent,
      connect,
      made: () => made,
      disposed: () => disposed,
      failNext: (it: boolean) => {
        fail = it
      },
    }
  }

  const ask = (lane: Lane, key: string) =>
    lane.run(key, FILE, (client, file) => client.slice({ fileKey: key, file } as SliceRequest))

  it('sends the file once per key, and again to a worker started over', async () => {
    const workers = fakeWorkers()
    const lane = new Lane(workers.connect)
    await ask(lane, 'k1')
    await ask(lane, 'k1')
    await ask(lane, 'k2')
    expect(workers.sent).toEqual(['a', undefined, 'a'])
    workers.failNext(true)
    await expect(ask(lane, 'k2')).rejects.toThrow('it stopped')
    expect([workers.made(), workers.disposed()]).toEqual([2, 1])
    workers.failNext(false)
    await ask(lane, 'k2')
    expect(workers.sent.at(-1)).toBe('a')
    lane.dispose()
    expect(workers.disposed()).toBe(2)
  })
})
