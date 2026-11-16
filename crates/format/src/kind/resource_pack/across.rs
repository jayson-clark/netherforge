//! A pack against the rest of the project (format's `ResourcePackKind.crossCheck`):
//! its pictures' pixels and sizes, what it hides and replaces of the game's
//! (which `client/vanilla.json` names), and what it shares with other packs.

use std::collections::BTreeSet;

use super::fonts::{self, SHEET};
use super::replaced::{self, Fit};
use super::{
    GLYPH_HEIGHT, PUA_COUNT, ResourcePackFile, SKIN_HEIGHT, TEXTURES, is_texture_path, sounds,
    under,
};
use crate::game::Registry;
use crate::json::child_path;
use crate::problem::{ProblemSink, codes};
use crate::project::Across;
use crate::vanilla;

/// A pack's texture by project path: `resource_packs/<id>/textures/<texture>`.
pub fn texture_path(pack: &str, texture: &str) -> String {
    let (package, id) = crate::kinds::split_package(pack);
    let local = format!("resource_packs/{id}/{TEXTURES}/{texture}");
    match package {
        Some(p) => crate::kinds::package_path(p, &local),
        None => local,
    }
}

impl ResourcePackFile {
    pub(super) fn across(&self, cx: &Across, sink: &mut ProblemSink) {
        let id = cx.id();
        let textures = under(cx.files(), TEXTURES);
        self.pixels(cx, sink, &textures);
        self.sizes(cx, sink);
        self.hidden(cx, sink);
        self.replaced(cx, sink, &textures);
        self.sound_events(cx, sink);
        // Glyphs share one font across every pack, numbered in id order, so the limit is the project's.
        let glyphs: usize = cx
            .models::<ResourcePackFile>()
            .filter(|(other, _)| *other <= id)
            .map(|(_, p)| p.glyphs.len())
            .sum();
        if !self.glyphs.is_empty() && glyphs > PUA_COUNT {
            sink.report(
                codes::RESOURCE_PACK_TOO_MANY_GLYPHS,
                format!(
                    "The project's resource packs hold more than {PUA_COUNT} glyphs between them"
                ),
                Some("$.glyphs".into()),
            );
        }
    }

    /// Only pixels say how wide a skin is, and without that its title prefix can't move back.
    fn pixels(&self, cx: &Across, sink: &mut ProblemSink, textures: &BTreeSet<&str>) {
        if !cx.reads_images() {
            return;
        }
        for (key, skin) in self.skins.iter() {
            if textures.contains(skin.texture.0.as_str())
                && cx.image(&texture_path(cx.id(), &skin.texture)).is_none()
            {
                sink.report(
                    codes::RESOURCE_PACK_IMAGE,
                    format!("textures/{} can't be read as a PNG, so the title's words will start after this skin instead of over it", skin.texture),
                    Some(format!("{}.texture", child_path("$.skins", key))),
                );
            }
        }
    }

    /// A font draws a picture too big for its sheet blank; one too wide is cut into equal pieces, if it can be.
    fn sizes(&self, cx: &Across, sink: &mut ProblemSink) {
        if !cx.reads_sizes() {
            return;
        }
        let mut fits = |what: String, texture: &str, drawn: Option<u32>, at: String| {
            let Some([width, height]) = cx.size(&texture_path(cx.id(), texture)) else {
                return;
            };
            let size = format!("{what} is {width}×{height} pixels");
            if height > SHEET {
                sink.report(
                    codes::RESOURCE_PACK_PICTURE_SIZE,
                    format!("{size}; Minecraft draws a font picture over {SHEET} pixels tall blank, so use one at most {SHEET} tall"),
                    Some(at),
                );
            } else if height > 0 && fonts::pieces(width, height, drawn.unwrap_or(height)).is_none()
            {
                sink.report(
                    codes::RESOURCE_PACK_PICTURE_SIZE,
                    format!(
                        "{size}; a picture over {SHEET} pixels wide is drawn in equal pieces of {SHEET} or less, and {width} doesn't cut into any. Pad it with transparent columns to {} pixels wide",
                        fonts::even_width(width)
                    ),
                    Some(at),
                );
            }
        };
        let height = |h: Option<i32>, default: i32| u32::try_from(h.unwrap_or(default)).ok();
        for (key, skin) in self.skins.iter() {
            let at = format!("{}.texture", child_path("$.skins", key));
            fits(
                format!("Skin {key}"),
                &skin.texture,
                height(skin.height, SKIN_HEIGHT),
                at,
            );
        }
        for (key, glyph) in self.glyphs.iter() {
            let at = format!("{}.texture", child_path("$.glyphs", key));
            fits(
                format!("Glyph {key}"),
                &glyph.texture,
                height(glyph.height, GLYPH_HEIGHT),
                at,
            );
        }
        let Some(hud) = &self.hud else { return };
        for (key, bar) in hud.bars.iter() {
            let at = child_path("$.hud.bars", key);
            fits(
                format!("Bar {key}'s full picture"),
                &bar.full,
                None,
                format!("{at}.full"),
            );
            fits(
                format!("Bar {key}'s empty picture"),
                &bar.empty,
                None,
                format!("{at}.empty"),
            );
            if let Some(half) = &bar.half {
                fits(
                    format!("Bar {key}'s half picture"),
                    half,
                    None,
                    format!("{at}.half"),
                );
            }
        }
        for (key, row) in hud.icons.iter() {
            for (icon, texture) in row.icons.iter() {
                let at = child_path(&format!("{}.icons", child_path("$.hud.icons", key)), icon);
                fits(format!("Icon {key}/{icon}"), texture, None, at);
            }
        }
    }

