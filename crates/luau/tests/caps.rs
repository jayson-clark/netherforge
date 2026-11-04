//! The ways a script could hang the server inside one library call, where no
//! interrupt fires: a function of the standard library doing unbounded work.
//! Each fails at the script's line, with the default limits, at once. The
//! Kotlin runtime's `HangTest`, on Luau.

use netherforge_luau::{Failure, Host, Limits, Stop};

const CHUNK: &str = "modules/t/init.lua";

fn host() -> Host {
    Host::new(Limits::default()).unwrap()
}

fn run(host: &Host, source: &str) -> Result<Vec<String>, Failure> {
    let scope = host.open_scope("t", "t").unwrap();
    let logged = host.lua().create_table().unwrap();
    let log = logged.clone();
    let log_fn = host
        .lua()
        .create_function(move |_, parts: netherforge_luau::mlua::Variadic<String>| {
            log.raw_push(parts.join("\t"))?;
            Ok(())
        })
        .unwrap();
    host.env(scope).unwrap().raw_set("log", log_fn).unwrap();
    host.run::<()>(scope, 10_000_000, CHUNK, source)?;
    Ok(logged
        .sequence_values::<String>()
        .map(Result::unwrap)
        .collect())
}

/// The error running `source` gives, which must be at its line `line`.
fn refused(source: &str, line: u32) -> String {
    let failure = run(&host(), source).expect_err("the script should fail");
    let message = failure.to_string();
    let at = format!("{CHUNK}:{line}: ");
    assert!(
        message.starts_with(&at),
        "expected an error at {at}, got {failure:?}"
    );
    message
}

fn assert_has(message: &str, part: &str) {
    assert!(message.contains(part), "{message:?} should say {part:?}");
}

