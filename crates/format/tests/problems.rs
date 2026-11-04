use netherforge_format::{ProblemCode, Severity, codes};

#[test]
fn the_catalogue_is_formats() {
    assert!(codes::ALL.len() > 300, "{}", codes::ALL.len());
    assert_eq!(codes::PARSE.code, "parse");
    assert_eq!(codes::PARSE.severity, Severity::Error);
    assert_eq!(codes::PROJECT_NAME.severity, Severity::Warning);
    assert_eq!(codes::PROJECT_NAMESPACE.page, "format/project.md");
    assert_eq!(
        ProblemCode::of("centity.animation-node"),
        Some(codes::CENTITY_ANIMATION_NODE)
    );
    let names: std::collections::HashSet<&str> = codes::ALL.iter().map(|c| c.code).collect();
    assert_eq!(names.len(), codes::ALL.len(), "each code once");
}

#[test]
fn a_problem_serializes_as_formats() {
    let p = codes::PROJECT_NAMESPACE.at("netherforge.json", "bad", Some("$.namespace".into()));
    assert_eq!(
        p.to_json().to_string(),
        r#"{"severity":"error","file":"netherforge.json","message":"bad","path":"$.namespace","code":"project.namespace"}"#
    );
}
