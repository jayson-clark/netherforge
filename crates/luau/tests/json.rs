//! The codec, held to the prelude's `json.lua` (`CodecTest`) and to the
//! numbers of the plugin-runtime skill.

use netherforge_luau::json::{self, Encoded, NoTags};
use netherforge_luau::mlua::Value;
use netherforge_luau::{Host, Limits};

fn host() -> Host {
    Host::new(Limits::default()).unwrap()
}

/// What encoding the value `expression` evaluates to says.
fn encode(host: &Host, expression: &str) -> Encoded {
    let scope = host.open_scope("t", "t").unwrap();
    let value: Value = host
        .run(scope, 10_000_000, "t.lua", &format!("return {expression}"))
        .unwrap();
    json::encode(host, &value, "data", "saved", &NoTags).unwrap()
}

fn text(expression: &str) -> String {
    let encoded = encode(&host(), expression);
    assert_eq!(encoded.problems, Vec::<String>::new(), "{expression}");
    encoded.text.expect("written")
}

#[test]
fn numbers_are_written_by_the_one_rule() {
    assert_eq!(json::number(2.0).unwrap(), "2");
    assert_eq!(json::number(-0.0).unwrap(), "0");
    assert_eq!(json::number(1.5).unwrap(), "1.5");
    assert_eq!(json::number(0.1).unwrap(), "0.1");
    assert_eq!(
        json::number(9_007_199_254_740_991.0).unwrap(),
        "9007199254740991"
    );
    // Past the safe range, a whole number is a double like any other.
    assert_eq!(
        json::number(9_007_199_254_740_992.0).unwrap(),
        "9007199254740992"
    );
    assert_eq!(json::number(1e21).unwrap(), "1e21");
    assert_eq!(json::number(1.5e-7).unwrap(), "1.5e-7");
    assert_eq!(json::number(123456.789).unwrap(), "123456.789");
    assert_eq!(json::number(f64::NAN), None);
    assert_eq!(json::number(f64::INFINITY), None);
    for n in [0.1 + 0.2, 1.0 / 3.0, 1e300, 5e-324, -2.5e-9] {
        let written = json::number(n).unwrap();
        assert_eq!(written.parse::<f64>().unwrap(), n, "{written}");
    }
}

