//! Format's noise in Rust is format's Kotlin noise, number for number: the
//! lines of `packages/format/testdata/noise/golden.txt` (written by Kotlin's
//! `FastNoiseLiteTest`), every noise type and fractal at fixed points, the
//! domain warps, and the seeds a world's parts start from.

use netherforge_format::noise::fast_noise_lite::{
    CellularDistanceFunction, CellularReturnType, DomainWarpType, FastNoiseLite, FractalType,
    NoiseType, RotationType3D,
};
use netherforge_format::noise::{NoiseDef, seeds};

const TYPES: [(&str, NoiseType); 6] = [
    ("OpenSimplex2", NoiseType::OpenSimplex2),
    ("OpenSimplex2S", NoiseType::OpenSimplex2S),
    ("Cellular", NoiseType::Cellular),
    ("Perlin", NoiseType::Perlin),
    ("ValueCubic", NoiseType::ValueCubic),
    ("Value", NoiseType::Value),
];

const FRACTALS: [(&str, FractalType); 4] = [
    ("None", FractalType::None),
    ("FBm", FractalType::FBm),
    ("Ridged", FractalType::Ridged),
    ("PingPong", FractalType::PingPong),
];

const ROTATIONS: [(&str, RotationType3D); 2] = [
    ("None", RotationType3D::None),
    ("ImproveXYPlanes", RotationType3D::ImproveXYPlanes),
];

/// The test's settings (`FastNoiseLiteTest.configure`), for variant 1.
fn configure(
    kind: NoiseType,
    fractal: FractalType,
    rotation: RotationType3D,
    seed: i32,
) -> FastNoiseLite {
    let mut n = FastNoiseLite::with_seed(seed);
    n.set_noise_type(Some(kind));
    n.set_fractal_type(Some(fractal));
    n.set_rotation_type_3d(Some(rotation));
    n.set_frequency(Some(0.013));
    n.set_fractal_octaves(Some(4));
    n.set_fractal_weighted_strength(Some(0.3));
    if kind == NoiseType::Cellular {
        n.set_cellular_distance_function(Some(CellularDistanceFunction::EuclideanSq));
        n.set_cellular_return_type(Some(CellularReturnType::Distance));
    }
    n
}

fn lines() -> Vec<String> {
    let mut lines = Vec::new();
    for (type_name, kind) in TYPES {
        for (fractal_name, fractal) in FRACTALS {
            for (rotation_name, rotation) in ROTATIONS {
                let n = configure(kind, fractal, rotation, -5025);
                for k in 0..3 {
                    let k = f64::from(k);
                    let (x, y, z) = (k * 713.25 - 600.5, k * -291.75 + 40.125, k * 57.5 - 3.0);
                    lines.push(format!(
                        "{type_name} {fractal_name} {rotation_name} {k} {:e} {:e}",
                        n.get_noise_2d(x, y),
                        n.get_noise_3d(x, y, z)
                    ));
                }
            }
        }
    }
    for (name, warp) in [
        ("OpenSimplex2", DomainWarpType::OpenSimplex2),
        ("OpenSimplex2Reduced", DomainWarpType::OpenSimplex2Reduced),
        ("BasicGrid", DomainWarpType::BasicGrid),
    ] {
        let mut n = FastNoiseLite::with_seed(77);
        n.set_domain_warp_type(Some(warp));
        n.set_fractal_type(Some(FractalType::DomainWarpProgressive));
        n.set_fractal_octaves(Some(3));
        n.set_domain_warp_amp(Some(25.0));
        n.set_frequency(Some(0.02));
        let (x2, y2) = n.domain_warp_2d(311.75, -902.5);
        let (x3, y3, z3) = n.domain_warp_3d(311.75, 12.5, -902.5);
        lines.push(format!("warp {name} {x2:e} {y2:e} {x3:e} {y3:e} {z3:e}"));
    }
    for role in [
        "height:hills",
        "climate:temperature",
        "cave:caverns",
        "ore:iron",
    ] {
        lines.push(format!(
            "seed {role} {} {}",
            seeds::for_role(123_456_789_012_345, role),
            seeds::for_role(-7, role)
        ));
    }
    lines.push(format!(
        "unit {:e} {:e}",
        seeds::unit(5, 10, -20, 3000),
        seeds::unit(-9, 0, 0, 0)
    ));
    lines
}

#[test]
fn every_noise_is_kotlins_to_the_last_digit() {
    let golden = include_str!("../../../packages/format/testdata/noise/golden.txt");
    let expected: Vec<&str> = golden.lines().filter(|l| !l.trim().is_empty()).collect();
    let actual = lines();
    assert_eq!(expected.len(), actual.len());
    let mut different = Vec::new();
    for (e, a) in expected.iter().zip(&actual) {
        let (ep, ap): (Vec<&str>, Vec<&str>) = (e.split(' ').collect(), a.split(' ').collect());
        let same = ep.len() == ap.len()
            && ep
                .iter()
                .zip(&ap)
                .all(|(x, y)| match (x.parse::<f64>(), y.parse::<f64>()) {
                    (Ok(x), Ok(y)) => x == y,
                    _ => x == y,
                });
        if !same {
            different.push(format!("expected {e}\n     got {a}"));
        }
    }
    assert!(
        different.is_empty(),
        "{} of {} differ:\n{}",
        different.len(),
        expected.len(),
        different.join("\n")
    );
}

