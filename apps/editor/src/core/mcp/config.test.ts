import { describe, expect, it } from 'vitest'
import { MEMORY_MCP_TOKEN, MemoryBackend } from '@/testing/backend/memory'
import {
  MCP_SERVER_NAME,
  OPENCODE_CONFIG_FILE,
  claudeAddCommand,
  syncMcpConfig,
  syncOpencodeConfig,
  withNetherforgeServer,
  withTokenFilesIgnored,
  withOpencodeServer,
} from './config'

const here = { port: 4000, token: 'abc' }
const server = {
  type: 'http',
  url: 'http://127.0.0.1:4000/mcp',
  headers: { Authorization: 'Bearer abc' },
}

describe('.mcp.json', () => {
  it('is created with the netherforge server and its token', () => {
    expect(JSON.parse(withNetherforgeServer(null, here)!)).toEqual({
      mcpServers: { netherforge: server },
    })
  })

  it("merges into the user's own, keeping everything in it", () => {
    const mine = JSON.stringify({ mcpServers: { other: { command: 'x' } }, extra: 1 })
    expect(JSON.parse(withNetherforgeServer(mine, here)!)).toEqual({
      mcpServers: { other: { command: 'x' }, netherforge: server },
      extra: 1,
    })
  })

  it("brings another editor's entry up to date, and leaves a current one alone", () => {
    const stale = JSON.stringify({
      mcpServers: { netherforge: { type: 'http', url: 'http://127.0.0.1:47615/mcp' } },
    })
    expect(JSON.parse(withNetherforgeServer(stale, here)!)).toEqual({
      mcpServers: { netherforge: server },
    })
    const current = withNetherforgeServer(null, here)
    expect(withNetherforgeServer(current, here)).toBeNull()
  })

  it("leaves a file alone that doesn't parse", () => {
    expect(withNetherforgeServer('{ nope', here)).toBeNull()
    expect(withNetherforgeServer('[]', here)).toBeNull()
    expect(withNetherforgeServer('{"mcpServers": 1}', here)).toBeNull()
  })

  it('on open, updates only a file that lists the netherforge server', async () => {
    const backend = new MemoryBackend({ projects: { '/p': { 'netherforge.json': '{}' } } })
    await backend.openProject('/p')
    expect(await syncMcpConfig(backend, [], false)).toBe(false)
    expect(backend.testFiles()['.mcp.json']).toBeUndefined()

    const others = JSON.stringify({ mcpServers: { other: { command: 'x' } } })
    backend.testWrite('.mcp.json', others)
    expect(await syncMcpConfig(backend, ['.mcp.json'], false)).toBe(false)
    expect(backend.testFiles()['.mcp.json']).toBe(others)

    backend.testWrite('.mcp.json', JSON.stringify({ mcpServers: { netherforge: {} } }))
    expect(await syncMcpConfig(backend, ['.mcp.json'], false)).toBe(true)
    const written = JSON.parse(backend.testFiles()['.mcp.json']!)
    expect(written.mcpServers.netherforge.headers).toEqual({
      Authorization: `Bearer ${MEMORY_MCP_TOKEN}`,
    })
    expect(await syncMcpConfig(backend, ['.mcp.json'], false)).toBe(false)
  })

  it('tells a terminal how to connect, header and all', () => {
    expect(claudeAddCommand(here)).toBe(
      'claude mcp add --transport http netherforge http://127.0.0.1:4000/mcp --header "Authorization: Bearer abc"',
    )
  })
})

describe('opencode.json', () => {
  const serverRemote = {
    type: 'remote',
    url: 'http://127.0.0.1:4000/mcp',
    headers: { authorization: 'Bearer abc' },
  }

  it('is created for a fresh opencode.json with the netherforge server', () => {
    const written = JSON.parse(withOpencodeServer(null, here)!)
    expect(written.mcp).toEqual({ [MCP_SERVER_NAME]: serverRemote })
  })

  it('merges netherforge into empty mcp map', () => {
    const mine = '{}'
    const result = JSON.parse(withOpencodeServer(mine, here)!)
    expect(result.mcp[MCP_SERVER_NAME]).toEqual(serverRemote)
  })

  it('merges into existing opencode.json, keeping other servers', () => {
    const mine = JSON.stringify({
      model: 'anthropic/claude-sonnet-4.5',
      mcp: { other: { type: 'remote', url: 'https://x.com/mcp' } },
    })
    const result = JSON.parse(withOpencodeServer(mine, here)!)
    expect(result.model).toBe('anthropic/claude-sonnet-4.5')
    expect(result.mcp.other).toEqual({ type: 'remote', url: 'https://x.com/mcp' })
    expect(result.mcp[MCP_SERVER_NAME]).toEqual(serverRemote)
  })

  it('brings a stale netherforge entry up to date', () => {
    const stale = JSON.stringify({
      mcp: { netherforge: { type: 'remote', url: 'http://127.0.0.1:9999/mcp' } },
    })
    const parsed = JSON.parse(withOpencodeServer(stale, here)!)
    expect(parsed.mcp[MCP_SERVER_NAME].url).toBe('http://127.0.0.1:4000/mcp')
  })

  it('leaves a current netherforge entry alone', () => {
    const mine = JSON.stringify({ mcp: { netherforge: serverRemote } })
    expect(withOpencodeServer(mine, here)).toBeNull()
  })

  it('on open, updates only if there is a netherforge server', async () => {
    const backend = new MemoryBackend({ projects: { '/p': { 'netherforge.json': '{}' } } })
    await backend.openProject('/p')
    expect(await syncOpencodeConfig(backend, [], false)).toBe(false)

    // Only other servers, no netherforge: should create/update
    backend.testWrite(OPENCODE_CONFIG_FILE, JSON.stringify({ mcp: { other: {} } }))
    expect(await syncOpencodeConfig(backend, [OPENCODE_CONFIG_FILE], true)).toBe(true)

    // Now with netherforge added, a second call should be no-op
    expect(await syncOpencodeConfig(backend, [OPENCODE_CONFIG_FILE], true)).toBe(false)
  })
})

describe('.gitignore', () => {
  it('gains a line for each token file it misses, keeping what it has', () => {
    expect(withTokenFilesIgnored('.netherforge/\n.mcp.json\n')).toBe(
      ".netherforge/\n.mcp.json\n# Connects coding agents to this computer's editor, with its token: not for sharing.\nopencode.json\n",
    )
    expect(withTokenFilesIgnored('build')).toBe(
      "build\n# Connects coding agents to this computer's editor, with its token: not for sharing.\n.mcp.json\nopencode.json\n",
    )
    expect(withTokenFilesIgnored(null)).toContain('.mcp.json\nopencode.json\n')
  })

  it('is left alone when it ignores both already, rooted or not', () => {
    expect(withTokenFilesIgnored('/.mcp.json\nopencode.json\n')).toBeNull()
  })

  it('is brought up to date before opencode.json is first written', async () => {
    const backend = new MemoryBackend({
      projects: { '/p': { 'netherforge.json': '{}', '.gitignore': '.netherforge/\n.mcp.json\n' } },
    })
    await backend.openProject('/p')
    expect(await syncOpencodeConfig(backend, ['.gitignore'], true)).toBe(true)
    expect(backend.testFiles()['.gitignore']).toContain('\nopencode.json\n')
    expect(backend.testFiles()[OPENCODE_CONFIG_FILE]).toContain(MEMORY_MCP_TOKEN)
  })
})
