use netherforge_format::kinds::{self, Contents, Layout, PathRole, classify};

type Seen = (
    Option<&'static str>,
    Option<String>,
    PathRole,
    Option<&'static str>,
    Option<String>,
);

fn at(path: &str) -> Option<Seen> {
    classify(path).map(|c| (c.kind, c.id, c.role, c.document, c.rest))
}

fn s(text: &str) -> Option<String> {
    Some(text.to_owned())
}

#[test]
fn paths_are_classified_by_their_kinds_layout() {
    // KindsTest's cases.
    use PathRole::*;
    assert_eq!(
        at("centities/tower/centity.json"),
        Some((
            Some("centity"),
            s("tower"),
            Main,
            Some("centity"),
            s("centity.json")
        ))
    );
    assert_eq!(
        at("centities/tower/lib/util.lua"),
        Some((Some("centity"), s("tower"), File, None, s("lib/util.lua")))
    );
    assert_eq!(
        at("centities/tower/extra/centity.json"),
        Some((
            Some("centity"),
            s("tower"),
            File,
            None,
            s("extra/centity.json")
        ))
    );
    assert_eq!(
        at("menus/shop"),
        Some((Some("menu"), s("shop"), Folder, None, s("")))
    );
    assert_eq!(
        at("recipes/ruby.json"),
        Some((Some("recipe"), s("ruby"), Main, Some("recipe"), None))
    );
    assert_eq!(
        at("structures/house.nbt"),
        Some((Some("structure"), s("house"), Main, None, s("house.nbt")))
    );
    assert_eq!(
        at("structures/house.json"),
        Some((
            Some("structure"),
            s("house"),
            File,
            Some("structure_generation"),
            s("house.json")
        ))
    );
    assert_eq!(
        at("maps/arena/level.dat"),
        Some((Some("map"), s("arena"), Main, None, s("level.dat")))
    );
    assert_eq!(
        at("modules/greeter/init.lua"),
        Some((Some("module"), s("greeter"), File, None, s("init.lua")))
    );
    assert_eq!(
        at("resource_packs/ui/textures/gui/shop.png"),
        Some((
            Some("resource_pack"),
            s("ui"),
            File,
            None,
            s("textures/gui/shop.png")
        ))
    );
    assert_eq!(
        at("netherforge.json"),
        Some((None, None, Project, Some("netherforge"), None))
    );
    assert_eq!(
        at("fonts/default.json"),
        Some((None, None, Project, Some("default_font"), None))
    );
    assert_eq!(
        at("\\menus\\shop\\menu.json"),
        Some((Some("menu"), s("shop"), Main, Some("menu"), s("menu.json")))
    );
    assert_eq!(
        classify("centities/Bad-Name/centity.json").unwrap().id,
        s("Bad-Name")
    );
    for nobody in [
        "README.md",
        "centities",
        "centities/.gitkeep",
        "recipes/.json",
        "recipes/swords/ruby.json",
        "recipes/notes.txt",
        "structures/a/b.nbt",
        "fonts/other.json",
        ".netherforge/schema/centity.schema.json",
    ] {
        assert_eq!(classify(nobody), None, "{nobody}");
    }
}

#[test]
fn paths_in_groups_are_classified_with_their_group() {
    // GroupsTest's cases.
    let at = |path: &str| classify(path).map(|c| (c.kind, c.id, c.role, c.rest, c.group));
    use PathRole::*;
    assert_eq!(
        at("items/(weapons)/ruby_wand/item.json"),
        Some((
            Some("item"),
            s("ruby_wand"),
            Main,
            s("item.json"),
            s("(weapons)")
        ))
    );
    assert_eq!(
        at("items/(weapons)/(wands)/ruby_wand/lib/(not_a_group)/util.lua"),
        Some((
            Some("item"),
            s("ruby_wand"),
            File,
            s("lib/(not_a_group)/util.lua"),
            s("(weapons)/(wands)")
        ))
    );
    assert_eq!(
        at("items/(weapons)/ruby_wand"),
        Some((Some("item"), s("ruby_wand"), Folder, s(""), s("(weapons)")))
    );
    assert_eq!(
        at("items/(weapons)/(wands)"),
        Some((Some("item"), None, Group, None, s("(weapons)/(wands)")))
    );
    assert_eq!(
        at("recipes/(smelting)/ruby.json"),
        Some((Some("recipe"), s("ruby"), Main, None, s("(smelting)")))
    );
    assert_eq!(
        at("structures/(town)/house.json"),
        Some((
            Some("structure"),
            s("house"),
            File,
            s("house.json"),
            s("(town)")
        ))
    );
    assert_eq!(
        at("modules/(economy)/shop/init.lua"),
        Some((
            Some("module"),
            s("shop"),
            File,
            s("init.lua"),
            s("(economy)")
        ))
    );
    assert_eq!(
        classify("acme:items/(gems)/coin/item.json").unwrap().group,
        s("(gems)")
    );
    assert_eq!(
        classify("acme:items/(gems)/coin/item.json")
            .unwrap()
            .package,
        s("acme")
    );
    assert_eq!(classify("items/(weapons)/.DS_Store"), None);
    assert_eq!(classify("recipes/(smelting)/old/ruby.json"), None);
    assert_eq!(
        classify("migrations/postgres/001_init.sql").unwrap().rest,
        s("postgres/001_init.sql")
    );
}

/// The registry is format's: every kind as the `KINDS` table format generates
/// for the editor says, when format's build is there (`pnpm test` makes it first).
#[test]
fn the_registry_is_formats() {
    let constants = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../packages/format/build/generated-contract/constants.ts");
    let Ok(text) = std::fs::read_to_string(&constants) else {
        eprintln!("skipped: no format build at {}", constants.display());
        return;
    };
    let table = text
        .split("export const KINDS = {")
        .nth(1)
        .unwrap()
        .split("} as const")
        .next()
        .unwrap();
    let rows: Vec<&str> = table
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .collect();
    assert_eq!(rows.len(), kinds::ALL.len());
    for (row, kind) in rows.iter().zip(kinds::ALL) {
        let (layout, main, extension, companion) = match kind.layout {
            Layout::Folder { main } => ("folder", main, None, None),
            Layout::SingleFile {
                extension,
                companion,
                ..
            } => ("file", None, Some(extension), companion),
        };
        let q = |v: Option<&str>| v.map_or("null".to_owned(), |v| format!("\"{v}\""));
        let contents = match kind.contents {
            Contents::Json => "json",
            Contents::Lua => "lua",
            Contents::Sql => "sql",
            Contents::Binary => "binary",
        };
        let expected = format!(
            "{}: {{ folder: \"{}\", layout: \"{layout}\", main: {}, extension: {}, companion: {}, contents: \"{contents}\", scripted: {}, template: {}, groupable: {}, exportable: {} }},",
            kind.id,
            kind.folder,
            q(main),
            q(extension),
            q(companion),
            kind.scripted,
            kind.template,
            kind.groupable,
            kind.exportable
        );
        assert_eq!(*row, expected);
    }
}