#[test]
fn string_rep_with_a_huge_count() {
    let message = refused(r#"local s = ("x"):rep(1e10)"#, 1);
    assert_has(
        &message,
        "string.rep: the result would be 10000000000 bytes, over the limit of 16 MB",
    );
}

#[test]
fn string_rep_of_nothing_a_huge_number_of_times() {
    let message = refused(r#"local s = string.rep("", 1e15)"#, 1);
    assert_has(
        &message,
        "string.rep: repeating 1000000000000000 times is over the limit",
    );
}

#[test]
fn string_rep_with_a_big_separator() {
    let message = refused(r#"local s = string.rep("x", 100000, ("y"):rep(1000))"#, 1);
    assert_has(&message, "string.rep: the result would be 100099000 bytes");
}

#[test]
fn a_pattern_that_backtracks() {
    let message = refused(r#"local i = string.find(("a"):rep(10000), ".-.-.-.-b")"#, 1);
    assert_has(
        &message,
        "string.find: the pattern \".-.-.-.-b\" could take up to",
    );
    assert_has(&message, "on 10000 bytes, over the limit of 1e+08");
}

#[test]
fn a_pattern_that_backtracks_through_each_pattern_function() {
    let s = r#"local s = ("a"):rep(10000)"#;
    assert_has(
        &refused(&format!("{s}\nlocal m = s:match(\"(.-)%w+=\")"), 2),
        "string.match: the pattern",
    );
    assert_has(
        &refused(&format!("{s}\nfor w in s:gmatch(\"%w+=\") do end"), 2),
        "string.gmatch: the pattern",
    );
    assert_has(
        &refused(&format!("{s}\nlocal r = s:gsub(\"(.-)(.-)b\", \"\")"), 2),
        "string.gsub: the pattern",
    );
}

#[test]
fn a_cheap_pattern_on_a_long_string_is_allowed() {
    let logs = run(&host(), r#"local s = ("a,"):rep(100000) local n = 0 for w in s:gmatch("[^,]+") do n += 1 end log(n)"#).unwrap();
    assert_eq!(logs, ["100000"]);
}

#[test]
fn looking_for_a_long_string_in_a_longer_one() {
    let message = refused(
        r#"local i = ("a"):rep(1000000):find(("a"):rep(500000) .. "b", 1, true)"#,
        1,
    );
    assert_has(
        &message,
        "string.find: looking for 500001 bytes in 1000000 could take up to",
    );
}

#[test]
fn a_format_width_past_what_the_library_allows() {
    // Luau, like Lua 5.4, refuses a width of more than two digits itself. Its
    // message comes from inside the caps, which have no lines, so it's located
    // at the script's.
    let message = refused(r#"local s = string.format("%99999d", 1)"#, 1);
    assert_has(&message, "format");
}

#[test]
fn a_format_string_repeating_big_arguments() {
    let message = refused(
        "local big = (\"x\"):rep(1000000)\nlocal args = {}\nfor i = 1, 20 do args[i] = big end\nlocal s = string.format((\"%s\"):rep(20), table.unpack(args))",
        4,
    );
    assert_has(
        &message,
        "string.format: the result would be 20000040 bytes, over the limit of 16 MB",
    );
}

#[test]
fn a_format_string_repeating_a_big_tostring() {
    let loud = vec!["loud"; 20].join(", ");
    let message = refused(
        &format!(
            "local big = (\"x\"):rep(1000000)\nlocal loud = setmetatable({{}}, {{ __tostring = function() return big end }})\nlocal s = string.format((\"%s\"):rep(20), {loud})"
        ),
        3,
    );
    assert_has(&message, "string.format: the result would be");
}

#[test]
fn gsub_multiplying_a_string() {
    let message = refused(
        r#"local s = ("x"):rep(100000):gsub("x", ("y"):rep(1000))"#,
        1,
    );
    assert_has(&message, "string.gsub: the result would be");
}

#[test]
fn gsub_multiplying_a_string_through_a_function() {
    let message = refused(
        "local big = (\"y\"):rep(1000000)\nlocal s = (\"x\"):rep(100):gsub(\"x\", function() return big end)",
        2,
    );
    assert_has(&message, "string.gsub: the result would be");
}

#[test]
fn table_concat_of_a_big_table() {
    let message = refused(
        "local big = (\"x\"):rep(1000000)\nlocal t = {}\nfor i = 1, 1000 do t[i] = big end\nlocal s = table.concat(t)",
        4,
    );
    assert_has(
        &message,
        "table.concat: the result would be 1000000000 bytes, over the limit of 16 MB",
    );
}

#[test]
fn string_pack_padding_a_huge_field() {
    let message = refused(r#"local s = string.pack("c2000000000", "")"#, 1);
    assert_has(&message, "string.pack: the result would be");
}

#[test]
fn table_move_over_a_huge_range() {
    let message = refused("table.move({}, 1, 1e12, 1, {})", 1);
    assert_has(
        &message,
        "table.move: moving 1000000000000 elements could take up to",
    );
}

#[test]
fn sorting_a_huge_table() {
    let message = refused(r#"local t = table.create(900000, 1) table.sort(t)"#, 1);
    assert_has(
        &message,
        "table.sort: sorting 900000 values could take up to",
    );
}

#[test]
fn a_string_doubled_with_concatenation_stops_at_the_memory_limit() {
    // `..` is a VM instruction, not a library function: nothing caps it, and
    // the allocator's limit stops it.
    let host = Host::new(Limits {
        memory_bytes: 64 * 1024 * 1024,
        ..Limits::default()
    })
    .unwrap();
    let failure = run(&host, "local s = \"x\"\nfor i = 1, 30 do s = s .. s end").unwrap_err();
    assert_eq!(failure, Failure::Memory);
}

#[test]
fn a_loop_making_garbage_is_still_stopped() {
    let failure = run(
        &host(),
        "local s = (\"x\"):rep(64 * 1024)\nwhile true do local u = s .. \"a\" end",
    )
    .unwrap_err();
    assert!(
        matches!(
            failure,
            Failure::Stopped {
                stop: Stop::Budget { .. } | Stop::Time { .. },
                ..
            }
        ),
        "{failure:?}"
    );
}

/// A table whose `__len` says 1 to the cap and two billion to the library
/// (which would then shift in C).
const LYING: &str = "local asked = 0\nlocal t = setmetatable({}, { __len = function() asked = asked + 1; return asked == 1 and 1 or 2000000000 end })";

#[test]
fn table_remove_reads_a_length_that_changes_only_once() {
    let logs = run(
        &host(),
        &format!("{LYING}\ntable.remove(t, 1)\nlog('removed, asked ' .. asked)"),
    )
    .unwrap();
    assert_eq!(logs, ["removed, asked 1"]);
}

#[test]
fn table_insert_reads_a_length_that_changes_only_once() {
    let logs = run(
        &host(),
        &format!("{LYING}\ntable.insert(t, 1, 'x')\nlog('inserted, asked ' .. asked)"),
    )
    .unwrap();
    assert_eq!(logs, ["inserted, asked 1"]);
}

#[test]
fn table_sort_reads_a_length_that_changes_only_once() {
    let logs = run(
        &host(),
        &format!("{LYING}\nrawset(t, 1, 5)\ntable.sort(t)\nlog('sorted, asked ' .. asked)"),
    )
    .unwrap();
    assert_eq!(logs, ["sorted, asked 1"]);
}

#[test]
fn table_concat_measures_the_very_values_it_joins() {
    let message = refused(
        "local big = (\"x\"):rep(16 * 1024 * 1024)\nlocal reads = 0\nlocal t = setmetatable({}, { __index = function() reads = reads + 1; return reads <= 200 and \"\" or big end })\nlocal s = table.concat(t, \"\", 1, 400)",
        4,
    );
    assert_has(&message, "table.concat: the result would be");
}

#[test]
fn tables_with_metatables_still_insert_remove_sort_and_concat_as_the_library_does() {
    let logs = run(
        &host(),
        "local List = {}
List.__index = List
local l = setmetatable({ 3, 1, 2 }, List)
table.insert(l, 1, 4)
table.insert(l, 9)
local removed = table.remove(l, 2)
table.sort(l)
log(table.concat(l, ','), tostring(removed), tostring(table.remove(l)), tostring(#l))
log(select(2, pcall(table.insert, l, 9, 1)))",
    )
    .unwrap();
    assert_eq!(logs[0], "1,2,4,9\t3\t9\t3");
    assert_has(&logs[1], "position out of bounds");
}

#[test]
fn ordinary_calls_are_untouched() {
    let logs = run(
        &host(),
        r#"log(("ab"):rep(3, "-"), string.format("%d-%s-%.2f", 7, "x", 1.5), (("a b c"):gsub(" ", "_")), table.concat({1, 2, 3}, ","))
local t = {5, 3, 9} table.sort(t) log(table.concat(t, " "))
log(string.match("key = value", "^(%w+)%s*=%s*(%w+)$"))"#,
    )
    .unwrap();
    // Luau's `string.rep` has no separator (it's Lua 5.1's), and ignores one.
    assert_eq!(
        logs,
        ["ababab\t7-x-1.50\ta_b_c\t1,2,3", "3 5 9", "key\tvalue"]
    );
}
