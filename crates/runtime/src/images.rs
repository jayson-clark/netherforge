//! The pictures scripts hold (`nf.images` and `Image`, Kotlin's `Images`, `NfImagesImpl` and `ImageImpl`), by
//! the number their handle is keyed by: made from colours or bytes, decoded from an image file (PNG, JPEG, GIF,
//! BMP), resized, cropped, encoded as PNG, and shown on an image display (`node:set_image`), which keeps the
//! pixels it shows itself. What scripts hold at once is bounded by `MAX_HELD` pixels.
//!
//! A picture Lua has let go of is let go of here when a new one needs the room: a hold that would pass `MAX_HELD`
//! first runs a full collection and drops every picture whose handle is gone, then tries again. Every picture goes
//! when the session ends. `nf.images.download` fetches through `nf.http`'s guarded client.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::Arc;

use base64::Engine;
use netherforge_api::api::{ImageApi, NfImagesApi};
use netherforge_api::{ApiError, ApiResult, Caller, Pending, handles, shapes, unions};
use netherforge_format::image::Pixels;

/// The most pixels an image has either way.
pub const MAX_SIDE: i64 = 2048;
/// The most pixels the images scripts hold have in all.
pub const MAX_HELD: u64 = 32_000_000;

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// Whether `width` by `height` is an image's size.
pub fn fits(width: i64, height: i64) -> bool {
    (1..=MAX_SIDE).contains(&width) && (1..=MAX_SIDE).contains(&height)
}

/// An error at the script's line unless `width` by `height` is an image's size.
fn size(width: i64, height: i64) -> ApiResult<()> {
    if fits(width, height) {
        return Ok(());
    }
    Err(err(format!(
        "an image is 1 to {MAX_SIDE} pixels each way, not {width} by {height}"
    )))
}

/// `#RRGGBB` (opaque) or `#AARRGGBB` as a pixel.
pub fn color(text: &str) -> Option<u32> {
    let hex = text.strip_prefix('#')?;
    if !matches!(hex.len(), 6 | 8) || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let value = u32::from_str_radix(hex, 16).ok()?;
    Some(if hex.len() == 6 {
        0xFF00_0000 | value
    } else {
        value
    })
}

/// A pixel as `#RRGGBB`, or `#AARRGGBB` when it isn't opaque.
pub fn color_name(pixel: u32) -> String {
    if pixel >> 24 == 0xFF {
        format!("#{:06X}", pixel & 0xFF_FFFF)
    } else {
        format!("#{pixel:08X}")
    }
}

/// An image file's bytes as pixels, or none when it isn't one or is bigger than `MAX_SIDE` either way (checked
/// from its header, before its pixels are decoded).
pub fn decode(bytes: &[u8]) -> Option<Pixels> {
    let reader = image::ImageReader::new(std::io::Cursor::new(bytes))
        .with_guessed_format()
        .ok()?;
    let (w, h) = reader.into_dimensions().ok()?;
    if !fits(i64::from(w), i64::from(h)) {
        return None;
    }
    let decoded = image::load_from_memory(bytes).ok()?.into_rgba8();
    let argb = decoded
        .pixels()
        .map(|p| {
            (u32::from(p[3]) << 24)
                | (u32::from(p[0]) << 16)
                | (u32::from(p[1]) << 8)
                | u32::from(p[2])
        })
        .collect();
    Pixels::new(w, h, argb).ok()
}

/// `pixels` as a PNG file.
pub fn encode(pixels: &Pixels) -> Vec<u8> {
    let rgba: Vec<u8> = pixels
        .argb()
        .iter()
        .flat_map(|p| [(p >> 16) as u8, (p >> 8) as u8, *p as u8, (p >> 24) as u8])
        .collect();
    let mut out = Vec::new();
    let encoder = image::codecs::png::PngEncoder::new(&mut out);
    use image::ImageEncoder;
    if encoder
        .write_image(
            &rgba,
            pixels.width(),
            pixels.height(),
            image::ExtendedColorType::Rgba8,
        )
        .is_err()
    {
        return netherforge_format::png::write(pixels.width(), pixels.height(), pixels.argb());
    }
    out
}

/// The pictures scripts hold, by number.
#[derive(Default)]
pub struct Images {
    held: RefCell<HashMap<i64, Arc<Pixels>>>,
    next: Cell<i64>,
    pixels: Cell<u64>,
    /// The room's Luau state, whose handles say which pictures scripts still hold.
    lua: RefCell<Option<mlua::WeakLua>>,
}

impl Images {
    /// The picture behind handle number `number`.
    pub fn get(&self, number: i64) -> Option<Arc<Pixels>> {
        self.held.borrow().get(&number).cloned()
    }

