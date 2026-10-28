/**
 * The memory backend's debug adapter: what the plugin answers on the
 * bridge's `dap` channel, small enough to read (see the hot-reload skill and
 * the plugin's `debug/` package for the real one). It keeps the breakpoints
 * it was sent and stops only when a test says so (`stop`), with a made-up
 * stack: a handler's frame at the stop's line, its locals (a number, a
 * string, a nested table, a `Player` handle), an upvalue and the scope's
 * globals.
 */
import type { DebugProtocol } from '@vscode/debugprotocol'

/** What a test stop shows. */
export interface FakeStop {
  /** The script it stops in; the first one with a breakpoint, else the tower's. */
  file?: string
  /** Its line; the file's first breakpoint, else 9. */
  line?: number
  /** `breakpoint` (default), `step`, `pause` or `exception`. */
  reason?: string
  /** For an exception: the error. */
  description?: string
}

/** The adapter's variables by reference, for the fixed stack below. */
const VARIABLES: Record<number, DebugProtocol.Variable[]> = {
  // Frame 1's locals.
  101: [
    { name: 'player', value: 'Player Steve', type: 'Player', variablesReference: 110 },
    { name: 'count', value: '3', type: 'number', variablesReference: 0 },
    { name: 'label', value: '"tower"', type: 'string', variablesReference: 0 },
    {
      name: 'settings',
      value: '{speed = 2, colors = {…}}',
      type: 'table',
      variablesReference: 111,
    },
  ],
  // Frame 1's upvalues.
  102: [{ name: 'clicks', value: '12', type: 'number', variablesReference: 0 }],
  // Frame 1's globals (the scope's own).
  103: [
    { name: 'this', value: 'Centity tower 6f1c2a0e', type: 'Centity', variablesReference: 112 },
  ],
  // Frame 2's.
  201: [],
  202: [],
  203: [
    { name: 'this', value: 'Centity tower 6f1c2a0e', type: 'Centity', variablesReference: 112 },
  ],
  110: [
    { name: 'name', value: '"Steve"', type: 'string', variablesReference: 0 },
    {
      name: 'uuid',
      value: '"0f1b0c7e-6a43-3d1f-9a8c-2b5e8f1a0c11"',
      type: 'string',
      variablesReference: 0,
    },
  ],
  111: [
    { name: 'speed', value: '2', type: 'number', variablesReference: 0 },
    { name: 'colors', value: '{"red", "blue"}', type: 'table', variablesReference: 113 },
  ],
  112: [{ name: 'centity', value: '"tower"', type: 'string', variablesReference: 0 }],
  113: [
    { name: '[1]', value: '"red"', type: 'string', variablesReference: 0 },
    { name: '[2]', value: '"blue"', type: 'string', variablesReference: 0 },
  ],
}

export class FakeDebugAdapter {
  /** Every request the editor sent, for assertions. */
  readonly log: DebugProtocol.Request[] = []
  /** Breakpoint lines by file, as the editor last set them. */
  readonly breakpoints = new Map<string, number[]>()
  exceptionFilters: string[] = []
  attached = false
  /** The stop the server is held at, if any. */
  private stopped: { file: string; line: number } | null = null
  private seq = 0

  constructor(private readonly emit: (message: DebugProtocol.ProtocolMessage) => void) {}

  get paused(): boolean {
    return this.stopped !== null
  }

  /** The bridge went down: the session goes with it, and a held server runs on. */
  reset() {
    this.attached = false
    this.stopped = null
    this.breakpoints.clear()
    this.exceptionFilters = []
  }

  /** A breakpoint (or a step, a pause, an error) holds the server. */
  stop(options: FakeStop = {}) {
    const first = [...this.breakpoints].find(([, lines]) => lines.length > 0)
    const file = options.file ?? first?.[0] ?? 'centities/tower/script.lua'
    const line = options.line ?? this.breakpoints.get(file)?.[0] ?? 9
    this.stopped = { file, line }
    this.event('stopped', {
      reason: options.reason ?? 'breakpoint',
      threadId: 1,
      allThreadsStopped: true,
      ...(options.description
        ? { description: options.description, text: options.description }
        : {}),
    })
  }

