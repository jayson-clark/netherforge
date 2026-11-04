//! `nf.random`'s generators: xoshiro256** (what Lua 5.4's own `math.random`
//! is), seeded through splitmix64. In Lua 5.4 the prelude wrote it in Lua's
//! wrapping 64-bit integers, and Luau has none, so it's native here, word for
//! word the same: a seed gives the same numbers it gave on the Kotlin runtime
//! (`tests/random.rs` holds it to the prelude's own Lua, run on Lua 5.4).

/// One generator: four words of state.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Random {
    s: [u64; 4],
}

impl Random {
    /// A generator for `seed`, a whole number (negative seeds are their
    /// two's complement, as Lua 5.4's integers were).
    pub fn new(seed: i64) -> Random {
        let mut x = seed as u64;
        let mut s = [0; 4];
        for word in &mut s {
            x = x.wrapping_add(0x9E37_79B9_7F4A_7C15);
            let mut z = x;
            z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
            z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
            *word = z ^ (z >> 31);
        }
        Random { s }
    }

    /// The next 64 bits.
    pub fn next_word(&mut self) -> u64 {
        let s = &mut self.s;
        let result = s[1].wrapping_mul(5).rotate_left(7).wrapping_mul(9);
        let t = s[1] << 17;
        s[2] ^= s[0];
        s[3] ^= s[1];
        s[1] ^= s[2];
        s[0] ^= s[3];
        s[2] ^= t;
        s[3] = s[3].rotate_left(45);
        result
    }

    /// A whole number from 0 to `n`: the low bits that cover `n`, drawn again
    /// until they're within it.
    pub fn up_to(&mut self, n: u64) -> u64 {
        let mut mask = n;
        for shift in [1, 2, 4, 8, 16, 32] {
            mask |= mask >> shift;
        }
        loop {
            let r = self.next_word() & mask;
            if r <= n {
                return r;
            }
        }
    }

    /// A whole number from `min` to `max`, both included. `min <= max`.
    pub fn integer(&mut self, min: i64, max: i64) -> i64 {
        debug_assert!(min <= max);
        min.wrapping_add(self.up_to(max.wrapping_sub(min) as u64) as i64)
    }

    /// From 0 up to 1: the top 53 bits, which a double holds exactly.
    pub fn fraction(&mut self) -> f64 {
        (self.next_word() >> 11) as f64 * (1.0 / 9_007_199_254_740_992.0)
    }

    /// An index into a list of `len` (`len > 0`), from 0.
    pub fn index(&mut self, len: usize) -> usize {
        self.up_to(len as u64 - 1) as usize
    }

    /// Shuffles `items` as `Random:shuffle` does (Fisher-Yates from the end),
    /// so a seed shuffles a list the same way it did.
    pub fn shuffle<T>(&mut self, items: &mut [T]) {
        for i in (1..items.len()).rev() {
            let j = self.up_to(i as u64) as usize;
            items.swap(i, j);
        }
    }
}
