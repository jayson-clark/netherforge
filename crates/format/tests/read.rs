//! The reader says what format's says, where it says it: the cases of
//! format's CanonicalJsonTest and the goldens' parse problems.

use std::collections::BTreeMap;

use netherforge_format::read::parse;
use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
#[allow(dead_code)]
struct Centity {
    nodes: BTreeMap<String, Node>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
#[allow(dead_code)]
struct Node {
    parent: Option<String>,
    display: Option<Display>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
#[allow(dead_code)]
struct Display {
    block: String,
    billboard: Option<Billboard>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "lowercase")]
#[allow(dead_code)]
enum Billboard {
    Fixed,
    Vertical,
    Horizontal,
    Center,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
#[allow(dead_code)]
struct Feature {
    feature: String,
}

fn problem(text: &str) -> serde_json::Value {
    parse::<Centity>(text, "centities/a/centity.json")
        .unwrap_err()
        .to_json()
}

#[test]
fn an_unknown_key_is_named_at_its_object_with_its_line_and_column() {
    // The parse-error golden, word for word.
    let text = "{\n  \"nodes\": {\n    \"root\": { \"colour\": \"red\" }\n  }\n}\n";
    assert_eq!(
        problem(text).to_string(),
        r#"{"severity":"error","file":"centities/a/centity.json","message":"Unknown key \"colour\"","path":"$.nodes.root","line":3,"column":16,"code":"parse"}"#
    );
}

#[test]
fn a_missing_key_is_named() {
    let p = problem(r#"{"nodes":{"a":{"display":{}}}}"#);
    assert_eq!(p["message"], "Missing required key \"block\"");
}

#[test]
fn a_value_that_isnt_one_of_an_enums_says_which_are() {
    // The enum-value golden.
    let p = problem(r#"{"nodes":{"label":{"display":{"block":"stone","billboard":"sideways"}}}}"#);
    assert_eq!(
        p["message"],
        "\"sideways\" isn't one of \"fixed\", \"vertical\", \"horizontal\", \"center\""
    );
    assert_eq!(p["path"], "$.nodes.label.display.billboard");
    assert!(p.get("line").is_none());
}

#[test]
fn a_trailing_comma_is_where_kotlinx_says() {
    // The datapack-semantics golden.
    let p = parse::<Feature>("{ \"feature\": \"case:rock\", }\n", "broken.json")
        .unwrap_err()
        .to_json();
    assert_eq!(
        p.to_string(),
        r#"{"severity":"error","file":"broken.json","message":"Trailing comma before the end of JSON object","path":"$","line":1,"column":26,"code":"parse"}"#
    );
}

#[test]
fn a_well_formed_file_reads() {
    let c: Centity = parse(r#"{"nodes":{"a":{},"b":{"parent":"a"}}}"#, "x").unwrap();
    assert_eq!(c.nodes.len(), 2);
}
