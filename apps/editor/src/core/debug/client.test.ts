import { describe, expect, it } from 'vitest'
import type { DebugProtocol } from '@vscode/debugprotocol'
import { BackendError } from '@/core/backend/types'
import { DapClient, type DapTransport } from './client'

/** An adapter behind a transport: what the client sends is recorded and answered by [answer] (null: never). */
function adapter(
  answer: (request: DebugProtocol.Request) => Partial<DebugProtocol.Response> | null = () => ({}),
) {
  const sent: DebugProtocol.Request[] = []
  let deliver: (message: unknown) => void = () => {}
  let seq = 0
  const transport: DapTransport = {
    async send(message) {
      const request = message as DebugProtocol.Request
      sent.push(request)
      const reply = answer(request)
      if (reply)
        queueMicrotask(() =>
          deliver({
            seq: (seq += 1),
            type: 'response',
            request_seq: request.seq,
            command: request.command,
            success: true,
            ...reply,
          }),
        )
    },
    async listen(listener) {
      deliver = listener
      return () => {}
    },
  }
  return { transport, sent, push: (message: unknown) => deliver(message) }
}

describe('the DAP client', () => {
  it('numbers requests and resolves each with its own response’s body', async () => {
    const plugin = adapter((request) =>
      request.command === 'threads'
        ? { body: { threads: [{ id: 1, name: 'Server thread' }] } }
        : { body: { scopes: [] } },
    )
    const client = new DapClient(plugin.transport)
    const [threads, scopes] = await Promise.all([
      client.request('threads', undefined),
      client.request('scopes', { frameId: 1 }),
    ])
    expect(threads.threads).toEqual([{ id: 1, name: 'Server thread' }])
    expect(scopes.scopes).toEqual([])
    expect(plugin.sent).toEqual([
      { seq: 1, type: 'request', command: 'threads' },
      { seq: 2, type: 'request', command: 'scopes', arguments: { frameId: 1 } },
    ])
  })

  it('rejects with the adapter’s message when a request fails', async () => {
    const client = new DapClient(
      adapter(() => ({ success: false, message: 'the server is running' })).transport,
    )
    await expect(client.request('stackTrace', { threadId: 1 })).rejects.toMatchObject({
      code: 'plugin',
      message: 'the server is running',
    })
  })

  it('hands events to their listeners', async () => {
    const plugin = adapter()
    const client = new DapClient(plugin.transport)
    const stops: unknown[] = []
    const stop = client.on('stopped', (body) => stops.push(body))
    await client.request('threads', undefined)
    plugin.push({
      seq: 9,
      type: 'event',
      event: 'stopped',
      body: { reason: 'breakpoint', threadId: 1 },
    })
    plugin.push({ seq: 10, type: 'event', event: 'continued', body: { threadId: 1 } })
    stop()
    plugin.push({ seq: 11, type: 'event', event: 'stopped', body: { reason: 'step' } })
    expect(stops).toEqual([{ reason: 'breakpoint', threadId: 1 }])
  })

  it('times out an adapter that never answers, and fails what waits on a reset', async () => {
    const silent = new DapClient(adapter(() => null).transport, 20)
    const error = await silent
      .request('initialize', { adapterID: 'netherforge' })
      .catch((e: unknown) => e)
    expect(error).toBeInstanceOf(BackendError)
    expect(error).toMatchObject({ code: 'timeout' })

    const plugin = adapter(() => null)
    const client = new DapClient(plugin.transport)
    const waiting = client.request('threads', undefined)
    await expect.poll(() => plugin.sent.length).toBe(1)
    client.reset()
    await expect(waiting).rejects.toMatchObject({ code: 'notConnected' })
  })
})