  receive(message: DebugProtocol.ProtocolMessage) {
    if (message.type !== 'request') return
    const request = message as DebugProtocol.Request
    this.log.push(request)
    const args = (request.arguments ?? {}) as Record<string, unknown>
    switch (request.command) {
      case 'initialize':
        this.respond(request, {
          supportsConfigurationDoneRequest: true,
          exceptionBreakpointFilters: [
            { filter: 'errors', label: 'Script errors', default: false },
          ],
        } satisfies DebugProtocol.Capabilities)
        this.event('initialized')
        return
      case 'attach':
        this.attached = true
        return this.respond(request)
      case 'setBreakpoints': {
        const source = args.source as DebugProtocol.Source
        const wanted = (args.breakpoints as DebugProtocol.SourceBreakpoint[] | undefined) ?? []
        this.breakpoints.set(
          source.path ?? '',
          wanted.map((it) => it.line),
        )
        return this.respond(request, {
          breakpoints: wanted.map((it, i) => ({
            id: i + 1,
            verified: true,
            line: it.line,
            source,
          })),
        })
      }
      case 'setExceptionBreakpoints':
        this.exceptionFilters = (args.filters as string[] | undefined) ?? []
        return this.respond(request)
      case 'configurationDone':
        return this.respond(request)
      case 'threads':
        return this.respond(request, { threads: [{ id: 1, name: 'Server thread' }] })
      case 'stackTrace':
        if (!this.stopped) return this.fail(request, 'the server is running')
        return this.respond(request, {
          stackFrames: [
            {
              id: 1,
              name: 'on_click',
              source: { path: this.stopped.file, name: this.stopped.file.split('/').pop() },
              line: this.stopped.line,
              column: 1,
            },
            {
              id: 2,
              name: 'main chunk',
              source: { path: this.stopped.file, name: this.stopped.file.split('/').pop() },
              line: 1,
              column: 1,
            },
          ],
          totalFrames: 2,
        })
      case 'scopes': {
        if (!this.stopped) return this.fail(request, 'the server is running')
        const frame = args.frameId as number
        return this.respond(request, {
          scopes: [
            {
              name: 'Locals',
              presentationHint: 'locals',
              variablesReference: frame * 100 + 1,
              expensive: false,
            },
            { name: 'Upvalues', variablesReference: frame * 100 + 2, expensive: false },
            { name: 'Globals', variablesReference: frame * 100 + 3, expensive: false },
          ],
        })
      }
      case 'variables':
        if (!this.stopped) return this.fail(request, 'the server is running')
        return this.respond(request, {
          variables: VARIABLES[args.variablesReference as number] ?? [],
        })
      case 'continue':
      case 'next':
      case 'stepIn':
      case 'stepOut':
        if (!this.stopped) return this.fail(request, 'the server is running')
        this.stopped = null
        this.respond(
          request,
          request.command === 'continue' ? { allThreadsContinued: true } : undefined,
        )
        this.event('continued', { threadId: 1, allThreadsContinued: true })
        return
      case 'pause':
        return this.respond(request)
      case 'disconnect': {
        const wasStopped = this.stopped !== null
        this.reset()
        this.respond(request)
        if (wasStopped) this.event('continued', { threadId: 1, allThreadsContinued: true })
        return
      }
      default:
        return this.fail(request, `Unknown command "${request.command}"`)
    }
  }

  private send(message: Omit<DebugProtocol.ProtocolMessage, 'seq'>) {
    this.seq += 1
    const full = { ...message, seq: this.seq } as DebugProtocol.ProtocolMessage
    // Each message arrives on its own, as frames from the relay do.
    setTimeout(() => this.emit(full), 0)
  }

  private respond(request: DebugProtocol.Request, body?: unknown) {
    this.send({
      type: 'response',
      request_seq: request.seq,
      success: true,
      command: request.command,
      ...(body === undefined ? {} : { body }),
    } as Omit<DebugProtocol.Response, 'seq'>)
  }

  private fail(request: DebugProtocol.Request, message: string) {
    this.send({
      type: 'response',
      request_seq: request.seq,
      success: false,
      command: request.command,
      message,
    } as Omit<DebugProtocol.Response, 'seq'>)
  }

  private event(event: string, body?: unknown) {
    this.send({ type: 'event', event, ...(body === undefined ? {} : { body }) } as Omit<
      DebugProtocol.Event,
      'seq'
    >)
  }
}
