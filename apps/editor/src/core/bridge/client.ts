/**
 * The editor's end of the dev bridge: JSON-RPC 2.0 with the plugin, through
 * vscode-jsonrpc, over the backend's relay (`bridgeSend`, `bridge://message`;
 * Rust only checks the hello and passes frames through). Requests and events
 * are typed by format's generated `BridgeRequests` and `BridgeEvents`, so a
 * method is declared once, in format's `Bridge.kt`.
 *
 * One `MessageConnection` per plugin connection: when the bridge goes down
 * the connection is disposed, which fails whatever is still waiting, and the
 * next frame or request starts a fresh one.
 */
import {
  AbstractMessageReader,
  AbstractMessageWriter,
  createMessageConnection,
  ErrorCodes,
  ResponseError,
  type DataCallback,
  type Disposable,
  type Message,
  type MessageConnection,
  type MessageReader,
  type MessageWriter,
} from 'vscode-jsonrpc/browser'
import type { BridgeEvents, BridgeRequests } from '@netherforge/format/types'
import { BackendError } from '@/core/backend/types'
import type { ErrorCode } from '@/core/backend/generated/bindings'

export type BridgeMethod = keyof BridgeRequests
export type BridgeParams<M extends BridgeMethod> = BridgeRequests[M]['params']
export type BridgeResult<M extends BridgeMethod> = BridgeRequests[M]['result']
/** A request's params as arguments: none for a method that takes none. */
export type BridgeParamsArg<M extends BridgeMethod> =
  BridgeParams<M> extends undefined ? [] : [params: BridgeParams<M>]
export type BridgeEventName = keyof BridgeEvents
export type { BridgeEvents, BridgeRequests }

/** How long a request may take; exporting game data (the backend's own) takes longer. */
export const REQUEST_TIMEOUT_MS = 30_000

/** JSON-RPC's code for a method the plugin doesn't have. */
const METHOD_NOT_FOUND = -32601

/** What the client needs of a backend: the relay, and when the bridge goes down. */
export interface BridgeTransport {
  send(message: string): Promise<void>
  listen(listener: (message: string) => void): Promise<() => void>
  /** Called with whether the plugin is connected, on every change. */
  onConnected(listener: (connected: boolean) => void): Promise<() => void>
}

class FrameReader extends AbstractMessageReader implements MessageReader {
  private callback: DataCallback | null = null
  private early: Message[] = []

  receive(message: Message) {
    if (this.callback) this.callback(message)
    else this.early.push(message)
  }

  listen(callback: DataCallback): Disposable {
    this.callback = callback
    for (const message of this.early.splice(0)) callback(message)
    return { dispose: () => void (this.callback = null) }
  }
}

class FrameWriter extends AbstractMessageWriter implements MessageWriter {
  constructor(private readonly send: (message: string) => Promise<void>) {
    super()
  }

  /** Rejects when the relay does, which fails the request that was being sent. */
  write(message: Message): Promise<void> {
    return this.send(JSON.stringify(message))
  }

  end(): void {}
}

export class BridgeClient {
  private connection: { rpc: MessageConnection; reader: FrameReader } | null = null
  private listeners = new Map<string, Set<(params: unknown) => void>>()
  private started: Promise<void> | null = null

  constructor(
    private readonly transport: BridgeTransport,
    private readonly timeoutMs = REQUEST_TIMEOUT_MS,
  ) {}

  /** Sends [method] and resolves with its result, or rejects with a `BackendError`. */
  async request<M extends BridgeMethod>(
    method: M,
    ...params: BridgeParamsArg<M>
  ): Promise<BridgeResult<M>> {
    await this.start()
    const { rpc } = this.open()
    let timer: ReturnType<typeof setTimeout> | undefined
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(
        () =>
          reject(
            new BackendError(
              'timeout',
              `The plugin didn't answer within ${this.timeoutMs / 1000} s`,
            ),
          ),
        this.timeoutMs,
      )
    })
    try {
      const sent =
        params.length === 0 ? rpc.sendRequest(method) : rpc.sendRequest(method, params[0])
      return (await Promise.race([sent, timeout])) as BridgeResult<M>
    } catch (error) {
      throw toBackendError(error)
    } finally {
      clearTimeout(timer)
    }
  }

  /**
   * Sends the notification [method] (never answered): the debugger's `dap`
   * channel is the only one the plugin hears. Rejects with a `BackendError`
   * (`notConnected` while the bridge is down).
   */
  async notify(method: string, params: object): Promise<void> {
    await this.start()
    const { rpc } = this.open()
    try {
      await rpc.sendNotification(method, params)
    } catch (error) {
      throw toBackendError(error)
    }
  }

  /** Calls [listener] with each [event]'s params (a stream's batch) until the returned function is called. */
  async onBridgeEvent<E extends BridgeEventName>(
    event: E,
    listener: (params: BridgeEvents[E]) => void,
  ): Promise<() => void> {
    await this.start()
    const set = this.listeners.get(event) ?? new Set()
    this.listeners.set(event, set)
    const typed = listener as (params: unknown) => void
    set.add(typed)
    return () => void set.delete(typed)
  }

  private start(): Promise<void> {
    this.started ??= (async () => {
      await this.transport.listen((text) => this.receive(text))
      await this.transport.onConnected((connected) => {
        if (!connected) this.close()
      })
    })()
    return this.started
  }

  private receive(text: string) {
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch {
      return
    }
    // The plugin only batches answers to batches, which this client never sends; read one anyway.
    const messages = Array.isArray(parsed) ? parsed : [parsed]
    const { reader } = this.open()
    for (const message of messages) reader.receive(message as Message)
  }

  private open() {
    if (this.connection) return this.connection
    const reader = new FrameReader()
    const rpc = createMessageConnection(reader, new FrameWriter((m) => this.transport.send(m)))
    rpc.onNotification((method, params) => {
      for (const listener of this.listeners.get(method) ?? []) listener(params)
    })
    rpc.listen()
    this.connection = { rpc, reader }
    return this.connection
  }

  /** The plugin went away: what's still waiting fails, and the next connection starts afresh. */
  private close() {
    const connection = this.connection
    this.connection = null
    connection?.rpc.dispose()
  }
}

/** A JSON-RPC failure as the `BackendError` the rest of the editor expects. */
function toBackendError(error: unknown): BackendError {
  if (error instanceof BackendError) return error
  if (error instanceof ResponseError) {
    const code: ErrorCode =
      error.code === METHOD_NOT_FOUND
        ? 'unknownMethod'
        : error.code === ErrorCodes.PendingResponseRejected ||
            error.code === ErrorCodes.MessageWriteError ||
            error.code === ErrorCodes.ConnectionInactive
          ? 'notConnected'
          : 'plugin'
    return new BackendError(code, error.message)
  }
  return new BackendError('other', error instanceof Error ? error.message : String(error))
}
