import { act, cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { bytesToBase64 } from '@/core/backend/base64'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { signature, usage, WasmEditor } from './WasmEditor'

const FOLDER = 'wasm/adder'

/** A module exporting `add(i32, i32) -> i32` and nothing else, byte by byte. */
const ADDER = new Uint8Array([
  0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
  // types: (i32, i32) -> i32
  0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01, 0x7f,
  // functions: one, of type 0
  0x03, 0x02, 0x01, 0x00,
  // exports: "add", function 0
  0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00,
  // code: local.get 0, local.get 1, i32.add
  0x0a, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6a, 0x0b,
])

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
})

afterEach(cleanup)

async function write(files: Record<string, Uint8Array>) {
  for (const [path, bytes] of Object.entries(files))
    backend.testWriteBytes(path, bytesToBase64(bytes))
  await act(() => app.workspace.getState().refreshFiles())
}

function show() {
  render(
    <AppProvider app={app}>
      <WasmEditor path={FOLDER} />
    </AppProvider>,
  )
}

describe('signature and usage', () => {
  it('write a function as WebAssembly text does, and say how a script runs the module', () => {
    expect(
      signature({ name: 'add', kind: 'FUNCTION', params: ['i32', 'i32'], results: ['i32'] }),
    ).toBe('(i32, i32) -> i32')
    expect(signature({ name: 'pair', kind: 'FUNCTION', params: [], results: ['i32', 'f32'] })).toBe(
      '() -> (i32, f32)',
    )
    expect(signature({ name: 'go', kind: 'FUNCTION', params: ['i64'], results: [] })).toBe('(i64)')
    const program = {
      imports: [],
      exports: [{ name: '_start', kind: 'FUNCTION' as const }],
      start: false,
    }
    expect(usage('engine', program)).toContain('nf.wasm.start("engine")')
    expect(usage('noise', { ...program, exports: [] })).toContain('nf.wasm.instantiate("noise")')
  })
})

describe('WasmEditor', () => {
  it('says where the module goes before there is one', async () => {
    show()
    expect((await screen.findByRole('status')).textContent).toContain('wasm/adder/module.wasm')
  })

  it('lists what a module exports, its data, and how scripts run it', async () => {
    await write({
      [`${FOLDER}/module.wasm`]: ADDER,
      [`${FOLDER}/tables/primes.bin`]: new Uint8Array(2048),
    })
    show()
    const functions = await screen.findByRole('table', { name: 'Functions' })
    expect(within(functions).getByText('add')).toBeTruthy()
    expect(within(functions).getByText('(i32, i32) -> i32')).toBeTruthy()
    expect(
      within(screen.getByRole('table', { name: 'Data files' })).getByText('tables/primes.bin'),
    ).toBeTruthy()
    expect(
      screen.getByText('nf.wasm.instantiate("adder"), then instance:call(name, { ... })'),
    ).toBeTruthy()
    expect(screen.getByLabelText('Module kind').textContent).toBe('A library of functions')
  })

  it("says why bytes aren't a module", async () => {
    await write({ [`${FOLDER}/module.wasm`]: new TextEncoder().encode('not wasm') })
    show()
    expect((await screen.findByRole('status')).textContent).toContain(
      "isn't a module the server can run: it isn't a WebAssembly module",
    )
  })
})
