//! Where the runtime finds a project's files (`ProjectFiles`): a project's packages on a dev server, a bundle's
//! checked against what it was built as and signed by, the packages a server provides, and the resource packs scripts
//! wrote laid over their package's.

use std::path::Path;

use netherforge_format::bundle::{BundleManifest, BundleSignature, BundledPackage};
use netherforge_format::canonical::{Entries, Key};
use netherforge_format::project::{self, Project, ProjectSource};
use netherforge_runtime::config::BundlesConfig;
use netherforge_runtime::project_files::{self, FilesConfig, ProjectFiles, hash_of};

fn write(root: &Path, files: &[(&str, &str)]) {
    for (path, text) in files {
        let file = root.join(path);
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, text).unwrap();
    }
}

fn manifest(namespace: &str, dependencies: &str) -> String {
    format!(
        r#"{{"formatVersion": 1, "name": "{namespace}", "namespace": "{namespace}", "version": "1.0.0", "minecraft": "26.3"{dependencies}}}"#
    )
}

fn load(files: &ProjectFiles) -> Project<'_> {
    project::load(
        files,
        project::Options {
            packages: files,
            ..Default::default()
        },
    )
}

fn refusals(project: &Project) -> Vec<String> {
    project
        .problems
        .iter()
        .filter(|p| project_files::refuses(p))
        .map(|p| format!("{} {}", p.code.clone().unwrap(), p.message))
        .collect()
}

fn files(configured: &Path) -> FilesConfig {
    FilesConfig {
        configured: configured.to_owned(),
        ..FilesConfig::default()
    }
}

#[test]
fn a_dev_server_opens_packages_where_the_manifest_points_and_a_production_server_refuses_them() {
    let dir = tempfile::tempdir().unwrap();
    write(
        dir.path(),
        &[
            (
                "game/netherforge.json",
                &manifest(
                    "game",
                    r#", "dependencies": {"library": {"path": "../library"}}"#,
                ),
            ),
            (
                "game/modules/main/init.lua",
                "local lib = require('library:api')",
            ),
            ("library/netherforge.json", &manifest("library", "")),
            ("library/modules/api/init.lua", "return {}"),
        ],
    );
    let dev = ProjectFiles::new(FilesConfig {
        resolves: true,
        ..files(&dir.path().join("game"))
    });
    let project = load(&dev);
    assert_eq!(refusals(&project), Vec::<String>::new());
    assert!(project.packages.contains_key("library"));
    assert_eq!(
        dev.read("library:modules/api/init.lua").as_deref(),
        Some("return {}")
    );
    assert_eq!(
        dev.project_path(&dir.path().join("library/modules/api/init.lua"))
            .as_deref(),
        Some("library:modules/api/init.lua")
    );
    assert_eq!(
        dev.project_path(&dir.path().join("game/modules/main/init.lua"))
            .as_deref(),
        Some("modules/main/init.lua")
    );

    let production = ProjectFiles::new(files(&dir.path().join("game")));
    let refused = refusals(&load(&production));
    assert_eq!(refused.len(), 1, "{refused:?}");
    assert!(refused[0].starts_with("runtime.unbundled"));
}

/// A bundle as `netherforge build` writes it: each package in a folder named by its namespace, hashed.
fn bundle(root: &Path, packages: &[(&str, &[(&str, &str)])], project: &str) -> BundleManifest {
    let mut listed = Vec::new();
    for (namespace, files) in packages {
        let folder = root.join(namespace);
        write(&folder, files);
        let source = netherforge_runtime::disk::DiskProject::new(folder);
        listed.push((
            Key((*namespace).into()),
            BundledPackage {
                version: "1.0.0".into(),
                hash: hash_of(&source),
            },
        ));
    }
    BundleManifest {
        format_version: 1,
        project: project.into(),
        packages: Entries(listed),
        signatures: None,
        resource_pack: None,
        provided: None,
    }
}

fn save(root: &Path, manifest: &BundleManifest) {
    std::fs::write(
        root.join(netherforge_format::bundle::FILE_NAME),
        manifest.write(),
    )
    .unwrap();
}