    /// Hiding the game's HUD elements blanks their sprites, which only the client lists.
    fn hidden(&self, cx: &Across, sink: &mut ProblemSink) {
        if self
            .hud
            .as_ref()
            .and_then(|h| h.hide.as_ref())
            .is_none_or(|h| h.is_empty())
        {
            return;
        }
        let vanilla = cx.vanilla();
        let stale =
            cx.minecraft().is_some() && vanilla.map(|v| v.minecraft.as_str()) != cx.minecraft();
        if vanilla.is_none_or(|v| v.sprites.is_empty()) || stale {
            sink.report(
                codes::RESOURCE_PACK_HUD_HIDE,
                format!(
                    "Hiding the game's HUD takes the names of its sprites, which the editor writes into {} from your Minecraft: open the project in the editor with {} imported (import it again if it was imported before the editor listed sprites). Until then nothing is hidden",
                    vanilla::FILE,
                    vanilla::import_hint(cx.minecraft())
                ),
                Some("$.hud.hide".into()),
            );
        }
    }

    /// Redrawing the game's sprites, textures and models takes their names, and for a texture its size.
    fn replaced(&self, cx: &Across, sink: &mut ProblemSink, textures: &BTreeSet<&str>) {
        let empty = crate::canonical::Map::default();
        let sprites = self.hud.as_ref().map_or(&empty, |h| &h.replace);
        if sprites.is_empty() && self.textures.is_empty() && self.models.is_empty() {
            return;
        }
        let named = |name: &str| is_texture_path(&format!("{name}{}", super::TEXTURE_EXTENSION));
        let vanilla = cx
            .vanilla()
            .filter(|v| cx.minecraft().is_none_or(|m| v.minecraft == m));
        // The file holds only the textures the project asked about: a name in neither list is one not looked up yet.
        let unlooked = vanilla.is_some_and(|v| {
            self.textures.iter().any(|(n, _)| {
                named(n) && v.textures.get_str(n).is_none() && !v.unknown_textures.contains(&n.0)
            }) || self.models.iter().any(|(n, _)| {
                named(n) && !v.models.contains(&n.0) && !v.unknown_models.contains(&n.0)
            })
        });
        let Some(vanilla) =
            vanilla.filter(|v| !unlooked && !(!sprites.is_empty() && v.sprites.is_empty()))
        else {
            let at = if !sprites.is_empty() {
                "$.hud.replace"
            } else if !self.textures.is_empty() {
                "$.textures"
            } else {
                "$.models"
            };
            sink.report(
                codes::RESOURCE_PACK_VANILLA_MISSING,
                format!(
                    "Replacing the game's sprites, textures and models takes their names and sizes, which the editor writes into {} from your Minecraft: open the project in the editor with {} imported (import it again if it was imported before the editor listed them). Until then nothing is replaced",
                    vanilla::FILE,
                    vanilla::import_hint(cx.minecraft())
                ),
                Some(at.into()),
            );
            return;
        };
        let others: Vec<&ResourcePackFile> = cx
            .models::<ResourcePackFile>()
            .filter(|(other, _)| *other < cx.id())
            .map(|(_, p)| p)
            .collect();
        let mut check = |map: &str,
                         names: Vec<&str>,
                         known: &dyn Fn(&str) -> bool,
                         taken: &dyn Fn(&ResourcePackFile, &str) -> bool| {
            let mut names = names;
            names.sort_unstable();
            for name in names {
                let at = Some(child_path(map, name));
                // A name that isn't a path is already reported where it's written.
                if !named(name) {
                } else if !known(name) {
                    sink.report(
                        codes::RESOURCE_PACK_REPLACE_NAME,
                        format!(
                            "The game has no \"{name}\" ({} lists what Minecraft {} has)",
                            vanilla::FILE,
                            vanilla.minecraft
                        ),
                        at,
                    );
                } else if others.iter().any(|o| taken(o, name)) {
                    sink.report(
                        codes::RESOURCE_PACK_REPLACE_NAME,
                        format!("Another resource pack replaces \"{name}\" too; only one can"),
                        at,
                    );
                }
            }
        };
        check(
            "$.hud.replace",
            sprites.iter().map(|(k, _)| k.0.as_str()).collect(),
            &|n| vanilla.sprites.iter().any(|s| s == n),
            &|o, n| {
                o.hud
                    .as_ref()
                    .is_some_and(|h| h.replace.get_str(n).is_some())
            },
        );
        check(
            "$.textures",
            self.textures.iter().map(|(k, _)| k.0.as_str()).collect(),
            &|n| vanilla.textures.get_str(n).is_some(),
            &|o, n| o.textures.get_str(n).is_some(),
        );
        check(
            "$.models",
            self.models.iter().map(|(k, _)| k.0.as_str()).collect(),
            &|n| vanilla.models.iter().any(|m| m == n),
            &|o, n| o.models.get_str(n).is_some(),
        );
        if !cx.reads_sizes() {
            return;
        }
        let mut replaced: Vec<_> = self.textures.iter().collect();
        replaced.sort_by(|a, b| a.0.0.cmp(&b.0.0));
        for (name, picture) in replaced {
            let Some(&[gw, gh]) = vanilla.textures.get_str(name).map(Vec::as_slice) else {
                continue;
            };
            let (Ok(gw), Ok(gh)) = (u32::try_from(gw), u32::try_from(gh)) else {
                continue;
            };
            let size = [gw, gh];
            let at = Some(child_path("$.textures", name));
            let Some(image) = cx.size(&texture_path(cx.id(), picture)) else {
                if textures.contains(picture.0.as_str()) {
                    sink.report(
                        codes::RESOURCE_PACK_IMAGE,
                        format!("textures/{picture} can't be read as a PNG, so its size can't be checked"),
                        at,
                    );
                }
                continue;
            };
            let animated = textures.contains(format!("{picture}.mcmeta").as_str());
            let [w, h] = image;
            match replaced::fit(name, size, image, animated) {
                Fit::Ok => {}
                Fit::Size => sink.report(
                    codes::RESOURCE_PACK_REPLACE_SIZE,
                    format!("textures/{picture} is {w}x{h} pixels, but the game's {name} is {gw}x{gh}: the screen cuts its parts out at fixed places, so a replacement must be the same size"),
                    at,
                ),
                Fit::Shape => sink.report(
                    codes::RESOURCE_PACK_REPLACE_SHAPE,
                    format!("textures/{picture} is {w}x{h} pixels, but the game's {name} is {gw}x{gh}: a replacement may be bigger or smaller, but the same shape, or it's drawn stretched"),
                    at,
                ),
                Fit::Still => sink.report(
                    codes::RESOURCE_PACK_REPLACE_SHAPE,
                    format!("textures/{picture} is {w}x{h} pixels, but the game animates {name} (its frames stacked, {gw}x{gh}): a still replacement is one square frame, and an animated one needs textures/{picture}.mcmeta beside it, or it's drawn as one tall picture"),
                    at,
                ),
            }
        }
    }

    /// The game's sound events a pack replaces: ones the game has, each by one pack.
    fn sound_events(&self, cx: &Across, sink: &mut ProblemSink) {
        let mut replaced: Vec<&str> = self
            .sounds
            .iter()
            .map(|(k, _)| k.0.as_str())
            .filter(|k| sounds::is_game_event(k))
            .collect();
        replaced.sort_unstable();
        for event in replaced {
            let at = Some(child_path("$.sounds", event));
            if let Some(game) = cx.game()
                && game.lacks(Registry::SoundEvent, event)
            {
                sink.report(
                    codes::RESOURCE_PACK_SOUND_EVENT,
                    format!(
                        "Minecraft {} has no sound event {event}",
                        game.minecraft_version()
                    ),
                    at,
                );
            } else if cx
                .models::<ResourcePackFile>()
                .any(|(other, p)| other < cx.id() && p.sounds.get_str(event).is_some())
            {
                sink.report(
                    codes::RESOURCE_PACK_SOUND_EVENT,
                    format!("Another resource pack replaces {event} too; only one can"),
                    at,
                );
            }
        }
    }
}
