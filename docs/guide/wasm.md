# WebAssembly

Lua scripts run on the server's main thread under an
[instruction budget](./performance.md), which keeps one script from holding
up the server, and also keeps serious computation out of Lua: a chess engine's
search, pathfinding over a big grid, noise for a whole region, compression.
**WebAssembly modules** are for that. Write the work in C, C++, Rust or
anything else that compiles to WebAssembly (or reuse a library that already
does), put the module in the project, and scripts hand it work off the main
thread and get the answer back on it.

A module can't touch the server: no world, players, network or files but its
own [data](#data-files). It can only answer the script that asked, so running
one is a [capability](../format/project.md#requirements) a package declares,
and whoever runs the server limits how much of the machine modules take
([`wasm:` in config.yml](./server-config.md#webassembly)).

## Adding a module

A module is a folder, `wasm/<id>/`, with the module as `module.wasm`:

```
wasm/
  pathfinder/
    module.wasm
```

and the package declares it runs modules, in `netherforge.json`:

```json
{ "requires": { "wasm": true } }
```

The editor shows a module's screen: what it exports and imports, its memory,
and its data. `netherforge check` and the dev server report a module the
server can't run as a [problem](../format/wasm.md#checks) (one that imports
something besides WASI's functions, one built for threads), and the dev server
reports what only compiling it finds (`runtime.wasm`).

## Building one

The server runs **WASI preview 1, single-threaded** modules: the
`wasm32-wasip1` target. A module may import WASI's functions (to read its
input, print, read the clock, get random bytes, read its data files) and
nothing else.

**Rust.** `rustup target add wasm32-wasip1`, then `cargo build --release
--target wasm32-wasip1`. A program (with a `main`) is a WASI command; a library
is a `cdylib` crate whose functions are `#[no_mangle] pub extern "C"`:

```rust
#[no_mangle]
pub extern "C" fn route(from_x: i32, from_z: i32, to_x: i32, to_z: i32) -> i32 {
    // ... a search over the grid ...
    steps
}
```

**C and C++.** Install [wasi-sdk](https://github.com/WebAssembly/wasi-sdk) and
build with its clang:

```sh
# a program: main() reads its input and prints its answer
$WASI_SDK/bin/clang++ --target=wasm32-wasip1 -O3 -fno-exceptions solver.cpp -o module.wasm

# a library: no main, the functions you export
$WASI_SDK/bin/clang --target=wasm32-wasip1 -O3 -mexec-model=reactor \
  -Wl,--export=sample -Wl,--export=alloc -Wl,--export=dealloc noise.c -o module.wasm
```

Leave out `-pthread` and the `wasm32-wasip1-threads` target, and SIMD
(`-msimd128`): the server runs neither, and says so. Optimise (`-O2`, `-O3`):
the module runs as it's built.

## Three ways to run one

### A library: instantiate it, call its functions

[`nf.wasm.instantiate`](../reference/nf.wasm.md#instantiate) makes an
**instance**: its own memory, kept from call to call. Its calls run off the
main thread one at a time, in order, and each gives its results back as a list:

```lua
nf.task(function()
  local paths, err = nf.wasm.instantiate("pathfinder")
  if not paths then
    log("no pathfinder: " .. err)
    return
  end
  local steps = paths:call("route", { 0, 0, 120, 64 })[1]
  log("the way is " .. steps .. " steps")
end)
```

Integers are `i32` or `i64` (an `i64` keeps all 64 bits), numbers `f32` or
`f64`; a function that returns several values gives a list of them all.
**Text** goes through the module's memory, the way C passes it: a string
argument is copied in with the module's exported `alloc(size)` (or `malloc`)
and passed as two `i32`s, where it starts and how many bytes it is;
[`call_text`](../reference/wasminstance.md#call_text) reads text back from a
function that returns where it is and how long (two `i32`s, or one `i64` with
the start in its high 32 bits). Both are freed after the call with the
module's `dealloc(pointer, size)` (or `free(pointer)`) when it exports one. So
JSON in and JSON out is:

```lua
local answer = planner:call_text("plan", { nf.json.encode(request) })
local plan = nf.json.decode(answer)
```

and in Rust the module's side is:

```rust
#[no_mangle]
pub extern "C" fn alloc(size: usize) -> *mut u8 {
    let mut buffer = Vec::with_capacity(size);
    let pointer = buffer.as_mut_ptr();
    std::mem::forget(buffer);
    pointer
}

#[no_mangle]
pub unsafe extern "C" fn dealloc(pointer: *mut u8, size: usize) {
    drop(Vec::from_raw_parts(pointer, 0, size));
}

#[no_mangle]
pub unsafe extern "C" fn plan(pointer: *const u8, size: usize) -> u64 {
    let request = std::str::from_utf8(std::slice::from_raw_parts(pointer, size)).unwrap();
    let answer = make_plan(request).into_bytes().into_boxed_slice();
    let (at, length) = (answer.as_ptr() as u64, answer.len() as u64);
    std::mem::forget(answer);
    (at << 32) | length
}
```

A call that **traps** (an out-of-bounds read, `unreachable`, a division by
zero) or runs past `max-call-seconds` fails with `nil, err`, and the instance
is closed: its memory may be half-written. Make a new one to carry on.

### A program: run it to its end

[`nf.wasm.run`](../reference/nf.wasm.md#run) runs a program with its input,
and gives what it printed and its exit code: for a tool that reads a question
and prints an answer.

```lua
nf.task(function()
  local result = nf.wasm.run("sudoku", { stdin = puzzle, arguments = { "--fast" } })
  if result.exit_code == 0 then
    player:send_message("Solved: " .. result.stdout)
  end
end)
```

### A program that keeps running: start it, talk line by line

[`nf.wasm.start`](../reference/nf.wasm.md#start) starts a program that keeps
running: [`send`](../reference/wasmprocess.md#send) writes a line of its input,
and every line it prints is an `output` event, on the main thread, in order.
This is how a UCI chess engine is used:

```lua
local engine = nf.wasm.start("stockfish")
engine:on("output", function(event)
  local move = event.line:match("^bestmove (%S+)")
  if move then
    board:play(move)
  end
end)
engine:on("end", function(event)
  log("the engine stopped:", event.reason, event.detail)
end)
engine:send("uci")
engine:send("isready")

function think(moves)
  engine:send("position startpos moves " .. table.concat(moves, " "))
  engine:send("go movetime 1000")
end
```

A program holds a thread while it runs, waiting while it reads input nobody has
sent. What it prints waits for scripts to take it (at most `max-queued-lines`;
past that it's paused, so nothing is dropped). It ends once, with its `end`
event: it exited, `stop()` was called, it failed, a limit was reached, or the
script that started it unloaded.

## Data files

Every file in the module's folder besides `module.wasm` is a file the module
can read from its root, `/`, as WASI programs read files: a chess engine's
network, a dictionary, a precomputed table.

```
wasm/
  stockfish/
    module.wasm
    nn-1a298aa575a0.nnue
```

It's read from the project as the module reads it, never all at once. The
module sees nothing else, and can't write or make anything: opening a file to
write is refused, and so is a path that climbs out of its folder.

## Lifecycle and limits

- **Everything a script makes is its own**: instances close, and programs end
  `"unloaded"`, when the script unloads (a reload, the server stopping). The
  script's own handlers are gone by then, so it doesn't hear that `end`.
- **A script that runs a module restarts when the module changes**: save a new
  `module.wasm` and the scripts that ran it start again on the new one.
- **Compiling happens as the project loads**, off the main thread: the server
  turns each module into Java classes once (half a second for a large engine),
  and the first call waits for it if it's still going.
- **The owner's limits** ([`wasm:`](./server-config.md#webassembly)): threads,
  memory per instance and in all, time per call, instances and programs per
  package. A limit is never an error at the call: a call fails with `nil, err`
  and a program ends `"limit"`, saying which key it was.

## Testing

In a [script test](./testing.md), modules really run: a test can call a small
library and check its answer. A test of a script around a heavy module (a
chess engine) can stand in for it instead with
[`nf.test.stub_wasm`](../reference/nf.test.md#stub_wasm):

```lua
nf.test.case("the bot plays the engine's move", function()
  nf.test.stub_wasm("stockfish", {
    input = function(line)
      if line:match("^go") then
        return { "bestmove e7e5" }
      end
    end,
  })
  local game = require("chess").new_game()
  game:play("e2e4")
  nf.test.advance(2)
  assert(game:last_move() == "e7e5")
end)
```

A stand-in's `functions` answer an instance's calls, `run` answers
`nf.wasm.run`, and `input` answers each line a started program is sent.

## A chess engine: Stockfish

Stockfish is the case `nf.wasm` was measured on. Its C++ builds for WASI with
wasi-sdk, with one change: it runs its search on a thread of its own, and a
module has none. Make its `Thread` run each job where it's asked, under
`__wasi__` without `_REENTRANT`:

- in `thread_native.h`, start no thread (`pthread_create` becomes a successful
  no-op, and `join` does nothing);
- in `thread.cpp`, `Thread::run_custom_job` runs the job at once (unless the
  thread is exiting), and `wait_for_search_finished` returns at once.

Then build it single-threaded, its network kept out of the binary (it's a data
file instead), and stub the one function wasi-libc lacks (`madvise`, which
only tablebase probing calls):

```sh
$WASI_SDK/bin/clang++ --target=wasm32-wasip1 -std=c++17 -O3 -DNDEBUG -DNO_PREFETCH \
  -DNNUE_EMBEDDING_OFF -DIS_64BIT -DUSE_POPCNT -fno-exceptions \
  -D_WASI_EMULATED_MMAN -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_PROCESS_CLOCKS \
  -lwasi-emulated-mman -lwasi-emulated-signal -lwasi-emulated-process-clocks \
  -Wl,-z,stack-size=8388608 madvise.cpp *.cpp nnue/*.cpp nnue/features/*.cpp syzygy/*.cpp \
  -o module.wasm
```

Put `module.wasm` and the network named in `evaluate.h` (`EvalFileDefaultName`)
in `wasm/stockfish/`, and start it as above. With the search on the same
thread as its input, it reads `stop` only once a search is done: give each
search its own end (`go movetime 1000`, `go depth 12`) rather than `go
infinite`.

**How fast.** Stockfish 19 on an Apple M-series machine (JDK 25), by
NetherForge's own benchmark (`WasmBenchmark`):

| Build                                         | Nodes a second  |
| --------------------------------------------- | --------------- |
| On the server (compiled), `bench` at depth 13 | about 20,700    |
| The same module in the interpreter            | about 400       |
| Native, without SIMD (`ARCH=general-64`)      | about 166,000   |
| Native, with NEON (`ARCH=apple-silicon`)      | about 1,390,000 |

So a module runs about eight times slower than the same code built natively,
and the compiler is what makes it usable: the interpreter is fifty times
slower again. For a chess engine that's still a strong opponent: a second's
search reaches depth 10 or so, far past any casual player. The engine starts
(compiled, its network read) in about two seconds and fits the default
`max-memory-mb` with a 16 MB hash.
