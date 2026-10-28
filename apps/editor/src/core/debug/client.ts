/**
 * The editor's end of the debugger: a Debug Adapter Protocol client. The
 * plugin on the dev server is the debug adapter; each DAP message rides one
 * `dap` notification on the dev bridge, both ways (`backend.dapSend`,
 * `onBridgeEvent('dap')`). DAP correlates its own answers: a request's `seq`
 * comes back as its response's `request_seq`. Types are `@vscode/debugprotocol`'s.
 *
 * One client per backend. When the bridge goes down, `reset` fails whatever
 * waits: the plugin forgets the session with its connection, so the next
 * bridge starts a new one from `initialize`.
 */
import type { DebugProtocol } from '@vscode/debugprotocol'
import { BackendError } from '@/core/backend/types'

/** What each request takes and its response's body, by command. */
export interface DapRequests {
  initialize: [DebugProtocol.InitializeRequestArguments, DebugProtocol.Capabilities | undefined]
  attach: [DebugProtocol.AttachRequestArguments, undefined]
  setBreakpoints: [
    DebugProtocol.SetBreakpointsArguments,
    DebugProtocol.SetBreakpointsResponse['body'],
  ]
  setExceptionBreakpoints: [
    DebugProtocol.SetExceptionBreakpointsArguments,
    DebugProtocol.SetExceptionBreakpointsResponse['body'] | undefined,
  ]
  configurationDone: [DebugProtocol.ConfigurationDoneArguments, undefined]
  threads: [undefined, DebugProtocol.ThreadsResponse['body']]
  stackTrace: [DebugProtocol.StackTraceArguments, DebugProtocol.StackTraceResponse['body']]
  scopes: [DebugProtocol.ScopesArguments, DebugProtocol.ScopesResponse['body']]
  variables: [DebugProtocol.VariablesArguments, DebugProtocol.VariablesResponse['body']]
  continue: [DebugProtocol.ContinueArguments, DebugProtocol.ContinueResponse['body'] | undefined]
  next: [DebugProtocol.NextArguments, undefined]
  stepIn: [DebugProtocol.StepInArguments, undefined]
  stepOut: [DebugProtocol.StepOutArguments, undefined]
  pause: [DebugProtocol.PauseArguments, undefined]
  disconnect: [DebugProtocol.DisconnectArguments, undefined]
}
export type DapCommand = keyof DapRequests

/** The events the editor acts on, and their bodies. */
export interface DapEvents {
  initialized: undefined
  stopped: DebugProtocol.StoppedEvent['body']
  continued: DebugProtocol.ContinuedEvent['body']
  terminated: DebugProtocol.TerminatedEvent['body']
  output: DebugProtocol.OutputEvent['body']
}
export type DapEventName = keyof DapEvents

/** What the client needs: sending one message, and hearing the adapter's. */
export interface DapTransport {
  send(message: DebugProtocol.ProtocolMessage): Promise<void>
  listen(listener: (message: unknown) => void): Promise<() => void>
}

/** How long the adapter may take to answer: a plugin without the debugger never does. */
export const DAP_TIMEOUT_MS = 10_000

interface Waiting {
  resolve: (body: unknown) => void
  reject: (error: unknown) => void
  timer: ReturnType<typeof setTimeout>
}

export class DapClient {
  private seq = 0
  private waiting = new Map<number, Waiting>()
  private listeners = new Map<string, Set<(body: unknown) => void>>()
  private started: Promise<void> | null = null

  constructor(
    private readonly transport: DapTransport,
    private readonly timeoutMs = DAP_TIMEOUT_MS,
  ) {}

  /** Sends [command] and resolves with its response's body, or rejects with the adapter's message. */
  async request<C extends DapCommand>(
    command: C,
    args: DapRequests[C][0],
  ): Promise<DapRequests[C][1]> {
    await this.start()
    this.seq += 1
    const seq = this.seq
    const message: DebugProtocol.Request = {
      seq,
      type: 'request',
      command,
      ...(args === undefined ? {} : { arguments: args }),
    }
    const answered = new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiting.delete(seq)
        reject(
          new BackendError(
            'timeout',
            `The debugger didn't answer ${command} within ${this.timeoutMs / 1000} s`,
          ),
        )
      }, this.timeoutMs)
      this.waiting.set(seq, { resolve, reject, timer })
    })
    try {
      await this.transport.send(message)
    } catch (error) {
      this.settle(seq)?.reject(error)
    }
    return (await answered) as DapRequests[C][1]
  }

  /** Calls [listener] with each [event]'s body until the returned function is called. */
  on<E extends DapEventName>(event: E, listener: (body: DapEvents[E]) => void): () => void {
    void this.start()
    const set = this.listeners.get(event) ?? new Set()
    this.listeners.set(event, set)
    const typed = listener as (body: unknown) => void
    set.add(typed)
    return () => void set.delete(typed)
  }

  /** The bridge went down: what waits fails, and sequence numbers start over. */
  reset() {
    for (const seq of [...this.waiting.keys()])
      this.settle(seq)?.reject(
        new BackendError('notConnected', "The NetherForge plugin isn't connected"),
      )
    this.seq = 0
  }

  private start(): Promise<void> {
    this.started ??= this.transport.listen((message) => this.receive(message)).then(() => {})
    return this.started
  }

  private settle(seq: number): Waiting | undefined {
    const waiting = this.waiting.get(seq)
    if (!waiting) return undefined
    clearTimeout(waiting.timer)
    this.waiting.delete(seq)
    return waiting
  }

  private receive(message: unknown) {
    if (!message || typeof message !== 'object') return
    const it = message as DebugProtocol.ProtocolMessage
    if (it.type === 'response') {
      const response = it as DebugProtocol.Response
      const waiting = this.settle(response.request_seq)
      if (!waiting) return
      if (response.success) waiting.resolve(response.body)
      else
        waiting.reject(
          new BackendError(
            'plugin',
            response.message ?? `The debugger couldn't ${response.command}`,
          ),
        )
    } else if (it.type === 'event') {
      const event = it as DebugProtocol.Event
      for (const listener of this.listeners.get(event.event) ?? []) listener(event.body)
    }
  }
}