#[test]
fn a_seed_gives_the_same_noise_and_another_seed_another() {
    let def = NoiseDef::of(0.02, 3);
    let (a, b, c) = (def.build(7), def.build(7), def.build(8));
    let mut different = 0;
    for i in 0..50 {
        let x = f64::from(i) * 37.0;
        assert_eq!(a.get_noise_2d(x, -x), b.get_noise_2d(x, -x));
        if a.get_noise_2d(x, -x) != c.get_noise_2d(x, -x) {
            different += 1;
        }
    }
    assert!(different > 40, "another seed is another pattern");
}

#[test]
fn the_roles_of_a_world_get_seeds_of_their_own() {
    let roles = [
        "height:hills",
        "height:detail",
        "climate:temperature",
        "climate:humidity",
        "cave:a",
        "cave:a:b",
        "ore:iron",
    ];
    let seeds: std::collections::HashSet<i32> =
        roles.iter().map(|r| seeds::for_role(42, r)).collect();
    assert_eq!(seeds.len(), roles.len());
    assert_ne!(
        seeds::for_role(42, "height:hills"),
        seeds::for_role(43, "height:hills")
    );
    let units: Vec<f64> = (0..200).map(|i| seeds::unit(9, i, i * 3, -i)).collect();
    assert!(units.iter().all(|u| (0.0..1.0).contains(u)));
    let distinct: std::collections::HashSet<u64> = units.iter().map(|u| u.to_bits()).collect();
    assert!(distinct.len() > 150);
}

/// The reference's settings for a `reference.csv` noise line (`configure` with its variant).
fn configured(kind: &str, fractal: &str, rotation: &str, variant: usize) -> FastNoiseLite {
    let kind = TYPES.iter().find(|(n, _)| *n == kind).unwrap().1;
    let fractal = FRACTALS.iter().find(|(n, _)| *n == fractal).unwrap().1;
    let rotation = match rotation {
        "None" => RotationType3D::None,
        "ImproveXYPlanes" => RotationType3D::ImproveXYPlanes,
        _ => RotationType3D::ImproveXZPlanes,
    };
    let mut n = configure(kind, fractal, rotation, 1337 + variant as i32 * 7);
    if kind == NoiseType::Cellular {
        use CellularDistanceFunction as D;
        use CellularReturnType as R;
        n.set_cellular_distance_function(Some(
            [D::Euclidean, D::EuclideanSq, D::Manhattan, D::Hybrid][variant % 4],
        ));
        n.set_cellular_return_type(Some(
            [
                R::CellValue,
                R::Distance,
                R::Distance2,
                R::Distance2Add,
                R::Distance2Sub,
                R::Distance2Mul,
                R::Distance2Div,
            ][variant % 7],
        ));
    }
    n
}

/// FastNoiseLite's Java reference (in 32-bit floats) at fixed points, which
/// format's doubles stay within a float's rounding of.
#[test]
fn the_noise_agrees_with_the_reference_to_its_rounding() {
    let reference = include_str!("../../../packages/format/testdata/noise/reference.csv");
    let lines: Vec<&str> = reference
        .lines()
        .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
        .collect();
    assert!(lines.len() > 200);
    let near = |expected: &str, actual: f64, tolerance: f64, line: &str| {
        let expected: f64 = expected.parse().unwrap();
        assert!(
            (expected - actual).abs() <= tolerance,
            "{line}: expected {expected}, got {actual}"
        );
    };
    for line in lines {
        let p: Vec<&str> = line.split(';').collect();
        let (x, y, z) = (
            p[5].parse().unwrap(),
            p[6].parse().unwrap(),
            p[7].parse().unwrap(),
        );
        if p[0] == "N" {
            let n = configured(p[1], p[2], p[3], p[4].parse().unwrap());
            near(p[8], n.get_noise_2d(x, y), 2e-3, line);
            near(p[9], n.get_noise_3d(x, y, z), 2e-3, line);
        } else {
            let mut n = FastNoiseLite::with_seed(99);
            n.set_domain_warp_type(Some(match p[1] {
                "OpenSimplex2" => DomainWarpType::OpenSimplex2,
                "OpenSimplex2Reduced" => DomainWarpType::OpenSimplex2Reduced,
                _ => DomainWarpType::BasicGrid,
            }));
            n.set_fractal_type(Some(match p[2] {
                "DomainWarpProgressive" => FractalType::DomainWarpProgressive,
                "DomainWarpIndependent" => FractalType::DomainWarpIndependent,
                _ => FractalType::None,
            }));
            n.set_rotation_type_3d(Some(match p[3] {
                "None" => RotationType3D::None,
                "ImproveXYPlanes" => RotationType3D::ImproveXYPlanes,
                _ => RotationType3D::ImproveXZPlanes,
            }));
            n.set_frequency(Some(0.02));
            n.set_domain_warp_amp(Some(30.0));
            n.set_fractal_octaves(Some(3));
            let (x2, y2) = n.domain_warp_2d(x, y);
            let (x3, y3, z3) = n.domain_warp_3d(x, y, z);
            // A warp moves a point by up to 30 blocks, so a float's rounding shows a little more.
            for (expected, actual) in [
                (p[8], x2),
                (p[9], y2),
                (p[10], x3),
                (p[11], y3),
                (p[12], z3),
            ] {
                near(expected, actual, 5e-2, line);
            }
        }
    }
}
