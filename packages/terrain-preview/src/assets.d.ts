// What a bundler (Vite, for the editor, the `netherforge` command and the tests alike) makes of wasmoon's WebAssembly.
declare module 'wasmoon/dist/glue.wasm?url' {
  const url: string
  export default url
}

declare module 'wasmoon/dist/glue.wasm?url&inline' {
  /** A `data:` URL of the file, base64. */
  const data: string
  export default data
}
