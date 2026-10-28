import { describe, expect, it, vi } from 'vitest'
import { MemoryBackend } from '@/testing/backend/memory'
import { createUpdates } from './updates'

const update = { version: '9.9.9', currentVersion: '0.1.0', notes: 'New', date: null }

describe('updates', () => {
  it('reports no update by default', async () => {
    const updates = createUpdates(new MemoryBackend())
    await updates.getState().check()
    expect(updates.getState()).toMatchObject({ available: null, upToDate: true, error: null })
  })

  it('stops the server before installing', async () => {
    const backend = new MemoryBackend({ update })
    const updates = createUpdates(backend)
    await updates.getState().check({ quiet: true })
    expect(updates.getState().available?.version).toBe('9.9.9')
    const order: string[] = []
    const install = vi.spyOn(backend, 'installUpdate').mockImplementation(async () => {
      order.push('install')
    })
    await updates.getState().install(async () => {
      order.push('stop')
    })
    expect(order).toEqual(['stop', 'install'])
    expect(install).toHaveBeenCalledOnce()
  })

  it('stays quiet about a failed check at start, and says so when asked', async () => {
    const backend = new MemoryBackend()
    vi.spyOn(backend, 'checkForUpdate').mockRejectedValue(new Error('offline'))
    const updates = createUpdates(backend)
    await updates.getState().check({ quiet: true })
    expect(updates.getState().error).toBeNull()
    await updates.getState().check()
    expect(updates.getState().error).toContain('offline')
  })

  it("doesn't install when stopping the server fails", async () => {
    const backend = new MemoryBackend({ update })
    const updates = createUpdates(backend)
    await updates.getState().check()
    await updates.getState().install(async () => {
      throw new Error('still running')
    })
    expect(backend.updatesInstalled).toEqual([])
    expect(updates.getState().error).toContain('still running')
  })
})
