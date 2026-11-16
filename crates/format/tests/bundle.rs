use netherforge_format::bundle::BundleManifest;

/// Written by the CLI's format (`nf.canonicalize('bundle', ...)` of a manifest whose `provided` was `zeta, alpha`).
const CLI: &str = "{\n  \"formatVersion\": 1,\n  \"project\": \"arena\",\n  \"packages\": {\n    \"arena\": {\n      \"version\": \"1.0.0\",\n      \"hash\": \"sha256:ab\"\n    },\n    \"lib\": {\n      \"version\": \"2.0.0\",\n      \"hash\": \"sha256:cd\"\n    }\n  },\n  \"resourcePack\": {\n    \"sha1\": \"ff\",\n    \"format\": [46, 0]\n  },\n  \"provided\": [\"alpha\", \"zeta\"]\n}\n";

#[test]
fn a_manifest_is_written_as_the_cli_writes_it() {
    let mut manifest = BundleManifest::parse(CLI).unwrap();
    assert_eq!(manifest.write(), CLI);
    manifest.provided = Some(vec!["zeta".into(), "alpha".into(), "zeta".into()]);
    assert_eq!(
        manifest.signed_text(),
        CLI,
        "canonical: sorted, no repeats, no signatures"
    );
    assert!(BundleManifest::parse("{\"project\": 1}").is_err());
}
