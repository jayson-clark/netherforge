//! `nf.wasm` on the Rust runtime, on real modules built here from WebAssembly text (Kotlin's `WasmFixtures`): calls
//! and their types, text through a module's memory, traps and time limits, programs run to their end and programs
//! that keep running, the read-only data folder, the owner's limits, what a package must declare, and what a
//! reload restarts. Scripts print; tests read the log, ticking until what they wait for is there.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::time::{Duration, Instant};

use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{FakePlatform, FakePlatformLog, PlatformError, PlatformLog, ServerInfo};
use netherforge_runtime::session::{Config, Session};
use netherforge_runtime::wasm::WasmConfig;

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
}

impl Fake {
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.lines.borrow_mut())
    }
}

impl FakePlatformLog for Fake {
    fn info(&self, message: String) {
        self.lines.borrow_mut().push(format!("info {message}"));
    }
    fn warn(&self, message: String) {
        self.lines.borrow_mut().push(format!("warn {message}"));
    }
    fn error(&self, message: String, _: Option<PlatformError>) {
        self.lines.borrow_mut().push(format!("error {message}"));
    }
}

impl netherforge_platform::FakeTeamOps for Fake {
    fn names(&self) -> Vec<String> {
        Vec::new()
    }
    fn clear_below_names(&self) {}
}

impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<netherforge_platform::PlayerRef> {
        Vec::new()
    }
}

/// No structure has put a marker in the world.
impl netherforge_platform::FakeEntityOps for Fake {
    fn structure_markers(&self) -> Vec<netherforge_platform::StructureMarker> {
        Vec::new()
    }
}

impl FakePlatform for Fake {
    fn entities(&self) -> &dyn netherforge_platform::EntityOps {
        self
    }
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn info(&self) -> ServerInfo {
        ServerInfo {
            minecraft_version: "26.3".into(),
            plugin_version: "0.0.0".into(),
            supported_targets: vec!["26.3".into()],
        }
    }
    fn log(&self) -> &dyn PlatformLog {
        self
    }
}

/// A project's files as bytes, read as the server reads them: modules' binaries included.
struct Files {
    names: RefCell<&'static BTreeSet<String>>,
    bytes: RefCell<BTreeMap<String, Vec<u8>>>,
}

impl Files {
    fn new(files: BTreeMap<String, Vec<u8>>) -> Files {
        Files {
            names: RefCell::new(Box::leak(Box::new(files.keys().cloned().collect()))),
            bytes: RefCell::new(files),
        }
    }

    fn write(&self, path: &str, bytes: Vec<u8>) {
        self.bytes.borrow_mut().insert(path.to_owned(), bytes);
        *self.names.borrow_mut() =
            Box::leak(Box::new(self.bytes.borrow().keys().cloned().collect()));
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        *self.names.borrow()
    }
    fn read(&self, path: &str) -> Option<String> {
        String::from_utf8(self.bytes.borrow().get(path)?.clone()).ok()
    }
    fn bytes(&self, path: &str) -> Option<Vec<u8>> {
        self.bytes.borrow().get(path).cloned()
    }
    fn reads_wasm(&self) -> bool {
        true
    }
    fn wasm(&self, path: &str) -> Option<netherforge_format::wasm::WasmInfo> {
        Some(netherforge_format::wasm::read(
            self.bytes.borrow().get(path)?,
        ))
    }
}

fn wat(text: &str) -> Vec<u8> {
    wat::parse_str(text).unwrap()
}

