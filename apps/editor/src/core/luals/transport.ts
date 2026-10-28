/**
 * The language client's connection to lua-language-server: JSON-RPC
 * messages over the backend (`lualsSend`, `luals://message`), where Rust
 * frames them onto the server's stdio. vscode-jsonrpc's reader and writer
 * shapes, so `monaco-languageclient` takes them as its `MessageTransports`.
 *
 * Only messages from this transport's server (its `generation`) are read;
 * the server exiting closes both sides, which the client reports.
 */
import {
  AbstractMessageReader,
  AbstractMessageWriter,
  type DataCallback,
  type Disposable,
  type Message,
  type MessageReader,
  type MessageWriter,
} from 'vscode-jsonrpc'
import type { Backend, LualsStarted, Unlisten } from '@/core/backend/types'

export class LualsReader extends AbstractMessageReader implements MessageReader {
  private callback: DataCallback | null = null
  /** What arrived before `listen`: the client starts listening a moment after the server starts. */
  private early: Message[] = []

  receive(message: Message) {
    if (this.callback) this.callback(message)
    else this.early.push(message)
  }

  error(error: unknown) {
    this.fireError(error)
  }

  close() {
    this.fireClose()
  }

  listen(callback: DataCallback): Disposable {
    this.callback = callback
    for (const message of this.early.splice(0)) callback(message)
    return { dispose: () => void (this.callback = null) }
  }
}

export class LualsWriter extends AbstractMessageWriter implements MessageWriter {
  private errors = 0
  private closed = false

  constructor(private readonly send: (message: string) => Promise<void>) {
    super()
  }

  async write(message: Message): Promise<void> {
    if (this.closed) return
    try {
      await this.send(JSON.stringify(message))
      this.errors = 0
    } catch (error) {
      this.fireError(error, message, ++this.errors)
    }
  }

  close() {
    if (this.closed) return
    this.closed = true
    this.fireClose()
  }

  end(): void {
    this.closed = true
  }
}

export interface LualsConnection {
  started: LualsStarted
  reader: LualsReader
  writer: LualsWriter
  /** Stops listening (the server itself is stopped with `backend.lualsStop`). */
  dispose(): void
}

/** Starts lua-language-server for the open project and connects to it. */
export async function connectLuals(backend: Backend): Promise<LualsConnection> {
  const reader = new LualsReader()
  let generation: number | null = null
  // Listen before starting, so not even the first message is lost.
  const queued: { generation: number; message: string }[] = []
  const deliver = (event: { generation: number; message: string }) => {
    if (generation === null) return void queued.push(event)
    if (event.generation !== generation) return
    try {
      reader.receive(JSON.parse(event.message) as Message)
    } catch (error) {
      reader.error(error)
    }
  }
  const unlisten: Unlisten[] = []
  let writer: LualsWriter | null = null
  unlisten.push(await backend.onLualsMessage(deliver))
  unlisten.push(
    await backend.onLualsExit((event) => {
      if (event.generation !== generation) return
      writer?.close()
      reader.close()
    }),
  )
  let started: LualsStarted
  try {
    started = await backend.lualsStart()
  } catch (error) {
    for (const stop of unlisten) stop()
    throw error
  }
  generation = started.generation
  for (const event of queued.splice(0)) deliver(event)
  writer = new LualsWriter((message) => backend.lualsSend(started.generation, message))
  return {
    started,
    reader,
    writer,
    dispose() {
      for (const stop of unlisten) stop()
      writer?.end()
    },
  }
}