    /// The room whose scripts hold the pictures: a new room (a full reload) says so again.
    pub fn attach(&self, lua: &mlua::Lua) {
        *self.lua.borrow_mut() = Some(lua.weak());
    }

    /// How many pixels the pictures held have in all.
    pub fn pixels_held(&self) -> u64 {
        self.pixels.get()
    }

    /// Lets go of every picture whose handle Lua no longer holds, after a full collection. Called only as a picture
    /// is about to be held, never between holding one and handing its handle over, so a picture being handed out is
    /// never taken.
    fn sweep(&self) {
        let Some(lua) = self.lua.borrow().as_ref().and_then(|l| l.try_upgrade()) else {
            return;
        };
        // Twice: a userdata with a finaliser is freed a cycle after it's found unreachable.
        let _ = lua.gc_collect();
        let _ = lua.gc_collect();
        let mut held = self.held.borrow_mut();
        held.retain(|number, _| {
            netherforge_api::handle::held(
                &lua,
                "Image",
                &[netherforge_api::handle::KeyPart::Int(*number)],
            )
        });
        let total = held
            .values()
            .map(|p| u64::from(p.width()) * u64::from(p.height()))
            .sum();
        self.pixels.set(total);
    }

    /// Keeps `pixels` and gives the handle it's keyed by; an error when scripts hold too much already.
    pub fn hold(&self, pixels: Arc<Pixels>) -> ApiResult<handles::Image> {
        let size = u64::from(pixels.width()) * u64::from(pixels.height());
        if self.pixels.get() + size > MAX_HELD {
            self.sweep();
        }
        if self.pixels.get() + size > MAX_HELD {
            return Err(err(format!(
                "scripts hold {} million pixels of images already: let go of images you no longer need",
                MAX_HELD / 1_000_000
            )));
        }
        self.pixels.set(self.pixels.get() + size);
        let number = self.next.get() + 1;
        self.next.set(number);
        self.held.borrow_mut().insert(number, pixels);
        Ok(handles::Image { number })
    }

    /// Every picture let go of: the session is ending.
    pub fn clear(&self) {
        self.held.borrow_mut().clear();
        self.pixels.set(0);
    }
}

/// `nf.images` and `Image` for one room.
pub struct ImagesApi {
    pub images: Rc<Images>,
    /// `nf.http`'s requests, which `download` makes.
    pub http: Option<crate::http::api::HttpApi>,
}

fn byte(bytes: &[u8], at: usize) -> u32 {
    u32::from(bytes[at])
}

impl ImagesApi {
    fn of(&self, this: &handles::Image) -> ApiResult<Arc<Pixels>> {
        self.images
            .get(this.number)
            .ok_or_else(|| err("this image is gone (the session that made it ended)"))
    }

    fn hold(&self, pixels: Pixels) -> ApiResult<handles::Image> {
        self.images.hold(Arc::new(pixels))
    }
}

impl NfImagesApi for ImagesApi {
    fn create(
        &self,
        _: &Caller,
        width: i64,
        height: i64,
        pixels: unions::StringListOrString,
    ) -> ApiResult<handles::Image> {
        size(width, height)?;
        let count = (width * height) as usize;
        let argb: Vec<u32> = match pixels {
            unions::StringListOrString::StringList(list) => {
                if list.len() != count {
                    return Err(err(format!(
                        "bad argument 'pixels' ({width} by {height} is {count} pixels, got {})",
                        list.len()
                    )));
                }
                list.iter()
                    .enumerate()
                    .map(|(i, c)| {
                        color(c).ok_or_else(|| {
                            err(format!(
                                "bad argument 'pixels[{}]' (#RRGGBB or #AARRGGBB, got \"{c}\")",
                                i + 1
                            ))
                        })
                    })
                    .collect::<ApiResult<_>>()?
            }
            unions::StringListOrString::String(text) => {
                let bytes = base64::engine::general_purpose::STANDARD
                    .decode(text.trim())
                    .map_err(|_| {
                        err("bad argument 'pixels' (base64 bytes, or a list of colours)")
                    })?;
                if bytes.len() == count * 3 {
                    (0..count)
                        .map(|i| {
                            0xFF00_0000
                                | (byte(&bytes, i * 3) << 16)
                                | (byte(&bytes, i * 3 + 1) << 8)
                                | byte(&bytes, i * 3 + 2)
                        })
                        .collect()
                } else if bytes.len() == count * 4 {
                    (0..count)
                        .map(|i| {
                            (byte(&bytes, i * 4 + 3) << 24)
                                | (byte(&bytes, i * 4) << 16)
                                | (byte(&bytes, i * 4 + 1) << 8)
                                | byte(&bytes, i * 4 + 2)
                        })
                        .collect()
                } else {
                    return Err(err(format!(
                        "bad argument 'pixels' ({width} by {height} is {} bytes as RGB or {} as RGBA, got {})",
                        count * 3,
                        count * 4,
                        bytes.len()
                    )));
                }
            }
        };
        self.hold(Pixels::new(width as u32, height as u32, argb).map_err(err)?)
    }

