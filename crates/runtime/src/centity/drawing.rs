//! What a node's display is drawn as on the server's entities (Kotlin's `DisplayDrawing`): block, item,
//! model and plain text displays as they are; a rect, an image and anchored text as a text display (format's
//! `display` glyphs) with a fit, the matrix in the display's own space that puts its text exactly where the
//! node's box is.
//!
//! Not yet: an image's pixels (scripts set them, with the image area): until then an image is drawn blank,
//! and its text whole rather than in the runs a platform sends it in.

use netherforge_format::kind::centity::{
    Anchor, Billboard, DisplayDef, PIXELS_PER_BLOCK, TextAlignment,
};
use netherforge_format::kind::resource_pack::display;
use netherforge_format::math::Matrix4;

/// A display as drawn: what the entity shows, and the matrix it's fitted by (none: none).
#[derive(Clone, Debug, PartialEq)]
pub struct Drawn {
    pub shown: DisplayDef,
    pub fit: Option<Matrix4>,
}

/// What an anchored text's line width is past its widest line, so a line the game measures a pixel wider
/// still doesn't wrap.
const UNWRAPPED_SLACK: i32 = 64;

const TRANSPARENT: &str = "#00000000";

/// `#RRGGBB` (opaque) or `#AARRGGBB` as `#AARRGGBB`.
pub fn argb(color: &str) -> String {
    if color.len() == 7 {
        format!("#FF{}", &color[1..])
    } else {
        color.to_owned()
    }
}

fn text(
    text: String,
    alignment: Option<TextAlignment>,
    background: &str,
    line_width: Option<i32>,
) -> DisplayDef {
    DisplayDef::Text {
        text,
        billboard: Some(Billboard::Fixed),
        alignment,
        background: Some(background.to_owned()),
        line_width,
        see_through: None,
        shadow: Some(false),
        anchor: None,
    }
}

/// `display` as drawn. `text_size` measures text as the game does (its widest line in pixels and its number
/// of lines, or none when it can't); an anchored text it can't measure is drawn unanchored, and `warn` says so.
pub fn drawn(
    display: &DisplayDef,
    text_size: &dyn Fn(&str) -> Option<(i32, i32)>,
    warn: &mut dyn FnMut(String),
) -> Drawn {
    match display {
        DisplayDef::Rect {
            color,
            size,
            anchor,
            ..
        } => {
            let size = size.clone().unwrap_or_else(|| vec![1.0, 1.0]);
            Drawn {
                shown: text(display::rect_text(), None, &argb(color), None),
                fit: Some(display::fit(
                    display::RECT_BOX,
                    anchor.unwrap_or(Anchor::Center),
                    size.first().copied(),
                    size.get(1).copied(),
                )),
            }
        }
        DisplayDef::Image {
            width,
            height,
            size,
            anchor,
        } => {
            let (w, h) = (*width, *height);
            let size = size.clone().unwrap_or_else(|| {
                vec![
                    f64::from(w) / PIXELS_PER_BLOCK,
                    f64::from(h) / PIXELS_PER_BLOCK,
                ]
            });
            let shown = text(
                display::image_text(w.max(0) as u32, h.max(0) as u32, None),
                Some(TextAlignment::Left),
                TRANSPARENT,
                Some(display::image_line_width(w)),
            );
            Drawn {
                shown,
                fit: Some(display::fit(
                    display::image_box(w, h),
                    anchor.unwrap_or(Anchor::Center),
                    size.first().copied(),
                    size.get(1).copied(),
                )),
            }
        }
        DisplayDef::Text {
            text: words,
            anchor: Some(anchor),
            ..
        } => match text_size(words) {
            Some((width, lines)) => {
                let mut shown = display.clone();
                if let DisplayDef::Text { line_width, .. } = &mut shown {
                    // Lines break only where the text breaks them: the box was measured that way.
                    *line_width = Some(width.max(1) + UNWRAPPED_SLACK);
                }
                Drawn {
                    shown,
                    fit: Some(display::fit(
                        display::text_box(width, lines),
                        *anchor,
                        None,
                        None,
                    )),
                }
            }
            None => {
                warn(format!(
                    "a text display anchored {} can't be placed: its text can't be measured (is fonts/default.json missing, or does it use a character the font doesn't cover?), so it's drawn unanchored",
                    netherforge_format::canonical::name_of(anchor)
                ));
                Drawn {
                    shown: display.clone(),
                    fit: None,
                }
            }
        },
        other => Drawn {
            shown: other.clone(),
            fit: None,
        },
    }
}
