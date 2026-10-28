/// The app's commands get their permission from `permissions/generated/commands.toml`,
/// which `cargo run --example bindings` derives from the command list in
/// `src/commands/bindings.rs`; `capabilities/default.json` grants it.
///
/// On Windows (MSVC) every executable this crate links needs Common Controls v6
/// in its manifest: Tauri's dialogs and menus import `TaskDialogIndirect`, which
/// only that version has, and an executable without it doesn't even start
/// (`STATUS_ENTRYPOINT_NOT_FOUND`, and nothing printed). tauri-build embeds its
/// manifest in the app's binary only, so the test binaries and the examples
/// (`bindings`, which `pnpm generate` and `pnpm lint` run) would die that way.
/// Here the linker embeds the same manifest (`windows-app-manifest.xml`, Tauri's
/// own) into everything instead, the app included.
fn main() {
    let mut attributes = tauri_build::Attributes::new();
    let target = |key: &str| std::env::var(key).unwrap_or_default();
    if target("CARGO_CFG_TARGET_OS") == "windows" && target("CARGO_CFG_TARGET_ENV") == "msvc" {
        attributes = attributes
            .windows_attributes(tauri_build::WindowsAttributes::new_without_app_manifest());
        let manifest =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("windows-app-manifest.xml");
        println!("cargo:rerun-if-changed={}", manifest.display());
        println!("cargo:rustc-link-arg=/MANIFEST:EMBED");
        println!("cargo:rustc-link-arg=/MANIFESTINPUT:{}", manifest.display());
    }
    tauri_build::try_build(attributes).expect("tauri-build failed");
}