#[test]
fn a_bundle_runs_as_it_was_built_and_is_refused_once_a_file_changes() {
    let dir = tempfile::tempdir().unwrap();
    let game_manifest = manifest(
        "game",
        r#", "dependencies": {"library": {"path": "../library"}}"#,
    );
    let library_manifest = manifest("library", "");
    let manifest = bundle(
        dir.path(),
        &[
            (
                "game",
                &[
                    ("netherforge.json", &game_manifest),
                    ("modules/main/init.lua", "log('ran')"),
                ],
            ),
            (
                "library",
                &[
                    ("netherforge.json", &library_manifest),
                    ("modules/api/init.lua", "return {}"),
                ],
            ),
        ],
        "game",
    );
    save(dir.path(), &manifest);
    let source = ProjectFiles::new(files(dir.path()));
    let project = load(&source);
    assert_eq!(refusals(&project), Vec::<String>::new());
    assert_eq!(project.namespace(), "game");
    assert!(project.packages.contains_key("library"));
    assert_eq!(source.root_folder(), dir.path().join("game"));

    // A README isn't the package: it's left out of the hash.
    write(dir.path(), &[("library/README.md", "notes")]);
    assert_eq!(refusals(&load(&source)), Vec::<String>::new());

    write(
        dir.path(),
        &[("library/modules/api/init.lua", "return { changed = true }")],
    );
    let refused = refusals(&load(&source));
    assert_eq!(refused.len(), 1, "{refused:?}");
    assert!(refused[0].starts_with("runtime.bundle-hash"));
    assert!(refused[0].contains("\"library\""));
}

/// Ed25519 keys as `netherforge keygen` makes them.
struct Signer(ring::signature::Ed25519KeyPair);

impl Signer {
    fn new() -> Signer {
        let rng = ring::rand::SystemRandom::new();
        let pkcs8 = ring::signature::Ed25519KeyPair::generate_pkcs8(&rng).unwrap();
        Signer(ring::signature::Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap())
    }

    fn key(&self) -> String {
        use base64::Engine as _;
        use ring::signature::KeyPair as _;
        format!(
            "ed25519:{}",
            base64::engine::general_purpose::STANDARD.encode(self.0.public_key().as_ref())
        )
    }

    fn sign(&self, manifest: &BundleManifest) -> BundleSignature {
        use base64::Engine as _;
        BundleSignature {
            key: self.key(),
            signature: base64::engine::general_purpose::STANDARD
                .encode(self.0.sign(manifest.signed_text().as_bytes()).as_ref()),
        }
    }
}

#[test]
fn a_signature_verifies_over_the_manifest_without_its_signatures_so_a_countersignature_adds_one() {
    // Signed by the CLI (node's Ed25519 over format's canonical text).
    let cli = "{\n  \"formatVersion\": 1,\n  \"project\": \"arena\",\n  \"packages\": {\n    \"arena\": {\n      \"version\": \"1.0.0\",\n      \"hash\": \"sha256:ab\"\n    },\n    \"lib\": {\n      \"version\": \"2.0.0\",\n      \"hash\": \"sha256:cd\"\n    }\n  },\n  \"resourcePack\": {\n    \"sha1\": \"ff\",\n    \"format\": [46, 0]\n  },\n  \"provided\": [\"alpha\", \"zeta\"]\n}\n";
    assert!(project_files::verifies(
        "ed25519:6gmbgCqn/w8ou17KHhJTQXzZClrKS2VqLJu1NpURcH4=",
        "gX4hs+r369ykOftRzMlLOYIPTtSAv5yGBVjwR8/FGDcmMnqNWkPqH1V9SX2ntIJrgRlT+IyWlGZ8SQlP8fYGAA==",
        cli
    ));
    assert!(!project_files::verifies("rsa:xyz", "abc", "text"));

    let manifest = BundleManifest::parse(cli).unwrap();
    let (author, network) = (Signer::new(), Signer::new());
    let mut signed = manifest.clone();
    signed.signatures = Some(vec![author.sign(&manifest)]);
    let mut countersigned = signed.clone();
    countersigned
        .signatures
        .as_mut()
        .unwrap()
        .push(network.sign(&signed));
    let trusting = |keys: Vec<String>| BundlesConfig {
        require_signature: true,
        trusted_keys: keys,
    };
    assert_eq!(
        project_files::signature_problem(&countersigned, &trusting(vec![network.key()])),
        None
    );
    assert!(
        project_files::signature_problem(&countersigned, &trusting(vec![Signer::new().key()]))
            .unwrap()
            .contains("aren't among them")
    );
    assert!(
        project_files::signature_problem(&manifest, &trusting(vec![network.key()]))
            .unwrap()
            .contains("isn't signed")
    );
    assert_eq!(
        project_files::signature_problem(&manifest, &BundlesConfig::default()),
        None
    );
    let mut changed = countersigned.clone();
    changed.project = "other".into();
    assert!(
        project_files::signature_problem(&changed, &BundlesConfig::default())
            .unwrap()
            .contains("doesn't match")
    );
}

