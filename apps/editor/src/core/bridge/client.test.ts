import { describe, expect, it } from 'vitest'
// eslint-disable-next-line no-restricted-imports -- format's shared fixture, outside src/
import session from '../../../../../packages/format/testdata/bridge/session.ndjson?raw'
import { BackendError } from '@/core/backend/types'
import { BridgeClient, type BridgeTransport } from './client'

interface Frame {
  jsonrpc: '2.0'
  id?: number | string | null
  method?: string
  params?: unknown
  result?: unknown
  error?: { code: number; message: string }
}

/** The recorded session's frames, which every side of the bridge reads. */
const recorded: Frame[] = session
  .split('\n')
  .filter((line) => line.trim())
  .flatMap((line) => JSON.parse(line) as Frame | Frame[])

/**
 * A relay with a plugin behind it: what the client sends is recorded and
 * answered by [answer] (null: never), and the test pushes notifications and
 * the bridge's ups and downs.
 */
function relay(answer: (request: Frame) => Partial<Frame> | null = () => ({ result: null })) {
  const sent: Frame[] = []
  let deliver: (message: string) => void = () => {}
  let connected: (up: boolean) => void = () => {}
  let up = true
  const transport: BridgeTransport = {
    async send(message) {
      if (!up) throw new BackendError('notConnected', "The NetherForge plugin isn't connected")
      const request = JSON.parse(message) as Frame
      sent.push(request)
      const reply = answer(request)
      if (reply)
        queueMicrotask(() => deliver(JSON.stringify({ jsonrpc: '2.0', id: request.id, ...reply })))
    },
    async listen(listener) {
      deliver = listener
      return () => {}
    },
    async onConnected(listener) {
      connected = listener
      return () => {}
    },
  }
  return {
    transport,
    sent,
    push: (frame: Frame) => deliver(JSON.stringify(frame)),
    setUp(value: boolean) {
      up = value
      connected(value)
    },
  }
}

describe('the bridge client', () => {
  it('sends requests as JSON-RPC, as the recorded session does, and resolves with their results', async () => {
    const reload = recorded.find((it) => it.method === 'reload')!
    const answered = recorded.find((it) => it.id === reload.id && 'result' in it)!
    const plugin = relay((request) =>
      request.method === 'reload' ? { result: answered.result } : { result: [] },
    )
    const client = new BridgeClient(plugin.transport)

    const result = await client.request('reload', reload.params as { paths: string[] })
    expect(result).toEqual(answered.result)
    expect(plugin.sent[0]).toMatchObject({
      jsonrpc: '2.0',
      method: 'reload',
      params: reload.params,
    })
    expect(result.resources[0]).toMatchObject({ package: 'basic', kind: 'centity', id: 'tower' })

    // A method without params is sent without them.
    expect(await client.request('instances')).toEqual([])
    expect(plugin.sent[1]).not.toHaveProperty('params')
  })

  it("rejects with the plugin's error: an unknown method is its own code", async () => {
    const unknown = recorded.find((it) => it.error?.code === -32601)!
    const failed = recorded.find((it) => it.error?.code === -32000)!
    const plugin = relay((request) => ({
      error: request.method === 'bots/list' ? unknown.error : failed.error,
    }))
    const client = new BridgeClient(plugin.transport)

    await expect(client.request('bots/list')).rejects.toMatchObject({
      code: 'unknownMethod',
      message: unknown.error!.message,
    })
    await expect(client.request('command', { line: 'time set day' })).rejects.toMatchObject({
      code: 'plugin',
      message: failed.error!.message,
    })
  })

  it('fails what waits when the plugin goes away, and starts afresh when it comes back', async () => {
    const plugin = relay(() => null)
    const client = new BridgeClient(plugin.transport)
    const waiting = client.request('instances')
    await expect.poll(() => plugin.sent.length).toBe(1)
    plugin.setUp(false)
    await expect(waiting).rejects.toMatchObject({ code: 'notConnected' })
    await expect(client.request('instances')).rejects.toMatchObject({ code: 'notConnected' })

    plugin.setUp(true)
    const again = client.request('ping')
    await expect.poll(() => plugin.sent.length).toBe(2)
    plugin.push({ jsonrpc: '2.0', id: plugin.sent[1]!.id, result: null })
    expect(await again).toBeNull()
  })

  it("sends the debugger's DAP messages as notifications, as the recorded session does", async () => {
    const recordedDap = recorded.find(
      (it) => it.method === 'dap' && (it.params as { type?: string }).type === 'request',
    )!
    const plugin = relay(() => null)
    const client = new BridgeClient(plugin.transport)
    await client.notify('dap', recordedDap.params as object)
    expect(plugin.sent).toEqual([{ jsonrpc: '2.0', method: 'dap', params: recordedDap.params }])

    plugin.setUp(false)
    await expect(client.notify('dap', {})).rejects.toMatchObject({ code: 'notConnected' })
  })

  it("times out a request the plugin doesn't answer", async () => {
    const client = new BridgeClient(relay(() => null).transport, 20)
    const error = await client.request('ping').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(BackendError)
    expect(error).toMatchObject({ code: 'timeout' })
  })

  it("hands each event's params to its listeners only, in order", async () => {
    const plugin = relay()
    const client = new BridgeClient(plugin.transport)
    const consoles: unknown[] = []
    const problems: unknown[] = []
    await client.onBridgeEvent('console', ({ items }) => consoles.push(...items))
    const stop = await client.onBridgeEvent('problems', (params) => problems.push(params))

    for (const frame of recorded.filter((it) => it.method && it.id === undefined))
      plugin.push(frame)
    const recordedConsole = recorded.find((it) => it.method === 'console')!.params as {
      items: unknown[]
    }
    await expect.poll(() => consoles).toEqual(recordedConsole.items)
    expect(problems).toEqual([recorded.find((it) => it.method === 'problems')!.params])

    stop()
    plugin.push({ jsonrpc: '2.0', method: 'problems', params: { problems: [] } })
    await expect.poll(() => consoles.length).toBe(3)
    expect(problems).toHaveLength(1)
  })
})
