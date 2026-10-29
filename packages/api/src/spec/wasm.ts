import { asyncFunction } from '../async.ts'
import type { EventSpec, LuaClass, Param } from '../types.ts'
import { eventFunctions } from './events.ts'

/** Where the owner's limits on WebAssembly are, as the docs name it. */
const LIMITS = "`config.yml`'s `wasm:` section"

/** How strings cross into a module, for `call` and `call_text`. */
const ARGS_DOC =
  "The arguments, in order, one for each of the function's parameters: an `integer` for an `i32` or `i64`, a `number` for an `f32` or `f64`. A `string` is copied into the module's memory and passed as two `i32`s, where it starts and how many bytes it is (UTF-8): the module must export its `memory` and an `alloc(size) -> pointer` (or `malloc`) to copy it into, and the server frees it after the call with the module's `dealloc(pointer, size)` (or `free(pointer)`) when it exports one, so a function that keeps the text copies it. A count or type that doesn't match the function is an error at the script's line."

/** The module every `nf.wasm` function takes first. */
const MODULE: Param = {
  name: 'module',
  type: 'string',
  doc: 'Its folder name under `wasm/`, or `namespace:id` for one a package it depends on exports (see [Names across packages](packages.md)).',
}

/** `nf.wasm`: the project's WebAssembly modules. */
export const nfWasm: LuaClass = {
  name: 'nf.wasm',
  doc: `Heavy computation in WebAssembly: a chess engine, pathfinding over a big grid, noise, compression, a library compiled from C, C++ or Rust. A module is \`wasm/<id>/module.wasm\` in the project (or a package's, exported), built for WASI preview 1 and single-threaded (\`wasm32-wasip1\`); every other file in its folder is data it can read, never write, as files from its root (\`/\`). It runs off the main thread, with no access to the server, the network or any file but its own data: it can only answer the script that asked. A package declares it runs modules with \`"requires": { "wasm": true }\`, and the server owner sees that (\`/nf requires\`) and limits it in ${LIMITS}: how many threads run modules, how much memory each may use, how long a call may take, and how many instances and programs a package may keep. There are three ways to run one: \`nf.wasm.instantiate\` for a library whose functions you call (\`instance:call\`), \`nf.wasm.run\` for a program that reads its input and prints its answer, and \`nf.wasm.start\` for a program that keeps running and talks line by line (a UCI chess engine). The server compiles every module off the main thread as the project loads; one that couldn't be compiled fails when it's run, saying why (and the problem \`runtime.wasm\` says so too). A module the project doesn't have is an error at the script's line. A script that runs a module restarts when the module changes.`,
  methods: false,
  fields: [],
  functions: [
    {
      name: 'functions',
      doc: "The functions a module exports, with their parameters' and results' types, in the order it exports them: a program (a WASI command) exports `_start`. Empty until the server has compiled it, and for one that couldn't be.",
      requires: 'wasm',
      params: [MODULE],
      returns: [{ type: 'WasmFunction[]' }],
      example:
        'for _, fn in ipairs(nf.wasm.functions("noise")) do\n  log(fn.name, table.concat(fn.params, ", "), "->", table.concat(fn.results, ", "))\nend',
    },
    asyncFunction({
      name: 'instantiate',
      doc: `Makes an instance of a library module: its own memory, kept between calls, which \`instance:call\` calls functions in. Its start function (and a reactor's \`_initialize\`) runs first. It fails when the module couldn't be compiled, it needs more memory than \`wasm.max-memory-mb\`, the server's modules together would hold more than \`wasm.max-memory-total-mb\`, or the package already holds \`wasm.max-instances-per-package\` instances. The instance belongs to the script that made it.`,
      requires: 'wasm',
      params: [MODULE],
      value: { name: 'instance', type: 'WasmInstance', doc: 'the instance' },
      example:
        'nf.task(function()\n  local noise, err = nf.wasm.instantiate("noise")\n  if not noise then\n    log("no noise: " .. err)\n    return\n  end\n  local values = noise:call("sample", { 12.5, 40.25 })\n  if values then\n    log("noise here:", values[1])\n  end\nend)',
    }),
    asyncFunction({
      name: 'run',
      doc: `Runs a program to its end with \`options.stdin\` as its input, and gives what it printed and its exit code: for a tool that reads a question and prints the answer. It's stopped (\`nil, err\`) past \`wasm.max-call-seconds\`, and what it prints is kept up to \`wasm.max-output-bytes\` of each stream. A program that exits with a code other than 0 has still run: check \`result.exit_code\`.`,
      requires: 'wasm',
      params: [
        MODULE,
        {
          name: 'options',
          type: 'WasmRunOptions',
          doc: 'Its input, arguments and environment.',
          optional: true,
        },
      ],
      value: {
        name: 'result',
        type: 'WasmRunResult',
        doc: 'its exit code and what it printed',
      },
      example:
        'nf.task(function()\n  local result, err = nf.wasm.run("solver", { stdin = "3 1 2", arguments = { "--fast" } })\n  if result and result.exit_code == 0 then\n    log("solved:", result.stdout)\n  end\nend)',
    }),
    {
      name: 'start',
      doc: `Starts a program that keeps running: each \`send\` is a line of its input, each line it prints is an \`output\` event, on the main thread, in order, and it ends once, with its \`end\` event. It returns at once; the program starts off the main thread, on a thread of its own while it runs, and belongs to the script that started it. A limit the owner set (\`wasm.max-processes-per-package\`, \`wasm.max-processes-total\`, memory) ends it at the next tick with reason \`"limit"\`; a module that couldn't be compiled ends it \`"failed"\`.`,
      requires: 'wasm',
      params: [
        MODULE,
        {
          name: 'options',
          type: 'WasmStartOptions',
          doc: 'Its arguments and environment.',
          optional: true,
        },
      ],
      returns: [
        {
          type: 'WasmProcess',
          doc: 'The program: listen to its `output` and `end`, and `send` it lines.',
        },
      ],
      example:
        'local engine = nf.wasm.start("stockfish")\nengine:on("output", function(event)\n  local move = event.line:match("^bestmove (%S+)")\n  if move then\n    nf.server.broadcast("The engine plays " .. move)\n  end\nend)\nengine:send("position startpos moves e2e4")\nengine:send("go movetime 1000")',
    },
  ],
}

