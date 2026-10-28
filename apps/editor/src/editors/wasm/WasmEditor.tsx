/**
 * A WebAssembly module's screen (`wasm/<id>/`): what its `module.wasm` exports and imports, read by format's own
 * reader (`readWasm`), its memory, the data files beside it (which it reads, never writes, from its root) and how
 * scripts run it. Nothing here edits it: a module is built elsewhere (wasi-sdk, Rust's `wasm32-wasip1`) and copied
 * in. What format can't tell from the bytes (code that doesn't validate) the dev server says as it compiles it,
 * in the problems list.
 */
import { basename, mainFileOf, resourceOf } from '@/core/paths'
import { readWasm, type WasmExport, type WasmInfo } from '@/core/format'
import { readProjectBytes } from '@/core/backend/projectBytes'
import { useApp, useWorkspace } from '@/state/providers'
import { Row, Section } from '@/ui/fields'
import { Hint, Mono, Tone } from '@/ui/text'
import { useAsyncResult } from '@/ui/useAsync'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, InspectorNote, Page } from '@/editors/shared/EditorLayout'
import { CountsTable } from '@/editors/structure/parts'
import { formatBytes } from '@/editors/map/MapEditor'

/** The function a WASI program runs. */
const COMMAND = '_start'

/** A function's signature as WebAssembly's text writes it: `(i32, i32) -> i64`. */
export function signature(fn: WasmExport): string {
  const params = `(${(fn.params ?? []).join(', ')})`
  const results = fn.results ?? []
  return results.length === 0
    ? params
    : `${params} -> ${results.length === 1 ? results[0] : `(${results.join(', ')})`}`
}

/** How a script runs [info]'s module [id]: a program is run or started, a library instantiated. */
export function usage(id: string, info: WasmInfo): string {
  const command = (info.exports ?? []).some((it) => it.kind === 'FUNCTION' && it.name === COMMAND)
  return command
    ? `nf.wasm.run("${id}", { stdin = "..." }) or nf.wasm.start("${id}")`
    : `nf.wasm.instantiate("${id}"), then instance:call(name, { ... })`
}

/** [path] is the module's folder (`wasm/chess`). */
export function WasmEditor({ path: folder }: { path: string }) {
  const { backend } = useApp()
  const id = basename(folder)
  const group = resourceOf(folder)?.group ?? ''
  const main = mainFileOf('wasm', id, group)
  const allFiles = useWorkspace((s) => s.files)
  const stamps = useWorkspace((s) => s.fileStamps)
  const inside = allFiles.filter((it) => it.startsWith(`${folder}/`))
  const hasModule = inside.includes(main)
  const data = inside.filter((it) => it !== main)

  const info = useAsyncResult(`${main}@${stamps[main] ?? 0}|${hasModule}`, () =>
    hasModule
      ? readProjectBytes(backend, main, stamps[main]).then(readWasm)
      : Promise.resolve(null),
  )

  // Sizes: the backend's listing has every file's.
  const sizes = useAsyncResult(
    `${folder}|${inside.length}|${inside.map((it) => stamps[it] ?? 0).join(',')}`,
    () =>
      backend
        .listFiles()
        .then((entries) =>
          Object.fromEntries(
            entries
              .filter((it) => it.path.startsWith(`${folder}/`))
              .map((it) => [it.path, it.size]),
          ),
        ),
  )
  const size = (path: string) => {
    const bytes = sizes?.value?.[path]
    return bytes === undefined ? '…' : formatBytes(bytes)
  }

  const value = info?.value
  const functions = value?.exports?.filter((it) => it.kind === 'FUNCTION') ?? []

  return (
    <EditorScreen aria-label={`WebAssembly module ${id}`}>
      <EditorBar title={`${folder}/`} />
      <Page>
        {!hasModule ? (
          <Hint role="status">
            Copy a module built for WASI preview 1, single-threaded (<Mono>wasm32-wasip1</Mono>), to{' '}
            <Mono>{main}</Mono>. Files beside it are data it reads from its root.
          </Hint>
        ) : info?.error ? (
          <Tone tone="error" role="status">
            {main} can&apos;t be read: {String(info.error)}
          </Tone>
        ) : !value ? (
          <Hint>Reading…</Hint>
        ) : value.error ? (
          <Tone tone="error" role="status">
            {main} isn&apos;t a module the server can run: {value.error}
          </Tone>
        ) : (
          <>
            <Section title={`Functions (${functions.length})`} icon="code">
              {functions.length === 0 ? (
                <Hint>It exports no function, so nothing can call it.</Hint>
              ) : (
                <CountsTable
                  label="Functions"
                  rows={functions.map((fn) => [
                    fn.name,
                    <Mono key={fn.name}>{signature(fn)}</Mono>,
                  ])}
                />
              )}
              <Hint>
                Scripts run it with <Mono>{usage(id, value)}</Mono>, once its package declares{' '}
                <Mono>
                  &quot;requires&quot;: {'{'} &quot;wasm&quot;: true {'}'}
                </Mono>
                .
              </Hint>
            </Section>
            <Section title={`Data (${data.length})`} icon="folder">
              {data.length === 0 ? (
                <Hint>
                  None. Files put beside {basename(main)} are what it reads (never writes) from its
                  root, /.
                </Hint>
              ) : (
                <CountsTable
                  label="Data files"
                  rows={data.map((path) => [path.slice(folder.length + 1), size(path)])}
                />
              )}
            </Section>
          </>
        )}
      </Page>
      <InspectorPanel label="WebAssembly module">
        {value && !value.error ? (
          <ModuleInfo info={value} size={size(main)} />
        ) : (
          <InspectorNote>
            {hasModule ? 'Nothing to show until it reads.' : `There's no ${main} yet.`}
          </InspectorNote>
        )}
      </InspectorPanel>
    </EditorScreen>
  )
}

function ModuleInfo({ info, size }: { info: WasmInfo; size: string }) {
  const memory = info.memory
  const imports = (info.imports ?? []).map((it) => `${it.module}.${it.name}`)
  return (
    <Section title="Module" icon="atom">
      <Row label="Kind">
        <span aria-label="Module kind">
          {(info.exports ?? []).some((it) => it.name === COMMAND)
            ? 'A program (a WASI command)'
            : 'A library of functions'}
        </span>
      </Row>
      <Row label="Size">
        <span aria-label="Module size">{size}</span>
      </Row>
      <Row label="Memory">
        <span aria-label="Memory">
          {memory
            ? `starts at ${formatBytes(memory.minimum * 65536)}${memory.maximum != null ? `, at most ${formatBytes(memory.maximum * 65536)}` : ''}`
            : 'none'}
        </span>
      </Row>
      <Row label="Imports">
        <span aria-label="Imports">
          {imports.length === 0
            ? 'nothing'
            : `${imports.length}, from ${[...new Set((info.imports ?? []).map((it) => it.module))].join(', ')}`}
        </span>
      </Row>
      <Hint>
        It runs off the main thread, within the server owner&apos;s limits (<Mono>wasm:</Mono> in
        config.yml): memory, time per call, how many a package keeps.
      </Hint>
    </Section>
  )
}
