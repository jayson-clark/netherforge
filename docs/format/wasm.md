# WebAssembly modules

A **WebAssembly module** is computation scripts hand off: a chess engine,
pathfinding over a big grid, noise, compression, a library compiled from C,
C++ or Rust. It's a folder, `wasm/<id>/`, whose `module.wasm` is the module;
every other file in the folder is its **data**, which it can read (never write)
while it runs. Scripts run it with [`nf.wasm`](../reference/nf.wasm.md), once
the package declares `"requires": { "wasm": true }` (see
[Requirements](project.md#requirements)). The [guide](../guide/wasm.md) says
how to build one and use it.

```
wasm/
  stockfish/
    module.wasm            the module: a WASI program (it exports _start)
    nn-1a298aa575a0.nnue   data it opens by name
  noise/
    module.wasm            a library of functions
```

The id is the folder's name, and a module may sit in
[group folders](project.md#group-folders) like any resource. A package may
export its modules for the projects that depend on it (`"exports": { "wasm":
["stockfish"] }`), which name them `namespace:id`.

## What the server runs

The server runs modules on [Chicory](https://chicory.dev), a WebAssembly
runtime written in Java: nothing native ships, and the server compiles each
module into Java classes as the project loads. A module must be:

- **A core module**, version 1 of the binary format. A component of the
  component model isn't one: build a core module.
- **Built for WASI preview 1** (`wasm32-wasip1`), or importing nothing at all.
  It may import WASI's functions (`wasi_snapshot_preview1`) and nothing else: no
  host function a package could use to reach the server, so whatever it
  computes, it can only answer the script that asked.
- **Single-threaded.** A threads build (`wasm32-wasip1-threads`: shared memory,
  `wasi.thread-spawn`) isn't run.
- **32-bit.** A memory addressed by 64-bit numbers (`memory64`) isn't run.

A **program** (a WASI command) exports `_start`, which `nf.wasm.run` and
`nf.wasm.start` run. A **library** exports functions scripts call through
`nf.wasm.instantiate`; to take or give text it exports its `memory`, an
`alloc(size)` (or `malloc`), and optionally a `dealloc(pointer, size)` (or
`free`). A reactor's `_initialize` runs once, when it's instantiated.

## Its data

Every file in the folder but `module.wasm` is a file the module may open, by
its path in the folder, from its root (`/`, its first preopened directory,
which WASI programs find for themselves): `nets/big.nnue` is `/nets/big.nnue`,
or `nets/big.nnue` relative to it. The module sees nothing else: no other file
of the project, of the server, or of another module. Opening one to write it,
making, renaming or removing anything is refused (`EROFS`), and a path that
climbs out of the root (`../x`) is refused (`ENOTCAPABLE`).

The data is read from the project's files as the module reads it, never all
at once, so a large file (a chess engine's 100 MB network) costs no memory
until the module reads it into its own.

## Checks

Format checks what the bytes say; the server checks the rest as it compiles
the module and reports it the same way.

| What                                                                    | Problem        |
| ----------------------------------------------------------------------- | -------------- |
| `module.wasm` isn't a core module (or it's cut short, or a component)   | `wasm.invalid` |
| It imports something that isn't one of WASI preview 1's functions       | `wasm.import`  |
| It was built for threads (shared memory, `wasi.thread-spawn`)           | `wasm.threads` |
| Its memory is 64-bit                                                    | `wasm.memory`  |
| It exports no function, so nothing can call it (a warning)              | `wasm.exports` |
| The server couldn't compile it: its code doesn't validate, it's too big | `runtime.wasm` |

The editor shows what a module exports and imports on its screen; the first
five problems come from `netherforge check` and the dev server, `runtime.wasm`
from the dev server alone. A module with problems doesn't run: running it
fails, saying why.