/** An instance of a library module, from `nf.wasm.instantiate`. */
export const wasmInstanceClass: LuaClass = {
  name: 'WasmInstance',
  doc: "An instance of a WebAssembly module, from `nf.wasm.instantiate`: its own memory, kept from call to call, so a function can build state another reads. Its calls run off the main thread one at a time, in the order they were made. It belongs to the script that made it and is closed when that script unloads. Ephemeral: it can't be saved in a `data()` table.",
  methods: true,
  handle: { key: [{ name: 'number', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'module',
      doc: 'The module it is an instance of, as the calling script names it.',
      params: [],
      returns: [{ type: 'string' }],
    },
    asyncFunction({
      name: 'call',
      doc: `Calls one of the functions the module exports and gives its results. It's stopped past \`wasm.max-call-seconds\`; a call that traps (an out-of-bounds read, \`unreachable\`, a division by zero) or is stopped fails with \`nil, err\`, and the instance is closed after it (its memory may be half-written).`,
      params: [
        { name: 'name', type: 'string', doc: 'The exported function: `"sample"`.' },
        { name: 'arguments', type: 'table', doc: ARGS_DOC, optional: true },
      ],
      value: {
        name: 'results',
        type: 'any[]',
        doc: 'its results in order (an `i32` or `i64` as an `integer`, an `f32` or `f64` as a `number`), empty for a function that returns nothing',
      },
      example:
        'nf.task(function()\n  local results, err = instance:call("add", { 2, 3 })\n  assert(results and results[1] == 5, err)\nend)',
    }),
    asyncFunction({
      name: 'call_text',
      doc: `Calls a function that answers with text: it returns two \`i32\`s (where the text starts and how many bytes it is) or one \`i64\` holding both (the start in its high 32 bits). The text is read from the module's memory as UTF-8, then freed as \`call\` frees a string argument. Otherwise as \`call\`: for text in and text out, JSON included.`,
      params: [
        { name: 'name', type: 'string', doc: 'The exported function: `"plan"`.' },
        { name: 'arguments', type: 'table', doc: ARGS_DOC, optional: true },
      ],
      value: { name: 'text', type: 'string', doc: 'the text it answered' },
      example:
        'nf.task(function()\n  local answer, err = instance:call_text("plan", { nf.json.encode({ from = 1, to = 9 }) })\n  if answer then\n    local plan = nf.json.decode(answer)\n    log("the plan has", #plan.steps, "steps")\n  end\nend)',
    }),
    {
      name: 'close',
      doc: 'Closes it now, freeing its memory: calls still waiting fail, and later ones do at once. Unloading the script that made it closes it too.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it was already closed.' }],
    },
  ],
}