    fn fill(
        &self,
        _: &Caller,
        width: i64,
        height: i64,
        color_text: String,
    ) -> ApiResult<handles::Image> {
        size(width, height)?;
        let pixel = color(&color_text).ok_or_else(|| {
            err(format!(
                "bad argument 'color' (#RRGGBB or #AARRGGBB, got \"{color_text}\")"
            ))
        })?;
        self.hold(
            Pixels::new(
                width as u32,
                height as u32,
                vec![pixel; (width * height) as usize],
            )
            .map_err(err)?,
        )
    }

    fn decode(&self, _: &Caller, data: String) -> ApiResult<Option<handles::Image>> {
        let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(data.trim()) else {
            return Ok(None);
        };
        decode(&bytes).map(|p| self.hold(p)).transpose()
    }

    fn download(
        &self,
        caller: &Caller,
        options: shapes::HttpRequestOptions,
    ) -> ApiResult<Pending<handles::Image>> {
        let Some(http) = &self.http else {
            return Err(crate::scripts::no_session("nf.images.download"));
        };
        // Decoded on the worker, after the request; held on the main thread as the value is delivered.
        let reply = http.fetch(caller, options, "nf.images.download", |reply| {
            if !(200..=299).contains(&reply.status) {
                return Err(format!("the server answered {}", reply.status));
            }
            decode(&reply.bytes).ok_or_else(|| {
                format!(
                    "what came back isn't an image (PNG, JPEG, GIF or BMP) of at most {MAX_SIDE} pixels either way"
                )
            })
        })?;
        let images = self.images.clone();
        Ok(Box::pin(async move {
            let pixels = reply.await.map_err(ApiError)?;
            images.hold(Arc::new(pixels))
        }))
    }
}

impl ImageApi for ImagesApi {
    fn width(&self, this: &handles::Image) -> ApiResult<i64> {
        Ok(i64::from(self.of(this)?.width()))
    }
    fn height(&self, this: &handles::Image) -> ApiResult<i64> {
        Ok(i64::from(self.of(this)?.height()))
    }
    fn pixel(&self, this: &handles::Image, x: i64, y: i64) -> ApiResult<Option<String>> {
        let image = self.of(this)?;
        if !(0..i64::from(image.width())).contains(&x)
            || !(0..i64::from(image.height())).contains(&y)
        {
            return Ok(None);
        }
        Ok(Some(color_name(image.get(x as u32, y as u32))))
    }
    fn resized(&self, this: &handles::Image, width: i64, height: i64) -> ApiResult<handles::Image> {
        size(width, height)?;
        let resized = self.of(this)?.resized(width as u32, height as u32);
        self.hold(resized)
    }
    fn cropped(
        &self,
        this: &handles::Image,
        x: i64,
        y: i64,
        width: i64,
        height: i64,
    ) -> ApiResult<Option<handles::Image>> {
        let image = self.of(this)?;
        let (iw, ih) = (i64::from(image.width()), i64::from(image.height()));
        if width < 1 || height < 1 || x < 0 || y < 0 || x + width > iw || y + height > ih {
            return Ok(None);
        }
        let argb = (0..width * height)
            .map(|i| image.get((x + i % width) as u32, (y + i / width) as u32))
            .collect();
        Ok(Some(self.hold(
            Pixels::new(width as u32, height as u32, argb).map_err(err)?,
        )?))
    }
    fn encode(&self, this: &handles::Image) -> ApiResult<String> {
        Ok(base64::engine::general_purpose::STANDARD.encode(encode(&*self.of(this)?)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn colours_and_files_round_trip() {
        assert_eq!(color("#ff0000"), Some(0xFFFF_0000));
        assert_eq!(color("#80ff0000"), Some(0x80FF_0000));
        assert_eq!(color("red"), None);
        assert_eq!(color_name(0xFF00_FF00), "#00FF00");
        assert_eq!(color_name(0x8000_FF00), "#8000FF00");
        let pixels = Pixels::new(2, 1, vec![0xFFFF_0000, 0x8000_00FF]).unwrap();
        assert_eq!(decode(&encode(&pixels)), Some(pixels));
    }
}
