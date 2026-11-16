//! Writes a PNG, for the pictures a build makes itself (the engine's pixel
//! glyphs; format's `PngWriter`): uncompressed (stored deflate blocks), which
//! costs nothing for the few small pictures it's for and needs no zlib, byte
//! for byte what format's Kotlin wrote.

const SIGNATURE: [u8; 8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
const STORED_BLOCK: usize = 65535;

/// `argb` (`width` by `height`, row by row) as an 8-bit RGBA PNG.
pub fn write(width: u32, height: u32, argb: &[u32]) -> Vec<u8> {
    let mut raw = Vec::new();
    for y in 0..height {
        // Filter type 0 (none) before each row.
        raw.push(0);
        for x in 0..width {
            let p = argb[(y * width + x) as usize];
            raw.extend([(p >> 16) as u8, (p >> 8) as u8, p as u8, (p >> 24) as u8]);
        }
    }
    let mut header = Vec::new();
    header.extend(width.to_be_bytes());
    header.extend(height.to_be_bytes());
    // 8 bits a channel, colour type 6 (RGBA), deflate, adaptive filtering, no interlace.
    header.extend([8, 6, 0, 0, 0]);
    let mut out = SIGNATURE.to_vec();
    chunk(&mut out, b"IHDR", &header);
    chunk(&mut out, b"IDAT", &zlib_stored(&raw));
    chunk(&mut out, b"IEND", &[]);
    out
}

/// A chunk: its length, its type and `data`, and the CRC of the type and data.
fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], data: &[u8]) {
    out.extend((data.len() as u32).to_be_bytes());
    let mut typed = kind.to_vec();
    typed.extend(data);
    out.extend(&typed);
    out.extend(crc32(&typed).to_be_bytes());
}

/// `data` as a zlib stream of stored (uncompressed) deflate blocks.
fn zlib_stored(data: &[u8]) -> Vec<u8> {
    let mut out = vec![0x78, 0x01];
    let mut at = 0;
    loop {
        let length = STORED_BLOCK.min(data.len() - at);
        let last = at + length >= data.len();
        out.push(u8::from(last));
        out.extend((length as u16).to_le_bytes());
        out.extend((!(length as u16)).to_le_bytes());
        out.extend(&data[at..at + length]);
        at += length;
        if last {
            break;
        }
    }
    out.extend(adler32(data).to_be_bytes());
    out
}

fn crc32(data: &[u8]) -> u32 {
    let table: Vec<u32> = (0..256u32)
        .map(|n| {
            (0..8).fold(n, |c, _| {
                if c & 1 != 0 {
                    0xEDB8_8320 ^ (c >> 1)
                } else {
                    c >> 1
                }
            })
        })
        .collect();
    !data.iter().fold(u32::MAX, |c, b| {
        table[((c ^ u32::from(*b)) & 0xFF) as usize] ^ (c >> 8)
    })
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for byte in data {
        a = (a + u32::from(*byte)) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

/// Width and height from a PNG's header, if `bytes` is a PNG.
pub fn size(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 24 || bytes[..8] != SIGNATURE {
        return None;
    }
    let int =
        |at: usize| u32::from_be_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]);
    Some((int(16), int(20)))
}