const processEvents: EventSpec[] = [
  {
    name: 'output',
    doc: `The program printed a line (to its standard output or error), without its line ending. Lines come in order, on the main thread, at most \`wasm.lines-per-tick\` a tick per program; while scripts haven't taken them, at most \`wasm.max-queued-lines\` wait and the program is paused when it prints the next, so nothing is dropped. A line longer than \`wasm.max-line-bytes\` comes in pieces of that size.`,
    payload: 'WasmOutputEvent',
  },
  {
    name: 'end',
    doc: 'The program ended, for any reason (`event.reason` says which): heard once, after every line it printed before, while the handle still answers. After it, every handler on the program is gone; start it again to restart it.',
    payload: 'WasmEndEvent',
    example:
      'engine:on("end", function(event)\n  if event.reason ~= "unloaded" then\n    log("engine ended:", event.reason, event.exit_code, event.detail)\n  end\nend)',
  },
]

/** A running program, from `nf.wasm.start`. */
export const wasmProcessClass: LuaClass = {
  name: 'WasmProcess',
  doc: 'A program started with `nf.wasm.start`: `send` it lines and hear each line it prints as `output`, then its one `end`. It runs on a thread of its own, waiting while it reads input nobody has sent. It belongs to the script that started it and ends with reason `"unloaded"` when that script unloads (a reload, the server stopping). Once it has ended its methods answer as an ended program does rather than erroring. Ephemeral: it can\'t be saved in a `data()` table.',
  methods: true,
  handle: {
    key: [
      { name: 'number', type: 'integer' },
      { name: 'module', type: 'string' },
    ],
  },
  fields: [],
  events: processEvents,
  functions: [
    {
      name: 'module',
      doc: 'The module it runs, as the calling script names it, even after it has ended.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'state',
      doc: '`"running"` until its `end` event, then `"ended"`.',
      params: [],
      returns: [{ type: '"running"|"ended"' }],
    },
    {
      name: 'send',
      doc: "Writes a line to its input, with a line ending after it: the program reads it when it next reads. A line over `wasm.max-line-bytes` is an error at the script's line.",
      params: [
        {
          name: 'line',
          type: 'string',
          doc: 'The line, without its line ending: `"go movetime 500"`.',
        },
      ],
      returns: [
        {
          type: 'boolean',
          doc: "`false` when it has ended, its input was closed, or it already holds `wasm.max-queued-lines` lines it hasn't read yet (send again once it has), so nothing was sent.",
        },
      ],
      example: 'engine:send("position startpos moves e2e4 e7e5")',
    },
    {
      name: 'close_input',
      doc: 'Ends its input: once it has read what was sent, it reads the end of the file, which most programs answer by finishing what they were doing and exiting.',
      params: [],
      returns: [
        { type: 'boolean', doc: '`false` when it had already ended or its input was closed.' },
      ],
    },
    {
      name: 'stop',
      doc: 'Ends it now, with reason `"stopped"`: lines it printed that weren\'t handed over yet are thrown away.',
      params: [],
      returns: [{ type: 'boolean', doc: '`false` when it had already ended.' }],
    },
    ...eventFunctions(
      'WasmProcess',
      'engine',
      'local engine = nf.wasm.start("stockfish")\nengine:on("output", function(event)\n  log("engine says:", event.line)\nend)',
    ),
  ],
}