/// A library: numbers of every type, state kept between calls, text in and out through its own bump allocator
/// (which counts what it's asked to free), a trap, a loop that never ends, and memory growth.
fn library() -> Vec<u8> {
    wat(r#"
    (module
      (memory (export "memory") 1)
      (global $heap (mut i32) (i32.const 1024))
      (global $count (mut i32) (i32.const 0))
      (global $frees (mut i32) (i32.const 0))
      (data (i32.const 16) "hello")
      (func $alloc (export "alloc") (param $size i32) (result i32)
        (local $at i32)
        (local.set $at (global.get $heap))
        (global.set $heap (i32.add (global.get $heap) (local.get $size)))
        (local.get $at))
      (func (export "dealloc") (param i32 i32)
        (global.set $frees (i32.add (global.get $frees) (i32.const 1))))
      (func (export "frees") (result i32) (global.get $frees))
      (func (export "add") (param i32 i32) (result i32) (i32.add (local.get 0) (local.get 1)))
      (func (export "wide") (param i64 i64) (result i64) (i64.mul (local.get 0) (local.get 1)))
      (func (export "half") (param f64) (result f64) (f64.div (local.get 0) (f64.const 2)))
      (func (export "pair") (result i32 f32) (i32.const 7) (f32.const 1.5))
      (func (export "count") (result i32)
        (global.set $count (i32.add (global.get $count) (i32.const 1)))
        (global.get $count))
      (func (export "length") (param i32 i32) (result i32) (local.get 1))
      (func (export "upper") (param $p i32) (param $n i32) (result i64)
        (local $out i32) (local $i i32) (local $c i32)
        (local.set $out (call $alloc (local.get $n)))
        (block $done
          (loop $each
            (br_if $done (i32.ge_u (local.get $i) (local.get $n)))
            (local.set $c (i32.load8_u (i32.add (local.get $p) (local.get $i))))
            (if (i32.and (i32.ge_u (local.get $c) (i32.const 97)) (i32.le_u (local.get $c) (i32.const 122)))
              (then (local.set $c (i32.sub (local.get $c) (i32.const 32)))))
            (i32.store8 (i32.add (local.get $out) (local.get $i)) (local.get $c))
            (local.set $i (i32.add (local.get $i) (i32.const 1)))
            (br $each)))
        (i64.or (i64.shl (i64.extend_i32_u (local.get $out)) (i64.const 32)) (i64.extend_i32_u (local.get $n))))
      (func (export "greet") (result i32 i32) (i32.const 16) (i32.const 5))
      (func (export "trap") unreachable)
      (func (export "spin") (loop $forever (br $forever)))
      (func (export "grow") (param i32) (result i32) (memory.grow (local.get 0)))
    )
    "#)
}

/// A program that reads its input a byte at a time and answers each line with `echo: <line>`; `err` goes to its
/// standard error, `quit` exits with 3, `spin` never returns, and the end of its input exits with 0.
fn echo() -> Vec<u8> {
    wat(r#"
    (module
      (import "wasi_snapshot_preview1" "fd_read" (func $fd_read (param i32 i32 i32 i32) (result i32)))
      (import "wasi_snapshot_preview1" "fd_write" (func $fd_write (param i32 i32 i32 i32) (result i32)))
      (import "wasi_snapshot_preview1" "proc_exit" (func $proc_exit (param i32)))
      (memory (export "memory") 1)
      (data (i32.const 24) "\n")
      (data (i32.const 100) "echo: ")
      (data (i32.const 300) "quit")
      (data (i32.const 304) "spin")
      (data (i32.const 308) "err")
      (func (export "_start")
        (local $n i32) (local $out i32)
        (loop $next
          (i32.store (i32.const 0) (i32.const 16))
          (i32.store (i32.const 4) (i32.const 1))
          (drop (call $fd_read (i32.const 0) (i32.const 0) (i32.const 1) (i32.const 8)))
          (if (i32.eqz (i32.load (i32.const 8))) (then (return)))
          (if (i32.eq (i32.load8_u (i32.const 16)) (i32.const 10))
            (then
              (if (i32.and (i32.eq (local.get $n) (i32.const 4)) (i32.eq (i32.load (i32.const 200)) (i32.load (i32.const 300))))
                (then (call $proc_exit (i32.const 3))))
              (if (i32.and (i32.eq (local.get $n) (i32.const 4)) (i32.eq (i32.load (i32.const 200)) (i32.load (i32.const 304))))
                (then (loop $forever (br $forever))))
              (local.set $out (i32.const 1))
              (if (i32.and (i32.eq (local.get $n) (i32.const 3))
                           (i32.eq (i32.and (i32.load (i32.const 200)) (i32.const 0xFFFFFF)) (i32.and (i32.load (i32.const 308)) (i32.const 0xFFFFFF))))
                (then (local.set $out (i32.const 2))))
              (i32.store (i32.const 32) (i32.const 100)) (i32.store (i32.const 36) (i32.const 6))
              (i32.store (i32.const 40) (i32.const 200)) (i32.store (i32.const 44) (local.get $n))
              (i32.store (i32.const 48) (i32.const 24)) (i32.store (i32.const 52) (i32.const 1))
              (drop (call $fd_write (local.get $out) (i32.const 32) (i32.const 3) (i32.const 8)))
              (local.set $n (i32.const 0))
              (br $next)))
          (i32.store8 (i32.add (i32.const 200) (local.get $n)) (i32.load8_u (i32.const 16)))
          (local.set $n (i32.add (local.get $n) (i32.const 1)))
          (br $next)))
    )
    "#)
}

/// A program that prints its data file `words/hello.txt`, then exits with what opening a file for writing answered
/// times 100 plus what opening `../escape.txt` answered (WASI's error numbers): 6976 is EROFS then ENOTCAPABLE.
fn reader() -> Vec<u8> {
    wat(r#"
    (module
      (import "wasi_snapshot_preview1" "path_open"
        (func $path_open (param i32 i32 i32 i32 i32 i64 i64 i32 i32) (result i32)))
      (import "wasi_snapshot_preview1" "fd_read" (func $fd_read (param i32 i32 i32 i32) (result i32)))
      (import "wasi_snapshot_preview1" "fd_write" (func $fd_write (param i32 i32 i32 i32) (result i32)))
      (import "wasi_snapshot_preview1" "proc_exit" (func $proc_exit (param i32)))
      (memory (export "memory") 1)
      (data (i32.const 1000) "words/hello.txt")
      (data (i32.const 1100) "../escape.txt")
      (func (export "_start")
        (local $err i32) (local $written i32) (local $escaped i32)
        (local.set $err (call $path_open (i32.const 3) (i32.const 0) (i32.const 1000) (i32.const 15)
          (i32.const 0) (i64.const 2) (i64.const 0) (i32.const 0) (i32.const 0)))
        (if (local.get $err) (then (call $proc_exit (local.get $err))))
        (i32.store (i32.const 8) (i32.const 2000)) (i32.store (i32.const 12) (i32.const 512))
        (drop (call $fd_read (i32.load (i32.const 0)) (i32.const 8) (i32.const 1) (i32.const 16)))
        (i32.store (i32.const 24) (i32.const 2000)) (i32.store (i32.const 28) (i32.load (i32.const 16)))
        (drop (call $fd_write (i32.const 1) (i32.const 24) (i32.const 1) (i32.const 16)))
        (local.set $written (call $path_open (i32.const 3) (i32.const 0) (i32.const 1000) (i32.const 15)
          (i32.const 0) (i64.const 64) (i64.const 0) (i32.const 0) (i32.const 4)))
        (local.set $escaped (call $path_open (i32.const 3) (i32.const 0) (i32.const 1100) (i32.const 13)
          (i32.const 0) (i64.const 2) (i64.const 0) (i32.const 0) (i32.const 4)))
        (call $proc_exit (i32.add (i32.mul (local.get $written) (i32.const 100)) (local.get $escaped))))
    )
    "#)
}

/// A module framed right, as format reads it, whose code doesn't validate: `bad` says it returns an i32 and leaves
/// an i64 (its `i32.const 7` patched to `i64.const 7`).
fn invalid_code() -> Vec<u8> {
    let mut bytes = wat(r#"(module (func (export "bad") (result i32) (i32.const 7)))"#);
    let at = (0..bytes.len() - 2)
        .rev()
        .find(|&i| bytes[i] == 0x41 && bytes[i + 1] == 0x07 && bytes[i + 2] == 0x0B)
        .unwrap();
    bytes[at] = 0x42;
    bytes
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3", "requires": REQUIRES }"#;

/// A project whose one module `t` is `script`, with `modules` as `wasm/<id>/module.wasm` and `extra` files.
fn project(
    script: &str,
    modules: &[(&str, Vec<u8>)],
    extra: &[(&str, &[u8])],
    requires: &str,
) -> Files {
    let mut files = BTreeMap::new();
    files.insert(
        "netherforge.json".to_owned(),
        MANIFEST.replace("REQUIRES", requires).into_bytes(),
    );
    files.insert("modules/t/init.lua".to_owned(), script.as_bytes().to_vec());
    for (id, bytes) in modules {
        files.insert(format!("wasm/{id}/module.wasm"), bytes.clone());
    }
    for (path, bytes) in extra {
        files.insert((*path).to_owned(), bytes.to_vec());
    }
    Files::new(files)
}

fn standard(script: &str) -> Files {
    project(
        script,
        &[("math", library()), ("echo", echo())],
        &[],
        r#"{ "wasm": true }"#,
    )
}

type Rc = std::rc::Rc<Fake>;

fn start<'a>(files: &'a Files, platform: &Rc, wasm: WasmConfig) -> Session<'a> {
    Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        std::rc::Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            wasm,
            ..Config::default()
        },
    )
}

/// What scripts printed and what went wrong, read off the log: a script's lines without the place, every warning
/// and error whole.
fn lines(platform: &Fake) -> Vec<String> {
    let mut lines = Vec::new();
    for line in platform.take() {
        if let Some(rest) = line.strip_prefix("info [module t] ") {
            let text = rest.rsplit_once(" (modules/").map_or(rest, |(t, _)| t);
            lines.push(text.to_owned());
        } else if line.starts_with("warn") || line.starts_with("error") {
            lines.push(line);
        }
    }
    lines
}

/// Ticks until scripts have printed `count` lines (at most ten seconds), and gives them.
fn printed(session: &Session, platform: &Fake, count: usize) -> Vec<String> {
    let mut seen = Vec::new();
    let started = Instant::now();
    while started.elapsed() < Duration::from_secs(10) {
        seen.extend(lines(platform));
        if seen.len() >= count {
            return seen;
        }
        session.tick();
        std::thread::sleep(Duration::from_millis(5));
    }
    panic!("only {} of {count} lines: {seen:?}", seen.len());
}

/// Runs `body` in a task of the standard project and gives what it printed, `count` lines.
fn task(body: &str, count: usize) -> Vec<String> {
    let files = standard(&format!("nf.task(function()\n{body}\nend)"));
    let platform = Rc::default();
    let session = start(&files, &platform, WasmConfig::default());
    printed(&session, &platform, count)
}

#[test]
fn an_instances_functions_take_and_give_every_number_type_and_keep_their_state() {
    let lines = task(
        r#"
        local lib, err = nf.wasm.instantiate("math")
        print(tostring(err), lib:module())
        print(lib:call("add", { 2, 3 })[1])
        print(lib:call("wide", { 3000000000, 3 })[1])
        print(lib:call("wide", { 3000000000, 3000000000 })[1])
        print(lib:call("half", { 5 })[1])
        local pair = lib:call("pair")
        print(pair[1], pair[2])
        print(lib:call("count")[1], lib:call("count")[1])
        "#,
        7,
    );
    assert_eq!(
        lines,
        [
            "nil\tmath",
            "5",
            "9000000000",
            // Past 2^53 an i64 comes back as its digits, never a double that lost its low bits.
            "9000000000000000000",
            "2.5",
            "7\t1.5",
            "1\t2"
        ]
    );
}

#[test]
fn strings_go_in_through_the_modules_memory_and_text_comes_back_each_freed_after() {
    let lines = task(
        r#"
        local lib = nf.wasm.instantiate("math")
        print(lib:call("length", { "héllo" })[1])
        print((lib:call_text("upper", { "abc" })))
        print((lib:call_text("greet")))
        print(lib:call("frees")[1])
        "#,
        4,
    );
    assert_eq!(lines, ["6", "ABC", "hello", "4"]);
}

#[test]
fn nf_wasm_functions_lists_what_a_module_exports_with_its_types() {
    let lines = task(
        r#"
        nf.wait(2)
        while #nf.wasm.functions("math") == 0 do nf.wait(1) end
        for _, fn in ipairs(nf.wasm.functions("math")) do
          if fn.name == "upper" or fn.name == "pair" then
            print(fn.name, table.concat(fn.params, ","), table.concat(fn.results, ","))
          end
        end
        while #nf.wasm.functions("echo") == 0 do nf.wait(1) end
        print(#nf.wasm.functions("echo"), nf.wasm.functions("echo")[1].name)
        "#,
        3,
    );
    assert_eq!(
        lines,
        ["pair\t\ti32,f32", "upper\ti32,i32\ti64", "1\t_start"]
    );
}

#[test]
fn what_a_script_gets_wrong_in_a_call_is_an_error_at_its_line() {
    for (call, error) in [
        (r#"lib:call("nope")"#, r#"exports no function "nope""#),
        (
            r#"lib:call("add", { 1 })"#,
            "add takes (i32, i32), but it was given fewer arguments",
        ),
        (r#"lib:call("add", { 1, 2, 3 })"#, "args[3] is one too many"),
        (
            r#"lib:call("add", { 1.5, 2 })"#,
            "args[1] is 1.5, but add takes an i32 there: give an integer",
        ),
        (r#"lib:call("add", { 1, true })"#, "args[2] is a boolean"),
        (r#"lib:call("add", { 1, nil, 3 })"#, "has a hole at 2"),
        (r#"lib:call("add", { 2 ^ 40, 1 })"#, "doesn't fit add's i32"),
        (
            r#"lib:call_text("add", { 1, 2 })"#,
            "add returns (i32), not text",
        ),
        (
            r#"nf.wasm.instantiate("missing")"#,
            r#"no WebAssembly module "missing" in this project"#,
        ),
    ] {
        let files = standard(&format!(
            "nf.task(function()\n  lib = nf.wasm.instantiate(\"math\")\n  nf.wait(1)\n  {call}\nend)"
        ));
        let platform = Rc::default();
        let session = start(&files, &platform, WasmConfig::default());
        let lines = printed(&session, &platform, 1);
        assert_eq!(lines.len(), 1, "{call}: {lines:?}");
        assert!(
            lines[0].starts_with("warn module t: task failed: "),
            "{call}: {}",
            lines[0]
        );
        assert!(lines[0].contains(error), "{call}: {}", lines[0]);
        assert!(
            lines[0].ends_with("(modules/t/init.lua:4)"),
            "{call}: {}",
            lines[0]
        );
    }
}

#[test]
fn a_call_that_traps_fails_and_its_instance_is_closed_after() {
    let lines = task(
        r#"
        local lib = nf.wasm.instantiate("math")
        local results, err = lib:call("trap")
        print(tostring(results), err)
        print(tostring(lib:call("add", { 1, 2 })), select(2, lib:call("add", { 1, 2 })))
        print(tostring(lib:close()), lib:module())
        "#,
        3,
    );
    assert!(lines[0].starts_with("nil\ttrap failed: "), "{}", lines[0]);
    assert!(lines[0].contains("unreachable"), "{}", lines[0]);
    assert_eq!(lines[1..], ["nil\tthe instance is closed", "false\tmath"]);
}

#[test]
fn a_call_that_traps_after_taking_a_string_says_why_without_running_the_modules_free() {
    // Its free fails differently: had it run after the trap, its failure would be what the script read.
    let faulty = wat(r#"
    (module
      (memory (export "memory") 1)
      (func (export "alloc") (param i32) (result i32) (i32.const 64))
      (func (export "dealloc") (param i32 i32) (drop (i32.load (i32.const 1000000))))
      (func (export "fail") (param i32 i32) unreachable)
    )
    "#);
    let files = project(
        r#"nf.task(function()
          local lib = nf.wasm.instantiate("faulty")
          print(select(2, lib:call("fail", { "text" })))
        end)"#,
        &[("faulty", faulty)],
        &[],
        r#"{ "wasm": true }"#,
    );
    let platform = Rc::default();
    let session = start(&files, &platform, WasmConfig::default());
    let err = printed(&session, &platform, 1).remove(0);
    assert!(
        err.contains("fail failed")
            && err.contains("unreachable")
            && !err.contains("out of bounds"),
        "{err}"
    );
}

#[test]
fn a_call_past_max_call_seconds_is_stopped_and_its_instance_closed() {
    let files = standard(
        r#"nf.task(function()
          local lib = nf.wasm.instantiate("math")
          local _, err = lib:call("spin")
          print(err)
          print(select(2, lib:call("count")))
        end)"#,
    );
    let platform = Rc::default();
    let session = start(
        &files,
        &platform,
        WasmConfig {
            max_call_seconds: 1,
            ..WasmConfig::default()
        },
    );
    assert_eq!(
        printed(&session, &platform, 2),
        [
            "spin took longer than 1 seconds (wasm.max-call-seconds in config.yml), so it was stopped and the instance closed",
            "the instance is closed"
        ]
    );
}

#[test]
fn a_program_runs_to_its_end_with_its_input_and_gives_what_it_printed_and_its_exit_code() {
    let lines = task(
        r#"
        local result = nf.wasm.run("echo", { stdin = "hi\nerr\nthere\n" })
        print(result.exit_code, result.stdout, result.stderr)
        print(nf.wasm.run("echo", { stdin = "quit\n" }).exit_code)
        local _, err = nf.wasm.run("math")
        print(err)
        "#,
        3,
    );
    assert_eq!(
        lines,
        [
            "0\techo: hi\necho: there\n\techo: err\n",
            "3",
            "module \"math\" isn't a program: it exports no _start (call its functions with nf.wasm.instantiate)"
        ]
    );
}

#[test]
fn a_started_program_talks_line_by_line_until_its_input_ends_then_exits() {
    let files = standard(
        r#"
        local echo = nf.wasm.start("echo")
        echo:on("output", function(event) print(event.stream, event.line) end)
        echo:on("end", function(event) print("end", event.reason, event.exit_code, echo:state()) end)
        echo:send("one")
        echo:send("err")
        nf.task(function()
          nf.wait_until(function() return seen == 2 end)
          echo:send("two")
          echo:close_input()
          print("sent after close:", echo:send("three"))
        end)
        echo:on("output", function() seen = (seen or 0) + 1 end)
        "#,
    );
    let platform = Rc::default();
    let session = start(&files, &platform, WasmConfig::default());
    assert_eq!(
        printed(&session, &platform, 5),
        [
            "stdout\techo: one",
            "stderr\techo: err",
            "sent after close:\tfalse",
            "stdout\techo: two",
            "end\texited\t0\tended"
        ]
    );
}

#[test]
fn a_program_ends_with_its_exit_code_when_stopped_and_when_its_script_unloads() {
    let files = standard(
        r#"
        local quits = nf.wasm.start("echo")
        quits:on("end", function(event) print("quits", event.reason, event.exit_code) end)
        quits:send("quit")
        local spins = nf.wasm.start("echo")
        spins:on("end", function(event) print("spins", event.reason, tostring(event.exit_code)) end)
        spins:send("spin")
        nf.after(1, function() print("stopped", spins:stop(), spins:stop(), spins:state()) end)
        kept = nf.wasm.start("echo")
        "#,
    );
    let platform = Rc::default();
    let mut session = start(
        &files,
        &platform,
        WasmConfig {
            max_processes_per_package: 3,
            ..WasmConfig::default()
        },
    );
    let mut lines = printed(&session, &platform, 3);
    lines.sort();
    // When the first program exits beside the second one's stop is the threads' business: not the order.
    assert_eq!(
        lines,
        [
            "quits\texited\t3",
            "spins\tstopped\tnil",
            "stopped\ttrue\tfalse\tended"
        ]
    );
    assert_eq!(session.shared().wasm.borrow().programs.len(), 1);
    files.write("modules/t/init.lua", b"print('again')".to_vec());
    session.reload(&["modules/t/init.lua".into()]);
    assert_eq!(printed(&session, &platform, 1), ["again"]);
    assert!(
        session.shared().wasm.borrow().programs.is_empty(),
        "the kept program went with its script"
    );
}

#[test]
fn a_module_reads_its_data_folder_read_only_and_nothing_outside_it() {
    let files = project(
        r#"nf.task(function()
          local result = nf.wasm.run("reader")
          print(result.stdout, result.exit_code)
        end)"#,
        &[("reader", reader())],
        &[
            ("wasm/reader/words/hello.txt", b"hello from the data folder"),
            ("wasm/escape.txt", b"secret"),
        ],
        r#"{ "wasm": true }"#,
    );
    let platform = Rc::default();
    let session = start(&files, &platform, WasmConfig::default());
    // EROFS (69) opening it for writing, ENOTCAPABLE (76) climbing out of the root.
    let lines = printed(&session, &platform, 1);
    assert_eq!(
        lines.last().unwrap(),
        "hello from the data folder\t6976",
        "{lines:?}"
    );
}

#[test]
fn the_owners_limits_on_instances_and_programs_fail_or_end_whats_over_them() {
    let files = standard(
        r#"nf.task(function()
          local first = nf.wasm.instantiate("math")
          print(select(2, nf.wasm.instantiate("math")))
          first:close()
          print(tostring(nf.wasm.instantiate("math") ~= nil))
          local one = nf.wasm.start("echo")
          local two = nf.wasm.start("echo")
          two:on("end", function(event) print(event.reason, event.detail) end)
        end)"#,
    );
    let platform = Rc::default();
    let session = start(
        &files,
        &platform,
        WasmConfig {
            max_instances_per_package: 1,
            max_processes_per_package: 1,
            ..WasmConfig::default()
        },
    );
    assert_eq!(
        printed(&session, &platform, 3),
        [
            "package \"test\" already holds 1 WebAssembly instances, the server's limit (wasm.max-instances-per-package in config.yml): close some",
            "true",
            "limit\tpackage \"test\" already has 1 WebAssembly programs running, the server's limit (wasm.max-processes-per-package in config.yml)"
        ]
    );
}

#[test]
fn an_instance_may_grow_to_its_cap_and_no_further_and_past_the_total_none_starts() {
    let files = standard(
        r#"nf.task(function()
          local lib = nf.wasm.instantiate("math")
          print(lib:call("grow", { 1 })[1], lib:call("grow", { 100 })[1])
          print(select(2, nf.wasm.instantiate("math")))
        end)"#,
    );
    let platform = Rc::default();
    let session = start(
        &files,
        &platform,
        WasmConfig {
            max_memory_mb: 1,
            max_memory_total_mb: 1,
            ..WasmConfig::default()
        },
    );
    let lines = printed(&session, &platform, 2);
    assert_eq!(lines[0], "1\t-1");
    assert!(
        lines[1].starts_with("the server's WebAssembly memory is taken"),
        "{}",
        lines[1]
    );
}

#[test]
fn running_modules_is_something_a_package_declares() {
    let files = project(
        r#"nf.wasm.functions("math")"#,
        &[("math", library())],
        &[],
        "{}",
    );
    let platform = Rc::default();
    let session = start(&files, &platform, WasmConfig::default());
    session.tick();
    let lines = lines(&platform);
    assert!(
        lines
            .iter()
            .any(|l| l.contains("nf.wasm.functions needs") && l.contains("wasm")),
        "{lines:?}"
    );
}

#[test]
fn a_module_the_server_cant_run_is_a_problem_and_running_it_fails_saying_why() {
    let files = project(
        r#"nf.task(function()
          print(select(2, nf.wasm.instantiate("host")))
          print(select(2, nf.wasm.instantiate("junk")))
          print(select(2, nf.wasm.instantiate("bad")))
          local vector = nf.wasm.instantiate("vector")
          print(vector:call("sum", { 2, 3 })[1])
        end)"#,
        &[
            (
                "host",
                wat(
                    r#"(module (import "env" "teleport" (func $t (param i32))) (func (export "go") (call $t (i32.const 1))))"#,
                ),
            ),
            ("junk", b"not a module".to_vec()),
            ("bad", invalid_code()),
            (
                "vector",
                wat(r#"(module (func (export "sum") (param i32 i32) (result i32)
                  (i32x4.extract_lane 0 (i32x4.add (i32x4.splat (local.get 0)) (i32x4.splat (local.get 1))))))"#),
            ),
        ],
        &[],
        r#"{ "wasm": true }"#,
    );
    let platform = Rc::default();
    let session = start(&files, &platform, WasmConfig::default());
    // Format's two errors are warned as the session starts, and the failed compile as it's found.
    let lines: Vec<String> = printed(&session, &platform, 7)
        .into_iter()
        .filter(|l| !l.starts_with("warn wasm/"))
        .collect();
    // What format sees in a module (its imports, its framing) is an error there, and it never runs.
    assert!(
        lines.contains(&"module \"host\" has errors, so it can't run: see the problems".to_owned()),
        "{lines:?}"
    );
    assert!(
        lines.contains(&"module \"junk\" has errors, so it can't run: see the problems".to_owned()),
        "{lines:?}"
    );
    // What only compiling finds is the runtime's: code that doesn't validate. SIMD runs (wasmtime has it).
    assert!(
        lines.iter().any(|l| l.starts_with(
            "module \"bad\" couldn't be compiled: module.wasm isn't a valid WebAssembly module"
        )),
        "{lines:?}"
    );
    assert!(lines.contains(&"5".to_owned()), "{lines:?}");
    assert!(
        lines
            .iter()
            .any(|l| l.starts_with("warn WebAssembly module \"bad\" couldn't be compiled")),
        "{lines:?}"
    );
    let problems: Vec<(String, String)> = session
        .problems()
        .into_iter()
        .map(|p| (p.code.unwrap_or_default(), p.file))
        .collect();
    let has = |code: &str, file: &str| problems.iter().any(|(c, f)| c == code && f == file);
    assert!(has("wasm.import", "wasm/host/module.wasm"), "{problems:?}");
    assert!(has("wasm.invalid", "wasm/junk/module.wasm"), "{problems:?}");
    assert!(has("runtime.wasm", "wasm/bad/module.wasm"), "{problems:?}");
}

#[test]
fn a_script_that_ran_a_module_restarts_when_the_module_changes() {
    let files = standard(
        r#"
        print("started")
        nf.task(function() print(nf.wasm.instantiate("math"):call("count")[1]) end)
        "#,
    );
    let platform = Rc::default();
    let mut session = start(&files, &platform, WasmConfig::default());
    assert_eq!(printed(&session, &platform, 2), ["started", "1"]);
    files.write("wasm/math/module.wasm", library());
    session.reload(&["wasm/math/module.wasm".into()]);
    assert_eq!(printed(&session, &platform, 2), ["started", "1"]);
}

#[test]
fn the_interpreter_runs_what_the_compiler_does() {
    let files = standard(
        r#"nf.task(function()
          local lib = nf.wasm.instantiate("math")
          print(lib:call("add", { 2, 3 })[1], (lib:call_text("upper", { "pulley" })))
          print(select(2, lib:call("spin")))
        end)"#,
    );
    let platform = Rc::default();
    let session = start(
        &files,
        &platform,
        WasmConfig {
            compile: false,
            max_call_seconds: 1,
            ..WasmConfig::default()
        },
    );
    // Epochs stop the interpreter's loops as they stop compiled code.
    assert_eq!(
        printed(&session, &platform, 2),
        [
            "5\tPULLEY",
            "spin took longer than 1 seconds (wasm.max-call-seconds in config.yml), so it was stopped and the instance closed"
        ]
    );
}
