//! Kotlin's `Random(seed)`, bit for bit: the XorWow generator format's
//! particle sampler and terrain generator draw from (the same on the JVM and in
//! JS), so a seed gives the same points and the same world wherever it runs.
//! Only what format draws is here: `next_int`, `next_bits`, `next_int_below`,
//! `next_int_in`, `next_double`, `next_boolean`.

/// Marsaglia's xorwow with Kotlin's seeding (`kotlin.random.XorWowRandom`).
#[derive(Clone, Debug)]
pub struct Random {
    x: i32,
    y: i32,
    z: i32,
    w: i32,
    v: i32,
    addend: i32,
}

impl Random {
    /// `Random(seed)`: the low and high halves of `seed` seed it.
    pub fn new(seed: i64) -> Random {
        Random::of_ints(seed as i32, (seed >> 32) as i32)
    }

    /// `Random(seed)` for an `Int` seed (which Kotlin widens to a `Long`).
    pub fn of_int(seed: i32) -> Random {
        Random::new(i64::from(seed))
    }

    fn of_ints(seed1: i32, seed2: i32) -> Random {
        let mut random = Random {
            x: seed1,
            y: seed2,
            z: 0,
            w: 0,
            v: !seed1,
            addend: (seed1 << 10) ^ ((seed2 as u32) >> 4) as i32,
        };
        assert!(
            (random.x | random.y | random.z | random.w | random.v) != 0,
            "the generator's state can't be all zeros"
        );
        for _ in 0..64 {
            random.next_int();
        }
        random
    }

    pub fn next_int(&mut self) -> i32 {
        let mut t = self.x;
        t ^= ((t as u32) >> 2) as i32;
        self.x = self.y;
        self.y = self.z;
        self.z = self.w;
        let v0 = self.v;
        self.w = v0;
        t = (t ^ (t << 1)) ^ v0 ^ (v0 << 4);
        self.v = t;
        self.addend = self.addend.wrapping_add(362_437);
        t.wrapping_add(self.addend)
    }

    /// The top `count` bits of the next int (0 for none).
    pub fn next_bits(&mut self, count: u32) -> i32 {
        let next = self.next_int();
        if count == 0 {
            return 0;
        }
        ((next as u32) >> (32 - count)) as i32
    }

    /// From 0 (inclusive) to `until` (exclusive, more than 0).
    pub fn next_int_below(&mut self, until: i32) -> i32 {
        self.next_int_in(0, until)
    }

    /// From `from` (inclusive) to `until` (exclusive, more than `from`).
    pub fn next_int_in(&mut self, from: i32, until: i32) -> i32 {
        assert!(until > from, "an empty range: {from} until {until}");
        let n = until.wrapping_sub(from);
        if n > 0 || n == i32::MIN {
            let picked = if n & n.wrapping_neg() == n {
                self.next_bits(31 - n.leading_zeros())
            } else {
                loop {
                    let bits = ((self.next_int() as u32) >> 1) as i32;
                    let v = bits % n;
                    if bits.wrapping_sub(v).wrapping_add(n - 1) >= 0 {
                        break v;
                    }
                }
            };
            from.wrapping_add(picked)
        } else {
            loop {
                let picked = self.next_int();
                if (from..until).contains(&picked) {
                    return picked;
                }
            }
        }
    }

    /// From 0 (inclusive) to 1 (exclusive), 53 bits of it.
    pub fn next_double(&mut self) -> f64 {
        let high = i64::from(self.next_bits(26));
        let low = i64::from(self.next_bits(27));
        ((high << 27) + low) as f64 / (1_i64 << 53) as f64
    }

    pub fn next_boolean(&mut self) -> bool {
        self.next_bits(1) != 0
    }
}

#[cfg(test)]
mod tests {
    use super::Random;

    /// What Kotlin's `Random(seed)` draws (`testdata/random.txt`, written by
    /// format's `RandomGoldenTest`): three ints, a double by its bits, then
    /// `nextInt(10)`, `nextInt(-5, 5)`, `nextInt(7)` and `nextBoolean()`.
    #[test]
    fn draws_what_kotlin_draws() {
        let golden = include_str!("../../../packages/format/testdata/random.txt");
        let actual: String = golden
            .lines()
            .map(|line| {
                let seed: i64 = line.split(':').next().unwrap().parse().unwrap();
                let mut r = Random::new(seed);
                let ints = [r.next_int(), r.next_int(), r.next_int()];
                format!(
                    "{seed}: {} {} {} {} {} {} {} {}\n",
                    ints[0],
                    ints[1],
                    ints[2],
                    r.next_double().to_bits() as i64,
                    r.next_int_below(10),
                    r.next_int_in(-5, 5),
                    r.next_int_below(7),
                    r.next_boolean()
                )
            })
            .collect();
        assert_eq!(actual, golden);
    }
}