const event = (name: string, doc: string, fields: LuaClass['fields']): LuaClass => ({
  name,
  doc,
  methods: false,
  functions: [],
  fields,
  extends: 'Event',
})

/** The tables `nf.wasm` takes and gives, and the events of a program. */
export const wasmShapes: LuaClass[] = [
  {
    name: 'WasmFunction',
    doc: 'A function a module exports, from `nf.wasm.functions`.',
    methods: false,
    functions: [],
    fields: [
      { name: 'name', type: 'string', doc: 'Its name: `"sample"`.' },
      {
        name: 'params',
        type: 'string[]',
        doc: 'Its parameters\' types in order: `"i32"`, `"i64"`, `"f32"`, `"f64"` (a `"v128"` or a reference can\'t be passed).',
      },
      { name: 'results', type: 'string[]', doc: "Its results' types in order." },
    ],
  },
  {
    name: 'WasmRunOptions',
    doc: 'What `nf.wasm.run` takes.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'stdin',
        type: 'string?',
        doc: 'Its whole input; after it, the program reads the end of the file. None when left out.',
      },
      {
        name: 'arguments',
        type: 'string[]?',
        doc: "Its arguments, after its name (the module's id, `argv[0]`).",
      },
      {
        name: 'environment',
        type: 'table<string, string>?',
        doc: "Its environment variables; none when left out (it never sees the server's).",
      },
    ],
  },
  {
    name: 'WasmStartOptions',
    doc: 'What `nf.wasm.start` takes.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'arguments',
        type: 'string[]?',
        doc: "Its arguments, after its name (the module's id, `argv[0]`).",
      },
      {
        name: 'environment',
        type: 'table<string, string>?',
        doc: "Its environment variables; none when left out (it never sees the server's).",
      },
    ],
  },
  {
    name: 'WasmRunResult',
    doc: 'What a program `nf.wasm.run` ran did.',
    methods: false,
    functions: [],
    fields: [
      {
        name: 'exit_code',
        type: 'integer',
        doc: 'Its exit code: `0` when it says it worked, as programs do.',
      },
      {
        name: 'stdout',
        type: 'string',
        doc: 'What it printed to its standard output, up to `wasm.max-output-bytes`.',
      },
      {
        name: 'stderr',
        type: 'string',
        doc: 'What it printed to its standard error, up to the same.',
      },
    ],
  },
  event('WasmOutputEvent', 'A program printed a line.', [
    { name: 'process', type: 'WasmProcess', doc: 'The program.' },
    { name: 'line', type: 'string', doc: 'The line, without its line ending (UTF-8).' },
    {
      name: 'stream',
      type: '"stdout"|"stderr"',
      doc: 'Which it printed it to: its standard output or its standard error.',
    },
  ]),
  event('WasmEndEvent', 'A program ended.', [
    { name: 'process', type: 'WasmProcess', doc: 'The program.' },
    {
      name: 'reason',
      type: '"exited"|"stopped"|"failed"|"limit"|"unloaded"',
      doc: `Why: it exited (\`exit_code\` says how), \`stop()\` was called, it failed (it trapped, or the module couldn't be compiled or started: \`detail\` says why), an owner's limit in ${LIMITS} was reached, or the script that started it unloaded.`,
    },
    {
      name: 'exit_code',
      type: 'integer?',
      doc: 'Its exit code, when it exited; `nil` otherwise.',
    },
    {
      name: 'detail',
      type: 'string?',
      doc: 'What went wrong, in words, for `"failed"` and `"limit"`; `nil` otherwise.',
    },
  ]),
]
