//! The generator, held to the prelude's own Lua (`utilities.lua`), run on Lua
//! 5.4 to make these numbers: words, `integer(1, 6)`, `number()`, and
//! `integer(-1000000007, 1000000007)`, for each seed. The fractions are Lua's
//! `%.17g`, digit for digit.
#![allow(clippy::excessive_precision)]

use netherforge_luau::random::Random;

type Case = (i64, [i64; 3], [i64; 8], [f64; 3], [i64; 3]);

const CASES: &[Case] = &[
    (
        42,
        [
            1546998764402558742,
            6990951692964543102,
            -5902157311460992607,
        ],
        [2, 2, 5, 1, 3, 6, 2, 6],
        [
            0.083862971059882163,
            0.37898025066266861,
            0.68004341102813937,
        ],
        [-795607793, 829845623, -28717158],
    ),
    (
        7,
        [
            -5523389002881075622,
            5142052590334782674,
            -2958351167216911978,
        ],
        [3, 3, 1, 1, 2, 5, 5, 1],
        [0.7005764821796896, 0.27875122947378428, 0.83962746187641979],
        [324971603, 330130123, -644763761],
    ),
    (
        -1,
        [
            -8118546653352383224,
            -4290065566684577747,
            -9088772293754075490,
        ],
        [1, 6, 3, 6, 3, 3, 3, 2],
        [0.55989270405052116, 0.7674350796247662, 0.50729666669428841],
        [-287055103, 252098086, 382041239],
    ),
    (
        0,
        [
            -7355399402456485196,
            -4652746763540216534,
            1900383378846508768,
        ],
        [5, 3, 1, 5, 2, 3, 1, 2],
        [
            0.60126299941790484,
            0.74777409254723981,
            0.10301998939503632,
        ],
        [266021037, 230390563, 228138201],
    ),
    (
        9007199254740991,
        [
            4096853559096369217,
            -2654821087738887303,
            1971179688699188416,
        ],
        [2, 2, 1, 2, 4, 4, 5, 1],
        [
            0.22209087645636272,
            0.85608186045565837,
            0.10685786504234795,
        ],
        [-484370886, 528529778, -238646599],
    ),
];

#[test]
fn a_seed_gives_the_numbers_it_gave_on_lua_5_4() {
    for &(seed, words, dice, fractions, wide) in CASES {
        let mut rng = Random::new(seed);
        let got: Vec<i64> = (0..3).map(|_| rng.next_word() as i64).collect();
        assert_eq!(got, words, "words for {seed}");

        let mut rng = Random::new(seed);
        let got: Vec<i64> = (0..8).map(|_| rng.integer(1, 6)).collect();
        assert_eq!(got, dice, "dice for {seed}");

        let mut rng = Random::new(seed);
        let got: Vec<f64> = (0..3).map(|_| rng.fraction()).collect();
        assert_eq!(got, fractions, "fractions for {seed}");

        let mut rng = Random::new(seed);
        let got: Vec<i64> = (0..3)
            .map(|_| rng.integer(-1_000_000_007, 1_000_000_007))
            .collect();
        assert_eq!(got, wide, "wide integers for {seed}");
    }
}

#[test]
fn ranges_hold() {
    let mut rng = Random::new(1);
    for _ in 0..10_000 {
        let roll = rng.integer(1, 6);
        assert!((1..=6).contains(&roll));
        let f = rng.fraction();
        assert!((0.0..1.0).contains(&f));
    }
    assert_eq!(rng.integer(5, 5), 5);
    assert_eq!(rng.integer(-3, -3), -3);
    let mut items: Vec<u32> = (0..100).collect();
    rng.shuffle(&mut items);
    let mut sorted = items.clone();
    sorted.sort();
    assert_eq!(sorted, (0..100).collect::<Vec<_>>());
    assert_ne!(items, sorted);
}
