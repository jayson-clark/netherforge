//! Writes the UI's command bindings and the commands permission (see
//! `commands::bindings`). `pnpm generate` runs it; `--out <dir>` writes under
//! another root than the repo's, which is how the generated-file check
//! compares without touching the working tree.

use std::path::PathBuf;

fn main() {
    let mut args = std::env::args().skip(1);
    let root = match (args.next().as_deref(), args.next()) {
        (Some("--out"), Some(dir)) => PathBuf::from(dir),
        (None, _) => PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../.."),
        _ => {
            eprintln!("usage: cargo run --example bindings [-- --out <dir>]");
            std::process::exit(2);
        }
    };
    if let Err(error) = netherforge_lib::commands::bindings::export(&root) {
        eprintln!("couldn't write the bindings: {error}");
        std::process::exit(1);
    }
}
