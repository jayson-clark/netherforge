//! Minecraft release numbers, matching `format`'s `MinecraftVersion`:
//! `1.21.11`, `26.3`, `26.3.1`. Snapshots and pre-releases aren't targets.

use std::cmp::Ordering;

/// The numeric parts of a release version, or None for anything else.
pub fn parse(text: &str) -> Option<Vec<u32>> {
    let parts: Vec<&str> = text.split('.').collect();
    if !(2..=3).contains(&parts.len()) {
        return None;
    }
    parts
        .iter()
        .map(|part| {
            if part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()) {
                None
            } else {
                part.parse().ok()
            }
        })
        .collect()
}

pub fn is_release(text: &str) -> bool {
    parse(text).is_some()
}

/// Part by part, missing parts read as zero (`26.3` == `26.3.0`).
pub fn compare(a: &str, b: &str) -> Ordering {
    let (a, b) = (parse(a).unwrap_or_default(), parse(b).unwrap_or_default());
    for i in 0..a.len().max(b.len()) {
        let ordering = a.get(i).unwrap_or(&0).cmp(b.get(i).unwrap_or(&0));
        if ordering != Ordering::Equal {
            return ordering;
        }
    }
    Ordering::Equal
}

/// Sorts newest first and removes duplicates.
pub fn sort_newest_first(versions: &mut Vec<String>) {
    versions.sort_by(|a, b| compare(b, a).then_with(|| a.cmp(b)));
    versions.dedup();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_releases_only() {
        for good in ["1.21.11", "26.3", "26.3.1", "1.8"] {
            assert!(is_release(good), "{good}");
        }
        for bad in [
            "26",
            "26.3-pre1",
            "26w14a",
            "1.21.11-rc1",
            "26.3.1.2",
            "",
            "a.b",
            "26..3",
            "26.3 ",
        ] {
            assert!(!is_release(bad), "{bad}");
        }
    }

    #[test]
    fn compares_numerically() {
        assert_eq!(compare("26.3", "1.21.11"), Ordering::Greater);
        assert_eq!(compare("26.3", "26.3.0"), Ordering::Equal);
        assert_eq!(compare("1.21.9", "1.21.11"), Ordering::Less);
        let mut v = vec![
            "1.21.11".into(),
            "26.3.1".into(),
            "26.3".into(),
            "26.3".into(),
        ];
        sort_newest_first(&mut v);
        assert_eq!(v, ["26.3.1", "26.3", "1.21.11"]);
    }
}