#[test]
fn plain_values() {
    assert_eq!(text("'hi'"), r#""hi""#);
    assert_eq!(text("true"), "true");
    assert_eq!(text("2.0"), "2");
    assert_eq!(text("{1, 2, 3}"), "[1,2,3]");
    assert_eq!(
        text("{b = 1, a = {true}, ['two words'] = 'x'}"),
        r#"{"a":[true],"b":1,"two words":"x"}"#
    );
    assert_eq!(text("{}"), "{}");
    assert_eq!(text("'a\"b\\\\c\\n\\1'"), r#""a\"b\\c\n\u0001""#);
    assert_eq!(text("'✓'"), r#""✓""#);
}

#[test]
fn a_list_may_have_a_few_holes() {
    assert_eq!(text("{1, nil, 3}"), "[1,null,3]");
    // Too sparse to be a list, and number keys aren't an object's.
    let encoded = encode(&host(), "{[1] = 1, [10] = 2}");
    assert_eq!(encoded.text.as_deref(), Some("{}"));
    let mut problems = encoded.problems.clone();
    problems.sort();
    assert_eq!(
        problems,
        [
            "data[10]: a number key is saved only in a list numbered from 1",
            "data[1]: a number key is saved only in a list numbered from 1",
        ]
    );
}

#[test]
fn vectors_are_tagged() {
    assert_eq!(text("vector.create(1, 2.5, -3)"), r#"{"$vec3":[1,2.5,-3]}"#);
    assert_eq!(
        text("{at = vector.create(0, 0, 0)}"),
        r#"{"at":{"$vec3":[0,0,0]}}"#
    );
}

#[test]
fn a_scripts_key_that_reads_as_a_tag_gets_another_dollar() {
    assert_eq!(text("{['$vec3'] = {1, 2, 3}}"), r#"{"$$vec3":[1,2,3]}"#);
    assert_eq!(text("{['$$vec3'] = 1}"), r#"{"$$$vec3":1}"#);
    // Any other `$` key is written as it is.
    assert_eq!(text("{['$ref'] = 'x'}"), r#"{"$ref":"x"}"#);
}

#[test]
fn what_cant_be_written_is_a_problem_at_its_path() {
    let encoded = encode(
        &host(),
        "{ok = 1, f = function() end, list = {1, coroutine.create(function() end)}, deep = {n = 0/0}}",
    );
    assert_eq!(
        encoded.text.as_deref(),
        Some(r#"{"deep":{},"list":[1,null],"ok":1}"#)
    );
    let mut problems = encoded.problems.clone();
    problems.sort();
    assert_eq!(
        problems,
        [
            "data.deep.n: nan can't be saved",
            "data.f: a function can't be saved",
            "data.list[2]: a coroutine can't be saved",
        ]
    );
    let looped = encode(&host(), "(function() local t = {} t.me = t return t end)()");
    assert_eq!(
        looped.problems,
        ["data.me: a table inside itself can't be saved"]
    );
    let top = encode(&host(), "function() end");
    assert_eq!(top.text, None);
}

#[test]
fn tables_nested_too_deep_arent_written() {
    let encoded = encode(
        &host(),
        "(function() local t = {} local root = t for i = 1, 70 do t.next = {} t = t.next end return root end)()",
    );
    assert_eq!(encoded.problems.len(), 1);
    assert!(
        encoded.problems[0].ends_with("tables nested this deep can't be saved"),
        "{:?}",
        encoded.problems
    );
}

#[test]
fn reading_back() {
    let host = host();
    let lua = host.lua();
    let read = |text: &str| json::decode(lua, text, &NoTags).unwrap();
    let t = read(r#"{"a":[1,null,3],"v":{"$vec3":[1,2,3]},"$$vec3":5,"$ref":"x"}"#);
    let Value::Table(t) = t else { panic!() };
    let a: netherforge_luau::mlua::Table = t.get("a").unwrap();
    assert_eq!(a.raw_get::<f64>(1).unwrap(), 1.0);
    assert!(a.raw_get::<Value>(2).unwrap().is_nil());
    assert_eq!(a.raw_get::<f64>(3).unwrap(), 3.0);
    assert!(
        matches!(t.get::<Value>("v").unwrap(), Value::Vector(v) if v.x() == 1.0 && v.z() == 3.0)
    );
    assert_eq!(t.get::<f64>("$vec3").unwrap(), 5.0);
    assert_eq!(t.get::<String>("$ref").unwrap(), "x");
}

#[test]
fn an_integer_past_the_safe_range_reads_as_its_digits() {
    let host = host();
    let lua = host.lua();
    let v = json::decode(
        lua,
        r#"{"id": 1234567890123456789, "small": 42, "float": 1e300}"#,
        &NoTags,
    )
    .unwrap();
    let Value::Table(t) = v else { panic!() };
    assert_eq!(t.get::<String>("id").unwrap(), "1234567890123456789");
    assert!(matches!(t.get::<Value>("id").unwrap(), Value::String(_)));
    assert_eq!(t.get::<f64>("small").unwrap(), 42.0);
    assert_eq!(t.get::<f64>("float").unwrap(), 1e300);
}

#[test]
fn round_trips() {
    let host = host();
    for expression in [
        "{1, 2, {a = 'b', c = {true, false}}, vector.create(1, 2, 3)}",
        "{['$vec3'] = 'mine', ['$$location'] = 2, name = 'x', n = 0.1}",
    ] {
        let first = text(expression);
        let back = json::decode(host.lua(), &first, &NoTags).unwrap();
        let again = json::encode(&host, &back, "data", "saved", &NoTags).unwrap();
        assert_eq!(again.text.unwrap(), first);
    }
}

#[test]
fn walking_a_huge_table_is_charged_to_the_script() {
    let host = host();
    let scope = host.open_scope("t", "t").unwrap();
    let encode = host
        .lua()
        .create_function({
            let host = host.downgrade();
            move |_, value: Value| {
                let host = host.upgrade().unwrap();
                Ok(json::encode(&host, &value, "data", "saved", &NoTags).is_err())
            }
        })
        .unwrap();
    host.env(scope).unwrap().raw_set("encode", encode).unwrap();
    // The table is made cheaply (one call); walking it isn't free.
    let stopped: bool = host
        .run(
            scope,
            50_000,
            "t.lua",
            "local t = table.create(1000000, 1) return encode(t)",
        )
        .unwrap_or(true);
    assert!(stopped);
}