#[test]
fn a_server_that_requires_a_trusted_signature_refuses_a_bundle_without_one() {
    let dir = tempfile::tempdir().unwrap();
    let game = manifest("game", "");
    let mut manifest = bundle(
        dir.path(),
        &[("game", &[("netherforge.json", &game)])],
        "game",
    );
    let key = Signer::new();
    manifest.signatures = Some(vec![key.sign(&manifest)]);
    save(dir.path(), &manifest);
    for (trusted, runs) in [(key.key(), true), (Signer::new().key(), false)] {
        let source = ProjectFiles::new(FilesConfig {
            bundles: BundlesConfig {
                require_signature: true,
                trusted_keys: vec![trusted],
            },
            ..files(dir.path())
        });
        let refused = refusals(&load(&source));
        assert_eq!(refused.is_empty(), runs, "{refused:?}");
        if !runs {
            assert!(refused[0].starts_with("runtime.bundle-signature"));
        }
    }
}

#[test]
fn a_package_the_server_provides_is_what_a_dependency_opens_and_a_bundle_carrying_its_own_is_refused()
 {
    let dir = tempfile::tempdir().unwrap();
    write(
        dir.path(),
        &[
            (
                "game/netherforge.json",
                &manifest(
                    "game",
                    r#", "dependencies": {"economy": {"path": "../economy"}}"#,
                ),
            ),
            (
                "game/modules/main/init.lua",
                "local economy = require('economy:api')",
            ),
            ("economy/netherforge.json", &manifest("economy", "")),
            (
                "economy/modules/api/init.lua",
                "return 'the project's copy'",
            ),
            ("server/economy/netherforge.json", &manifest("economy", "")),
            (
                "server/economy/modules/api/init.lua",
                "return 'the server's'",
            ),
        ],
    );
    let provided = vec![dir.path().join("server/economy")];
    // A production server running a project whose only dependency is provided needs no bundle.
    let source = ProjectFiles::new(FilesConfig {
        provided: provided.clone(),
        ..files(&dir.path().join("game"))
    });
    let project = load(&source);
    assert_eq!(refusals(&project), Vec::<String>::new());
    assert_eq!(
        source.read("economy:modules/api/init.lua").as_deref(),
        Some("return 'the server's'")
    );
    let in_use: Vec<String> = source
        .provided_in_use()
        .iter()
        .map(|p| p.namespace.clone())
        .collect();
    assert_eq!(in_use, ["economy"]);

    let bundled = dir.path().join("bundle");
    let game = manifest(
        "game",
        r#", "dependencies": {"economy": {"path": "../economy"}}"#,
    );
    let economy = manifest("economy", "");
    let built = bundle(
        &bundled,
        &[
            ("game", &[("netherforge.json", &game)]),
            ("economy", &[("netherforge.json", &economy)]),
        ],
        "game",
    );
    save(&bundled, &built);
    let source = ProjectFiles::new(FilesConfig {
        provided,
        ..files(&bundled)
    });
    let refused = refusals(&load(&source));
    assert_eq!(refused.len(), 1, "{refused:?}");
    assert!(refused[0].starts_with("runtime.provided-package"));
}

