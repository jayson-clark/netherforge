/**
 * The backend contract (`contract.json`, see `contractRunner.ts`) on the
 * app's `TauriBackend`, over the real Rust commands. `@tauri-apps/api`'s IPC
 * is mocked (`mockIPC`) to forward every command to the contract host, a
 * test of the Rust backend's own (`src-tauri/src/commands/contract.rs`) that
 * runs each on Tauri's mock runtime and sends back its answer and every event
 * the app emits, which reach the UI's listeners as Tauri's would. So the
 * whole path is the app's: `TauriBackend`'s mapping onto the generated
 * commands, Tauri's argument deserialization and permissions, the commands,
 * and the events.
 *
 * The host is started with cargo (`cargo test … commands::contract::host
 * -- --ignored --exact`), the same build `cargo test` makes, so the first run
 * on a fresh checkout compiles the backend first.
 */
import { spawn, type ChildProcess } from 'node:child_process'
import { createServer, type Server, type Socket } from 'node:net'
import path from 'node:path'
import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'
import { emit } from '@tauri-apps/api/event'
import { clearMocks, mockIPC } from '@tauri-apps/api/mocks'
import { afterAll, afterEach, beforeAll, describe, it } from 'vitest'
import {
  runCase,
  suite,
  watches,
  type ContractCase,
  type ContractWorld,
  type Json,
} from './contractRunner'
import { TauriBackend } from './tauri'

const manifest = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../src-tauri/Cargo.toml',
)

/** The contract host: requests by id, and the events it forwards. */
class Host {
  private next = 1
  private waiting = new Map<number, { resolve: (v: Json) => void; reject: (e: Json) => void }>()
  onEvent: (event: string, payload: Json) => void = () => {}

  constructor(private readonly socket: Socket) {
    createInterface({ input: socket }).on('line', (line) => {
      const message = JSON.parse(line) as {
        id?: number
        ok?: Json
        err?: Json
        event?: string
        payload?: Json
      }
      if (message.event !== undefined) return this.onEvent(message.event, message.payload ?? null)
      const waiter = this.waiting.get(message.id!)
      this.waiting.delete(message.id!)
      if ('err' in message) waiter?.reject(message.err ?? null)
      else waiter?.resolve(message.ok ?? null)
    })
  }

  request(op: string, fields: Record<string, unknown> = {}): Promise<Json> {
    const id = this.next++
    this.socket.write(`${JSON.stringify({ id, op, ...fields })}\n`)
    return new Promise((resolve, reject) => this.waiting.set(id, { resolve, reject }))
  }
}

let server: Server
let cargo: ChildProcess
let host: Host
let output = ''

beforeAll(async () => {
  server = createServer()
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const { port } = server.address() as { port: number }
  const connected = new Promise<Socket>((resolve) => server.once('connection', resolve))
  cargo = spawn(
    'cargo',
    [
      'test',
      '--manifest-path',
      manifest,
      '--',
      '--ignored',
      '--exact',
      'commands::contract::host',
      '--nocapture',
    ],
    { env: { ...process.env, NETHERFORGE_CONTRACT_HOST: `127.0.0.1:${port}` } },
  )
  cargo.stdout?.on('data', (chunk: Buffer) => (output += chunk.toString()))
  cargo.stderr?.on('data', (chunk: Buffer) => (output += chunk.toString()))
  const exited = new Promise<never>((_, reject) =>
    cargo.once('exit', (code) =>
      reject(new Error(`the contract host exited (${code}) before connecting:\n${output}`)),
    ),
  )
  const failed = new Promise<never>((_, reject) =>
    cargo.once('error', (error) =>
      reject(new Error(`cargo couldn't be started: ${error.message}`)),
    ),
  )
  host = new Host(await Promise.race([connected, exited, failed]))
  // An event can arrive after its case ended (the mocks are gone): nothing listens to it then.
  host.onEvent = (event, payload) => void emit(event, payload).catch(() => {})
  // A cold build of the backend takes minutes; after that it's seconds.
}, 30 * 60_000)

afterAll(async () => {
  await host?.request('end').catch(() => {})
  cargo?.kill()
  server?.close()
})

afterEach(() => clearMocks())

/** The case's world on the Rust backend. */
async function tauriWorld(testCase: ContractCase): Promise<ContractWorld> {
  mockIPC((command, args) => host.request('invoke', { cmd: command, args: args ?? {} }), {
    shouldMockEvents: true,
  })
  const vars = (await host.request('begin', {
    project: suite.project,
    packages: suite.packages,
    repositories: suite.repositories,
    setup: testCase.setup ?? {},
    watch: watches(testCase),
  })) as Record<string, string | number>
  const backend = await new TauriBackend().init()
  return {
    backend,
    vars,
    async outside(change) {
      const { outside, ...rest } = change
      await host.request('outside', { action: { do: outside, ...rest } })
    },
    async agent(message) {
      const handle = await host.request('outside', { action: { do: 'agent', message } })
      return host.request('agentAnswer', { handle })
    },
    async fetch(url) {
      const { status, bytes } = (await host.request('fetch', { url })) as {
        status: number
        bytes: number[]
      }
      return { status, text: new TextDecoder().decode(new Uint8Array(bytes)) }
    },
  }
}

describe('TauriBackend on the Rust backend keeps the backend contract', () => {
  for (const testCase of suite.cases) {
    it(testCase.name, { timeout: 120_000 }, async () => {
      const world = await tauriWorld(testCase)
      if (testCase.open !== false) await world.backend.openProject(world.vars.ROOT as string)
      await runCase(world, testCase)
    })
  }
})
