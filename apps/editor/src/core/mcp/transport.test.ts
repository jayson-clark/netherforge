import { describe, expect, it, vi } from 'vitest'
import { LATEST_PROTOCOL_VERSION } from '@modelcontextprotocol/sdk/types.js'
import { MemoryBackend } from '@/testing/backend/memory'
import { createApp } from '@/state/providers'

/** An app whose MCP server is connected, as the editor starts it. */
async function connected() {
  const backend = new MemoryBackend({ appInfo: { version: '1.2.3' } })
  createApp(backend)
  // createApp connects in the background.
  await vi.waitFor(() => expect(backend.testMcpConnected).toBe(true))
  expect((await backend.testMcpCall('ping')).result).toEqual({})
  return backend
}

describe('the MCP transport', () => {
  it('lets the SDK do the handshake', async () => {
    const backend = await connected()
    const response = await backend.testMcpCall('initialize', {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'test', version: '0' },
    })
    expect(response.result).toMatchObject({
      protocolVersion: '2025-06-18',
      serverInfo: { name: 'netherforge', title: 'NetherForge editor', version: '1.2.3' },
      capabilities: { tools: {} },
      instructions: expect.stringContaining('.netherforge/docs/README.md'),
    })
    const newest = await backend.testMcpCall('initialize', {
      protocolVersion: '1999-01-01',
      capabilities: {},
      clientInfo: { name: 'test', version: '0' },
    })
    expect((newest.result as { protocolVersion: string }).protocolVersion).toBe(
      LATEST_PROTOCOL_VERSION,
    )
  })

  it('refuses a request whose protocol version header the SDK does not speak', async () => {
    const backend = await connected()
    const refused = await backend.testMcpCall('tools/list', {}, '2000-01-01')
    expect(refused.error?.message).toMatch(/Unsupported MCP-Protocol-Version 2000-01-01/)
    expect((await backend.testMcpCall('tools/list', {}, '2025-06-18')).result).toBeDefined()
  })

  it('answers an unknown method as JSON-RPC says, and ignores what is no message', async () => {
    const backend = await connected()
    expect((await backend.testMcpCall('resources/nope')).error?.code).toBe(-32601)
    const sent = vi.spyOn(backend, 'mcpSend')
    backend.testMcpMessage('hello')
    backend.testMcpMessage({ jsonrpc: '2.0', method: 'notifications/initialized' })
    // Messages are handled in order: once a later request is answered, these two have been
    // handled, and nothing was sent for them.
    expect((await backend.testMcpCall('tools/list')).result).toBeDefined()
    expect(sent.mock.calls.length).toBe(1)
  })
})