#[test]
fn a_bundle_by_url_is_named_by_its_hash_and_unpacked_whole() {
    assert!(
        netherforge_runtime::bundle_fetch::expected_sha256("https://example.com/a.nfbundle")
            .is_err()
    );
    let hash = "ab".repeat(32);
    assert_eq!(
        netherforge_runtime::bundle_fetch::expected_sha256(&format!(
            "https://example.com/a.nfbundle#sha256={}",
            hash.to_uppercase()
        )),
        Ok(hash)
    );
    let dir = tempfile::tempdir().unwrap();
    // A zip as the CLI writes one: deflated entries.
    let mut zip = Vec::new();
    let mut central = Vec::new();
    for (name, data) in [
        ("game/netherforge.json", "{}"),
        ("netherforge-bundle.json", "{}"),
    ] {
        use std::io::Write as _;
        let mut deflate =
            flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::best());
        deflate.write_all(data.as_bytes()).unwrap();
        let packed = deflate.finish().unwrap();
        let offset = zip.len() as u32;
        let crc = crc32fast::hash(data.as_bytes());
        let mut header = vec![0u8; 30];
        header[0..4].copy_from_slice(&0x0403_4b50u32.to_le_bytes());
        header[8..10].copy_from_slice(&8u16.to_le_bytes());
        header[14..18].copy_from_slice(&crc.to_le_bytes());
        header[18..22].copy_from_slice(&(packed.len() as u32).to_le_bytes());
        header[22..26].copy_from_slice(&(data.len() as u32).to_le_bytes());
        header[26..28].copy_from_slice(&(name.len() as u16).to_le_bytes());
        zip.extend(header);
        zip.extend(name.as_bytes());
        zip.extend(&packed);
        let mut entry = vec![0u8; 46];
        entry[0..4].copy_from_slice(&0x0201_4b50u32.to_le_bytes());
        entry[10..12].copy_from_slice(&8u16.to_le_bytes());
        entry[16..20].copy_from_slice(&crc.to_le_bytes());
        entry[20..24].copy_from_slice(&(packed.len() as u32).to_le_bytes());
        entry[24..28].copy_from_slice(&(data.len() as u32).to_le_bytes());
        entry[28..30].copy_from_slice(&(name.len() as u16).to_le_bytes());
        entry[42..46].copy_from_slice(&offset.to_le_bytes());
        central.extend(entry);
        central.extend(name.as_bytes());
    }
    let start = zip.len() as u32;
    let size = central.len() as u32;
    zip.extend(&central);
    let mut end = vec![0u8; 22];
    end[0..4].copy_from_slice(&0x0605_4b50u32.to_le_bytes());
    end[8..10].copy_from_slice(&2u16.to_le_bytes());
    end[10..12].copy_from_slice(&2u16.to_le_bytes());
    end[12..16].copy_from_slice(&size.to_le_bytes());
    end[16..20].copy_from_slice(&start.to_le_bytes());
    zip.extend(end);
    let folder = dir.path().join("unpacked");
    netherforge_runtime::bundle_fetch::unpack(&zip, &folder).unwrap();
    assert_eq!(
        std::fs::read_to_string(folder.join("game/netherforge.json")).unwrap(),
        "{}"
    );
    assert!(folder.join(".netherforge-fetched").is_file());
}

#[test]
fn a_pack_scripts_wrote_is_read_as_its_packages_and_one_with_errors_falls_back_to_its_last_good_copy()
 {
    let dir = tempfile::tempdir().unwrap();
    write(
        dir.path(),
        &[
            ("game/netherforge.json", &manifest("game", "")),
            (
                "data/game/packs/featured/pack.json",
                r#"{"name": "Featured"}"#,
            ),
        ],
    );
    let source = ProjectFiles::new(FilesConfig {
        written_packs: Some(dir.path().join("data")),
        kept_packs: Some(dir.path().join("kept")),
        ..files(&dir.path().join("game"))
    });
    let project = load(&source);
    let errors: Vec<_> = project
        .problems
        .iter()
        .filter(|p| p.severity == netherforge_format::Severity::Error)
        .collect();
    assert!(errors.is_empty(), "{errors:?}");
    assert!(source.reads_written_packs());
    assert!(
        project
            .present("resource_pack")
            .iter()
            .any(|(name, _)| name == "featured")
    );
    assert!(
        dir.path().join("kept/game/featured/pack.json").is_file(),
        "kept once it loaded clean"
    );
    assert_eq!(
        source.written_pack_files(),
        ["resource_packs/featured/pack.json"]
    );

    write(
        dir.path(),
        &[("data/game/packs/featured/pack.json", "{ not json")],
    );
    let project = load(&source);
    let errors: Vec<_> = project
        .problems
        .iter()
        .filter(|p| p.severity == netherforge_format::Severity::Error)
        .collect();
    assert!(
        errors.is_empty(),
        "the broken pack's problems are warnings: {errors:?}"
    );
    assert!(
        project
            .problems
            .iter()
            .any(|p| p.code.as_deref() == Some("runtime.written-pack")
                && p.message.contains("its last good copy is built instead")),
        "{:?}",
        project.problems
    );
    assert!(
        project
            .present("resource_pack")
            .iter()
            .any(|(name, _)| name == "featured")
    );
}
