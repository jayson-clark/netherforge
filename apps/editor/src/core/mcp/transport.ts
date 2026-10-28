/**
 * The MCP SDK's `Transport` over the backend: the Rust side is the HTTP pipe
 * (`src-tauri/src/mcp/`), which checks the caller (loopback, the token) and
 * hands each JSON-RPC message an agent POSTed to the UI as `mcp://message`;
 * what the SDK's server sends goes back with `mcpSend`, and a response becomes
 * the HTTP reply to the request it answers. Ids are already unique across
 * agents by then (the pipe gives each request its own).
 */
import type { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import {
  ErrorCode,
  JSONRPCMessageSchema,
  SUPPORTED_PROTOCOL_VERSIONS,
  isJSONRPCRequest,
  type JSONRPCMessage,
} from '@modelcontextprotocol/sdk/types.js'
import type { Backend, McpMessage, Unlisten } from '@/core/backend/types'

export class BackendTransport implements Transport {
  onclose?: () => void
  onerror?: (error: Error) => void
  onmessage?: (message: JSONRPCMessage) => void
  private unlisten: Unlisten | null = null

  constructor(private readonly backend: Backend) {}

  async start(): Promise<void> {
    if (this.unlisten) throw new Error('The MCP transport is already started')
    this.unlisten = await this.backend.onMcpMessage((event) => this.receive(event))
  }

  private receive({ message, protocolVersion }: McpMessage) {
    const parsed = JSONRPCMessageSchema.safeParse(message)
    if (!parsed.success) {
      this.onerror?.(new Error(`Not a JSON-RPC message: ${JSON.stringify(message)}`))
      return
    }
    // What the SDK's own HTTP transports check: an agent speaking a version the
    // SDK doesn't is refused before anything runs.
    if (protocolVersion !== null && !SUPPORTED_PROTOCOL_VERSIONS.includes(protocolVersion)) {
      if (isJSONRPCRequest(parsed.data)) {
        void this.send({
          jsonrpc: '2.0',
          id: parsed.data.id,
          error: {
            code: ErrorCode.InvalidRequest,
            message: `Unsupported MCP-Protocol-Version ${protocolVersion} (supported: ${SUPPORTED_PROTOCOL_VERSIONS.join(', ')})`,
          },
        })
      }
      return
    }
    this.onmessage?.(parsed.data)
  }

  async send(message: JSONRPCMessage): Promise<void> {
    await this.backend.mcpSend(message)
  }

  async close(): Promise<void> {
    this.unlisten?.()
    this.unlisten = null
    this.onclose?.()
  }
}
