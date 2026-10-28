import { describe, expect, it, vi } from 'vitest'
import type { Message } from 'vscode-jsonrpc'
import { MemoryBackend } from '@/testing/backend/memory'
import type { Backend, LualsExit, LualsMessage } from '@/core/backend/types'
import { connectLuals } from './transport'

/** A backend whose lua-language-server is played by the test. */
function fakeServer() {
  const backend: Backend = new MemoryBackend()
  let onMessage: ((event: LualsMessage) => void) | null = null
  let onExit: ((event: LualsExit) => void) | null = null
  const sent: { generation: number; message: string }[] = []
  backend.onLualsMessage = async (listener) => ((onMessage = listener), () => (onMessage = null))
  backend.onLualsExit = async (listener) => ((onExit = listener), () => (onExit = null))
  backend.lualsStart = async () => {
    // The server may speak before the start command has even answered.
    onMessage?.({ generation: 7, message: '{"jsonrpc":"2.0","method":"early"}' })
    return { generation: 7, plugin: '/data/luals/plugin.lua' }
  }
  backend.lualsSend = async (generation, message) => void sent.push({ generation, message })
  return {
    backend,
    sent,
    say: (generation: number, message: object) =>
      onMessage?.({ generation, message: JSON.stringify(message) }),
    exit: (generation: number) => onExit?.({ generation, code: 1 }),
  }
}

describe('the connection to lua-language-server', () => {
  it("reads its server's messages, none lost before listening, and writes to it", async () => {
    const server = fakeServer()
    const connection = await connectLuals(server.backend)
    expect(connection.started.plugin).toBe('/data/luals/plugin.lua')
    const read: Message[] = []
    server.say(7, { jsonrpc: '2.0', method: 'before-listen' })
    connection.reader.listen((message) => read.push(message))
    server.say(7, { jsonrpc: '2.0', method: 'after' })
    // A previous server's last words are ignored.
    server.say(6, { jsonrpc: '2.0', method: 'stale' })
    expect(read.map((it) => (it as unknown as { method: string }).method)).toEqual([
      'early',
      'before-listen',
      'after',
    ])

    await connection.writer.write({ jsonrpc: '2.0', id: 1, method: 'initialize' } as Message)
    expect(server.sent).toEqual([
      { generation: 7, message: '{"jsonrpc":"2.0","id":1,"method":"initialize"}' },
    ])
  })

  it('closes both ways when its server exits', async () => {
    const server = fakeServer()
    const connection = await connectLuals(server.backend)
    const readerClosed = vi.fn()
    const writerClosed = vi.fn()
    connection.reader.onClose(readerClosed)
    connection.writer.onClose(writerClosed)
    server.exit(6)
    expect(readerClosed).not.toHaveBeenCalled()
    server.exit(7)
    expect(readerClosed).toHaveBeenCalled()
    expect(writerClosed).toHaveBeenCalled()
  })

  it("fails where there's no server, leaving nothing listening", async () => {
    const backend = new MemoryBackend({ projects: { '/p': { 'netherforge.json': '{}' } } })
    await backend.openProject('/p')
    await expect(connectLuals(backend)).rejects.toThrow(/only runs in the desktop editor/)
  })
})
