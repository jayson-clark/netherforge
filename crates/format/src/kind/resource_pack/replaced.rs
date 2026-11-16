//! How a pack's picture fits as the game's texture it replaces (format's
//! `ReplacedTexture`).

/// How a replacement fits.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Fit {
    Ok,
    /// Not exactly the size of a texture that has to be.
    Size,
    /// Not the game's shape.
    Shape,
    /// Not one square frame of a texture the game animates, with no `.mcmeta` of its own.
    Still,
}

/// The game's textures read at fixed pixel places, by the folders they're in.
const EXACT: [&str; 2] = ["gui/", "colormap/"];
/// Textures under these are scaled like any other, though in a folder `EXACT` names.
const SCALED: [&str; 1] = ["gui/sprites/"];

/// Whether `name`'s replacement must be exactly the game's size.
pub fn exact(name: &str) -> bool {
    EXACT.iter().any(|f| name.starts_with(f)) && !SCALED.iter().any(|f| name.starts_with(f))
}

/// How a picture of `image` pixels fits as `name`, the game's being `game`
/// pixels; `animated` when the picture has a `.mcmeta` of its own.
pub fn fit(name: &str, game: [u32; 2], image: [u32; 2], animated: bool) -> Fit {
    let ([gw, gh], [w, h]) = (game, image);
    if exact(name) {
        return if image == game { Fit::Ok } else { Fit::Size };
    }
    if animated {
        return Fit::Ok;
    }
    // A strip of square frames, the game's animation: without a .mcmeta of its own, the replacement is one frame.
    if gw > 0 && gh > gw && gh % gw == 0 {
        return if w == h { Fit::Ok } else { Fit::Still };
    }
    if u64::from(w) * u64::from(gh) == u64::from(h) * u64::from(gw) {
        Fit::Ok
    } else {
        Fit::Shape
    }
}
