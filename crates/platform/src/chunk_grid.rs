//! `ChunkGrid` (Kotlin's `platform/ChunkGrid.kt`), written by hand: its state is its own, worked on by its methods.

use std::collections::HashMap;

const SECTION: usize = 16 * 16 * 16;

/// A small number (1 to 65535) for some of one chunk's positions, kept a section at a time: a chunk full of them
/// costs a few kilobytes a section, not an object a block. x and z are the chunk's own (0 to 15), y the world's.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ChunkGrid {
    sections: HashMap<i32, Box<[u16]>>,
    size: usize,
}

fn index(x: i32, y: i32, z: i32) -> usize {
    (((y & 15) << 8) | ((z & 15) << 4) | (x & 15)) as usize
}

impl ChunkGrid {
    pub fn new() -> ChunkGrid {
        ChunkGrid::default()
    }

    /// How many positions have a number.
    pub fn size(&self) -> usize {
        self.size
    }

    pub fn is_empty(&self) -> bool {
        self.size == 0
    }

    /// The number at a position, 0 where there's none.
    pub fn get(&self, x: i32, y: i32, z: i32) -> u16 {
        self.sections
            .get(&(y >> 4))
            .map_or(0, |s| s[index(x, y, z)])
    }

    /// Sets the number at a position (0 clears it), and returns what was there.
    pub fn set(&mut self, x: i32, y: i32, z: i32, value: u16) -> u16 {
        let section = match self.sections.get_mut(&(y >> 4)) {
            Some(section) => section,
            None if value == 0 => return 0,
            None => self
                .sections
                .entry(y >> 4)
                .or_insert_with(|| vec![0; SECTION].into_boxed_slice()),
        };
        let i = index(x, y, z);
        let before = section[i];
        section[i] = value;
        if before == 0 && value != 0 {
            self.size += 1;
        }
        if before != 0 && value == 0 {
            self.size -= 1;
        }
        before
    }

    /// Each position with a number, and its number.
    pub fn for_each(&self, mut each: impl FnMut(i32, i32, i32, u16)) {
        for (sy, section) in &self.sections {
            for (i, value) in section.iter().enumerate() {
                if *value != 0 {
                    let i = i as i32;
                    each(i & 15, (sy << 4) | (i >> 8), (i >> 4) & 15, *value);
                }
            }
        }
    }
}
